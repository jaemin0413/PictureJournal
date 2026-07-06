## Summary
G003 covers the collaboration-core happy paths in code and controller tests: signup/login/me, folder create/list/get/update, invite create/read/accept, member listing, and a server-side write-denial path are all present in the reviewed files. The implementation is not ready for approval, though, because auth still persists and compares plaintext passwords, the checked-in OpenAPI contract does not describe bearer authentication for protected operations, and `listFolders` silently hides folder/membership inconsistencies instead of surfacing them.

## Claims
- The protected collaboration endpoints are wired through `AuthService.getCurrentUser(...)` in `src/main/java/com/picturejournal/collaboration/api/CollaborationController.java`, so server-side auth/capability checks are applied in the controller/service path.
- The reviewed tests exercise the main scope flows in `src/test/java/com/picturejournal/auth/api/AuthControllerTests.java` and `src/test/java/com/picturejournal/collaboration/api/CollaborationControllerTests.java`.
- The checked-in OpenAPI artifact is enforced to match generated `/api-docs` by `src/test/java/com/picturejournal/OpenApiContractTests.java`.
- The error surface for capability denials is standardized through `FolderCapabilityPolicy` and `GlobalExceptionHandler` (`src/main/java/com/picturejournal/folder/application/FolderCapabilityPolicy.java`, `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java`) and covered by contract-style tests.

## Analysis
### Spec compliance
- Auth scope exists: `AuthController` exposes `/signup`, `/login`, and `/me`; `AuthService` performs duplicate-email rejection and token-based identity resolution.
- Folder scope exists: `CollaborationController` exposes create/list/get/update; `CollaborationService` creates an owner membership at folder creation and requires membership for reads.
- Membership/invite scope exists: owners can create invites, invites can be inspected and accepted, and folder members can be listed.
- Capability checks are enforced server-side for the reviewed write path: `updateFolder(...)` routes through `FolderCapabilityPolicy.assertCanWriteToFolder(...)`, which only allows OWNER/EDITOR and maps denials to `FOLDER_WRITE_NOT_ALLOWED`; the controller test `viewerCannotPatchFolder` asserts the 403/error-code behavior.
- Owner-only invite issuance is enforced server-side inline in `CollaborationService.createInvite(...)`.

### Architecture
- The layering is mostly clean for this slice: controllers resolve identity, services apply business rules, domain records stay small, and `CollaborationStore`/auth stores keep persistence behind interfaces.
- The authorization boundary is only partially centralized. `updateFolder(...)` uses `FolderCapabilityPolicy`, but `createInvite(...)` bypasses that boundary with an inline owner-role check. That is tolerable for now because invite issuance is a stricter rule than generic writes, but the current policy abstraction is already too narrow for the full collaboration surface.
- Multi-record mutations are file-backed and non-atomic (`createFolder`: save folder then membership; `acceptInvite`: save membership then accepted invite). That is a manageable implementation choice for an early local store, but it raises the importance of surfacing inconsistencies instead of suppressing them.

### Product / contract quality
- The contract is kept in sync with generated docs, but it does not describe authentication. The protected endpoints read `Authorization` in controller code, yet the checked-in OpenAPI JSON has no `security`, `securitySchemes`, `bearer`, or `Authorization` entries. Consumers therefore cannot discover which operations require auth or how to authenticate from the published contract alone.
- The reviewed controller tests cover happy paths plus a small set of negative cases (duplicate email, wrong password, invite role restriction, viewer patch denial). They do not cover unauthenticated collaboration access or non-member reads, so current confidence in server-side rejection behavior comes primarily from direct code inspection.

### Code quality / security
- Password handling is the biggest problem in the reviewed slice. `AuthService.signup(...)` persists the raw password into `UserAccount`, `FileUserAccountStore` serializes that record directly, and `AuthService.login(...)` authenticates via plain string equality. That is a direct credential-exposure issue.
- `listFolders(...)` converts missing folders into `null` and filters them out. This hides persistence corruption and partial-write defects instead of preserving failure evidence.

## Root Cause
The slice is functionally complete, but the implementation still reflects a local-spike mindset in two places that should already be contract-grade for collaboration core: security boundaries are described and enforced manually rather than through shared platform mechanisms, and file-store inconsistencies are tolerated by suppressing evidence instead of making the primary contract explicit.

## Findings
1. **HIGH** — `src/main/java/com/picturejournal/auth/application/AuthService.java:41-49`, with persistence in `src/main/java/com/picturejournal/auth/application/FileUserAccountStore.java:31-36`
   - **Impact:** Passwords are stored recoverably in plaintext and compared directly on login. Any disk exposure or artifact leak becomes a credential leak.
   - **Fix:** Persist a password hash only, verify with a one-way password encoder, and remove the raw password from the persisted domain model/API path.
2. **MEDIUM** — `contracts/openapi/picture-journal.openapi.json` (checked-in/generated artifact), with protected endpoints implemented in `src/main/java/com/picturejournal/auth/api/AuthController.java` and `src/main/java/com/picturejournal/collaboration/api/CollaborationController.java`
   - **Impact:** The published contract does not communicate authentication requirements for protected operations, weakening client integration and contract correctness even though the runtime enforces auth.
   - **Fix:** Add a bearer security scheme and security requirements (or explicit auth header parameters if intentionally avoiding security schemes) to generated docs.
3. **MEDIUM** — `src/main/java/com/picturejournal/collaboration/application/CollaborationService.java:54-56`
   - **Impact:** Orphaned memberships are silently hidden from `listFolders`, masking data corruption and making non-atomic write failures harder to detect and repair.
   - **Fix:** Fail loudly on folder/membership mismatches or add explicit reconciliation/transactional semantics rather than filtering them out.

## Recommendations
1. Replace plaintext password storage/comparison with hashed credential handling before approving the collaboration/auth slice.
2. Fix the OpenAPI auth contract so protected operations advertise the bearer requirement and remain synchronized through the existing contract test.
3. Remove the `orElse(null)`/null-filter suppression path in `listFolders(...)`; either surface a deterministic error or reconcile orphaned memberships explicitly.
4. As a follow-up hardening step, expand the authorization abstraction beyond generic folder writes so invite-management rules are expressed through a consistent policy boundary.

## Architectural Status
WATCH

## Code Review Recommendation
REQUEST CHANGES

## Tradeoffs
- **Current file-backed stores**: simple and testable, but no transactionality and higher risk of hidden drift between related records.
- **Hashing credentials now**: slightly more plumbing and test updates, but it removes an otherwise unacceptable security liability from an auth surface already exposed through product code.
- **Security schemes in OpenAPI**: small documentation effort, large gain in client usability and contract truthfulness.
- **Fail-fast on orphaned memberships**: noisier in the short term, but much better for detecting and repairing real integrity bugs than silently omitting data.
