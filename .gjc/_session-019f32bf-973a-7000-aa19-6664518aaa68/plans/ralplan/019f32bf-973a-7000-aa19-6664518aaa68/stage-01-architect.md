## Summary
G001 foundation은 Spring Boot 골격, `folder/application`·`folder/domain` 분리, `FolderCapabilityPolicy` 경계, OpenAPI/Swagger 경로, Docker Postgres 개발 계약, `.env` ignore 측면에서는 계획과 대체로 맞습니다. 다만 `FolderCapabilityPolicy.folderWriteNotAllowed(...)`가 만드는 `DomainException`이 현재 API 오류 계약으로 매핑되지 않아 도메인/인가 실패가 `500 INTERNAL_SERVER_ERROR`로 노출되는 구조적 결함이 있어 승인하면 안 됩니다.

## Claims
- DDD 스켈레톤과 명시적 폴더 쓰기 경계는 존재합니다: `src/main/java/com/picturejournal/folder/application/FolderCapabilityPolicy.java:11-25`, `src/main/java/com/picturejournal/folder/domain/FolderRole.java`.
- 초기 OpenAPI/runtime 계약의 표면은 있습니다: `src/main/java/com/picturejournal/shared/api/OpenApiConfig.java:13-19`, `src/main/resources/application.yml:28-31`.
- Docker Postgres 개발 계약과 dev-safe 예시는 있습니다: `docker-compose.yml:7-15`, `.env.example:1-8`, `.gitignore:1-4`.
- 부모 에이전트가 남긴 검증 산출물상 테스트는 통과했습니다: `build/test-results/test/TEST-com.picturejournal.PictureJournalApplicationTests.xml:2-4`, `build/test-results/test/TEST-com.picturejournal.LocalRuntimeContractTests.xml:2-4`.

