# RALPLAN-DR 01 — Picture Journal MVP Initial Consensus Draft

## Summary
이 계획은 협업 가능한 타입 고정 폴더를 중심으로 `photo_diary`와 `reels_place`를 분리한 그린필드 MVP의 1차 합의 초안이다. 목표는 웹 앱을 주 사용자 경험의 중심으로 두되, iPhone 공유 시트 수신 제약 때문에 모바일 공유 저장은 얇은 네이티브 수신 레이어를 포함하는 구조로 설계하는 것이다. 본 문서는 Architect/Critic 리뷰 전 단계의 deliberate-mode 실행 초안이며, 제품 코드 변경 없이 구현 순서, 경계, 검증, 실패 시나리오, 리스크를 명시한다.

## Title + Scope
**Title:** Picture Journal MVP — 협업 폴더 기반 사진일기 + 모바일 공유 저장 서비스

**Scope:**
- 회원 기반 인증/로그인
- 타입 고정 협업 폴더 생성: `photo_diary` / `reels_place`
- 초대 링크 기반 멤버 참여와 역할 반영
- `photo_diary` 폴더에서 단일 사진 업로드, EXIF 분석, 위치 보정, 지도/리스트/상세 조회
- `reels_place` 폴더에서 모바일 공유 또는 동등한 네이티브 공유 진입 기반 링크/텍스트 intake, 후보 확인/선택/수동 보정, 저장 장소 관리
- 서버 사이드 지오코딩, 캐시, rate limiting, provider adapter
- iPhone/Android 공유 진입 지원

## Intent Diff
- **From:** 모호한 아이디어 수준의 “사진 추억과 저장하고 싶은 장소를 함께 관리하는 서비스”
- **To:** 타입 고정 협업 폴더, 폴더별 도메인 분리, 웹 중심 제품 + 모바일 share ingestion companion, 서버 표준화 intake, 지도·지오코딩 adapter, background analysis를 포함한 구현 지향 MVP
- **Why this matters:** iPhone 공유 시트 지원과 폴더 타입 분리를 먼저 구조화하지 않으면 PWA-only 설계, 데이터 혼합, 상태 모델 붕괴, QA 범위 폭증으로 이어진다.

## RALPLAN-DR Summary
### Principles
1. **폴더 타입이 도메인 경계를 강제해야 한다.** `photo_diary`와 `reels_place`는 저장 모델, UI, API, 권한 검증에서 섞이지 않는다.
2. **모바일 공유는 플랫폼별 수신 차이를 서버 intake 표준화로 흡수해야 한다.** iOS/Android 입력 차이를 client-side 분기보다 backend contract로 제어한다.
3. **저장 실패보다 보류 가능한 저장이 우선이다.** 후보 0개, AI timeout, 비공개 링크 등에서도 `share_intake` draft를 남겨 사용자 복구 경로를 보장한다.
4. **위치 품질은 강제하되 외부 provider 의존은 격리해야 한다.** 사진일기는 위치 필수, 지오코딩/타일/provider는 adapter와 캐시로 교체 가능해야 한다.
5. **MVP는 협업·기록·수집의 최소 루프를 완성하고, 추천/자동화/복합 공개범위는 뒤로 미룬다.**

### Top 3 Decision Drivers
1. **iPhone 공유 시트 지원은 필수이며 PWA Web Share Target에 의존할 수 없다.**
2. **폴더 타입 고정과 폴더별 전원 조회 정책이 데이터 모델과 권한 정책을 단순화한다.**
3. **무료/오픈소스 지도·지오코딩 제약 때문에 서버 캐시, rate limit, provider adapter를 초기부터 넣어야 운영 리스크를 낮출 수 있다.**

### Viable Options
#### Option A — Web app + thin native mobile companion for share ingestion **(Recommended)**
- **Shape:** `apps/web`가 주 제품, `apps/mobile`은 인증 상태와 최소 라우팅을 가진 companion이며 iOS Share Extension / Android share receiver가 raw payload를 서버 `share_intake`로 전송
- **Pros:**
  - iPhone 공유 시트 요구를 정면 충족
  - Android도 native share receiver로 일관성 확보
  - backend intake contract 하나로 분석/후보 추출/저장 로직을 통합
  - 웹 UX와 공유 수신 UX를 분리해 제품 복잡도를 제어
