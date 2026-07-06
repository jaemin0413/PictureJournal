## Summary
G001 foundation 범위에서는 부트스트랩, 로컬 Postgres 계약, OpenAPI 스냅샷 흐름, 스토리지 설정 바인딩 seam 자체는 일관됩니다. 다만 공용 오류 계층이 아직 foundation 계약으로 닫히지 않았고, 운영 노출 기본값도 느슨해서 이 상태로는 상위 기능이 기대하는 공통 API 계약을 안정적으로 받쳐주기 어렵습니다.

## Claims
- 애플리케이션 부트스트랩과 설정 바인딩 기반은 갖춰져 있습니다: `src/main/java/com/picturejournal/PictureJournalApplication.java`, `src/main/java/com/picturejournal/shared/config/StorageProperties.java`.
- 로컬 런타임 계약은 Postgres 중심으로 맞물립니다: `.env.example`, `docker-compose.yml`, `src/main/resources/application.yml`, `src/test/java/com/picturejournal/LocalRuntimeContractTests.java`.
- OpenAPI 계약 고정 장치는 존재하지만 현재 surface는 빈 `paths` 상태입니다: `contracts/openapi/picture-journal.openapi.json`, `src/test/java/com/picturejournal/OpenApiContractTests.java`, `src/test/java/com/picturejournal/OpenApiContractArtifactTests.java`.
- 공용 오류 계층은 `DomainException` 전용 경로와 generic catch-all만 제공하며, validation/500 redaction 경계가 완성되지 않았습니다: `src/main/java/com/picturejournal/shared/error/ErrorCode.java`, `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java`.

## Analysis
### Stage 1 — Spec compliance
- G001 foundation 범위에서 요구되는 기본 골격(앱 기동, 설정 바인딩, 로컬 DB 계약, OpenAPI 산출물 고정, 폴더 권한 seam)은 전반적으로 준비되어 있습니다.
- `FolderCapabilityPolicy`와 관련 계약 테스트는 폴더 쓰기 권한 경계를 명시적으로 강제하는 방향으로 잘 잡혀 있습니다: `src/main/java/com/picturejournal/folder/application/FolderCapabilityPolicy.java`, `src/test/java/com/picturejournal/folder/application/FolderCapabilityPolicyContractTests.java`.
- 반면 공용 오류 foundation은 아직 스스로 정의한 taxonomy를 끝까지 집행하지 못합니다. `ErrorCode.INVALID_ARGUMENT`가 정의되어 있고 validation starter도 포함되어 있지만, `GlobalExceptionHandler`에는 validation/binding 예외 전용 매핑이 없습니다. 첫 `@Valid` 엔드포인트가 추가되는 즉시 Spring 기본 오류 포맷이 새어나와 foundation 응답 envelope가 깨집니다.

### Stage 2 — Architecture
- 설정 경계는 대체로 명확합니다. `StorageProperties`는 `@ConfigurationProperties`와 `@Validated`를 사용해 저장소 provider seam을 독립시켰고, `PictureJournalApplication`의 `@ConfigurationPropertiesScan`으로 바인딩도 활성화되어 있습니다.
- 로컬 스토리지 문서는 아직 "동작하는 로컬 스토리지 런타임"이 아니라 "향후 S3-compatible seam"에 가깝습니다. `.env.example`는 endpoint/access key를 문서화하지만 `docker-compose.yml`은 Postgres만 기동하고, `StorageProperties`도 `LOCAL`에서는 해당 값들을 실질적으로 요구하지 않습니다. G001에선 허용 가능하지만 실제 로컬 object storage 제공으로 오해되면 안 됩니다.
- OpenAPI 흐름은 `generateOpenApiContract` → `refreshOpenApiContract`로 단순하고 유지보수 가능하지만, 현재 고정 계약은 메타데이터만 담고 있습니다. foundation 단계에서는 acceptable하나 이후 첫 API 도입 시 오류 응답 스키마까지 같이 고정해야 drift를 막을 수 있습니다.

