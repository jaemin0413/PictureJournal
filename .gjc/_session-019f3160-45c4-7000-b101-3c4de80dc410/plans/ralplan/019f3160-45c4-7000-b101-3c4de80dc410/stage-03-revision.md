# RALPLAN-DR 01 — Picture Journal MVP Initial Consensus Draft (Revised v2)

## Summary
이 계획은 협업 가능한 타입 고정 폴더를 중심으로 `photo_diary`와 `reels_place`를 분리한 Picture Journal MVP의 deliberate-mode 합의 초안이다. 본 수정안은 **Spring Boot 고정 + DDD + web/app balance**를 유지하되, Architect/Critic 피드백을 반영해 **MVP 경계가 흔들리지 않도록 클라이언트 capability를 명시적으로 잠그고**, 계약 거버넌스, 폴더 불변성 소유점, share spike 게이트, `placeintake`↔`savedplace` choregraphy를 실행 가능 수준으로 구체화한다. 추천 방향은 모든 화면 대칭을 강제하는 full parity가 아니라, **capability-tiered dual-primary**다. 즉 웹과 모바일을 모두 1급 클라이언트로 인정하되, 플랫폼별로 반드시 제공해야 하는 능력을 명시적으로 다르게 고정한다.

## Title + Scope
**Title:** Picture Journal MVP — Spring Boot + DDD 기반 협업 폴더형 사진일기 / 저장장소 서비스

**Scope:**
- 회원 기반 인증/로그인
- 타입 고정 협업 폴더 생성: `photo_diary` / `reels_place`
- 초대 링크 기반 멤버 참여와 역할 반영
- `photo_diary` 폴더에서 단일 사진 업로드, EXIF 분석, 위치 보정, 지도/리스트/상세 조회
- `reels_place` 폴더에서 모바일 공유 또는 동등한 네이티브 공유 진입 기반 링크/텍스트 intake, 후보 확인/선택/수동 보정, 저장 장소 관리
- 서버 사이드 지오코딩, 역지오코딩, 캐시, rate limiting, provider adapter
- iPhone/Android 공유 진입 지원
- 웹/모바일 dual-primary 클라이언트 체계
- contract governance, invariant ownership, mandatory execution gates

## Intent Diff
- **From:** Spring Boot + DDD + dual-primary 개념적 합의
- **To:** capability-tiered dual-primary, 계약 단일 진실 공급원, invariant 단일 소유 정책, kill/reconfirm 기준까지 포함한 execution-safe draft
- **Why this matters:** dual-primary를 선언만 하면 parity 과잉, contract drift, 모바일 share 리스크, 도메인 경계 누수를 동시에 맞는다. 이번 수정은 그 폭발 지점을 미리 잠근다.

## RALPLAN-DR Summary
### Principles
1. **폴더 타입과 역할 불변성은 단일 정책 소유점에서만 결정한다.** UI, controller, repository가 임의 판단하면 안 된다.
2. **웹과 모바일은 모두 1급 클라이언트지만, MVP는 capability-tiered 방식으로 범위를 잠근다.** 완전 대칭 parity는 의도적으로 피한다.
3. **계약은 하나의 소스에서 생성·검증되어야 한다.** backend, web, mobile이 각자 DTO를 손으로 복제하면 안 된다.
4. **공유 수집은 실패보다 복구 가능성이 우선이다.** intake payload는 extension/app lifecycle보다 오래 살아야 한다.
5. **동기 일관성이 필요한 경계와 eventual consistency를 허용할 경계를 명시적으로 나눈다.**

### Top 3 Decision Drivers
1. **Spring Boot + DDD가 고정되었으므로 도메인 경계, policy ownership, contract governance가 먼저 잠겨야 한다.**
2. **모바일은 1급 클라이언트여야 하지만, full parity는 MVP scope를 망친다.**
3. **iPhone native share-entry는 핵심 acceptance이며, warm/cold/auth recovery를 통과하지 못하면 전략을 재검토해야 한다.**

## Client Capability Matrix
아래 매트릭스는 MVP 플랫폼 책임을 고정한다.