- **Cons:**
  - 모바일 앱/extension 배포 복잡도 증가
  - 인증 토큰 전달, deep link 복귀, extension lifecycle 관리가 필요
  - greenfield MVP 기준 초기 셋업 비용이 큼
- **Bounded downside:** 모바일 코드는 share intake, 로그인, intake 상태 확인 정도로 최소화하면 범위를 통제할 수 있다.

#### Option B — PWA-first web app with web paste fallback and optional Android share target
- **Shape:** 웹 앱이 전부를 담당하고 모바일에서는 공유 링크 복사→붙여넣기, Android는 보조적으로 Web Share Target 또는 intent link를 사용
- **Pros:**
  - 구현/배포가 단순
  - 하나의 프런트엔드 surface로 통합 가능
  - 초반 개발 속도는 빠름
- **Cons:**
  - iPhone에서 “공유하기 → 앱으로 보내기” 동등 경험을 만족시키기 어렵다
  - user expectation과 acceptance의 “동등한 네이티브 공유 진입”을 충족하기 애매함
  - 플랫폼별 UX 편차가 커져 support 비용이 커진다
- **Rejection reason:** 사용자 acceptance에 iPhone/Android 네이티브 공유 진입이 포함되어 있고, spec도 pure PWA 가정을 명시적으로 폐기했다.

#### Option C — Full native mobile-first app with embedded web admin surface
- **Shape:** 모바일 앱이 주 제품, 웹은 보조 관리/뷰어
- **Pros:**
  - 공유 수신과 카메라/사진 권한 흐름이 자연스러움
  - 모바일 UX 품질을 높이기 좋음
- **Cons:**
  - 협업 관리, 지도 조회, 상세 입력 편집을 모바일에 과도하게 싣게 됨
  - greenfield MVP에서 web discoverability와 admin productivity를 잃음
  - scope가 가장 큼
- **Rejection reason:** 현재 요구는 웹 앱 + 모바일 공유 경로의 복합 surface이며, full-native는 MVP에 비해 비용이 과하다.

## Decision Drivers
- iPhone share sheet 호환성
- 폴더 타입 분리로 인한 데이터/권한 단순화
- 후보 0개와 실패 상황에서의 draft 복구 경로 필요성
- 무료 지도/지오코딩 운영 상한과 provider 교체 가능성
- greenfield MVP에서 web collaboration UX를 빠르게 제공할 필요

## Options
### Primary Path
Option A를 기본 경로로 채택한다. 이유는 iPhone 네이티브 공유 수신을 acceptance 수준으로 충족하면서도, 주 제품 경험은 웹에서 빠르게 제공할 수 있고, backend intake를 공유 인입의 단일 진실 공급원으로 만들 수 있기 때문이다.

### Rejected / Deferred Alternatives
- **PWA-only:** iPhone 공유 수신 요구와 충돌하므로 배제
- **Full native-first:** scope 과대. MVP 이후 adoption이 증명되면 재평가
- **Single unified content table for diary/place:** 폴더 타입 고정이라는 제품 규칙과 충돌하고 권한/필터/지도 semantics가 흐려지므로 배제

## Explicit Recommended Architecture
### 1) Web
- **Primary surface:** `apps/web`
- **Responsibilities:** 인증, 폴더 생성/관리, 멤버 관리, 초대 수락, 사진일기 작성/조회, 저장 장소 조회/수정, 지도/리스트/상세 화면
- **Recommended stack shape:** Next.js App Router + TypeScript + query cache layer + component system + MapLibre GL JS
- **Why:** 협업 관리와 지도 기반 browsing은 larger-screen web UX가 효율적이고, 모바일 share extension에 실리지 않아도 되는 무거운 UI를 웹에 집중할 수 있다.

### 2) Mobile Share Ingestion
- **Primary surface:** `apps/mobile`
- **Responsibilities:** 로그인 세션 유지, iOS Share Extension / Android share receiver, raw payload preview, folder 선택, intake 상태 확인/수정으로 라우팅
- **Hard constraint:** 모바일 앱은 “주 기능 앱”이 아니라 “공유 진입 + 최소 후처리 surface”로 범위를 고정
- **Data flow:** share receiver/extension → auth 확인 → `POST /share-intake` → server normalizes → analysis job → client polls/subscribes → user confirms/selects/fixes → `resolve`
- **Why:** iPhone share sheet 요구 충족과 web-heavy 제품의 균형점이다.