### Stage 3 — Code quality / security / performance
- `GlobalExceptionHandler.handleDomainException()`는 모든 `DomainException` 메시지를 그대로 반환합니다. 동시에 `ErrorCode.INTERNAL_ERROR`도 domain code로 허용하므로, 애플리케이션 코드가 `DomainException(INTERNAL_ERROR, ...)`를 던지는 순간 generic 500 경로의 redaction을 우회해 내부 메시지를 외부로 노출할 수 있습니다.
- `management.endpoint.health.show-details: always`와 actuator starter 조합은 foundation 공통 설정에서 상세 health 정보를 항상 노출합니다. 리뷰 범위 내에는 이를 보호할 security 설정이 없습니다.
- 테스트 구성은 전반적으로 목적이 명확합니다. 특히 `application-test.yml`에서 JDBC auto-config를 끄고 MockMvc 기반 OpenAPI 테스트를 독립시키는 결정은 적절합니다.

## Root Cause
핵심 문제는 foundation 공통 계층이 아직 "미래 기능이 얹혀도 동일 계약이 유지되는 닫힌 기반"으로 마무리되지 않았다는 점입니다. 오류 taxonomy, validation 예외, 500 redaction, actuator 운영 노출이 한곳에서 끝나지 않아 이후 기능 추가 시 각 기능이 공통 계약을 우회하거나 흔들 가능성이 큽니다.

## Findings
1. **MEDIUM** — `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java`
   - 영향: bean validation/binding 실패가 foundation `ErrorResponse`/`INVALID_ARGUMENT` 계약 밖으로 빠져나갑니다.
   - 근거: validation starter는 포함(`build.gradle.kts`), `INVALID_ARGUMENT`는 정의(`ErrorCode.java`)되어 있으나 handler는 `DomainException`과 generic `Exception`만 처리합니다.
   - 수정: `MethodArgumentNotValidException`, `BindException`, `ConstraintViolationException` 등을 `INVALID_ARGUMENT`와 구조화된 field details로 매핑하세요.
2. **MEDIUM** — `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java`
   - 영향: `DomainException(INTERNAL_ERROR, ...)` 경로가 내부 실패 메시지를 클라이언트에 그대로 노출할 수 있습니다.
   - 근거: `handleDomainException()`는 항상 `exception.getMessage()`를 응답에 싣고, `resolveStatus()`는 `INTERNAL_ERROR`를 500으로 허용합니다.
   - 수정: `INTERNAL_ERROR`를 `DomainException`에서 금지하거나, 해당 code는 catch-all과 동일한 generic message로 redaction하세요.
3. **MEDIUM** — `src/main/resources/application.yml`
   - 영향: production에서도 상세 actuator health 정보가 무인증으로 노출될 가능성이 큽니다.
   - 근거: actuator starter 포함(`build.gradle.kts`), `health,info` 노출 및 `show-details: always` 기본 설정, 리뷰 범위 내 security 설정 부재.
   - 수정: 상세 health는 non-prod profile로 제한하거나 인증된 경로 뒤로 보내세요.

## Recommendations
1. `GlobalExceptionHandler`를 foundation 계약의 단일 진실 원천으로 완성하세요: validation/binding 예외 → `INVALID_ARGUMENT`, internal failures → redacted 500.
2. actuator 상세 health 노출을 프로파일 분리 또는 인증 보호 뒤로 이동하세요.
3. 첫 실제 API 추가 시 OpenAPI 계약에 오류 응답 스키마까지 명시적으로 고정해 이후 drift를 막으세요.

## Architectural Status
WATCH

## Code Review Recommendation
REQUEST CHANGES

## Tradeoffs
- **지금 validation/error 계약을 닫기**
  - 장점: 이후 컨트롤러가 늘어나도 공통 오류 envelope가 깨지지 않음
  - 단점: G001 시점에 예외 매핑/테스트를 조금 더 작성해야 함
- **기능 추가 시마다 개별 보완**
  - 장점: 당장 파일 수는 적음
  - 단점: 첫 기능부터 Spring 기본 오류 포맷이 섞일 수 있고, foundation의 의미가 약해짐
- **actuator 상세 health를 기본 공개 유지**
  - 장점: 로컬/초기 운영 진단이 쉬움
  - 단점: 환경 정보 노출 위험이 커지고 이후 보안 변경 때 호환성 비용이 생김