| Capability | Scope | Notes |
|---|---|---|
| Auth | BOTH-MUST | signup, login, logout, session restore 모두 웹/모바일 필수 |
| Folder Browse/Detail | BOTH-MUST | 폴더 목록/상세/멤버 기본 조회는 양쪽 필수 |
| Invite Accept | BOTH-MUST | 링크 진입 후 로그인/가입/수락은 웹/모바일 모두 필수 |
| Invite/Admin | WEB-MUST | 초대 생성, 역할 변경, 링크 상태 관리, 멤버 관리의 full admin UX는 웹 필수. 모바일은 조회 또는 제한적 관리만 허용 가능 |
| Photo Diary Authoring | BOTH-MUST | 단일 사진 업로드, EXIF 처리 결과 확인, 위치 보정, 저장은 양쪽 필수 |
| Saved Place Browse/Edit | BOTH-MUST | 저장 장소 목록/상세/상태 수정/수동 보정은 양쪽 필수 |
| Share Intake Create/Resolve | MOBILE-MUST | 네이티브 share entry 생성/복구/resolve는 모바일 필수. 웹은 fallback paste intake만 허용 가능하나 core acceptance는 모바일 기준 |
| Cross-client consistency | BOTH-MUST | 웹 생성 데이터가 모바일에서 보여야 하고 반대도 성립해야 함 |
| Folder creation/edit metadata | BOTH-MUST | 폴더 생성과 기본 정보 수정은 양쪽 제공 권장, 단 admin richness는 웹 우선 |
| Activity/admin diagnostics | WEB-MUST | 운영성 높은 상태 조회는 웹 우선 |
| Rich bulk management / advanced filters | DEFER | MVP 이후 |
| Deep parity of every screen layout | DEFER | capability parity만 보장, UI symmetry는 보장 대상 아님 |
| Email invites | DEFER | MVP 제외 |
| Multi-photo diary authoring | DEFER | MVP 제외 |

### Matrix Interpretation
- **BOTH-MUST:** MVP 승인 조건으로 웹/모바일 모두 제공해야 하는 능력
- **WEB-MUST:** 웹이 반드시 제공해야 하며, 모바일은 제한 제공 또는 defer 가능
- **MOBILE-MUST:** 모바일이 반드시 제공해야 하며, 웹은 대체 경로나 조회만 허용 가능
- **DEFER:** MVP 범위 밖

## Alternatives
### Option A — Capability-tiered dual-primary **(Recommended)**
- **Shape:** 웹/모바일 모두 1급 클라이언트. 다만 기능을 `BOTH-MUST / WEB-MUST / MOBILE-MUST / DEFER`로 잠근다.
- **Pros:**
  - 사용자의 web/app balance 요구를 만족
  - full parity보다 일정과 비용을 통제 가능
  - iPhone native share-entry를 모바일 핵심 능력으로 고정 가능
  - 협업/관리 UX는 웹에서 더 강하게, 이동 중 intake/수정은 모바일에서 자연스럽게 제공 가능
- **Cons:**
  - capability matrix governance가 약하면 다시 parity creep 발생
  - 일부 이해관계자는 “왜 이 화면은 완전 동일하지 않나”를 질문할 수 있음
- **Why it wins:** 범위, 리스크, 비용의 균형이 가장 좋다. dual-primary를 유지하면서도 MVP 폭발을 막는다.

### Option B — Web-centered + native share layer
- **Shape:** 핵심 제품은 웹, 모바일은 로그인 + share intake + 간단 조회 중심
- **Pros:**
  - 구현 속도가 빠름
  - 공유 진입 핵심만 빠르게 검증 가능
  - 웹 협업 UX를 집중적으로 다듬기 쉽다
- **Cons:**
  - 현재 사용자 요구인 web/app balance와 맞지 않음
  - 모바일이 사실상 2급 클라이언트로 밀려남
  - 장기적으로 다시 모바일 확장 비용을 치르게 됨
- **Fair assessment:** scope control에는 강하지만, 현재 명시 요구를 충분히 만족하지 못한다.

### Option C — Heavier full dual-primary / symmetric parity
- **Shape:** 웹과 모바일이 거의 모든 화면과 관리 기능에서 높은 수준의 parity를 가진다.
- **Pros:**
  - 제품 메시지가 명확함
  - 어느 플랫폼에서도 거의 같은 능력을 제공 가능
  - 장기적으로 경험 일관성이 높다