### 3) Backend
- **Recommended shape:** 단일 monorepo 안에서 web/API를 함께 두되, 도메인 서비스 계층과 background job 계층을 분리
- **Core subsystems:**
  - Auth & Session
  - Folder / Membership / Invite
  - Media upload & EXIF extraction
  - Share intake normalization
  - Place candidate extraction/orchestration
  - Geocoding / reverse geocoding adapter + cache + throttle
  - Activity log / audit trail
- **Background work:** EXIF retry, share analysis, geocode throttling/backoff, provider timeouts, dedupe heuristics
- **Why:** synchronous request 안에 분석/외부 provider 호출을 몰아넣으면 모바일 share UX가 깨지고 timeout risk가 커진다.

### 4) Data Model Boundaries
- **Collaboration boundary:** `users`, `folders`, `folder_memberships`, `folder_invites`
- **Photo diary boundary:** `media_assets`, `diary_entries`, `diary_tags`, `diary_entry_tags`
- **Share intake boundary:** `share_intake_items`, `place_candidates`
- **Saved place boundary:** `saved_places`
- **Infra boundary:** `geocode_cache`, `activity_logs`
- **Rule:** `folders.type`는 단순 metadata가 아니라 write-path validator여야 한다. `photo_diary` folder에는 `saved_places` write 금지, `reels_place` folder에는 `diary_entries` write 금지.
- **Rule:** `share_intake_items`와 `saved_places`는 절대 합치지 않는다. draft/failed/manual-fix 상태를 유지할 inbox가 필요하다.

### 5) Provider Abstractions
- **Map tiles:** tile source adapter로 OpenFreeMap 우선, 장애 시 교체 가능
- **Geocoding:** server-side adapter (`nominatim` first, future `photon`/`pelias`) + cache + throttle
- **Place extraction:** AI/heuristic extraction behind provider interface; contract는 structured candidate list, timeout/failure semantics 포함
- **Media storage:** object storage adapter
- **Reason:** 외부 provider 한계를 제품 핵심 도메인과 격리하지 않으면 비용·장애·정확도 문제로 MVP가 흔들린다.

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
현재 저장소에 제품 소스가 없다는 전제를 기준으로, execution 단계에서 생길 변경 단위는 아래와 같이 예상한다.

### Planned top-level surfaces
- `apps/web/*` — 웹 앱 화면, API route entry, auth/session integration, 지도 UI, 폴더/일기/저장장소 UX
- `apps/mobile/*` — Expo 앱, share entry routing, auth bootstrap, intake confirmation UI
- `packages/domain/*` — 폴더 타입 규칙, 권한, 상태기계, input/output schemas
- `packages/data/*` — ORM schema, repositories, migrations
- `packages/providers/*` — geocode/tile/AI/storage adapters
- `packages/jobs/*` — share analysis, EXIF retry, geocode/backoff workers
- `packages/shared/*` — DTO, validation schemas, constants, event/status enums
- `infra/*` or platform config — object storage, queue, monitoring wiring

### File-level guidance by domain
- **Auth:** session contract should be shared between web and mobile; avoid separate auth stacks
- **Folders/Memberships:** role rules and type constraints belong in shared domain layer, not duplicated in UI
- **Photo diary:** EXIF parsing pipeline should preserve raw metadata and normalized fields separately
- **Share intake:** intake schema must capture raw_url/raw_title/raw_text/source_app/platform/received_via even if some fields are null
- **Providers:** adapter interface definitions should exist before first provider implementation to prevent inlined provider logic

## Sequencing and Dependencies
### Phase 0 — Foundation Decisions
**Goal:** lock repo shape, auth/session approach, database baseline, shared type strategy
- Monorepo/app boundaries
- Shared schema validation approach
- Session/auth usable by web and mobile
- Core enums: folder type, membership role, intake status, visit status

**Dependency reason:** without shared contracts, web and mobile will diverge immediately.

### Phase 1 — Risk Spike: Mobile Share Ingestion First
**Goal:** prove iOS/Android native share entry can authenticate and land raw payload in backend
- iOS Share Extension happy path
- Android share receiver happy path
- Minimal `share_intake` creation and retrieval
- Deep link/app return behavior

