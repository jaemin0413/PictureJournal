## Summary
The collaboration/auth slice is mostly coherent and its happy-path, error-envelope, and OpenAPI synchronization tests are in place, but two invariant-breaking race windows remain in the current file-backed implementation. I do not recommend final approval until invite redemption and email uniqueness are enforced atomically.

## Claims
- Auth and collaboration controllers consistently route failures through the shared error envelope and advertise bearer auth in OpenAPI (`src/main/java/com/picturejournal/auth/api/AuthController.java`, `src/main/java/com/picturejournal/collaboration/api/CollaborationController.java`, `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java`, `src/test/java/com/picturejournal/auth/api/AuthControllerTests.java`, `src/test/java/com/picturejournal/collaboration/api/CollaborationControllerTests.java`, `src/test/java/com/picturejournal/OpenApiContractTests.java`).
- Password storage, session expiry, unsafe token-path rejection, folder-type validation, and folder write-policy enforcement are covered by focused tests (`src/test/java/com/picturejournal/auth/application/AuthServiceTests.java`, `src/test/java/com/picturejournal/auth/application/FileAuthSessionStoreTests.java`, `src/test/java/com/picturejournal/collaboration/application/CollaborationServiceTests.java`, `src/test/java/com/picturejournal/collaboration/application/FileCollaborationStoreTests.java`, `src/test/java/com/picturejournal/shared/error/GlobalExceptionHandlerTests.java`).
- The persistence layer only synchronizes individual store methods, while core auth/collaboration invariants are implemented as multi-step read-check-write flows in services (`src/main/java/com/picturejournal/auth/application/AuthService.java`, `src/main/java/com/picturejournal/collaboration/application/CollaborationService.java`, `src/main/java/com/picturejournal/auth/application/FileUserAccountStore.java`, `src/main/java/com/picturejournal/collaboration/application/FileCollaborationStore.java`).

## Analysis
The overall boundary layout is sensible for a small service: controllers authenticate then hand off to services, services use domain records plus explicit `FolderCapabilityPolicy`, and a shared exception mapper keeps transport behavior consistent. The OpenAPI configuration and contract test also keep the checked-in contract aligned with the generated document (`src/main/java/com/picturejournal/shared/api/OpenApiConfig.java`, `contracts/openapi/picture-journal.openapi.json`, `src/test/java/com/picturejournal/OpenApiContractTests.java`).

The main problem is that the file-backed stores provide only per-operation synchronization. `AuthService.signup` performs `findByEmail` and then `save` as separate calls, so two concurrent requests for the same normalized email can both pass the uniqueness check and persist different user records (`src/main/java/com/picturejournal/auth/application/AuthService.java`, `src/main/java/com/picturejournal/auth/application/FileUserAccountStore.java`). Because `findByEmail` later scans the directory and returns the first matching file, authentication behavior becomes nondeterministic once duplicates exist.

`CollaborationService.acceptInvite` has the same shape: it reads the invite, verifies `PENDING`, checks membership absence, then writes membership and invite state in separate store calls (`src/main/java/com/picturejournal/collaboration/application/CollaborationService.java`, `src/main/java/com/picturejournal/collaboration/application/FileCollaborationStore.java`). Two concurrent acceptors can both redeem the same token before either write makes the invite non-pending, which can add multiple members from one supposedly single-use invite and leave the invite audit trail reflecting only whichever write landed last.

The current tests are good for nominal behavior and some edge conditions, but they do not exercise concurrent access or atomicity of these invariants (`src/test/java/com/picturejournal/auth/application/AuthServiceTests.java`, `src/test/java/com/picturejournal/collaboration/application/CollaborationServiceTests.java`). That leaves the most security-sensitive contracts in auth and collaboration unverified under the actual multi-threaded request model of a Spring web application.

## Root Cause
Core identity and invitation invariants are implemented above a persistence layer that has no transactional or compare-and-set primitive, so service-level check-then-act flows are vulnerable to concurrent interleavings.

## Findings
- HIGH — `src/main/java/com/picturejournal/collaboration/application/CollaborationService.java`: `acceptInvite` is not atomic. A single pending invite token can be redeemed concurrently by multiple actors because status check, membership check, membership write, and invite-state write happen in separate steps. Impact: one invite may grant folder access to multiple accounts and the stored invite audit data can be overwritten. Fix: enforce single-use redemption atomically in the persistence boundary (for example, a synchronized compare-and-accept operation or a transactional store method that both verifies pending state and commits membership/invite updates together), then add a concurrent acceptance test.
- MEDIUM — `src/main/java/com/picturejournal/auth/application/AuthService.java`: signup email uniqueness is a non-atomic `findByEmail` + `save` sequence. Concurrent signups for the same normalized email can create duplicate accounts, after which login behavior depends on directory scan order. Impact: broken identity uniqueness and unpredictable authentication results. Fix: move uniqueness enforcement into the store with an atomic email index/claim operation or another persistence-level uniqueness guarantee, then add coverage for duplicate concurrent signup attempts.

## Recommendations
1. Block release on an atomic invite-redemption fix in the collaboration persistence boundary, plus a regression test proving one token cannot create two memberships.
2. Add a persistence-level uniqueness guarantee for normalized emails and cover it with a race-oriented auth test.
3. Keep the existing controller/OpenAPI/error-envelope tests; they already provide good contract coverage once the invariant bugs are fixed.

## Architectural Status
WATCH

## Code Review Recommendation
REQUEST CHANGES

## Tradeoffs
- **Service-level coarse synchronization**: minimal change for the current file store, but couples correctness to single-process deployment and can become a bottleneck.
- **Store-level atomic operations / transactional backend**: slightly more implementation work, but it places invariants at the persistence boundary where they belong and scales better to future storage changes.
