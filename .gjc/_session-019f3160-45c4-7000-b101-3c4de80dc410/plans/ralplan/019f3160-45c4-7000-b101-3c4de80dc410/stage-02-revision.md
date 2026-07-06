# RALPLAN-DR 01 — Picture Journal MVP Initial Consensus Draft (Revised)

## Summary
이 계획은 협업 가능한 타입 고정 폴더를 중심으로 `photo_diary`와 `reels_place`를 분리한 Picture Journal MVP의 deliberate-mode 1차 합의 초안이다. 이번 수정안은 백엔드를 **Spring Boot로 고정**하고, 전체 설계를 **DDD(domain-driven design)** 기반으로 재구성한다. 또한 클라이언트 전략은 웹 우선/모바일 보조가 아니라, **웹과 모바일을 모두 1급 클라이언트로 취급하는 dual-primary 구조**로 전환한다. 핵심 제품 경험은 두 클라이언트가 공통 도메인 규칙·API 계약·디자인 토큰을 공유하되, UI는 플랫폼 특성에 맞게 별도 구현하는 방식이다.

## Title + Scope
**Title:** Picture Journal MVP — Spring Boot + DDD 기반 협업 폴더형 사진일기 / 저장장소 서비스

**Scope:**
- 회원 기반 인증/로그인
- 타입 고정 협업 폴더 생성: `photo_diary` / `reels_place`
- 초대 링크 기반 멤버 참여와 역할 반영
- `photo_diary` 폴더에서 단일 사진 업로드, EXIF 분석, 위치 보정, 지도/리스트/상세 조회
- `reels_place` 폴더에서 모바일 공유 또는 동등한 네이티브 공유 진입 기반 링크/텍스트 intake, 후보 확인/선택/수동 보정, 저장 장소 관리
- 서버 사이드 지오코딩, 캐시, rate limiting, provider adapter
- iPhone/Android 공유 진입 지원
- 웹/모바일 dual-primary 클라이언트 체계

## Intent Diff
- **From:** 웹 중심 제품 + 모바일 보조 share ingestion
- **To:** Spring Boot + DDD 백엔드 위에 웹/모바일을 모두 1급 클라이언트로 두는 구조
- **Why this matters:** 이번 제품은 협업 관리, 지도 조회, 사진 업로드, 모바일 공유 수집이 모두 핵심 가치다. 따라서 웹만 중심으로 두면 모바일 product quality가 희생되고, 모바일만 중심으로 두면 협업/관리 UX가 약해진다. dual-primary가 요구와 더 정합적이다.

## RALPLAN-DR Summary
### Principles
1. **폴더 타입이 도메인 경계를 강제해야 한다.** `photo_diary`와 `reels_place`는 저장 모델, 유스케이스, 조회 모델, 권한 검증에서 섞이지 않는다.
2. **도메인 규칙은 Spring Boot DDD 계층 안에 모여야 하며, UI나 controller에 흩어지면 안 된다.** 핵심 정책은 aggregate/application service/use case로 수렴한다.
3. **웹과 모바일은 모두 1급 클라이언트다.** 공통 API 계약, 인증 모델, 디자인 토큰, 상태 의미를 공유하되 UI는 플랫폼별 최적 구현을 허용한다.
4. **모바일 공유는 플랫폼별 차이를 서버 intake 표준화로 흡수해야 한다.** iOS/Android raw payload 차이는 backend contract가 정리한다.
5. **저장 실패보다 복구 가능한 저장이 우선이다.** 후보 0개, AI timeout, 비공개 링크 등에서도 `share_intake` draft를 남겨 후속 보정 경로를 보장한다.

### Top 3 Decision Drivers
1. **Backend is fixed to Spring Boot, and architecture is DDD.** 따라서 패키지/계층 구조와 유스케이스 경계가 먼저 명확해야 한다.
2. **웹과 모바일이 모두 핵심 사용자 접점이다.** one-primary framing은 현재 요구에 맞지 않는다.
3. **iPhone 네이티브 공유 진입은 필수다.** PWA-only 또는 web-fallback-only 접근은 acceptance와 충돌한다.