**Why this order reduces risk:** this is the hardest product constraint and the biggest architecture invalidator. If this fails late, web work is partially wasted.

### Phase 2 — Collaboration Core
**Goal:** auth, folder creation, memberships, invite links, role enforcement
- Signup/login/me
- `folders`, `memberships`, `invites`
- Accept invite after signup/login
- Owner/editor/viewer permissions

**Dependency reason:** all diary/place writes are folder-scoped and permission-bound.

### Phase 3 — Photo Diary Core
**Goal:** first complete value loop for visited places
- Upload image
- EXIF extraction
- GPS auto-fill or manual map/search correction
- Diary create/read/update/delete
- Folder map/list/detail views

**Why now:** lower ambiguity than place extraction and establishes map, media, and location primitives reused elsewhere.

### Phase 4 — Share Intake Analysis + Saved Place Core
**Goal:** convert raw mobile/web shares into folder-scoped saved places
- Raw intake normalization
- Candidate extraction pipeline
- Confirm/select/manual-fix flows
- Draft save and later resolution
- Saved place CRUD

**Dependency reason:** needs collaboration core and share spike outcome; reuses map/geocoding components from photo diary.

### Phase 5 — Collaboration UX Completion
**Goal:** make shared usage operationally understandable
- Member list
- Invite status states
- Activity feed
- Duplicate-link warning
- Empty/error states

**Why after domain cores:** collaboration UX is valuable but not a blocker for proving the core record/save loops.

### Phase 6 — Reliability, Provider Hardening, Observability
**Goal:** make external dependencies survivable
- Geocode cache/throttle/backoff
- Queue retries/dead-letter semantics
- Error tracing
- Admin-level diagnostics for failed intakes

**Why last:** reliability tuning is meaningful only after the main flows exist, but must complete before MVP release.

## Delivery Phases / Dependencies / Risk Reduction Rationale
1. **Share ingestion spike before heavy product build** reduces the risk of discovering iOS integration blockers after investing in web-only assumptions.
2. **Collaboration core before content domains** ensures every later flow inherits correct folder/type/role semantics instead of retrofitting ACL.
3. **Photo diary before saved places** gives one simpler end-to-end location workflow first and validates map/geocode/media primitives with lower ambiguity.
4. **Saved places after location primitives** isolates the complex candidate-analysis state machine to a later phase when folder, auth, and location foundations are stable.
5. **Reliability hardening after feature loops exist** prevents premature infra work while still protecting release quality.

## Acceptance Criteria
1. Authenticated users can create a folder with immutable type `photo_diary` or `reels_place`.
2. Folder owner can generate an invite link with role, and an invited user can sign up/log in and accept into that role.
3. `photo_diary` folder member can upload one image, extract EXIF, and save diary only when location is present via GPS or manual selection/search.
4. Missing GPS does not block the flow entirely; it blocks final save until manual location is set.
5. All folder members can view all diary entries in that folder on map/list/detail surfaces.
6. `reels_place` folder member can create `share_intake` from mobile share or equivalent native share entry.
7. Candidate extraction supports: exactly 1 candidate → confirm; multiple candidates → choose one; 0 candidates → save draft and allow later manual fix.
8. iPhone and Android both support a native-equivalent share entry into the product.
9. Folder map surfaces only show data allowed by the folder type.
10. Cross-type writes are rejected server-side regardless of UI path.
11. Geocoding calls are server-side only, cached, and rate-limited.
12. External provider failures degrade to recoverable user states instead of silent data loss.

## Verification
### Contract / Domain Verification
- Folder type invariant tests: reject diary writes into `reels_place` and place writes into `photo_diary`
- Membership role tests: owner/editor/viewer action matrix
- Intake status machine tests: `queued → analyzing → needs_confirmation|needs_manual_fix|resolved|failed_recoverable`
- Invite lifecycle tests: pending/accepted/revoked/expired transitions

### API / Integration Verification
- Invite accept after signup/login
- Image upload + EXIF extract + manual location fallback
- Share intake creation from web paste and mobile share payload
- Candidate resolution into `saved_place`
- Duplicate raw URL warning within same folder
- Reverse geocode fallback when address lookup fails but coordinates exist

