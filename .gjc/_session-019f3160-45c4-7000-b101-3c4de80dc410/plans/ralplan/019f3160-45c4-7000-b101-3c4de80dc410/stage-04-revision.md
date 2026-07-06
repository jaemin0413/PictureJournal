# RALPLAN-DR 01 — Picture Journal MVP Initial Consensus Draft (Reconciled Revision)

## Summary
이 revision은 intent reconciliation에서 드러난 핵심 차이를 반영한다. 사용자는 **모바일에서도 웹과 거의 비슷한 수준의 사진일기 작성 경험**을 원한다. 따라서 기존의 `capability-tiered dual-primary`는 유지하되, `photo_diary` 작성만큼은 `BOTH-MUST` 중에서도 **near-parity core flow**로 승격한다. 반면 invite/admin, 운영 진단, 고급 관리 기능은 계속 웹 우선으로 두어 MVP 범위를 통제한다. 즉 최종 방향은 **Spring Boot + DDD backend + dual-primary clients + mobile diary authoring near parity + web-heavier admin**이다.

## Intent Reconciliation Delta
- 기존 가정: 모바일 사진일기 작성은 basic/minimal 수준이어도 된다.
- 사용자 보정: **모바일에서도 웹과 거의 비슷한 수준의 사진일기 작성 경험이 필요하다.**
- 계획 수정: photo diary create/edit 핵심 흐름은 web/mobile 둘 다 강하게 지원한다. 단, bulk 관리/고급 필터/운영성 기능은 여전히 웹 우선이다.

## Principles
1. 폴더 타입과 역할 불변성은 `FolderCapabilityPolicy` 한 곳에서 강제한다.
2. 웹과 모바일은 모두 1급 클라이언트이며, **사진일기 작성은 near-parity core flow**를 제공한다.
3. 계약은 backend-owned source of truth에서 생성/검증한다.
4. 공유 수집은 복구 가능성을 우선한다.
5. 동기 일관성이 필요한 경계(`ResolveShareIntake`)와 비동기 허용 경계를 명시적으로 나눈다.

## Recommended Architecture
- **Backend:** Spring Boot + DDD, domain-centric packages, backend-owned OpenAPI/contracts
- **Web:** 협업 관리, invite/admin, 대화면 지도 탐색, 사진일기/저장장소 작성·조회·수정
- **Mobile:** 네이티브 share entry, 저장장소 처리, **사진일기 작성/수정/조회 near parity**, 폴더 브라우즈, 초대 수락
- **Shared:** OpenAPI, generated client + checked wrapper, auth semantics, domain vocabulary, error taxonomy, design tokens

## Client Capability Matrix
| Capability | Scope | Notes |
|---|---|---|
| Auth | BOTH-MUST | signup/login/logout/session restore |
| Folder Browse/Detail | BOTH-MUST | 목록/상세/기본 멤버 조회 |
| Folder create/edit metadata | BOTH-MUST | 이름/설명/타입 생성과 기본 수정 |
| Invite Accept | BOTH-MUST | 링크 진입 후 가입/로그인/수락 |
| Invite/Admin | WEB-MUST | 초대 생성, 역할 변경, 링크 상태 관리, 멤버 관리 |
| Photo Diary Authoring | BOTH-MUST (near parity) | 단일 사진 업로드, EXIF 확인, 위치 보정, 제목/본문/태그, 저장/수정까지 web/mobile 거의 동급 제공 |
| Photo Diary Browse | BOTH-MUST | 지도/리스트/상세 조회 |
| Saved Place Browse/Edit | BOTH-MUST | 목록/상세/방문상태/수동보정 |
| Share Intake Create/Resolve | MOBILE-MUST | 네이티브 공유 진입, 후보 확인/선택/수정 |
| Web paste intake fallback | WEB-SHOULD | core acceptance는 아님, 보조 경로 |
| Cross-client consistency | BOTH-MUST | 웹 생성 데이터가 모바일에서 보이고, 모바일 생성 데이터가 웹에서 보여야 함 |
| Activity/admin diagnostics | WEB-MUST | 운영성/감사성 기능 |
| Advanced bulk management | DEFER | MVP 이후 |
| Full visual parity of every screen | DEFER | capability parity만 보장 |