### Viable Options
#### Option A — Dual-primary clients: Next.js web + React Native mobile, shared contracts, Spring Boot DDD backend **(Recommended)**
- **Shape:** 웹은 Next.js, 모바일은 React Native(Expo 기반 또는 필요한 native customization 포함), 백엔드는 Spring Boot DDD. API/OpenAPI, auth semantics, design tokens, domain vocab를 공유한다.
- **Pros:**
  - 웹과 모바일을 둘 다 강하게 지원 가능
  - iPhone/Android share entry와 웹 협업 UX를 동시에 만족
  - TypeScript 생태계로 web/mobile 개발 경험을 공유하면서 backend는 Spring Boot로 분리 가능
  - UI는 플랫폼별 최적화, 계약은 공통화 가능
- **Cons:**
  - 기술 스택이 다층적이라 계약 관리가 중요
  - React Native share extension/native integration은 조기 검증이 필요
  - web/mobile/backend 간 schema drift 방지 장치가 필요
- **Why balanced:** 클라이언트 둘 다 1급으로 유지하면서도, backend를 Spring Boot DDD로 안정화할 수 있는 가장 현실적인 균형안이다.

#### Option B — Dual-primary clients: separate native mobile + separate SPA/web, Spring Boot DDD backend
- **Shape:** 웹은 SPA 또는 SSR 웹, 모바일은 native-first 구현, backend는 동일
- **Pros:**
  - 각 클라이언트 최적화 여지가 가장 큼
  - 모바일 공유 진입 제약을 풀기 쉽다
- **Cons:**
  - 구현 비용과 팀 부담이 큼
  - design/API contract drift 리스크가 더 큼
  - greenfield MVP에 비해 과도한 운영 복잡도
- **Rejection reason:** MVP 단계에서 dual-primary는 필요하지만, 완전 분리형 클라이언트 전략은 비용이 과하다.

#### Option C — Web-primary with mobile ingestion shell
- **Pros:** 단기 구현 속도는 빠름
- **Cons:** 현재 변경 요구와 정면 충돌. 모바일을 1급 클라이언트로 다루지 못함
- **Rejection reason:** 사용자 요구가 명시적으로 바뀌었으므로 배제

## Decision Drivers
- Spring Boot 고정
- DDD 기반 설계 강제
- 도메인별 패키지 전략 필요
- dual-primary client 요구
- iPhone native share-entry 필수
- 폴더 타입 분리와 candidate 0 draft 저장 유지

## Options
### Primary Path
**Option A**를 추천한다. 즉, **Spring Boot DDD backend + Next.js web + React Native mobile** 조합을 dual-primary 전제로 채택한다. 이 조합은 웹 협업/지도 UX와 모바일 공유 진입 UX를 모두 강하게 지원하면서도, backend에서 도메인 규칙을 일관되게 통제하기 좋다.

### Why this is dual-primary, not web-primary
- 기능 우선순위를 웹에만 몰지 않는다.
- 인증, 폴더 목록/상세, 사진일기 조회/작성, 저장장소 조회/수정, 초대 수락은 웹과 모바일 둘 다 가능해야 한다.
- 모바일은 공유 수신만 담당하는 shell이 아니라, 자체적으로 폴더 선택/저장 확인/보정/조회가 가능한 정식 클라이언트여야 한다.
- 웹은 협업 관리와 대화면 지도 탐색에서 강점을 가지지만, 제품 가치의 절반인 share-ingestion과 이동 중 사용은 모바일이 담당한다.

## Explicit Recommended Architecture
### 1) Backend — Spring Boot + DDD
- **Framework:** Spring Boot
- **Architectural style:** DDD
- **Core rule:** 패키지는 기술 레이어가 아니라 **도메인 중심**으로 나눈다. 각 도메인 폴더 안에 domain/application/adapter를 둔다.
- **Why:** 폴더 타입 분리, 초대 링크, 사진일기 위치 필수, share draft/recovery 같은 정책이 기술 레이어보다 도메인 경계에 더 강하게 묶여 있기 때문이다.

### 2) Web Client — First-class
- **Primary responsibilities:** 협업 폴더 생성/관리, 멤버/초대, 사진일기 작성/조회, 저장장소 조회/보정, 지도/리스트/상세, 운영 친화적 관리 UX
- **Recommended shape:** Next.js 기반 웹 앱
- **Why:** 대화면 지도, 폼 입력, 협업 관리, 링크 기반 onboarding에 유리하다.

