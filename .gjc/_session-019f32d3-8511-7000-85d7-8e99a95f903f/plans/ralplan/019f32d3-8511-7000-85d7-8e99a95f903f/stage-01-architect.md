## Summary
구조 자체는 G001 의도에 대체로 맞습니다. `PictureJournalApplication`의 `@ConfigurationPropertiesScan`, `FolderType`, `FolderCapabilityPolicy`, `DomainException`/`ErrorCode`, 그리고 `OpenApiConfig` + checked-in OpenAPI 계약으로 Spring Boot 기반 스켈레톤, 오류/설정 taxonomy, 폴더 권한 경계, 백엔드 소유 계약 파이프라인의 뼈대는 확인됩니다.
다만 로컬 Docker Postgres 계약이 `application.yml`과 나머지 계약 소스들 사이에서 어긋나 있고, OpenAPI 계약 갱신 태스크가 전체 테스트 스위트에 묶여 있어 파이프라인 경계가 흐려집니다. 읽기 전용 검토 기준으로는 아직 체크포인트 진행 승인 전 수정이 필요합니다.

## Claims
- Spring Boot DDD skeleton의 최소 부팅/설정 스캔 경계는 `src/main/java/com/picturejournal/PictureJournalApplication.java`에서 확인됩니다.
- 공유 오류 taxonomy와 폴더 권한 ownership boundary는 `src/main/java/com/picturejournal/shared/error/ErrorCode.java`, `src/main/java/com/picturejournal/shared/error/DomainException.java`, `src/main/java/com/picturejournal/folder/application/FolderCapabilityPolicy.java`로 확인됩니다.
- 폴더 타입 분리는 `src/main/java/com/picturejournal/folder/domain/FolderType.java`의 별도 enum으로 분리되어 있습니다.
- 백엔드 소유 OpenAPI 계약 파이프라인 형태는 `src/main/java/com/picturejournal/shared/api/OpenApiConfig.java`, `src/test/java/com/picturejournal/OpenApiContractTests.java`, `build.gradle.kts`, `contracts/openapi/picture-journal.openapi.json`에 의해 확인됩니다.
- 로컬 런타임 계약은 `.env.example`, `docker-compose.yml`, `src/main/resources/application.yml`, `src/test/java/com/picturejournal/LocalRuntimeContractTests.java`에 중복 정의되어 있으며 이 중 일부가 불일치합니다.
- MinIO-ready storage seam은 `StorageProvider` + `StorageProperties` + `application.yml` + `.env.example`로 존재하지만, `S3_COMPATIBLE` 경로의 유효성 보장과 계약 검증은 약합니다.

## Analysis
- **Architecture status detail: WATCH** — 계층/경계 방향은 괜찮습니다. `FolderCapabilityPolicy`가 애플리케이션 레이어 권한 소유 경계를 명시하고, 공유 오류 코드가 foundation taxonomy 역할을 수행합니다. OpenAPI도 런타임에서 생성된 결과를 checked-in 계약과 비교하는 형태라 ownership 방향은 맞습니다.
- **Product status detail: BLOCK** — 승인된 foundation intent 중 "local Docker Postgres runtime contract"가 아직 단일 계약으로 수렴되지 않았습니다. `application.yml`은 `DB_PASSWORD` 기본값을 `picturejournal`로 두지만, `.env.example`, `docker-compose.yml`, `LocalRuntimeContractTests`는 모두 `picturejournal-dev-password`를 계약값으로 봅니다. `.env` import가 optional인 이상 fallback 자체가 계약인데, 현재 앱 기본 부팅 경로가 문서화된 Docker 계약과 맞지 않습니다.
- **Code status detail: WATCH** — 구현 난이도는 낮고 구조 파손은 없지만, OpenAPI 계약 갱신 태스크가 전체 `test` 태스크에 의존해 계약 파이프라인이 불필요하게 실 DB 전제(LocalRuntimeContractTests)에 묶여 있습니다. 또한 storage seam은 현재 LOCAL 기본값만 검증하며 `S3_COMPATIBLE` 분기에서 fail-fast 보장이 없습니다.
- `src/test/resources/application-test.yml`가 JDBC 자동구성을 제외해 `OpenApiContractTests`를 격리한 점은 좋습니다. 문제는 그 격리된 테스트가 `build.gradle.kts`에서 전체 `test`에 합류되면서 실제 갱신 경로에서는 경계 이점이 사라진다는 데 있습니다.
- `contracts/openapi/picture-journal.openapi.json`이 비어 있는 `paths`를 가진 것은 foundation skeleton 단계에서는 허용 가능합니다. 현재 검토 범위에서는 "엔드포인트 없음"보다 "계약 소유/동기화 방식 존재"가 핵심입니다.

