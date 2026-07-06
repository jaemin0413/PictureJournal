## Summary
G003 is materially stronger after the auth hardening and OpenAPI security-contract fixes: credentials are now hashed, session expiry is enforced, the checked-in contract advertises bearer auth, and `listFolders` now fails loudly on orphaned memberships. The remaining gap is architectural but scope-relevant: invite creation and invite acceptance still bypass `FolderCapabilityPolicy`, so the collaboration-core slice does not yet satisfy the approved requirement that all folder-scoped writes flow through that policy boundary.

## Claims
- Auth hardening is present in the reviewed source: `AuthService` now hashes passwords with PBKDF2 and rejects expired sessions, with focused coverage in `src/test/java/com/picturejournal/auth/application/AuthServiceTests.java`.
- The OpenAPI security contract is now explicit: `src/main/java/com/picturejournal/shared/api/OpenApiConfig.java` defines `bearerAuth`, protected controller methods in `src/main/java/com/picturejournal/auth/api/AuthController.java` and `src/main/java/com/picturejournal/collaboration/api/CollaborationController.java` carry `@SecurityRequirement(name = "bearerAuth")`, and `src/test/java/com/picturejournal/OpenApiContractTests.java` asserts the generated `/api-docs` document matches the checked-in `contracts/openapi/picture-journal.openapi.json`.
- The earlier data-integrity suppression issue is fixed: `src/main/java/com/picturejournal/collaboration/application/CollaborationService.java` now throws on orphaned memberships during `listFolders`, and `src/test/java/com/picturejournal/collaboration/application/CollaborationServiceTests.java` locks that behavior.
- The approved plan and ultragoal brief require `FolderCapabilityPolicy` to be the single gateway for folder-scoped writes, including `CreateFolderInvite` and `AcceptFolderInvite`: `.gjc/_session-019f3160-45c4-7000-b101-3c4de80dc410/plans/ralplan/019f3160-45c4-7000-b101-3c4de80dc410/stage-04-revision.md` and `.gjc/_session-019f32ab-f4df-7000-97a4-bc9e252f5334/ultragoal/goals.json`.

## Analysis
### Spec compliance
- Collaboration-core behavior is mostly present in the reviewed files. `AuthController` exposes signup/login/me; `CollaborationController` exposes folder create/list/detail/update, invite create/read/accept, and member listing; controller tests cover the main happy paths and selected denials in `src/test/java/com/picturejournal/auth/api/AuthControllerTests.java` and `src/test/java/com/picturejournal/collaboration/api/CollaborationControllerTests.java`.
- The hardening deltas called out in the prior review are now addressed. Passwords are no longer stored as plaintext (`AuthService.hashPassword(...)`, `matchesPassword(...)`), session expiry is enforced (`AuthService.isSessionActive(...)`), the generated contract advertises bearer auth (`OpenApiConfig`, `OpenApiContractTests`), and orphaned membership/folder mismatches now surface as deterministic failures (`CollaborationService.listFolders(...)`, `CollaborationServiceTests.listFoldersFailsLoudlyOnOrphanedMembership`).
- The remaining spec/constraint miss is policy coverage. `CollaborationService.updateFolder(...)` calls `folderCapabilityPolicy.assertCanWriteToFolder(actorId, folderId)`, but `createInvite(...)` and `acceptInvite(...)` write folder-scoped state without passing through that boundary. That conflicts with the approved boundary rule that `FolderCapabilityPolicy` is the single entry point for folder-scoped writes and the explicit coverage list naming `CreateFolderInvite` and `AcceptFolderInvite`.

### Architecture
- The layering remains otherwise healthy for this slice: controllers resolve identity, application services coordinate invariants, domain records stay simple, and persistence is abstracted behind store interfaces.
- The policy boundary is still too narrow for the collaboration surface. By expressing invite creation as an inline `OWNER` check and invite acceptance as a direct membership write, the code reintroduces authorization logic outside the declared seam. That makes the plan’s promised “single source of truth” for folder-scoped write authorization false in practice.
- This is not just a style concern. The whole reason for the `FolderCapabilityPolicy` constraint in the plan is to keep later clients and later folder-scoped commands from drifting into ad hoc role checks. Leaving invite writes outside the seam weakens that contract exactly where collaboration core is supposed to establish it.

### Code quality / security / performance
- The auth surface is in acceptable shape for this phase after the hardening: hashed credentials, explicit bearer parsing, shared error codes, and session TTL coverage are all present in the reviewed files.
- The checked-in OpenAPI contract now has a real security scheme and is governed by an equality test against the runtime document, which is the right contract-management shape for this codebase.
- The main remaining code-quality concern inside the target slice is the fragmented authorization path for invite writes.

## Root Cause
The collaboration service still treats `FolderCapabilityPolicy` as an update-folder helper instead of the authoritative authorization gateway promised by the plan. That leaves invite-related writes on a separate inline authorization path, so the architecture has not actually converged on one policy boundary for folder-scoped mutations.

## Findings
1. **MEDIUM** — `src/main/java/com/picturejournal/collaboration/application/CollaborationService.java`
   - **Impact:** `createInvite(...)` and `acceptInvite(...)` mutate folder-scoped collaboration state without traversing `FolderCapabilityPolicy`, even though the approved plan and ultragoal brief require that boundary for `CreateFolderInvite` and `AcceptFolderInvite`. The current behavior works for today's tests, but it breaks the intended architectural contract and invites future rule drift across write commands and clients.
   - **Fix:** Route invite creation and acceptance through `FolderCapabilityPolicy`. The cleanest fix is to evolve the policy from a single generic `assertCanWriteToFolder(...)` method into command/capability-aware authorization that can represent owner-only invite issuance and token-based invite acceptance without scattering inline role checks.

## Recommendations
1. Close the remaining policy-boundary gap before calling G003 complete: invite creation and invite acceptance must be expressed through `FolderCapabilityPolicy` rather than ad hoc service-local checks.
2. Keep the current auth hardening and OpenAPI security-contract shape; those issues appear resolved in the reviewed files and should remain part of the regression surface.
3. Add focused tests that prove invite-related writes traverse the policy seam once the policy API is widened, so this architectural guarantee becomes enforceable rather than documentary.

## Architectural Status
WATCH

## Code Review Recommendation
REQUEST CHANGES

## Tradeoffs
- **Keep inline invite authorization:** smallest short-term code footprint, but it violates the approved boundary contract and increases drift risk.
- **Expand `FolderCapabilityPolicy` to typed capabilities/commands:** more design work now, but it makes owner-only/admin/token-based collaboration writes explicit and centrally testable.
- **Explicitly exempt invite acceptance from the policy contract:** simpler than widening the policy, but only acceptable if the approved plan/brief is updated to state that exception instead of silently diverging from it.