- **Cons:**
  - MVP 일정/비용 리스크가 가장 큼
  - admin/detail/filter 같은 영역까지 과투자하게 됨
  - mobile native share spike와 별개로 전체 클라이언트 부담이 커짐
- **Fair assessment:** 장기적으로는 매력적이지만, greenfield MVP의 첫 실행 계획으로는 과하다.

### Recommendation Rationale
Option A를 추천한다. Option B는 현재 요구보다 모바일을 낮게 두고, Option C는 현재 MVP에 비해 비용이 크다. Option A는 **요구 충족도는 유지하면서도 execution safety를 확보하는 중간해**다.

## Decision Drivers
- Spring Boot 고정
- DDD 기반 설계 강제
- web/app balance 유지
- iPhone native share-entry 필수
- 계약 drift 방지 필요
- MVP 범위 폭증 억제 필요

## Explicit Recommended Architecture
### 1) Backend — Spring Boot + DDD
- **Framework:** Spring Boot
- **Architectural style:** DDD
- **Rule:** 패키지는 기술 레이어가 아니라 도메인 중심으로 나눈다.
- **Rule:** 핵심 도메인 정책은 domain/application 내부에만 둔다.

### 2) Web Client — First-class, capability-tiered
- **Must-own areas:** 협업 관리, invite/admin, 대화면 지도 탐색, 진단/운영 친화적 흐름
- **Also supports:** auth, 폴더 브라우즈, photo diary authoring, saved place browse/edit, invite accept

### 3) Mobile Client — First-class, capability-tiered
- **Must-own areas:** native share entry, intake recovery, 이동 중 photo diary authoring, saved place browse/edit
- **Also supports:** auth, 폴더 브라우즈, invite accept, 기본 폴더 조작

### 4) Shared Contracts Across Clients
공유 대상:
- OpenAPI contract
- auth/session semantics
- folder type / role / intake status / visit status vocabulary
- error code taxonomy
- validation rules
- design tokens

비공유 대상:
- UI component implementation
- navigation/router
- platform-native file/share integration
- map SDK wiring

## Contract Governance
### Single Source of Truth
- **단일 진실 공급원은 backend-owned API contract다.**
- Spring Boot 코드 기준으로 `contracts/*`에 **버전 관리되는 OpenAPI 산출물, error catalog, enum vocabulary, contract changelog**를 둔다.
- source-of-truth는 “백엔드 구현과 분리된 손문서”가 아니라, **백엔드에서 추출·검증되는 계약 산출물**이어야 한다.

### Role of `contracts/*`
- `contracts/openapi/*` — API 스펙 산출물
- `contracts/errors/*` — 공통 에러 코드/의미/복구 지침
- `contracts/domain-vocab/*` — enums/status/value semantics
- `contracts/changelog/*` — 계약 변경 기록
- 목적은 문서 저장소가 아니라 **client generation/validation input**이다.

### Generated Client vs Checked Wrapper
- **추천:** generated client + checked wrapper 혼합
- generated client는 OpenAPI로부터 web/mobile용 기본 API client를 생성한다.
- checked wrapper는 각 클라이언트에서 다음만 담당한다:
  - auth token injection
  - retry/cancel policy
  - platform-specific error mapping
  - analytics/tracing hooks
- hand-written raw API calls는 금지한다.
- 이유: generated-only는 ergonomics가 나쁘고, wrapper-only는 drift가 생긴다.

### Versioning / Breaking-change CI Gate
- 계약 버전은 semantic intent를 가진다: additive vs breaking 구분
- CI에서 아래를 강제한다:
  1. OpenAPI diff 검사
  2. breaking change 발생 시 version bump 요구
  3. web/mobile generated client 재생성 또는 compatibility check 강제
  4. error code 제거/의미 변경 시 changelog 필수
- 실행 승인 전, 이 게이트 없이는 backend/client 병렬 작업을 열지 않는다.

## Concrete Recommended Package Structure
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
    - application
    - adapter
  - collaboration
    - domain
      - model
        - Folder
        - FolderMembership
        - FolderInvite
        - FolderType
        - MembershipRole
      - policy
        - FolderCapabilityPolicy
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
        - messaging
  - photojournal
    - domain
      - model
        - MediaAsset
        - DiaryEntry
        - DiaryTag
      - policy
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
        - storage
        - exif
        - geocode
  - placeintake
    - domain
      - model
        - ShareIntakeItem
        - PlaceCandidate
        - IntakeStatus
      - policy
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
      - policy
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
        - geocode
  - location
    - domain
      - model
      - policy
      - service
    - application
      - command
      - query
      - dto
      - usecase
    - adapter
      - out
        - geocode
        - reversegeocode
        - cache
        - throttle