### 3) Mobile Client — First-class
- **Primary responsibilities:** iPhone/Android 네이티브 공유 수신, 저장 후보 확인/선택/보정, 사진일기/저장장소 조회·수정, 초대 수락, 기본 협업 사용
- **Recommended shape:** React Native 기반 모바일 앱
- **Why:** 네이티브 share entry가 필수이며, 이후 사용 흐름도 앱 안에서 닫아야 UX가 자연스럽다.

### 4) Shared Contracts Across Clients
웹과 모바일은 아래를 공유해야 한다.
- API schema / OpenAPI contract
- auth/session semantics
- domain vocabulary: folder type, membership role, intake status, visit status
- validation semantics
- design tokens / color / spacing / typography primitives
- error code taxonomy

공유하지 않는 것:
- UI component implementation 자체
- navigation/router implementation
- platform-native media/share integration

### 5) Data Model Boundaries
- **Collaboration boundary:** `users`, `folders`, `folder_memberships`, `folder_invites`
- **Photo diary boundary:** `media_assets`, `diary_entries`, `diary_tags`, `diary_entry_tags`
- **Share intake boundary:** `share_intake_items`, `place_candidates`
- **Saved place boundary:** `saved_places`
- **Infra boundary:** `geocode_cache`, `activity_logs`

핵심 규칙:
- `folders.type`는 단순 분류가 아니라 write-path 제약이다.
- `share_intake_items`와 `saved_places`는 합치지 않는다.
- 후보 0개, timeout, dead link는 intake draft/repair 상태로 남겨야 한다.

### 6) Provider Abstractions
- **Map tiles:** tile provider adapter
- **Geocoding:** server-side adapter (`nominatim` 우선), cache, rate limit, backoff
- **Place extraction:** AI/heuristic provider abstraction
- **Media storage:** object storage adapter
- **Reason:** 외부 provider 교체 가능성과 failure isolation을 확보해야 한다.

## Concrete Recommended Package Structure
아래는 Spring Boot + DDD 기준의 권장 패키지 구조다.

picturejournal
- common
  - config
  - security
  - web
  - exception
  - events
  - ids
  - time
- domains
  - auth
    - domain
      - model
      - service
      - event
      - exception
    - application
      - command
      - query
      - dto
      - usecase
      - service
    - adapter
      - in
        - web
      - out
        - persistence
        - security
        - external
  - collaboration
    - domain
      - model
        - Folder
        - FolderMembership
        - FolderInvite
        - FolderType
        - MembershipRole
      - service
      - event
      - exception
      - policy
    - application
      - command
      - query
      - dto
      - usecase
      - service
    - adapter
      - in
        - web
      - out
        - persistence
        - messaging
  - photojournal
    - domain
      - model
        - MediaAsset
        - DiaryEntry
        - DiaryTag
      - service
      - policy
      - exception
      - event
    - application
      - command
      - query
      - dto
      - usecase
      - service
    - adapter
      - in
        - web
      - out
        - persistence
        - storage
        - exif
        - geocode
  - placeintake
    - domain
      - model
        - ShareIntakeItem
        - PlaceCandidate
        - IntakeStatus
      - service
      - policy
      - exception
      - event
    - application
      - command
      - query
      - dto
      - usecase
      - service
    - adapter
      - in
        - web
        - async
      - out
        - persistence
        - extractor
        - queue
  - savedplace
    - domain
      - model
        - SavedPlace
        - VisitStatus
      - service
      - policy
      - exception
      - event
    - application
      - command
      - query
      - dto
      - usecase
      - service
    - adapter
      - in
        - web
      - out
        - persistence
        - geocode
  - location
    - domain
      - model
      - service
      - policy
    - application
      - command
      - query
      - dto
      - usecase
    - adapter
      - in
        - web
      - out
        - geocode
        - tile
        - cache