### Near-Parity Definition for Mobile Photo Diary
모바일 사진일기 작성의 near-parity는 최소한 아래를 포함한다.
- 사진 1장 업로드
- EXIF GPS/촬영일 확인
- GPS 없을 때 지도 선택/장소 검색
- 제목/본문/장소명/태그 입력
- 저장 후 상세/지도/리스트 반영
- 기존 일기 수정

웹에만 남길 수 있는 것:
- 운영성 높은 bulk 관리
- 복수 항목 대량 수정
- 고급 관리 필터 UI

## Alternatives
### Option A — Reconciled dual-primary with mobile diary near parity **(Recommended)**
- 장점: 사용자 의도를 가장 정확히 반영, 웹과 앱 정체성 균형, iPhone share-entry 요구 충족
- 단점: 모바일 diary authoring 범위가 넓어져 MVP 난이도 상승
- 채택 이유: 현재 사용자가 원하는 제품 정체성과 가장 정합적이며, admin 영역을 웹 우선으로 유지해 전체 폭주는 막을 수 있다.

### Option B — Capability-tiered dual-primary with basic mobile diary authoring
- 장점: 속도와 구현 난이도에 유리
- 단점: 방금 확인된 사용자 의도와 불일치
- 배제 이유: intent reconciliation에서 사용자에게 직접 반박되었다.

### Option C — Web-centered + native share layer
- 장점: 가장 보수적이고 출시 속도가 빠름
- 단점: 앱을 1급 경험으로 보는 사용자 의도와 맞지 않음
- 배제 이유: 제품 정체성 목표와 어긋난다.

## Contract Governance
### Source of Truth
- backend(Spring Boot)에서 추출·검증된 OpenAPI가 단일 진실 공급원이다.

### `contracts/*` 역할
- `contracts/openapi/*`: 버전 관리되는 OpenAPI 산출물
- `contracts/errors/*`: 에러 코드/복구 의미
- `contracts/domain-vocab/*`: enum/status 의미
- `contracts/changelog/*`: breaking/additive 변경 기록

### Client Consumption
- web/mobile은 generated client를 사용한다.
- checked wrapper는 auth injection, retry/cancel, analytics, platform-specific error mapping만 담당한다.
- hand-written raw API calls는 금지한다.

### CI Gates
- OpenAPI diff 검사
- breaking change 시 version bump 강제
- web/mobile client regenerate or compatibility check 강제
- error code 삭제/변경 시 changelog 필수

## DDD Package Strategy
picturejournal
- common
- domains
  - auth
  - collaboration
    - domain
      - model
      - policy
        - FolderCapabilityPolicy
    - application
    - adapter
  - photojournal
    - domain
    - application
    - adapter
  - placeintake
    - domain
    - application
    - adapter
  - savedplace
    - domain
    - application
    - adapter
  - location
    - domain
    - application
    - adapter(out: geocode, reversegeocode, cache, throttle)

### Boundary Rules
- `location`은 핵심 business bounded context가 아니라 supporting domain/service로 취급한다.
- tile provider/fallback은 web/mobile 클라이언트 인프라 책임이다.
- `FolderCapabilityPolicy`는 모든 folder-scoped write command의 단일 진입 정책이다.

## FolderCapabilityPolicy Coverage
다음 command는 모두 `FolderCapabilityPolicy`를 통과해야 한다.
- `CreateDiaryEntry`
- `UpdateDiaryEntry`
- `DeleteDiaryEntry`
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

## Share Choreography
### Async allowed
- 후보 추출 분석
- observability / activity log 적재
- 비핵심 파생 데이터

### Sync required
- `ResolveShareIntake`는 application-layer synchronous orchestration으로 처리
- 결과는 둘 중 하나만 허용
  - intake resolved + saved place created
  - 둘 다 미반영

## Sequencing
### Phase 0 — Foundation
- Spring Boot DDD skeleton
- `FolderCapabilityPolicy`
- OpenAPI/contracts pipeline
- shared vocab and error taxonomy