### Cross-surface Verification
- Mobile share → server intake → web detail visibility
- Web-created folder available during mobile share confirmation
- Logged-out share entry handling and post-login continuation

### External Dependency Verification
- Geocoder rate limit simulation
- AI extraction timeout/failure fallback to draft/manual fix
- Tile provider unavailability handling

## Deliberate-Only: Pre-Mortem
### Failure Scenario 1 — iOS share extension works inconsistently or loses auth/session
- **What fails:** user invokes share sheet, extension cannot authenticate or hand off payload reliably
- **Blast radius:** a core acceptance criterion fails; iPhone users lose the headline feature
- **Mitigation:** isolate Phase 1 spike, choose the simplest token handoff/session design early, keep extension responsibilities minimal, store payload server-side first and defer heavy processing
- **Architect/Critic focus:** auth/session transport between host app and extension; deep link recovery behavior

### Failure Scenario 2 — place candidate extraction is too noisy, causing wrong saved places
- **What fails:** single-candidate auto-confidence is wrong, or multi-candidate lists are unusable
- **Blast radius:** user trust erosion, polluted saved place data, high support burden
- **Mitigation:** do not auto-save on extraction alone; require confirm/select/manual fix, persist raw intake, log extraction confidence/provider, keep manual correction first-class
- **Architect/Critic focus:** candidate confidence thresholds, structured provider contract, dedupe policy, failure observability

### Failure Scenario 3 — free geocoding/tile providers throttle hard under real usage
- **What fails:** map/search/reverse geocode become flaky or slow
- **Blast radius:** photo diary save friction, saved place correction friction, release instability
- **Mitigation:** server cache, request coalescing, token bucket throttling, provider adapter, nullable address with coordinate-first persistence, operational dashboards
- **Architect/Critic focus:** cache key design, retry/backoff semantics, provider swap cost, acceptable degraded UX

## Verification Plan
### Unit
- Folder type validation
- Role permission matrix
- Invite token state rules
- Intake normalization from different payload shapes
- Candidate resolution logic
- EXIF parsing normalization
- Geocode adapter contract and cache-key generation

### Integration
- Auth + invite acceptance + membership creation
- Upload asset → EXIF extraction → diary creation
- Share intake → analysis job → candidate persistence
- Manual fix → saved place creation
- Provider timeout → draft/manual-fix fallback
- Duplicate URL detection within folder

### E2E
- Web: signup → create `photo_diary` folder → upload no-GPS image → choose location → save → map/list/detail visible
- Web: owner creates invite → second user accepts → viewer can read but cannot edit
- Mobile iOS: share link/text → app intake screen → choose folder → confirm/select/fix → saved place visible on web
- Mobile Android: same as iOS path
- Web fallback: paste link/text into reels flow when mobile share is unavailable

### Observability
- Structured logs for share intake create/analyze/resolve lifecycle
- Metrics: intake counts by platform/source_app/status, candidate count distribution, geocode cache hit rate, provider error rate, EXIF failure rate
- Alerts: repeated share-intake failures, geocoder rate-limit spikes, queue backlog, unresolved draft accumulation beyond threshold
- Traces: end-to-end span from share receive to resolved saved place

## Escalation / Risk Gate
### Requires Architect scrutiny
- Whether web/API stay in one Next.js runtime initially or split background job API/service earlier
- Exact mobile companion shape: Expo-managed feasibility with share extension integration vs bare/native customization needs
- Whether provider abstractions are minimal enough to avoid overengineering while still isolating external risk
- Status model for `share_intake_items` and when `saved_places` is created

### Requires Critic scrutiny
- Whether Phase 1 spike acceptance is concrete enough to kill/reconfirm Option A quickly
- Whether photo diary-before-saved-place sequencing is the best risk order
- Whether observability gates are sufficient before MVP release
- Whether rejection of PWA-only is justified by acceptance wording and product constraints

### Risk Gate Before Execution Approval
Execution should not start until Architect/Critic explicitly validate:
1. native share ingestion shape is feasible within chosen mobile stack,
2. intake status machine is complete enough for recovery states,
3. provider/caching plan is sufficient for free-tier map/geocode realities.

