# RALPLAN Pending Approval — Picture Journal MVP

## Status
- Phase: pending approval
- Source spec: `.gjc/_session-019f3160-45c4-7000-b101-3c4de80dc410/specs/deep-interview-picture-journal-mvp.md`
- Planning mode: deliberate
- Consensus status: Planner revised through reconciliation, Architect WATCH/COMMENT, Critic OKAY

## Goal
Spring Boot + DDD 백엔드를 중심으로, 타입 고정 협업 폴더 안에서 사진일기와 저장 장소를 분리해 관리하는 서비스를 만든다. 웹과 모바일은 모두 1급 클라이언트이며, 모바일은 네이티브 공유 진입을 담당하고, 사진일기 작성도 웹과 거의 비슷한 수준의 핵심 흐름을 제공한다.

## Recommended Architecture
- **Backend:** Spring Boot + DDD, domain-centric packages
- **Policy owner:** `FolderCapabilityPolicy`
- **Contracts:** backend-owned OpenAPI + `contracts/*` 산출물 + generated client + checked wrapper
- **Web:** 협업 관리, invite/admin, 지도 탐색, 사진일기/저장장소 작성·조회·수정
- **Mobile:** iPhone/Android 네이티브 공유 진입, 저장장소 처리, 사진일기 작성·조회·수정 near parity
- **Sync boundary:** `ResolveShareIntake`는 application-layer synchronous orchestration
- **Async boundary:** 후보 추출, observability, 비핵심 파생 데이터

## Client Capability Matrix
| Capability | Scope |
|---|---|
| Auth | BOTH-MUST |
| Folder Browse/Detail | BOTH-MUST |
| Folder create/edit metadata | BOTH-MUST |
| Invite Accept | BOTH-MUST |
| Invite/Admin | WEB-MUST |
| Photo Diary Authoring | BOTH-MUST (near parity) |
| Photo Diary Browse | BOTH-MUST |
| Saved Place Browse/Edit | BOTH-MUST |
| Share Intake Create/Resolve | MOBILE-MUST |
| Web paste intake fallback | WEB-SHOULD |
| Cross-client consistency | BOTH-MUST |
| Activity/admin diagnostics | WEB-MUST |
| Advanced bulk management / full visual parity | DEFER |

## Core Rules
1. `photo_diary`와 `reels_place`는 섞지 않는다.
2. 모든 folder-scoped write는 `FolderCapabilityPolicy`를 통과한다.
3. geocode/reverse-geocode는 서버사이드 only + cache + rate limit를 지킨다.
4. tile provider/fallback은 클라이언트 인프라 책임이다.
5. `ResolveShareIntake` 결과는 atomic해야 한다.
6. 모바일 사진일기 작성은 basic shell이 아니라 near-parity core flow다.

## Delivery Sequence
### Phase 0 — Foundation
- Spring Boot DDD skeleton
- `FolderCapabilityPolicy`
- OpenAPI/contracts pipeline
- shared enums/error taxonomy

### Phase 1 — Native share spike
- iOS/Android 네이티브 공유 진입
- warm start / cold start / unauthenticated recovery / folder continuity / payload durability
- 실패 시 전략 재검토

### Phase 2 — Collaboration core
- auth, folders, memberships, invites

### Phase 3 — Photo diary parity core
- web/mobile 단일 사진 업로드
- EXIF
- 위치 보정
- 제목/본문/태그
- CRUD + map/list/detail

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
- contract CI gates
- unresolved draft monitoring

## Verification Gates
### Mandatory before execution approval
1. `FolderCapabilityPolicy` ownership과 command coverage 확정
2. OpenAPI/contracts breaking-change CI gate 확정
3. RN/native share spike go/no-go 통과 기준 확정
4. mobile photo diary near-parity 범위 확정
5. `ResolveShareIntake` atomic orchestration 검증 계획 확정

### Core verification
- folder type invariant tests
- role matrix tests
- diary create/update tests on web and mobile
- resolve atomicity tests
- web↔mobile cross-client consistency scenarios
- mobile share recovery scenarios
- OpenAPI diff / generated client compatibility checks

## Risks
- 모바일 diary near parity로 MVP 속도 저하 가능
- RN native integration complexity
- contract drift
- folder invariant leakage

## Mitigations
- admin/bulk/high-end parity defer 유지
- native share spike 선행
- backend-owned contracts + CI gates
- `FolderCapabilityPolicy` 강제

## Intent Reconciliation
### Reconciled item — 모바일 사진일기 작성 범위
- Initial planning assumption: 모바일 사진일기 작성은 basic/minimal 수준이면 충분할 수 있다.
- User-confirmed correction: 모바일에서도 웹과 거의 비슷한 수준의 사진일기 작성 경험이 필요하다.
- Applied change: `Photo Diary Authoring`을 `BOTH-MUST (near parity)`로 수정했다. 단, admin richness와 full visual parity는 계속 defer 한다.
- Result: reconciled-clean

## ADR
### Decision
Spring Boot + DDD 백엔드 위에 dual-primary clients를 두되, 모바일 사진일기 작성도 near-parity core flow로 포함한다. invite/admin과 운영성 높은 관리 기능은 웹 우선으로 둔다.

### Drivers
- web/app balance를 제품 정체성으로 유지해야 한다.
- iPhone native share-entry가 필수다.
- full parity는 MVP 비용이 과하다.
- contract drift와 folder invariant leakage를 초기에 막아야 한다.

### Alternatives considered
- web-centered + native share layer: 더 빠르지만 사용자 의도와 덜 맞음
- full dual-primary parity: 더 대칭적이지만 MVP 비용이 과함
- capability-tiered dual-primary: 채택

### Consequences
- 모바일 범위는 넓어지지만 제품 정체성이 선명해진다.
- admin/web-heavy 영역을 억제하지 않으면 일정이 커진다.
- contract governance와 policy ownership이 필수 선행 조건이 된다.

### Follow-ups
- execution 단계에서 모바일 diary authoring을 독립 slice로 관리
- contract pipeline을 first sprint deliverable로 잠금
- share spike 결과에 따라 RN/native integration 전략 재평가 가능

## Approval Note
이 계획은 `pending approval` 상태다. 실행은 별도 승인 후 `ultragoal` 또는 `team`으로만 진행한다.
