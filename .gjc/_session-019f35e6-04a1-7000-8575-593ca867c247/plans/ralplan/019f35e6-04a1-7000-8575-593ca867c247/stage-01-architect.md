## Summary
The reviewed auth/collaboration slice is structurally coherent and the checked-in OpenAPI contract is pinned by tests, but the public error boundary is still incomplete. I found one correctness issue that can surface 500s for ordinary client input mistakes, plus one contract gap where the documented API omits the shared error payloads the runtime already returns.

## Claims
- `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java` only maps `DomainException` and `BindException` to 4xx responses, then falls through to a catch-all `Exception` handler that emits `INTERNAL_ERROR`/500.
- The reviewed controllers accept JSON request bodies and enum/query/path conversion at the Spring MVC layer: `src/main/java/com/picturejournal/auth/api/AuthController.java` and `src/main/java/com/picturejournal/collaboration/api/CollaborationController.java`.
- The runtime already exposes a shared error envelope that callers rely on: `src/test/java/com/picturejournal/auth/api/AuthControllerTests.java` asserts `CONFLICT` and `UNAUTHORIZED`, and `src/test/java/com/picturejournal/collaboration/api/CollaborationControllerTests.java` asserts `INVALID_ARGUMENT` and `FOLDER_WRITE_NOT_ALLOWED` in `$.code`.
- The checked-in OpenAPI contract is governed as source of truth by `src/test/java/com/picturejournal/OpenApiContractTests.java`, which compares `/api-docs` byte-for-byte with `contracts/openapi/picture-journal.openapi.json`.
- The reviewed codebase contains no `@ApiResponse`, `@ApiResponses`, `@Operation`, or `@Schema` annotations in the target auth/collaboration/error/OpenAPI files, and the checked-in OpenAPI JSON contains no `"400"`, `"401"`, `"403"`, or `"409"` response entries.

## Analysis
### Spec compliance
- Happy-path auth and collaboration flows are present and covered at the controller/service level (`AuthControllerTests`, `CollaborationControllerTests`, `AuthServiceTests`, `CollaborationServiceTests`).
- Shared domain/application failures are intentionally normalized through `ErrorCode` and `GlobalExceptionHandler`, but that normalization stops short of framework-level request parsing/conversion failures.
- Because the checked-in contract is locked by `OpenApiContractTests`, any omission in `contracts/openapi/picture-journal.openapi.json` is not accidental drift; it is the currently approved external contract.

### Architecture
- Layering is otherwise clean: controllers translate HTTP concerns, services hold invariants, persistence sits behind store interfaces, and `OpenApiConfig` centralizes the security scheme.
- The weak spot is the boundary between Spring MVC input binding and the shared error contract. The application defines a stable error taxonomy (`ErrorCode`, `GlobalExceptionHandler.ErrorResponse`), but the HTTP adapter does not fully translate framework-originated client errors into that taxonomy.
- The OpenAPI surface mirrors that incompleteness: auth/security is documented, but error payloads and reviewed 4xx responses are absent from the contract, so the public spec under-describes actual behavior.

### Code quality / security / performance
- Password hashing, session TTL, file-store path hardening, and folder write authorization checks all look reasonable within this slice.
- The main correctness risk is misclassifying client mistakes as server faults, which pollutes observability and makes client remediation harder.

## Root Cause
The error-handling design only models application-thrown `DomainException`/`BindException` cases and leaves common Spring MVC request-binding failures to the generic 500 handler. In parallel, the OpenAPI generation path documents security but never documents the shared error envelope or 4xx responses, so the external contract lags behind the runtime behavior.

## Findings
1. **HIGH** — `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java:18-39`, with immediate implications in `src/main/java/com/picturejournal/auth/api/AuthController.java:27-46` and `src/main/java/com/picturejournal/collaboration/api/CollaborationController.java:36-90`
   - **Impact:** malformed JSON bodies, invalid enum values, and similar Spring MVC binding/conversion errors are not translated into `INVALID_ARGUMENT`; they fall into the generic `Exception` handler and surface as `INTERNAL_ERROR`/500. That breaks the shared API error contract for routine client mistakes across reviewed endpoints.
   - **Fix:** add explicit handlers for framework-level client-input failures such as `HttpMessageNotReadableException` and `MethodArgumentTypeMismatchException`/conversion failures, map them to 400 with the existing `ErrorResponse` shape, and add focused controller tests for malformed JSON and bad enum/query values.

2. **MEDIUM** — `contracts/openapi/picture-journal.openapi.json`, `src/main/java/com/picturejournal/shared/api/OpenApiConfig.java`, `src/test/java/com/picturejournal/OpenApiContractTests.java:37-59`
   - **Impact:** the checked-in OpenAPI contract documents bearer auth but omits the reviewed 4xx error responses and shared error schema that runtime tests already depend on (`$.code` in `AuthControllerTests` and `CollaborationControllerTests`). Client generators and contract consumers therefore cannot learn the actual failure shapes from the spec.
   - **Fix:** annotate the reviewed endpoints (or shared advice/components) with explicit error responses/schema references, regenerate `contracts/openapi/picture-journal.openapi.json`, and extend the contract assertions to pin at least representative 400/401/403/409 responses.

## Recommendations
1. Fix the 4xx/500 classification bug first by extending `GlobalExceptionHandler` to cover framework-level request parse/conversion failures.
2. After the runtime error mapping is complete, publish it in OpenAPI so the checked-in contract matches the real error surface, not just the happy path.
3. Add contract-level regression coverage for the documented error responses once the contract is regenerated.

## Architectural Status
WATCH

## Code Review Recommendation
REQUEST CHANGES

## Tradeoffs
- **Keep the current generic catch-all behavior:** simplest implementation, but it mislabels client mistakes as server failures and undermines the shared error taxonomy.
- **Map framework input failures into the shared error envelope:** slightly more handler code and test surface, but it yields a consistent API contract and cleaner operational signals.
- **Document only success responses in OpenAPI:** lower annotation overhead, but contract consumers remain blind to the runtime error model already enforced in tests.
- **Document representative shared 4xx responses:** more explicit maintenance work, but the checked-in contract becomes useful for real client integration.