### Package Strategy Rules
1. 도메인마다 독립 폴더를 가진다.
2. 각 도메인 안에 `domain / application / adapter`를 둔다.
3. `FolderCapabilityPolicy`가 폴더 타입 + 역할 불변성의 단일 소유점이다.
4. repository는 port-first로 노출한다.
5. controller/service/repository 횡단식 구조는 금지한다.

## Single Ownership of Folder Type + Role Invariants
### Policy Owner
- **Owner:** `collaboration.domain.policy.FolderCapabilityPolicy`

### Responsibilities
- 폴더 타입별 허용 command 검증
- 역할별 허용 action 검증
- invite 수락 후 role 적용 가능성 검증
- cross-type write 차단

### Commands That MUST Pass Through It
아래 command는 모두 `FolderCapabilityPolicy`를 통과해야 한다.
- `CreateDiaryEntry`
- `UpdateDiaryEntry`
- `DeleteDiaryEntry`
- `UploadDiaryMedia` 관련 최종 저장 command
- `CreateShareIntake`
- `ResolveShareIntake`
- `SaveShareDraft`
- `CreateSavedPlace`
- `UpdateSavedPlace`
- `DeleteSavedPlace`
- `CreateFolderInvite`
- `AcceptFolderInvite`
- `ChangeMembershipRole`
- `RemoveMembership`

이 정책은 UI 힌트가 아니라, **서버 authoritative rule**이다.

## Data Model Boundaries
- **Collaboration boundary:** `users`, `folders`, `folder_memberships`, `folder_invites`
- **Photo diary boundary:** `media_assets`, `diary_entries`, `diary_tags`, `diary_entry_tags`
- **Share intake boundary:** `share_intake_items`, `place_candidates`
- **Saved place boundary:** `saved_places`
- **Location backend boundary:** geocode / reverse-geocode / cache / throttle only
- **Activity boundary:** `activity_logs`

### Location Boundary Clarification
- backend `location` 도메인은 **geocode, reverse-geocode, cache, throttle**만 소유한다.
- **tile provider 선택/전환/fallback은 클라이언트 인프라 책임**으로 둔다.
- 이유: 타일 렌더링은 실제 지도 SDK와 밀접하게 결합되며, web/mobile에서 다른 렌더링 전략과 fallback이 필요하다. backend가 이를 소유할 충분한 근거가 없다.

## Place Intake and Saved Place Choreography
### MVP Recommendation
- **기본 경로는 application-layer synchronous orchestration**으로 둔다.
- 즉, `ResolveShareIntake` use case 안에서:
  1. intake 상태와 후보/수동 보정 입력 검증
  2. `FolderCapabilityPolicy` 검증
  3. `SavedPlace` 생성
  4. intake 상태를 `resolved`로 변경
  5. 같은 transaction boundary 안에서 커밋

### Where Eventual Consistency Is Allowed
- 허용:
  - 후보 추출 background analysis
  - observability/audit/activity log 후속 적재
  - 검색 인덱스/추천/비핵심 파생 데이터
- 허용하지 않음:
  - `ResolveShareIntake` 후 `SavedPlace`가 생성됐는데 intake는 unresolved로 남는 상태
  - 반대로 intake는 resolved인데 `SavedPlace`가 없는 상태
  - folder type/role validation 이후 최종 write가 누락되는 상태

### Why this MVP choice
- share analysis는 비동기여도 괜찮지만, **사용자가 확인 버튼을 누른 resolve 순간**은 동기 일관성이 필요하다.
- domain event choreography는 장기적으로 확장 가능하지만, MVP에서는 실패 모드만 늘린다.

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
- 서버 geocode/reverse-geocode/cache/throttle/provider adapter
- iOS/Android 공유 진입 MVP
- 웹/모바일 capability-tiered dual-primary 사용 흐름

