## Summary
G001 foundation은 전반적으로 일관적이다. `build.gradle.kts`의 OpenAPI 산출물 파이프라인과 `contracts/openapi/*` 검증, `FolderCapabilityPolicy`를 통한 폴더 쓰기 권한 경계, `StorageProperties`의 provider별 검증은 모두 foundation scope에 맞는 명시적 계약을 만든다.

다만 외부 에러 표면과 로컬 런타임 설정 표면 중 두 곳이 아직 accidental contract를 굳히고 있다. `GlobalExceptionHandler`는 예상치 못한 예외 메시지를 그대로 노출하고, 로컬 런타임 계약 테스트는 설정 표면이 허용하는 DB override와 다르게 기본 DB 이름을 하드코딩한다. 이 두 점은 G001에서 먼저 바로잡는 편이 낫다.

## Claims
- OpenAPI 계약은 런타임 `/api-docs`를 checked-in contract와 byte-for-byte가 아니라 semantic JSON equality로 비교하는 구조다 (`build.gradle.kts:22-23,42-57`, `src/test/java/com/picturejournal/OpenApiContractTests.java:37-55`, `src/test/java/com/picturejournal/OpenApiContractArtifactTests.java:28-40`).
- 폴더 범위 쓰기 권한은 application-layer policy boundary로 고정돼 있다 (`src/main/java/com/picturejournal/folder/application/FolderCapabilityPolicy.java:18-25`, `src/test/java/com/picturejournal/folder/application/FolderCapabilityPolicyContractTests.java:20-23,54,68-72`).
- 저장소 설정은 `LOCAL`과 `S3_COMPATIBLE`를 enum으로 제한하고, S3 모드에서만 필수 속성을 강제한다 (`src/main/java/com/picturejournal/shared/config/StorageProperties.java:10-22`, `src/test/java/com/picturejournal/shared/config/StoragePropertiesContractTests.java:31-50`).
- 전역 예외 처리기는 `DomainException`과 일반 `Exception`을 모두 HTTP 응답으로 직렬화한다 (`src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java:13-35`).
- 로컬 런타임 표면은 `.env.example`, `docker-compose.yml`, `application.yml`에서 DB 관련 값을 override 가능하게 드러내지만, 계약 테스트는 DB 이름을 기본값으로 고정한다 (`docker-compose.yml:9-15`, `.env.example:5-7`, `src/main/resources/application.yml:10-12,49-51`, `src/test/java/com/picturejournal/LocalRuntimeContractTests.java:20-30`).

## Analysis
- 아키텍처 경계는 foundation 단계치고 꽤 선명하다. `PictureJournalApplication`에서 `@ConfigurationPropertiesScan`으로 설정 바인딩을 활성화하고 (`src/main/java/com/picturejournal/PictureJournalApplication.java:7-12`), OpenAPI 메타데이터를 코드에서 소유한 뒤 (`src/main/java/com/picturejournal/shared/api/OpenApiConfig.java:15-19`) 테스트와 Gradle task로 checked-in contract를 동기화하는 방식은 backend-owned contract governance의 최소 형태로 충분히 설득력 있다.
- `FolderCapabilityPolicy`는 아직 구현이 없는 인터페이스지만, 지금 단계에서 오히려 장점이 있다. 도메인/애플리케이션 write path가 이후 어떤 persistence나 membership 모델을 쓰더라도 반드시 통과해야 할 권한 소유점을 선점했고, 테스트가 그 boundary를 직접 호출하도록 고정했다.
- `StorageProperties`도 foundation 범위에 맞다. provider switch를 enum으로 닫고 S3-compatible 경로만 엄격하게 검증해서, 현재는 LOCAL 기본값으로 부팅하면서도 미래 확장 seam을 남겼다.
- 그러나 에러 경계는 아직 public contract로 내보내기엔 거칠다. `handleException`이 모든 uncaught exception의 원문 메시지를 그대로 응답 본문에 넣고, 테스트도 그 행동을 정답으로 잠가 둔다. 지금은 컨트롤러가 거의 없어서 노출 면이 작아 보이지만, foundation code에서 이 패턴을 고정하면 이후 JDBC/validation/framework 예외까지 그대로 API surface로 새어 나가게 된다.
- 로컬 런타임 계약도 약간 비정합적이다. compose와 application config는 DB 값을 override 가능한 seam으로 문서화하는데, `LocalRuntimeContractTests`는 연결 URL은 override해서 사용하면서 검증값은 `picturejournal`로 고정한다. 결국 “defaults-only contract”인지 “overrideable local contract”인지가 테스트 수준에서 모호하다.

## Root Cause
근본 원인은 G001에서 “무엇이 의도된 외부 계약이고 무엇이 단지 현재 구현 세부사항인지”를 두 영역에서 아직 분리하지 못한 것이다. 그 결과 테스트가 의도된 제품 규칙이 아니라 우연한 현재 동작(원문 예외 메시지, 기본 DB 이름)을 계약으로 굳혀 버렸다.

## Findings
1. **MEDIUM** — Raw internal exception messages are exposed as the public 500 contract.
   - **Files:** `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java:27-35`, `src/test/java/com/picturejournal/shared/error/GlobalExceptionHandlerTests.java:27-32`
   - **Impact:** Any uncaught exception will echo framework/driver/business-internal text to API callers. That leaks internals, makes the external contract unstable across dependency upgrades, and encourages clients to depend on accidental message wording.
   - **Fix:** Return a fixed generic message for `INTERNAL_ERROR`, log the actual exception server-side, and if needed include an opaque correlation id. Update the test to assert the stable contract rather than the raw exception text.

2. **LOW** — The local runtime contract test conflicts with the overrideable DB configuration surface.
   - **Files:** `src/test/java/com/picturejournal/LocalRuntimeContractTests.java:20-30`, `docker-compose.yml:9-15`, `.env.example:5-7`, `src/main/resources/application.yml:10-12,49-51`
   - **Impact:** The runtime surfaces advertise environment-driven DB customization, but the contract test hardcodes `current_database() == "picturejournal"` even when `DB_URL` is overridden. This makes the test reject legitimate local setups or, alternatively, makes the documented override knobs misleading.
   - **Fix:** Choose one contract explicitly: either (a) defaults-only, in which case stop reading env overrides in the test, or (b) overrideable local runtime, in which case derive the expected DB name from `DB_URL`/`POSTGRES_DB` and assert consistently.

## Recommendations
1. Fix the `INTERNAL_ERROR` response contract first: generic client message, server-side logging, optional correlation id.
2. Align the local runtime contract around a single story—either immutable defaults or supported overrides—and make `.env.example`, `docker-compose.yml`, `application.yml`, and `LocalRuntimeContractTests` tell the same story.
3. Keep the current OpenAPI artifact pipeline and policy/config seams; those are good foundation decisions for G001 and do not need redesign.

## Architectural Status
WATCH

## Code Review Recommendation
REQUEST CHANGES

## Tradeoffs
- **500 response message**
  - Generic message + logs: safer and more stable public API, worse direct client-side debugging.
  - Raw exception message: easier ad hoc debugging, but leaks internals and creates accidental API coupling.
- **Local runtime contract**
  - Defaults-only: simplest onboarding/test story, but less flexible.
  - Overrideable via env: more flexible for contributors/CI, but tests and docs must derive expectations from configuration instead of hardcoding defaults.