### Package Strategy Rules
1. **도메인마다 독립 폴더를 가진다.** `photojournal`, `placeintake`, `savedplace`를 기술 레이어로 합치지 않는다.
2. **각 도메인 안에 domain/application/adapter를 둔다.**
3. **domain은 순수 규칙과 모델 중심이다.** Spring annotation 의존을 최소화한다.
4. **application은 use case orchestration이다.** transaction boundary, permission check orchestration, port 호출 조합이 이 계층에 온다.
5. **adapter는 in/out으로 나눈다.** web controller, persistence adapter, external provider adapter를 분리한다.
6. **repository는 domain/application이 직접 JPA를 모르도록 port를 먼저 정의한다.**
7. **cross-domain 조합은 application service나 domain event를 통해 수행한다.** 예: share intake resolve 후 saved place 생성.

## In Scope / Out of Scope
### In Scope
- 회원가입/로그인
- 폴더 생성 시 타입 고정
- 초대 링크 생성/수락
- 단일 이미지 업로드 기반 사진일기 저장
- EXIF GPS 자동 사용 또는 수동 위치 보정
- 사진일기 지도/리스트/상세
- 공유 링크/텍스트 intake
- 후보 1개 확인, 다수 선택, 0개 draft 저장
- 저장 장소 CRUD와 지도 조회
- 서버 geocoding cache/throttle/provider adapter
- iOS/Android 공유 진입 MVP
- 웹/모바일 dual-primary 사용 흐름

### Out of Scope
- 이메일 초대
- 다중 사진 업로드
- AI 자동 일기 작성
- 장소 추천
- 폴더 내부 개인 비공개 일기
- 오프라인 지도
- 댓글/반응
- 방문 완료 후 사진일기 자동 연결

## File-level Changes
현재 제품 소스가 없다는 전제를 기준으로 execution 단계에서 예상되는 구조는 아래와 같다.

### Top-level
- `apps/web/*` — 웹 클라이언트
- `apps/mobile/*` — 모바일 클라이언트
- `apps/backend/*` 또는 `backend/*` — Spring Boot 애플리케이션
- `contracts/*` — OpenAPI, shared schemas, error catalog
- `design/*` — 디자인 토큰 및 클라이언트 공통 시각 계약

### Backend emphasis
- 도메인별 패키지 중심
- JPA entity를 infra concern으로만 보지 말고, aggregate 경계를 반영하는 persistence mapping 전략 필요
- controller/service/repository 횡단식 구조 금지

### Client emphasis
- web/mobile은 API contract와 domain semantics를 공유
- UI 구현은 각 앱에서 분리
- 모바일 share integration은 platform-specific 영역으로 캡슐화

## Sequencing and Dependencies
### Phase 0 — DDD Foundation + Contract Baseline
**Goal:** Spring Boot DDD 구조와 dual-primary contract baseline을 먼저 고정
- bounded context 후보 정리: auth, collaboration, photojournal, placeintake, savedplace, location
- package structure 확정
- OpenAPI / error code / auth semantics / enum vocabulary 확정
- 폴더 타입, 역할, intake 상태, visit status 정의

**Why first:** 패키지/계약이 흔들리면 web/mobile/backend 모두 빠르게 drift 난다.

### Phase 1 — Mobile Share Entry Risk Spike
**Goal:** iOS/Android native share entry가 실제로 intake 생성까지 이어지는지 검증
- iOS share extension 또는 동등 네이티브 진입
- Android share receiver
- 로그인/세션 확인
- raw payload를 backend `share_intake`까지 전송

**Why this order reduces risk:** iPhone 공유 진입은 구조 전체를 무효화할 수 있는 최대 리스크다.

### Phase 2 — Collaboration Core
**Goal:** auth, folder, membership, invite 링크, role enforcement 완성
- signup/login/me
- folder create/list/detail
- invite create/view/accept
- owner/editor/viewer action policy

**Dependency reason:** 모든 도메인 write는 folder scope와 role check에 의존한다.

### Phase 3 — Photo Diary Core
**Goal:** 방문한 장소 기록의 완전한 1개 가치 루프 구축
- image upload
- EXIF extraction
- GPS auto-fill or manual map/search
- diary CRUD
- map/list/detail

**Why before saved place core:** 위치 필수 정책과 지도/지오코딩/미디어 흐름을 더 단순한 문제로 먼저 검증할 수 있다.