### Out of Scope
- 이메일 초대
- 다중 사진 업로드
- AI 자동 일기 작성
- 장소 추천
- 폴더 내부 개인 비공개 일기
- 오프라인 지도
- 댓글/반응
- 방문 완료 후 사진일기 자동 연결
- full UI parity

## File-level Changes
### Top-level
- `apps/web/*` — 웹 클라이언트
- `apps/mobile/*` — 모바일 클라이언트
- `apps/backend/*` 또는 `backend/*` — Spring Boot 애플리케이션
- `contracts/*` — OpenAPI, error catalog, domain vocab, changelog
- `design/*` — 디자인 토큰

### Backend emphasis
- 도메인별 패키지 중심
- policy/use case/adapter 경계 분리
- transaction boundary가 choreography 요구와 맞아야 함

### Client emphasis
- generated client + checked wrapper 사용
- 타일 provider/fallback은 각 클라이언트 infra concern
- cross-client consistency 시나리오를 처음부터 테스트 대상에 포함

## Sequencing and Dependencies
### Phase 0 — DDD Foundation + Contract Baseline
- bounded context 정리
- package structure 고정
- `FolderCapabilityPolicy` 설계
- OpenAPI/error catalog/domain vocab baseline 확정
- generated client 전략과 CI gate 설계

### Phase 1 — RN/Native Share Spike with Kill/Reconfirm Criteria
- iOS native share entry
- Android share receiver
- intake API create
- payload durability
- auth/cold/warm recovery 검증

### Phase 2 — Collaboration Core
- auth
- folder create/list/detail
- invite create/accept
- role policy enforcement

### Phase 3 — Photo Diary Core
- upload
- EXIF
- 위치 보정
- diary CRUD
- map/list/detail

### Phase 4 — Place Intake Analysis + Saved Place Core
- async analysis
- candidate persistence
- confirm/select/manual fix
- synchronous resolve orchestration
- saved place CRUD

### Phase 5 — Capability-tiered Client Completion
- BOTH-MUST gaps closing
- WEB-MUST admin completion
- MOBILE-MUST recovery completion
- cross-client consistency polish

### Phase 6 — Reliability / Observability / Release Gate
- geocode throttle/cache/backoff
- contract breaking-change gate
- traces/metrics/logging
- unresolved draft and queue health monitoring

## RN/Native Share Spike Acceptance — Kill/Reconfirm Criteria
이 스파이크는 단순 데모가 아니라 **go/no-go gate**다.

### Must pass to reconfirm strategy
1. **Authenticated warm start:** 로그인된 상태에서 share sheet 진입 후 payload가 손실 없이 intake 생성으로 이어진다.
2. **Cold start recovery:** 앱이 꺼져 있던 상태에서도 share 진입 후 앱 실행/복귀를 통해 payload를 복구한다.
3. **Unauthenticated share then login recovery:** 비로그인 상태에서 공유해도 로그인 후 payload를 잃지 않고 이어서 처리 가능하다.
4. **Folder selection continuity:** 공유 후 폴더 선택 도중 앱 전환/재진입이 발생해도 선택 문맥을 복구한다.
5. **Payload durability after extension closes:** extension/share UI가 닫힌 뒤에도 raw payload가 지속된다.

### Kill / re-scope triggers
아래 중 하나라도 해결 경로가 불분명하면 현재 전략을 재검토한다.
- iOS에서 payload durability를 신뢰 가능하게 보장할 수 없음
- 비로그인 → 로그인 후 복구가 불가능하거나 UX가 지나치게 깨짐
- RN 선택에서 native share integration이 과도하게 brittle함

## Delivery Phases / Dependencies / Risk Reduction Rationale
1. **Policy + contract first**로 drift를 줄인다.
2. **share spike second**로 가장 치명적인 mobile risk를 먼저 검증한다.
3. **collaboration before content**로 type/role invariants를 먼저 잠근다.
4. **photo diary before saved place**로 location/media primitive를 단순한 도메인에서 먼저 검증한다.
5. **resolve orchestration is synchronous**로 핵심 user action consistency를 보호한다.
6. **capability-tiered completion before release**로 dual-primary 요구를 bounded form으로 마감한다.