### Phase 1 — Mobile share spike
- iOS/Android 네이티브 공유 진입
- warm start / cold start / unauthenticated recovery / folder continuity / payload durability 검증
- 실패 시 전략 재검토

### Phase 2 — Collaboration core
- auth
- folders
- memberships
- invites

### Phase 3 — Photo diary parity core
- web/mobile 모두 단일 사진 업로드
- EXIF
- 위치 보정
- 제목/본문/태그
- diary CRUD
- 지도/리스트/상세

### Phase 4 — Saved place core
- share intake
- 후보 추출
- confirm/select/manual fix
- synchronous resolve
- saved place CRUD

### Phase 5 — Platform completion
- web admin richness
- mobile share polish
- cross-client consistency hardening

### Phase 6 — Reliability / release gates
- geocode throttle/cache/backoff
- observability
- CI contract gates
- unresolved draft monitoring

## Acceptance Criteria
1. 사용자는 웹과 모바일 모두에서 회원가입/로그인 후 폴더를 생성하고 조회할 수 있다.
2. owner는 웹에서 초대 링크를 만들 수 있고, 사용자는 웹/모바일 모두에서 초대를 수락할 수 있다.
3. `photo_diary` 폴더에서 사용자는 **웹과 모바일 모두** 단일 사진 업로드, EXIF 확인, 위치 보정, 제목/본문/태그 입력 후 저장할 수 있다.
4. 모바일 사진일기 작성 경험은 웹 대비 핵심 입력 필드와 위치 보정 흐름에서 실질적으로 동등해야 한다.
5. `reels_place` 폴더에서 사용자는 모바일 공유 진입으로 intake를 만들고 후보 확인/선택/수정 후 저장할 수 있다.
6. 후보 0개는 draft로 저장되고 이후 수동 보정 가능해야 한다.
7. `ResolveShareIntake`는 atomic하게 처리되어 split-brain 상태를 남기지 않는다.
8. web/mobile 간 생성·수정 결과가 서로 즉시 조회 가능해야 한다.
9. 폴더 타입과 역할 정책은 서버 authoritative rule로 강제된다.
10. geocode/reverse-geocode는 서버사이드 only + cache + rate limit를 지킨다.

## Mandatory Pre-Execution Gates
1. `FolderCapabilityPolicy` ownership과 command coverage 확정
2. contracts/OpenAPI CI gate 확정
3. RN/native share spike go/no-go 통과
4. mobile photo diary near-parity 범위 문서화 확정
5. `ResolveShareIntake` atomic orchestration 검증 계획 확정

## Verification Plan
### Domain/Application
- folder type invariant tests
- role matrix tests
- diary create/update policy tests
- resolve atomicity tests

### Cross-client
- web diary create → mobile read/edit
- mobile diary create → web read/edit
- mobile share intake resolve → web saved place visible
- web invite → mobile accept

### Contract
- OpenAPI diff
- client generation check
- error catalog drift check

### Mobile share spike
- authenticated warm start
- cold start recovery
- unauthenticated login recovery
- folder continuity
- payload durability

### Observability
- intake lifecycle metrics
- diary creation failure metrics by platform
- geocode cache hit/error metrics
- unresolved draft aging

## Risks
1. 모바일 diary near parity가 MVP 속도를 늦출 수 있음
   - mitigation: admin/bulk/high-end parity는 계속 defer
2. RN native integration complexity
   - mitigation: spike first, fail-fast gate
3. contract drift
   - mitigation: backend-owned OpenAPI + CI gate
4. folder invariant leakage
   - mitigation: `FolderCapabilityPolicy`

## ADR
**Decision:** Spring Boot + DDD backend 위에 dual-primary clients를 두되, mobile photo diary authoring도 near-parity core flow로 포함한다. admin richness는 웹 우선으로 남긴다.

**Why chosen:** 사용자의 제품 정체성 요구를 충족하면서도, 완전한 시각/UI parity 대신 capability parity 중심으로 범위를 묶어 MVP를 통제할 수 있기 때문이다.

**Consequences:** 모바일 범위는 넓어지지만, 앱의 1급 정체성이 명확해진다. 대신 운영/admin 영역은 웹 우선으로 둬 일정 폭발을 막아야 한다.