### Phase 4 — Share Intake + Saved Place Core
**Goal:** 저장하고 싶은 장소 수집 흐름 완성
- intake create
- candidate extraction
- 1개 확인 / 다수 선택 / 0개 draft
- manual fix
- saved place CRUD

**Dependency reason:** Phase 1 spike와 location primitives, collaboration core를 재사용한다.

### Phase 5 — Dual-primary UX Completion
**Goal:** 웹/모바일 둘 다 product-complete 수준의 기본 UX 제공
- 폴더 목록/상세 parity 정의
- 초대 수락 parity
- 기본 지도/상세 parity
- share 후 후속 편집/조회 parity

**Why here:** 핵심 도메인 루프가 먼저 살아야 parity 범위를 냉정하게 조절할 수 있다.

### Phase 6 — Reliability / Provider Hardening / Observability
**Goal:** 운영 가능한 MVP 마감
- geocode cache/throttle/backoff
- intake retry/dead-letter semantics
- tracing/metrics/logging
- failed intake operational visibility

## Delivery Phases / Dependencies / Risk Reduction Rationale
1. **DDD foundation first**로 package drift와 정책 중복을 줄인다.
2. **share-entry spike second**로 iPhone 요구를 조기 검증한다.
3. **collaboration core before content**로 ACL과 folder type 불변성을 먼저 고정한다.
4. **photo diary before saved places**로 location/media/map primitive를 더 낮은 복잡도로 먼저 검증한다.
5. **dual-primary parity after core loops**로 무의미한 UI parity 작업을 방지한다.
6. **reliability hardening last**로 premature infra를 피하면서 release risk는 통제한다.

## Acceptance Criteria
1. Authenticated users can create a folder with immutable type `photo_diary` or `reels_place`.
2. Folder owner can generate an invite link with role, and an invited user can sign up/log in and accept into that role.
3. `photo_diary` folder member can upload one image, extract EXIF, and save diary only when location is present via GPS or manual selection/search.
4. Missing GPS blocks final save until manual location is set, but does not dead-end the flow.
5. All folder members can view all diary entries in that folder on map/list/detail surfaces.
6. `reels_place` folder member can create `share_intake` from native mobile share entry.
7. Candidate extraction supports: exactly 1 candidate → confirm; multiple candidates → choose one; 0 candidates → save draft and allow later manual fix.
8. iPhone and Android both support a native-equivalent share entry into the product.
9. Web and mobile both function as first-class clients for the MVP-critical flows defined for their platforms.
10. Folder map surfaces only show data allowed by the folder type.
11. Cross-type writes are rejected server-side regardless of client path.
12. Geocoding calls are server-side only, cached, and rate-limited.
13. Domain rules live in Spring Boot DDD use cases/domain policies, not duplicated inconsistently across clients.

## Verification
### Domain / Application Verification
- folder type invariant tests
- membership role policy tests
- invite lifecycle tests
- intake status machine tests
- saved place creation from resolved intake tests
- photo diary location-required policy tests

### Spring Boot Adapter / Integration Verification
- controller → application use case → repository port/adapter 흐름 검증
- persistence adapter가 aggregate 규칙을 깨지 않는지 검증
- external geocode/extractor adapter timeout/failure fallback 검증
- transaction boundary 검증

### Contract Verification
- OpenAPI schema drift 방지
- enum/status/error code contract validation
- web/mobile generated or verified client compatibility

### Cross-client Verification
- 웹에서 생성한 폴더를 모바일 공유 저장에 즉시 사용 가능
- 모바일에서 생성/해결한 saved place를 웹 지도/상세에서 즉시 조회 가능
- 웹 초대 링크를 모바일에서 수락 가능
- 모바일 로그인 후 share-entry continuity 검증

### External Dependency Verification
- geocoder rate limit simulation
- AI extraction timeout/failure → draft/manual fix fallback
- tile provider outage handling
- object storage upload failure recovery