## Analysis
- 아키텍처 측면에서 패키지 레이아웃은 최소한의 DDD 골격을 형성합니다. 메인 소스가 `folder/application`, `folder/domain`, `shared/api`, `shared/error`로 분리되어 있고, 폴더 쓰기 권한 판단을 `FolderCapabilityPolicy.assertCanWriteToFolder(...)`로 집중시키려는 의도가 명확합니다 (`FolderCapabilityPolicy.java:11-18`). 이는 승인된 계획의 "explicit folder-scoped write boundary" 요구와 맞습니다.
- 제품/운영 측면에서 로컬 개발 계약은 대체로 갖춰져 있습니다. `docker-compose.yml`은 Postgres 16, 포트/DB/user/password 기본값, healthcheck를 제공하고 (`docker-compose.yml:1-18`), `.env.example`은 로컬 안전값만 포함하며 실제 `.env`는 ignore 됩니다 (`.env.example:1-8`, `.gitignore:1-4`).
- OpenAPI 초기 표면도 존재합니다. `OpenApiConfig`는 backend-owned `OpenAPI` bean을 만들고, 런타임 경로는 `/api-docs`, `/docs`로 고정됩니다 (`OpenApiConfig.java:13-19`, `application.yml:28-31`). 이는 아직 리소스별 스키마가 비어 있어도 foundation 단계의 초기 파이프라인으로는 충분합니다.
- 그러나 오류 계약은 핵심 경계를 끝까지 보존하지 못합니다. `FolderCapabilityPolicy.folderWriteNotAllowed(...)`는 `ErrorCode.FOLDER_WRITE_NOT_ALLOWED`를 실은 `DomainException`을 만들지만 (`FolderCapabilityPolicy.java:20-25`), `GlobalExceptionHandler`는 예외에서 `status`, `code`, `details` 속성만 반사적으로 읽고 (`GlobalExceptionHandler.java:16-18`, `:36-45`, `:50-72`), `DomainException`은 `errorCode`만 노출합니다 (`DomainException.java:8-24`). 그 결과 도메인/인가 실패는 `500` + `INTERNAL_SERVER_ERROR`로 강등됩니다. 이는 explicit boundary가 있어도 runtime contract가 그 의미를 전달하지 못한다는 뜻입니다.
- 추가로, 로컬 런타임 검증 표면은 한 가지 어긋남이 있습니다. `.env.example`은 `SPRING_DATASOURCE_*`와 `POSTGRES_*`를 문서화하지만 (`.env.example:1-8`), `LocalRuntimeContractTests`는 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`만 읽습니다 (`LocalRuntimeContractTests.java:15-17`). 현재 테스트는 하드코딩 기본값으로 성공할 수 있어, 문서화된 환경 표면과 테스트 표면이 동일하다고 보기는 어렵습니다.

## Root Cause
오류 처리 레이어가 도메인 예외를 명시적으로 모델링하지 않고, 임의의 `Exception`을 Bean reflection으로 느슨하게 해석하는 우회 경로에 의존한 것이 근본 원인입니다. 이 방식은 `DomainException`의 실제 계약(`errorCode`)과 핸들러가 기대하는 계약(`code`, `status`, `details`)을 분리시켜, 경계는 선언했지만 의미는 런타임에서 유실되게 만듭니다.

## Findings
1. **HIGH** — `src/main/java/com/picturejournal/shared/error/GlobalExceptionHandler.java:16-45`, `src/main/java/com/picturejournal/shared/error/DomainException.java:8-24`, `src/main/java/com/picturejournal/folder/application/FolderCapabilityPolicy.java:20-25`
   - **Impact:** 폴더 쓰기 거부 같은 예상 가능한 도메인/인가 실패가 `500 INTERNAL_SERVER_ERROR`로 반환되어 API 계약이 잘못됩니다. 계획상 explicit boundary와 initial runtime contract를 연결하지 못합니다.
   - **Fix suggestion:** `DomainException` 전용 핸들러를 추가하고 `ErrorCode -> HttpStatus` 매핑을 명시적으로 정의하십시오. reflection fallback은 최후의 generic handler로만 남기고, `code`는 `errorCode.name()` 또는 별도 안정 문자열로 직접 직렬화하십시오.
2. **MEDIUM** — `src/test/java/com/picturejournal/LocalRuntimeContractTests.java:15-17`, `.env.example:1-8`
   - **Impact:** 검증 테스트가 문서화된 `.env.example` 표면을 직접 소비하지 않아, 로컬 런타임 계약 드리프트를 잡지 못할 수 있습니다.
   - **Fix suggestion:** 테스트가 `SPRING_DATASOURCE_*` 또는 `.env.example`와 동일한 단일 env naming scheme을 읽도록 맞추십시오. 또는 앱/테스트/compose 전반을 하나의 명명 체계로 통일하십시오.

## Recommendations
1. `DomainException`을 runtime error contract의 1급 입력으로 승격하고 `ErrorCode`별 HTTP status 매핑을 명시하십시오. 이 작업 전까지는 G001 foundation 승인을 보류해야 합니다.
2. 로컬 개발 환경 변수 표면을 하나로 정리하고, `LocalRuntimeContractTests`가 그 표면을 실제로 검증하도록 맞추십시오.
3. 그 외 foundation 골격(패키지 분리, OpenAPI bootstrap, Docker Postgres, `.env` ignore)은 유지해도 됩니다.

## Architectural Status
WATCH

## Code Review Recommendation
REQUEST CHANGES

## Tradeoffs
- **현재 reflection 기반 generic handler 유지**
  - 장점: 예외 타입 추가 시 보일러플레이트가 적음.
  - 단점: 도메인 계약이 암묵적 속성명에 의존해 쉽게 깨지고, 현재처럼 `DomainException` 의미가 유실됨.
- **명시적 `DomainException`/`ErrorCode` 매핑 도입**
  - 장점: 경계 의미와 API 계약이 일치하고, 장기 유지보수성이 높음.
  - 단점: 초기 매핑 테이블 작성이 필요함.