## Root Cause
근본 원인은 foundation 계약이 여러 파일에 수동 복제되어 있고, 각 계약(애플리케이션 기본값, Docker 런타임, 문서 샘플, 테스트, OpenAPI 갱신 흐름)을 하나의 진실 공급원으로 묶지 않았다는 점입니다. 그래서 로컬 DB 자격증명은 서로 다른 기본값으로 드리프트했고, OpenAPI 갱신도 전용 계약 테스트 대신 전체 테스트 스위트에 묶여 경계가 섞였습니다.

## Findings
- **HIGH** — `src/main/resources/application.yml` (lines 12, 51)
  - **Impact:** `DB_PASSWORD` fallback이 `picturejournal`이라서 `.env`를 복사하지 않았거나 부분적으로만 환경변수를 준 상태에서, 제공된 `docker-compose.yml`의 Postgres(`picturejournal-dev-password`)와 앱 기본 런타임 계약이 충돌합니다. 승인 범위의 local runtime contract가 깨집니다.
  - **Fix:** fallback을 문서/Compose/테스트와 같은 값으로 맞추거나, `.env`를 필수화하고 충돌하는 fallback 경로를 제거하세요.
- **MEDIUM** — `build.gradle.kts` (line 43)
  - **Impact:** `refreshOpenApiContract`가 전체 `test`에 의존하므로, checked-in OpenAPI 계약 갱신이 로컬 Postgres 가용성까지 요구합니다. OpenAPI 계약 파이프라인이 자체적으로 재현 가능하지 않고 unrelated runtime test에 결합됩니다.
  - **Fix:** `OpenApiContractTests`만 실행하는 전용 test task/filter/source set을 만들어 그 결과물로 계약을 갱신하세요.
- **LOW** — `src/main/java/com/picturejournal/shared/config/StorageProperties.java` (lines 6-11), `src/test/java/com/picturejournal/shared/config/StoragePropertiesContractTests.java` (lines 18-21)
  - **Impact:** MinIO-ready seam이 enum/record 수준에 머물고 `S3_COMPATIBLE`에서 endpoint/bucket/credentials가 필수인지 보장하지 않습니다. 향후 활성화 시 잘못된 설정이 부팅 시점에 걸러지지 않고 첫 사용 시점까지 지연될 수 있습니다.
  - **Fix:** provider-aware validation을 추가하고 `S3_COMPATIBLE` 바인딩/검증 계약 테스트를 최소 1개 두세요.

## Recommendations
1. `application.yml`의 `DB_PASSWORD` 기본값을 `.env.example`, `docker-compose.yml`, `LocalRuntimeContractTests`와 일치시키세요. 이 항목이 G001 승인 범위에서 가장 직접적인 blocker입니다.
2. `refreshOpenApiContract`를 전체 `test`에서 분리해 OpenAPI 계약 갱신이 로컬 DB 의존 없이 실행되도록 하세요.
3. storage seam에 대해 `S3_COMPATIBLE` 유효성 계약을 명시적으로 추가하세요. 이건 즉시 blocker는 아니지만 foundation seam 품질을 올립니다.

## Architectural Status
WATCH

## Code Review Recommendation
REQUEST CHANGES

## Tradeoffs
- **현재 방식 유지 (`refreshOpenApiContract -> test`)**
  - 장점: 태스크 정의가 단순함.
  - 단점: OpenAPI 계약 갱신이 실 DB 테스트에 종속되어 파이프라인 ownership이 흐려짐.
- **전용 OpenAPI test task로 분리**
  - 장점: 계약 갱신이 재현 가능하고 빠르며, runtime contract와 API contract를 독립 검증 가능.
  - 단점: Gradle task 하나를 더 관리해야 함.
- **`.env` optional fallback 유지**
  - 장점: 로컬 부팅 편의성.
  - 단점: fallback도 계약이 되므로 드리프트 위험이 큼.
- **`.env` 필수화 또는 단일 소스화**
  - 장점: 계약 불일치 감소.
  - 단점: 초기 설정 단계가 약간 더 엄격해짐.

### Compact verdict
- architectureStatus: WATCH
- productStatus: BLOCK
- codeStatus: WATCH
- recommendation: REQUEST CHANGES
- checkpointReady: 아니오. 로컬 Postgres 계약 정합성과 OpenAPI 계약 갱신 경계 정리가 먼저 필요합니다.