## Risks and Mitigations
### Risk 1 — Mobile stack underestimates share extension complexity
- **Mitigation:** time-boxed spike with explicit exit criteria; keep mobile scope minimal; avoid full-native feature creep

### Risk 2 — Domain leakage between folder types
- **Mitigation:** shared server validators, separate domain services, database constraints where possible, cross-type integration tests

### Risk 3 — Draft accumulation creates operational clutter
- **Mitigation:** expose draft states clearly, add activity/log visibility, define stale draft review path, instrument unresolved age metrics

### Risk 4 — AI/provider dependency becomes correctness bottleneck
- **Mitigation:** never require AI success for persistence; preserve raw source; allow manual correction; collect provider diagnostics

### Risk 5 — Free provider usage causes unpredictable latency
- **Mitigation:** cache aggressively, backoff, queue where needed, degrade to coordinate-only persistence

### Risk 6 — Invite links become abuse or leakage vector
- **Mitigation:** expirations, revocation, single-purpose tokens, role restrictions, audit logs

## Open Assumptions
- Mobile companion can share auth/session with acceptable UX and security
- One-image photo diary is enough to validate value before multi-upload
- Users accept folder-level visibility for all diary entries in MVP
- Saved place creation from extracted candidates does not require stronger dedupe than warning-level UX initially
- Managed Postgres + object storage + Redis are acceptable baseline infra choices

## Places Architect/Critic Should Scrutinize
- Is Expo plus share extension integration practical enough, or should the mobile shell assume native customization from day one?
- Should `share_intake` analysis be synchronous-with-timeout for small cases, or always backgrounded for consistent UX?
- Are `saved_places` and `share_intake_items` boundaries sufficient, or is an intermediate “resolution attempt” audit model needed?
- Should geocode cache and throttle live in the same subsystem as place search, or be isolated earlier?
- Is activity logging MVP-critical or can it be reduced to operational events first?

## ADR-Style Decision Block
**ADR ID:** ADR-PPJ-MVP-001

**Title:** Adopt web-primary architecture with thin native mobile share-ingestion companion and server-standardized intake pipeline

**Status:** Proposed for Architect/Critic review

**Context:**
- Product requires collaboration folders of fixed type `photo_diary` or `reels_place`
- iPhone must support native-equivalent share entry
- PWA Web Share Target is not a valid sole solution
- Photo diary requires persisted location
- Geocoding must be server-side with caching, throttling, and provider adapters

**Decision:**
Use a web-primary product surface for collaboration, photo diary, and place management, plus a thin mobile companion that handles iOS/Android share intake and forwards normalized raw payloads into a backend `share_intake` pipeline. Keep `share_intake_items` separate from `saved_places`, enforce folder type boundaries server-side, and route geocoding/provider integrations through adapters with cache and rate limiting.

**Consequences:**
- Satisfies iPhone/Android share entry requirements without forcing the whole product native-first
- Introduces extension/auth/deep-link complexity that must be validated early
- Preserves recoverability for failed/uncertain share analysis via draft intake states
- Keeps future provider swaps possible at modest cost
- Requires stronger early architecture review around mobile stack and background job boundary

**Alternatives considered:**
- PWA-only with paste fallback: rejected because iPhone share-entry acceptance is too weak
- Full native-first product: rejected because scope is too large for MVP and weakens web collaboration velocity
- Unified content model for diary and saved place: rejected because it conflicts with fixed folder-type semantics and complicates ACL/query behavior

## Handoff Guidance
- **Executor:** suitable after Architect/Critic approval, split by bounded slices: foundation/auth, share spike, photo diary, saved places, provider/ops
- **Architect:** needed now for mobile stack feasibility, service boundaries, provider abstractions
- **Critic:** needed now for sequence pressure-testing and acceptance/verification sufficiency
- **Team:** consider only after execution approval if parallel worktrees are justified by separate web/mobile/backend lanes
- **Ultragoal:** not necessary yet unless this MVP expands into multi-iteration delivery tracking across several approved milestones

## Recommended Primary Path
Proceed with **Option A: web-primary app + thin native mobile share-ingestion companion**. It is the only option in this set that directly satisfies the product’s iPhone share requirement while preserving an efficient collaboration-heavy web surface. Alternatives are rejected because they either fail the explicit acceptance boundary (PWA-only) or over-expand MVP scope (full native-first).