## Acceptance Criteria
1. Authenticated users can create a folder with immutable type `photo_diary` or `reels_place`.
2. Folder owner can generate an invite link with role, and an invited user can sign up/log in and accept into that role.
3. `photo_diary` folder member can upload one image, extract EXIF, and save diary only when location is present via GPS or manual selection/search.
4. Missing GPS blocks final save until manual location is set, but does not dead-end the flow.
5. All folder members can view all diary entries in that folder on map/list/detail surfaces.
6. `reels_place` folder member can create `share_intake` from native mobile share entry.
7. Candidate extraction supports: exactly 1 candidate → confirm; multiple candidates → choose one; 0 candidates → save draft and allow later manual fix.
8. iPhone and Android both support a native-equivalent share entry into the product.
9. The client capability matrix is satisfied for all `BOTH-MUST`, `WEB-MUST`, and `MOBILE-MUST` items.
10. Folder map surfaces only show data allowed by the folder type.
11. Cross-type writes are rejected server-side regardless of client path.
12. Geocoding and reverse geocoding are server-side only, cached, and rate-limited.
13. `ResolveShareIntake` produces a consistent result: resolved intake and created `SavedPlace`, or neither.
14. Domain rules live in Spring Boot DDD policies/use cases, not duplicated inconsistently across clients.

## Deliberate Verification
### Mandatory Pre-Execution Approval Gates
실행 승인 전 아래 게이트가 **모두** 합의되어야 한다.
1. `FolderCapabilityPolicy` ownership과 적용 command 목록 확정
2. `contracts/*` 거버넌스와 breaking-change CI gate 정의 확정
3. RN/native share spike의 kill/reconfirm 기준 승인
4. capability matrix 승인
5. `ResolveShareIntake` 동기 orchestration 경계 승인

이 다섯 개가 잠기지 않으면 execution approval을 열지 않는다.

## Verification
### Domain / Application Verification
- `FolderCapabilityPolicy` action matrix tests
- folder type invariant tests
- invite lifecycle tests
- intake status machine tests
- `ResolveShareIntake` atomicity tests
- photo diary location-required policy tests

### Spring Boot Adapter / Integration Verification
- controller → use case → port/adapter 흐름 검증
- persistence adapter가 aggregate invariants를 깨지 않는지 검증
- geocode/reverse-geocode adapter timeout/backoff 검증
- transaction boundary 검증: resolve 시 atomicity 유지

### Contract Verification
- OpenAPI diff CI
- generated client compatibility check
- error code catalog drift check
- breaking change version bump gate

### Cross-client Verification
- 웹 생성 폴더를 모바일에서 즉시 사용 가능
- 모바일 resolve 결과를 웹에서 즉시 조회 가능
- 웹 invite 링크를 모바일에서 수락 가능
- 모바일 share 후 로그인 복구 가능
- BOTH-MUST 항목 전부 시나리오 검증

### External Dependency Verification
- geocoder rate limit simulation
- extractor timeout/failure → draft/manual fix fallback
- object storage upload failure recovery
- client tile fallback은 각 플랫폼 클라이언트 테스트에서 검증

## Deliberate-Only: Pre-Mortem
### Failure Scenario 1 — capability matrix가 문서에만 있고 실제 scope control에 실패
- **What fails:** web/mobile parity creep 발생
- **Blast radius:** 일정 붕괴, 팀 분산, MVP 지연
- **Mitigation:** matrix를 acceptance와 task slicing의 기준으로 사용
- **Architect/Critic focus:** BOTH-MUST와 parity 욕심을 명확히 분리했는가

### Failure Scenario 2 — contract governance가 약해 client drift 발생
- **What fails:** web/mobile/backend가 서로 다른 enum/status/error semantics를 가짐
- **Blast radius:** runtime bug, QA 증가, 배포 리스크 증가
- **Mitigation:** `contracts/*` 단일 소스, generated client, CI gate
- **Architect/Critic focus:** source-of-truth가 정말 하나인가

### Failure Scenario 3 — share spike가 부분 성공처럼 보이나 recovery semantics가 불완전함
- **What fails:** demo는 되지만 cold/auth recovery가 깨짐
- **Blast radius:** 실제 사용자 핵심 가치 붕괴
- **Mitigation:** kill/reconfirm 기준을 명시적 통과 조건으로 운영
- **Architect/Critic focus:** warm-start demo와 real survivability를 구분했는가

