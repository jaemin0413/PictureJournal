## Summary
G001 foundation now covers the requested skeleton cleanly enough to advance: the Spring Boot base, backend-owned OpenAPI contract flow, folder write policy seam, folder type separation, shared error taxonomy, Docker Postgres local contract, and provider-aware storage seam are all present in the reviewed files. The remaining concerns are implementation hardening rather than scope gaps: unexpected 500 responses currently echo raw exception messages, and the local runtime contract test mixes env-driven connection inputs with a hard-coded database assertion.

## Claims
- The Spring Boot foundation skeleton is present through `build.gradle.kts`, `src/main/java/com/picturejournal/PictureJournalApplication.java`, and `src/main/resources/application.yml`.
- The backend-owned contract pipeline shape exists through `src/main/java/com/picturejournal/shared/api/OpenApiConfig.java`, `src/test/java/com/picturejournal/OpenApiContractTests.java`, `src/test/java/com/picturejournal/OpenApiContractArtifactTests.java`, `build.gradle.kts`, and `contracts/openapi/picture-journal.openapi.json`.
- The explicit folder write boundary is owned by `src/main/java/com/picturejournal/folder/application/FolderCapabilityPolicy.java` and exercised in `src/test/java/com/picturejournal/folder/application/FolderCapabilityPolicyContractTests.java`.
- Folder type separation is explicit in `src/main/java/com/picturejournal/folder/domain/FolderType.java` and distinct from membership role concerns in `src/main/java/com/picturejournal/folder/domain/FolderRole.java`.
- Shared error taxonomy is defined by `src/main/java/com/picturejournal/shared/error/ErrorCode.java`, `src/main/java/com/picturejournal/shared/error/DomainException.java`, and `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java`, with focused tests in `src/test/java/com/picturejournal/shared/error/GlobalExceptionHandlerTests.java`.
- The local Docker Postgres runtime contract is aligned across `.env.example`, `docker-compose.yml`, `src/main/resources/application.yml`, and `src/test/java/com/picturejournal/LocalRuntimeContractTests.java` on the default `picturejournal` / `picturejournal-dev-password` values.
- The MinIO-ready storage seam with provider-aware validation is present through `src/main/java/com/picturejournal/shared/config/StorageProvider.java`, `src/main/java/com/picturejournal/shared/config/StorageProperties.java`, `src/main/resources/application.yml`, `.env.example`, and `src/test/java/com/picturejournal/shared/config/StoragePropertiesContractTests.java`.

## Analysis
- **Spec compliance:** For the approved G001 foundation scope only, the implementation now matches the requested shape. `build.gradle.kts` provides a Spring Boot 3.3 / Java 21 base with web, validation, actuator, JDBC, and springdoc dependencies. `PictureJournalApplication` enables configuration-properties scanning, `application.yml` wires the default datasource and storage seam, and `application-test.yml` keeps contract-oriented tests from depending on JDBC autoconfiguration.
- **Contract ownership:** The OpenAPI flow is correctly backend-owned. `OpenApiConfig` defines the runtime doc identity, `OpenApiContractArtifactTests` writes a generated artifact, `build.gradle.kts` exposes `generateOpenApiContract` and `refreshOpenApiContract`, and `OpenApiContractTests` verifies the checked-in file at `contracts/openapi/picture-journal.openapi.json` matches `/api-docs`. That is the right foundation-level contract loop even though the contract still has no business paths yet.
- **Boundary design:** `FolderCapabilityPolicy` is the right application-layer seam for folder-scoped writes, and the contract test demonstrates that callers can route writes through the policy rather than embedding ad hoc authorization logic. `FolderType` and `FolderRole` are also separated correctly, which prevents the folder-content invariant from being conflated with membership authorization.
- **Runtime contract:** The local Docker Postgres contract is coherent on defaults: `.env.example`, `docker-compose.yml`, `application.yml`, and `LocalRuntimeContractTests` all converge on localhost:5432, database `picturejournal`, and password `picturejournal-dev-password`.
- **Storage seam:** `StorageProperties` is appropriately provider-aware for foundation scope. The `@AssertTrue` guard ensures `S3_COMPATIBLE` cannot start without bucket, endpoint, access key, and secret key, while `LOCAL` remains the default path.
- **Quality concern:** `GlobalExceptionHandler.handleException` currently serializes `exception.getMessage()` into the public 500 response body (`src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java`, lines 25-32), and `GlobalExceptionHandlerTests` locks that behavior in by asserting `"boom"` is returned for an `IllegalStateException` (`src/test/java/com/picturejournal/shared/error/GlobalExceptionHandlerTests.java`, lines 25-30). That weakens the shared error taxonomy by leaking internal failure strings into a supposedly stable foundation error envelope.
- **Quality concern:** `LocalRuntimeContractTests` reads connection settings from env (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) but still hard-codes the expected database name to `picturejournal` (`src/test/java/com/picturejournal/LocalRuntimeContractTests.java`, lines 18-27). That is acceptable if the database name is intentionally part of the fixed contract, but misleading if the env-based surface is meant to support overrides.

## Root Cause
The remaining issues come from contract surfaces that are only partially normalized. The public error envelope distinguishes domain failures cleanly but still treats unexpected failures as pass-through strings, and the runtime test exposes some configurability via environment variables without fully deciding whether those variables are true extension points or just conveniences around a fixed default contract.

## Findings
- **MEDIUM** — `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java` and `src/test/java/com/picturejournal/shared/error/GlobalExceptionHandlerTests.java`
  - **Impact:** Unexpected server failures currently return raw exception messages to clients. That can leak internal details, makes the 500 contract unstable across implementation changes, and weakens the value of the shared taxonomy at the exact place where callers most need a boring, durable error shape.
  - **Fix suggestion:** Keep the explicit `INTERNAL_ERROR` code, but replace the public message for generic exceptions with a stable server-safe string; adjust the test to assert the normalized envelope instead of the thrown message.
- **LOW** — `src/test/java/com/picturejournal/LocalRuntimeContractTests.java`
  - **Impact:** The test mixes env-driven connection inputs with a hard-coded expected database name. That is fine for a fixed default contract, but it leaves the override semantics ambiguous and can create false negatives if developers reasonably assume the env-backed settings are meant to be customized together.
  - **Fix suggestion:** Either treat `picturejournal` as an intentionally fixed contract and remove the appearance of broader configurability, or read/assert `POSTGRES_DB` (or the database segment from `DB_URL`) so the test matches the advertised override surface.

## Recommendations
1. Normalize unexpected 500 responses so the shared error taxonomy does not expose raw exception strings.
2. Clarify the local runtime test contract by making the database-name assertion either explicitly fixed or fully env-aware.
3. Leave the rest of the G001 foundation as-is; the core architectural seams are in place and appropriately scoped for later phases.

## Architectural Status
CLEAR

## Code Review Recommendation
COMMENT

## Tradeoffs
- **Stable generic 500 message**
  - Pros: safer public envelope, less implementation leakage, cleaner long-term client contract.
  - Cons: less immediate debugging detail in raw HTTP responses.
- **Echo generic exception messages**
  - Pros: convenient during very early local debugging.
  - Cons: couples clients to internal strings and risks leaking internals.
- **Fixed default local DB contract**
  - Pros: simpler onboarding and fewer moving parts.
  - Cons: env-backed override surface looks broader than it really is.
- **Fully env-aware local DB test**
  - Pros: clearer contract if customization is intentional.
  - Cons: slightly more logic in the test.

## Product Status
CLEAR

## Code Status
WATCH