## Deliberate-Only: Pre-Mortem
### Failure Scenario 1 — Spring Boot DDD를 선언만 하고 실제로는 layered-anemic 구조로 무너짐
- **What fails:** 도메인 규칙이 controller/service/repository에 분산되고, folder type / invite / intake policy가 중복 구현됨
- **Blast radius:** 변경 비용 급증, web/mobile 간 동작 불일치, review difficulty 증가
- **Mitigation:** 초기 package rule 고정, use case 단위 설계 리뷰, domain/application/adapter 경계 체크리스트 적용
- **Architect/Critic focus:** aggregate 경계, transaction boundary, cross-domain orchestration 방식

### Failure Scenario 2 — iOS native share entry는 붙었지만 app/session handoff가 불안정함
- **What fails:** 공유 시트에서 앱으로 넘어오며 인증 또는 payload continuity가 깨짐
- **Blast radius:** 핵심 acceptance 실패, 모바일 1급 클라이언트 전략 신뢰도 하락
- **Mitigation:** Phase 1 spike를 독립 성공 기준으로 운영, payload는 먼저 backend에 저장, 후속 확인은 app에서 복구 가능하게 설계
- **Architect/Critic focus:** auth/session transfer, deferred completion UX, extension lifecycle 제한

### Failure Scenario 3 — dual-primary를 과도하게 parity-driven으로 해석해 일정이 붕괴함
- **What fails:** 웹과 모바일 모든 화면을 동일 깊이로 맞추려다 MVP scope가 폭증
- **Blast radius:** delivery 지연, 핵심 루프 완성 실패
- **Mitigation:** parity는 도메인 capability 기준으로 정의하고, UI depth parity는 강제하지 않음
- **Architect/Critic focus:** capability parity vs UI parity 구분, MVP essential flow만 1차 보장

## Verification Plan
### Unit
- aggregate/domain policy tests
- folder type validation
- invite token state rules
- intake normalization
- candidate resolution logic
- EXIF normalization
- provider adapter contract tests

### Integration
- signup/login + invite accept + membership create
- upload asset → EXIF extraction → diary create
- share intake → analysis → candidate persistence
- manual fix → saved place create
- duplicate URL warning within folder
- reverse geocode failure with coordinate-only persistence

### E2E
- Web: signup → create `photo_diary` folder → upload no-GPS image → choose location → save → map/list/detail visible
- Web: owner creates invite → second user accepts → viewer can read but cannot edit
- Mobile iOS: share link/text → app intake screen → choose folder → confirm/select/fix → saved place visible in mobile and web
- Mobile Android: same as iOS path
- Mobile: invite link accept and folder join
- Cross-client: web-created diary/place/folder is readable on mobile, and mobile-created intake result is readable on web

### Observability
- structured logs for use case execution by domain
- metrics: intake counts by platform/status, candidate count distribution, geocode cache hit rate, EXIF failure rate, invite acceptance funnel
- alerts: share-intake failure spike, queue backlog, geocoder rate-limit spike, unresolved draft accumulation
- traces: mobile share receive → backend intake → analysis → resolve → saved place creation

## Escalation / Risk Gate
### Requires Architect scrutiny
- Spring Boot package boundaries and bounded contexts
- aggregate 설계: folder, invite, diary entry, share intake, saved place 경계
- React Native 선택 시 share extension/native bridge 현실성
- API contract governance 방식
- location domain을 독립 도메인으로 둘지 supporting domain으로 둘지

### Requires Critic scrutiny
- DDD 구조가 과설계인지, 또는 오히려 cross-domain orchestration이 불충분한지
- dual-primary parity 범위가 MVP에 맞게 제한되어 있는지
- Phase 1 spike acceptance가 충분히 kill/reconfirm 가능한지
- photo diary before saved places ordering이 여전히 최선인지

### Risk Gate Before Execution Approval
Execution should not start until Architect/Critic validate:
1. Spring Boot DDD package strategy is concrete and enforceable,
2. iOS/Android share-entry path is feasible under chosen mobile stack,
3. dual-primary capability scope is bounded enough for MVP,
4. intake/saved place state machine is complete for recovery states.

## Risks and Mitigations
### Risk 1 — DDD package strategy collapses into technical-layer sprawl
- **Mitigation:** domain-centric package rule 명시, review checklist, adapter/in/out 분리 강제

### Risk 2 — web/mobile 계약 drift
- **Mitigation:** OpenAPI as shared contract, enum/error catalog centralization, compatibility checks