## Risks and Mitigations
### Risk 1 — DDD package strategy collapses into technical-layer sprawl
- **Mitigation:** domain-centric package review checklist, policy ownership 강제

### Risk 2 — web/mobile contract drift
- **Mitigation:** backend-owned OpenAPI, generated clients, checked wrappers, CI diff gate

### Risk 3 — RN/native share integration complexity exceeds MVP assumption
- **Mitigation:** spike first, kill triggers explicit, native customization 허용

### Risk 4 — full parity pressure expands scope
- **Mitigation:** capability-tiered dual-primary 고정, DEFER 명확화

### Risk 5 — resolve consistency split-brain
- **Mitigation:** synchronous orchestration in application layer, same transaction commit

### Risk 6 — backend owns too much client map concern
- **Mitigation:** tile provider/fallback를 클라이언트 infra로 이동

## Open Assumptions
- Spring Boot에서 contract extraction and publication workflow를 안정적으로 운영할 수 있다.
- RN 기반에서 share-entry kill/reconfirm 기준을 통과할 가능성이 충분하다.
- capability-tiered 접근이 사용자 기대를 훼손하지 않으면서 scope를 제어한다.
- web paste fallback은 core acceptance가 아니라 보조 경로로만 유지 가능하다.

## Places Architect/Critic Should Scrutinize
- `FolderCapabilityPolicy`가 collaboration 도메인 안에서 가장 자연스러운 소유점인지
- `ResolveShareIntake` 동기 orchestration 경계가 너무 넓거나 좁지 않은지
- generated client + checked wrapper 분리가 적절한지
- capability matrix가 실제 execution slicing에 바로 쓸 만큼 충분히 구체적인지
- RN/native share spike kill criteria가 엄격하지만 현실적인지

## ADR-Style Decision Block
**ADR ID:** ADR-PPJ-MVP-001

**Title:** Adopt capability-tiered dual-primary clients on Spring Boot DDD backend with backend-owned contracts and synchronous intake resolution

**Status:** Proposed for renewed Architect/Critic review

**Context:**
- Backend is fixed to Spring Boot
- Architecture is DDD
- Web and mobile must both be first-class
- iPhone native share-entry is mandatory
- Full parity is too expensive for MVP
- Contract drift and share recovery are major failure risks

**Decision:**
Adopt a **capability-tiered dual-primary** architecture. Keep web and mobile as first-class clients, but explicitly lock platform scope through a capability matrix. Use a Spring Boot DDD backend with domain-centric packages. Make backend-extracted API contracts the single source of truth under `contracts/*`. Enforce folder type and role invariants through `FolderCapabilityPolicy`. Keep share analysis asynchronous where acceptable, but make `ResolveShareIntake` a synchronous application-layer orchestration that atomically creates `SavedPlace` and marks intake resolved.

**Consequences:**
- Preserves web/app balance without forcing full parity
- Gives clear MVP scope boundaries per platform
- Reduces contract drift risk through backend-owned generation and CI gates
- Forces early truth-testing of RN/native share viability
- Keeps critical user action consistency strong at resolve time

**Alternatives considered:**
- web-centered + native share layer: fair on cost, weak on current product balance requirement
- heavier full dual-primary: strongest symmetry, weakest MVP cost/risk profile
- capability-tiered dual-primary: chosen as the best balanced path on scope/risk/cost

## Handoff Guidance
- **Executor:** after renewed Architect/Critic approval, split into bounded slices: contract/policy foundation, RN share spike, collaboration core, photo diary core, place resolve core, client capability closure
- **Architect:** review capability matrix, policy ownership, resolve atomicity, contract governance
- **Critic:** stress-test whether scope is truly bounded and gates are strong enough
- **Team:** appropriate only after approval if web/mobile/backend lanes run in parallel with contract gate discipline
- **Ultragoal:** unnecessary unless effort expands into multi-milestone execution tracking

## Recommended Primary Path
Proceed with **capability-tiered dual-primary** on **Spring Boot DDD backend + Next.js web + React Native mobile**. This wins because it preserves the user’s desired web/app balance, satisfies iPhone native share-entry, keeps invariants authoritative on the backend, and avoids the cost explosion of symmetric full parity. It is the most MVP-bounded and execution-safe option among the viable alternatives.
