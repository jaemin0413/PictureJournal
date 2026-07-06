## Summary
G001 foundation is ready to advance for the reviewed scope. The files establish the Spring Boot base, explicit folder-write policy seam, backend-owned OpenAPI contract loop, shared error taxonomy, and a coherent local Docker Postgres plus placeholder storage configuration story.
The only issue worth carrying forward is low-risk test fragility: the storage-default contract test still inherits the optional `.env` import, so a supported local override can produce a false failure in a checked-in contract test.

## Claims
- The Spring Boot foundation skeleton is present through `build.gradle.kts`, `src/main/java/com/picturejournal/PictureJournalApplication.java`, and `src/main/resources/application.yml`.
- The backend-owned OpenAPI pipeline exists through `src/main/java/com/picturejournal/shared/api/OpenApiConfig.java`, `contracts/openapi/picture-journal.openapi.json`, `src/test/java/com/picturejournal/OpenApiContractTests.java`, `src/test/java/com/picturejournal/OpenApiContractArtifactTests.java`, and the Gradle tasks in `build.gradle.kts`.
- The explicit folder-scoped write boundary is owned by `src/main/java/com/picturejournal/folder/application/FolderCapabilityPolicy.java` and stays distinct from folder taxonomy in `FolderType` and membership role taxonomy in `FolderRole`.
- The shared error contract is explicit via `ErrorCode`, `DomainException`, and `GlobalExceptionHandler`, including a dedicated mapping for `FOLDER_WRITE_NOT_ALLOWED`.
- The local runtime contract is coherent across `.env.example`, `docker-compose.yml`, `src/main/resources/application.yml`, `src/test/java/com/picturejournal/LocalRuntimeContractTests.java`, and `src/main/java/com/picturejournal/shared/config/StorageProperties.java`.

## Analysis
- **Spec compliance:** For G001 foundation only, the requested deliverables are present. `build.gradle.kts` provides a Java 21 / Spring Boot 3.3.2 base with web, validation, actuator, JDBC, PostgreSQL, and springdoc dependencies. `PictureJournalApplication` enables configuration-properties scanning, and `application.yml` wires datasource, profile, springdoc, management, and storage seams.
- **Architecture:** The boundary directions are clean. `FolderCapabilityPolicy` sits in the application layer and gives later write paths a single authorization seam (`FolderCapabilityPolicy.java:11-25`). `FolderType` (`PHOTO_DIARY`, `REELS_PLACE`) is separated from `FolderRole` (`OWNER`, `EDITOR`, `VIEWER`), which avoids conflating content invariants with membership capabilities.
- **Contract governance:** The OpenAPI ownership model is appropriate for foundation scope. `OpenApiConfig` defines the runtime document identity, `OpenApiContractTests` checks `/api-docs` against the checked-in contract, `OpenApiContractArtifactTests` writes the generated artifact, and `build.gradle.kts` exposes focused refresh tasks (`build.gradle.kts:22-23,42-58`; `OpenApiContractTests.java:28-55`; `OpenApiContractArtifactTests.java:24-40`). The checked-in contract is intentionally skeletal (`paths:{}`) but valid for a no-endpoint foundation checkpoint.
- **Product/runtime contract:** The local runtime story is internally consistent. `.env.example` documents `DB_*`, `POSTGRES_*`, and storage placeholders; `docker-compose.yml` provisions Postgres 16 with matching defaults and healthcheck; `application.yml` imports optional `.env` and uses the same datasource password default; `LocalRuntimeContractTests` reads the same env surface and checks both connectivity and the checked-in `.env.example` values.
- **Shared error surface:** `GlobalExceptionHandler` explicitly maps `DomainException` by `ErrorCode`, including `FOLDER_WRITE_NOT_ALLOWED -> 403`, and uses a generic message for uncaught exceptions instead of leaking raw internals (`GlobalExceptionHandler.java:18-52`; `GlobalExceptionHandlerTests.java:15-32`). That is adequate for foundation scope.
- **Storage seam:** `StorageProperties` is provider-aware without overcommitting. `LOCAL` is the default, and `S3_COMPATIBLE` is guarded by `@AssertTrue` so the app cannot start with partial bucket/endpoint/credential configuration (`StorageProperties.java:9-22`; `StoragePropertiesContractTests.java:31-50`).
- **Code quality concern:** `StoragePropertiesContractTests` asserts fixed default values while `application.yml` still imports optional `.env`. Because `@SpringBootTest` loads the main config path first, a legitimate local override can flip the storage provider or endpoint and fail the checked-in contract test even though the runtime contract itself remains valid.

## Root Cause
The remaining issue comes from mixing two different goals in the same test context: runtime parity with local `.env` overrides and deterministic default-value assertions. The main application config keeps the override seam open, but the storage contract test still treats defaults as immutable.

## Findings
- **LOW** — `src/main/resources/application.yml:5`, `src/test/java/com/picturejournal/shared/config/StoragePropertiesContractTests.java:31-34`, `src/test/resources/application-test.yml:1-25`  
  The storage default contract test is not hermetic because the test context still inherits the optional `.env` import from the main application config. A supported local override such as `STORAGE_PROVIDER=S3_COMPATIBLE` or a custom endpoint can make the checked-in test fail for the wrong reason.  
  **Fix:** Disable the `.env` import for the test profile or pin the expected storage properties directly inside the test/test profile.

## Recommendations
1. Keep the current G001 foundation structure, OpenAPI pipeline, folder policy boundary, and local Docker Postgres contract as the baseline for the next checkpoint.
2. Isolate `StoragePropertiesContractTests` from developer-local `.env` overrides so the defaults contract stays deterministic.
3. Do not broaden scope at this checkpoint; the remaining issue is hardening, not a missing foundation deliverable.

## Architectural Status
CLEAR

## Code Review Recommendation
COMMENT

## Tradeoffs
- **Keep `.env` import active in tests**: better local parity, weaker hermeticity.
- **Disable `.env` import in tests**: stronger deterministic contract tests, slightly less parity with a developer's customized runtime.
- **Override only storage properties in the test**: preserves most parity while stabilizing the specific defaults assertion.