### Risk 3 — mobile native integration complexity exceeds RN defaults
- **Mitigation:** early spike, native customization 허용, mobile architecture decision 조기 고정

### Risk 4 — parity 요구가 범위 폭증으로 이어짐
- **Mitigation:** capability parity만 MVP 목표로 정의, UI richness parity는 차기 범위로 둠

### Risk 5 — folder type/domain leakage
- **Mitigation:** server-side validation, aggregate policy tests, integration guardrails

### Risk 6 — free provider limits degrade UX
- **Mitigation:** cache, throttle, backoff, coordinate-first persistence, provider adapter

## Open Assumptions
- Spring Boot 기반에서 chosen persistence/queue stack이 MVP 속도와 운영성 균형을 제공한다.
- React Native 기반에서 iPhone share-entry 구현이 현실적인 범위다.
- dual-primary라도 웹과 모바일의 UI 깊이를 완전히 동일하게 맞출 필요는 없다.
- one-image photo diary와 draftable share intake만으로 MVP 가치 검증이 가능하다.
- raw intake preservation이 provider failure 대응에 충분한 복구 기반이 된다.

## Places Architect/Critic Should Scrutinize
- auth, collaboration, photojournal, placeintake, savedplace를 어떤 bounded context 강도로 나눌지
- `placeintake`와 `savedplace`를 완전히 분리할지, 일부 aggregate choreography를 둘지
- React Native + native share integration 선택이 적절한지
- contract governance를 OpenAPI 중심으로 충분히 통제할 수 있는지
- location/geocode concerns를 별도 supporting domain으로 분리하는 것이 과한지

## ADR-Style Decision Block
**ADR ID:** ADR-PPJ-MVP-001

**Title:** Adopt Spring Boot DDD backend with dual-primary web/mobile clients and server-standardized share intake

**Status:** Proposed for Architect/Critic review

**Context:**
- Backend is fixed to Spring Boot
- Architectural style is DDD
- Product requires fixed folder types `photo_diary` and `reels_place`
- iPhone must support native-equivalent share entry
- Web and mobile are both first-class clients
- Geocoding must be server-side with caching, throttling, and provider adapters

**Decision:**
Use a Spring Boot backend organized by domain-centric DDD packages. Expose shared API/domain contracts to two first-class clients: a web client and a mobile client. Keep `share_intake_items` separate from `saved_places`, enforce folder type boundaries server-side in domain/application logic, and standardize all mobile/web share-originated inputs through a backend intake pipeline. Treat web and mobile as capability peers with platform-appropriate UI implementations.

**Consequences:**
- Aligns architecture with fixed backend and DDD requirements
- Preserves iPhone/Android native share-entry support while avoiding mobile-only product framing
- Increases importance of contract governance across backend/web/mobile
- Requires early validation of mobile native integration and strict package discipline
- Keeps recovery-oriented intake draft model for uncertain or failed place extraction

**Alternatives considered:**
- Web-primary + mobile companion: rejected because mobile must now be first-class
- Fully separate native/web stacks with looser contract sharing: rejected due to MVP cost and drift risk
- PWA-only: rejected because iPhone native share-entry acceptance is not met

## Handoff Guidance
- **Executor:** suitable after Architect/Critic approval, split by bounded slices: Spring Boot DDD foundation, mobile share spike, collaboration core, photo diary core, saved place core, shared contract governance
- **Architect:** needed now for bounded context/package structure, aggregate design, mobile native feasibility
- **Critic:** needed now for sequencing, scope containment, verification sufficiency
- **Team:** useful after approval if web/mobile/backend lanes run in parallel
- **Ultragoal:** only needed if this expands into multi-milestone tracked delivery

## Recommended Primary Path
Proceed with **Spring Boot DDD backend + Next.js web + React Native mobile** as a **dual-primary client architecture**. This is the best balanced path because it satisfies the fixed backend constraint, keeps domain logic centered in backend use cases and aggregates, preserves iPhone native share-entry, supports Android naturally, and lets both web and mobile participate as real product surfaces rather than one being a secondary shell. Alternatives are rejected because they either violate the new dual-primary requirement or cost too much for an MVP.
