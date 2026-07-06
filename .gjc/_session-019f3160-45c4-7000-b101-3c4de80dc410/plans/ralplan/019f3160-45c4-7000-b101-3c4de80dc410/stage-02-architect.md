## Summary
전략적 방향 자체는 합리적이다. `photo_diary`/`reels_place` 분리, Spring Boot + DDD 고정, iPhone 네이티브 공유 진입 유지, `share_intake`와 `saved_places` 분리는 모두 스펙과 잘 맞는다. 다만 execution 승인 단계로 넘기기에는 dual-primary 범위, 계약 거버넌스, bounded context 절단선, RN share feasibility gate가 아직 선언적이라서 `BLOCK / REQUEST CHANGES`가 맞다.

## Claims
- revised draft는 Spring Boot + DDD 백엔드와 dual-primary web/mobile 전략으로 전환했다 (`stage-02-revision.md:4`, `stage-02-revision.md:17`, `stage-02-revision.md:77`).
- 스펙의 필수 요구는 폴더 타입 고정, 초대 링크, 사진일기 위치 필수, 폴더 멤버 전체 조회, 후보 0개 draft 저장, iPhone/Android 네이티브 동등 공유 진입이다 (`deep-interview-picture-journal-mvp.md:69-76`, `deep-interview-picture-journal-mvp.md:88-97`).
- draft는 스스로 dual-primary scope 폭증, RN native share 현실성, contract governance, location domain 분리 여부를 핵심 scrutiny 대상으로 적었다 (`stage-02-revision.md:464-468`, `stage-02-revision.md:505-520`, `stage-02-revision.md:552-554`).
- 따라서 현재 검토 포인트는 방향의 타당성보다, 그 방향을 안전하게 실행 가능한 형태로 얼마나 구체화했는지다.

## Analysis
### 1. Spec compliance
좋은 점부터 말하면, 스펙 핵심 acceptance는 대부분 정확히 계승했다. 폴더 타입 고정, cross-type write 금지, 사진일기 위치 필수, `share_intake` draft 유지, iPhone native-equivalent share entry는 revised plan에서 빠지지 않았다 (`deep-interview-picture-journal-mvp.md:69-76`, `deep-interview-picture-journal-mvp.md:95-96`; `stage-02-revision.md:415-416`, `stage-02-revision.md:517-521`, `stage-02-revision.md:572`).

문제는 revised draft가 스펙을 위반했다기보다, dual-primary 전환으로 스펙보다 넓어진 범위를 아직 제어하지 못한다는 점이다. 스펙은 mobile을 필수 공유 진입점으로 요구하지만, full parity 수준의 양면 authoring을 요구하지는 않는다 (`deep-interview-picture-journal-mvp.md:96`, `deep-interview-picture-journal-mvp.md:121`, `deep-interview-picture-journal-mvp.md:244-255`). 반면 revised draft는 인증, 폴더 목록/상세, 사진일기 조회/작성, 저장장소 조회/수정, 초대 수락을 web/mobile 모두에서 가능해야 한다고 적어 MVP capability 범위를 급격히 넓힌다 (`stage-02-revision.md:79-82`).

### 2. Bounded contexts and aggregate/package strategy
`collaboration`, `photojournal`, `placeintake`, `savedplace` 분리는 방향상 맞다. 특히 `share_intake_items`와 `saved_places`를 분리한 것은 후보 0개/실패 복구 경로를 보존해야 한다는 스펙과 정합적이다 (`deep-interview-picture-journal-mvp.md:193`, `stage-02-revision.md:121-127`, `stage-02-revision.md:572`).

하지만 현재 절단선은 한 군데 과하고 한 군데 약하다.
- 과한 쪽: `location`을 별도 도메인으로 올리고 backend에 `tile` adapter까지 넣은 설계다 (`stage-02-revision.md:129`, `stage-02-revision.md:264-277`, `stage-02-revision.md:508`, `stage-02-revision.md:554`). 타일 공급자는 본질적으로 client rendering concern이다. geocode/reverse-geocode/cache/throttle은 서버 supporting service로 둘 수 있지만, tile을 server domain abstraction으로 승격하면 DDD 경계가 흐려진다.
- 약한 쪽: 폴더 타입 불변성의 단일 소유자가 아직 안 보인다. 원칙 1은 folder type이 domain boundary를 강제해야 한다고 말하지만 (`stage-02-revision.md:26`), 패키지 구조상 `FolderType`은 `collaboration`에 있고 실제 write-path는 `photojournal`/`savedplace`에서 열린다. 이 경우 타입 검증이 각 application service에 분산될 위험이 높다.

### 3. React Native native-share feasibility
revised draft가 이 위험을 인지한 점은 좋다 (`stage-02-revision.md:47`, `stage-02-revision.md:343-347`, `stage-02-revision.md:506`, `stage-02-revision.md:519`). 다만 현재 spike success definition이 너무 얕다. “share extension/receiver에서 raw payload를 backend `share_intake`까지 보낸다”만 통과하면, 실제로는 가장 어려운 문제인 extension→app handoff, 인증 continuity, cold start 복구, folder selection continuity, extension 종료 후 재개가 남는다. 스펙은 단순 POST 성공이 아니라 사용자가 실제로 공유하기→앱으로 보내기 흐름을 완결할 수 있어야 함을 요구한다 (`deep-interview-picture-journal-mvp.md:96`, `deep-interview-picture-journal-mvp.md:429-430`).

### 4. API contract governance
이 구조에서 제일 위험한 장기 비용은 코드가 아니라 계약 drift다. draft도 OpenAPI, enum/error catalog, compatibility checks가 중요하다고 적었지만 (`stage-02-revision.md:104-108`, `stage-02-revision.md:319`, `stage-02-revision.md:337`, `stage-02-revision.md:435-437`, `stage-02-revision.md:528`, `stage-02-revision.md:553`), 누가 source of truth인지가 없다. Spring Boot에서 생성한 OpenAPI가 진실인지, `contracts/*`의 hand-authored spec가 진실인지, mobile/web client를 codegen할지 수동 wrapper를 둘지, breaking change gate를 CI에서 어떻게 막을지 없다. Principle 3은 “공통 API 계약, 인증 모델, 상태 의미 공유”인데 (`stage-02-revision.md:28`, `stage-02-revision.md:103-110`), enforcement가 빠져 있어 원칙 선언만 남아 있다.

### 5. Dual-primary scope and MVP boundedness
 draft는 parity-driven failure를 스스로 pre-mortem에 적었고 (`stage-02-revision.md:464-468`, `stage-02-revision.md:533-545`), 또 execution 전 validation gate에도 “dual-primary capability scope is bounded enough for MVP”를 넣었다 (`stage-02-revision.md:517-520`). 그런데 정작 본문 acceptance는 “MVP-critical flows defined for their platforms”라는 문장 하나로 남겨 둬서 (`stage-02-revision.md:413`) 무엇이 각 플랫폼의 MVP-critical flow인지 문서상 확정되지 않았다. 이건 deliberate-mode 문서로는 부족하다. 지금 상태로 승인하면 구현 단계에서 “모바일에서도 photo diary 작성이 꼭 돼야 하나?”, “웹에서도 share intake create를 해야 하나?”, “invite 관리와 invite 수락은 둘 다 양면인가?” 같은 해석 싸움이 거의 확실하다.

## Root Cause
근본 문제는 방향이 틀린 게 아니라, 방향을 강제할 메커니즘이 비어 있다는 점이다. revised draft는 좋은 원칙들을 선언했지만, 그 원칙을 실행 레벨로 고정하는 네 가지 장치—클라이언트 capability matrix, 계약 source-of-truth, 폴더 타입/권한의 단일 정책 소유자, RN share spike의 kill criteria—가 아직 없다. 그래서 모든 리스크가 “알고는 있음” 수준에 머물고 있다.

## Findings
### 1. HIGH — dual-primary 범위가 아직 계획 수준에서 bounded 되지 않았다
- **Reference:** `stage-02-revision.md:79-82`, `stage-02-revision.md:413`, `stage-02-revision.md:464-468`, `stage-02-revision.md:517-520`
- **Impact:** MVP가 capability parity와 UI parity를 혼동해 범위 폭증으로 무너질 수 있다. 특히 mobile photo diary authoring, web share-intake authoring, invite/admin parity가 암묵적으로 추가되면 일정과 설계가 동시에 흔들린다.
- **Principle weak spot:** Principle 3의 “둘 다 1급 클라이언트”가 실행 기준 없이 선언으로만 남아 있다.
- **Change request:** 기능별 client capability matrix를 추가하라. 예: Auth/Folder Browse/Invite Accept/Saved Place Browse는 BOTH-MUST, Share Intake Create/Resolve는 MOBILE-MUST + WEB-SHOULD/DEFER, Rich Photo Diary Authoring은 WEB-MUST + MOBILE-DEFER 같은 식으로 명시하고 Acceptance/Phase 5를 그 표에 맞춰 다시 써라.

### 2. HIGH — API contract governance가 선언적이라 Spring Boot ↔ web/mobile drift를 막기 어렵다
- **Reference:** `stage-02-revision.md:104-108`, `stage-02-revision.md:319`, `stage-02-revision.md:337`, `stage-02-revision.md:435-437`, `stage-02-revision.md:528`, `stage-02-revision.md:553`
- **Impact:** enum/status/error semantics가 분기되면 dual-primary의 가장 중요한 장점이 사라지고, 클라이언트별 hotfix가 도메인 규칙 중복으로 이어진다.
- **Principle violation:** Principle 3은 계약 공유를 요구하지만, source-of-truth와 breakage gate가 없어 실제로는 지켜질 수 없다.
- **Change request:** OpenAPI ownership을 하나로 고정하라. 가장 무난한 안은 Spring Boot adapter layer에서 OpenAPI를 생성하고, `contracts/*`에는 generated artifact + error catalog + compat policy만 두는 것이다. 동시에 generated clients or checked wrappers, semver/versioning rule, CI breaking-change gate를 문서에 넣어라.

### 3. MEDIUM — `location` 독립 도메인 + backend `tile` adapter는 DDD 경계를 흐린다
- **Reference:** `stage-02-revision.md:129`, `stage-02-revision.md:264-277`, `stage-02-revision.md:508`, `stage-02-revision.md:554`; spec constraint at `deep-interview-picture-journal-mvp.md:75-76`
- **Impact:** 서버 도메인 모델이 presentation/provider configuration까지 먹어 들어가고, 실제 핵심 도메인인 photo/place의 위치 규칙과 supporting infra concern이 섞인다.
- **Principle weak spot:** Principle 2는 도메인 규칙 수렴을 말하지만, 여기서는 도메인보다 렌더링 인프라 concern이 서버 DDD 경계에 들어온다.
- **Change request:** `location`을 독립 bounded context가 아니라 supporting module/service로 낮추고, backend는 geocode/reverse-geocode/cache/throttle만 소유하게 하라. tile provider 선택과 fallback은 web/mobile client infrastructure concern으로 옮겨라.

### 4. MEDIUM — 폴더 타입 불변성의 단일 정책 소유자가 정의되지 않았다
- **Reference:** `deep-interview-picture-journal-mvp.md:69`, `deep-interview-picture-journal-mvp.md:120`, `stage-02-revision.md:26`, `stage-02-revision.md:119-127`, `stage-02-revision.md:415`
- **Impact:** `photojournal`과 `savedplace` application service가 각각 `folders.type`를 검사하는 방식으로 흩어질 가능성이 높다. 그러면 가장 중요한 invariant가 여러 write path에 중복된다.
- **Principle violation:** Principle 1은 폴더 타입이 경계를 강제해야 한다고 했지만, 지금 문서만으로는 그 강제가 aggregate 수준인지 application policy 수준인지 불명확하다.
- **Change request:** `FolderCapabilityPolicy` 같은 단일 서버 권한/타입 정책을 명시하라. 모든 folder-scoped command가 이 정책을 통과하도록 하고, `share_intake resolve → saved_place create`, `diary create/update` 같은 경로가 이 정책을 우회하지 못하게 적어라.

### 5. MEDIUM — RN native-share spike의 exit criteria가 얕다
- **Reference:** `stage-02-revision.md:39`, `stage-02-revision.md:47`, `stage-02-revision.md:343-347`, `stage-02-revision.md:506`, `stage-02-revision.md:519`; spec acceptance at `deep-interview-picture-journal-mvp.md:96`, `deep-interview-picture-journal-mvp.md:244-255`
- **Impact:** Phase 1을 통과했다고 착각한 뒤, 실제 MVP 후반에 iOS extension lifecycle/auth continuity에서 막힐 수 있다.
- **Principle weak spot:** Principle 5는 복구 가능한 저장을 우선시하지만, spike 기준에 durable handoff와 interrupted-flow recovery가 없다.
- **Change request:** spike acceptance를 다음까지 확장하라: (a) authenticated user warm start, (b) cold start with pending share payload recovery, (c) unauthenticated share 후 로그인 뒤 복구, (d) folder selection and resolve continuation, (e) extension 종료 후에도 backend에 raw payload가 유실되지 않음. 이 중 하나라도 실패하면 RN stack/extension strategy를 재결정하는 kill gate를 명시하라.

## Recommendations
1. **가장 먼저 dual-primary capability matrix를 문서화하라.** 이게 없으면 MVP 범위가 흔들리고, 이후 package/API 논의도 다 흔들린다.
2. **계약 거버넌스를 source-of-truth 중심으로 고정하라.** OpenAPI ownership, enum/error catalog ownership, client generation/check 방식, CI breakage gate까지 문서에 넣어라.
3. **bounded context를 한 단계 단순화하라.** `location`은 supporting service로 내리고, `tile`은 client concern으로 분리하라.
4. **folder type + role enforcement의 단일 정책 진입점을 정의하라.** 가장 중요한 invariant를 각 도메인 use case에 복붙하지 마라.
5. **RN share spike를 kill/reconfirm 가능한 형태로 재작성하라.** payload POST 데모가 아니라 실제 사용자 흐름 완결 기준으로 바꿔라.
6. **`placeintake` vs `savedplace`의 choreography 방식을 문장 하나 더 명시하라.** 추천은 “동일 bounded context 안의 두 aggregate를 application service가 동기 orchestration, domain event는 부수효과 전파에만 사용”이다. 최소한 MVP에서는 eventual consistency를 늘리지 않는 편이 낫다.

## Architectural Status
BLOCK

## Code Review Recommendation
REQUEST CHANGES

## Tradeoffs
| Tension | Option A | Option B | My read |
|---|---|---|---|
| Dual-primary breadth vs MVP speed | 양면 capability를 넓게 잡으면 사용자 대칭성은 좋아진다 | capability를 표로 제한하면 delivery가 빨라진다 | MVP에서는 capability-tiered dual-primary가 맞다 |
| `placeintake`/`savedplace` 분리 vs orchestration 단순성 | 분리하면 복구 상태와 draft semantics가 선명하다 | 합치면 구현은 단순하지만 recovery 모델이 흐려진다 | 분리는 유지하되, MVP에서는 같은 bounded context 안 동기 orchestration이 안전하다 |
| Contract-first rigor vs iteration speed | 엄격한 contract governance는 drift를 강하게 막는다 | 느슨하면 초기 속도는 빠르다 | Spring Boot + web + mobile 3자 구조에서는 느슨한 계약이 결국 더 느리다 |
| DDD purity vs practical infrastructure placement | `location`을 독립 도메인으로 두면 개념상 예뻐 보인다 | supporting service로 두면 현실 경계가 더 명확하다 | 이 MVP에서는 후자가 더 maintainable하다 |

## Strongest Steelman Antithesis
가장 강한 반론은 “dual-primary 자체가 MVP에 과하다”는 주장이다. 스펙 원형은 이미 `웹 중심 + 얇은 네이티브 모바일 컴패니언`이 가장 현실적이라고 정리했고 (`deep-interview-picture-journal-mvp.md:121`, `deep-interview-picture-journal-mvp.md:244-255`), 실제 hardest acceptance도 iPhone share entry다 (`deep-interview-picture-journal-mvp.md:96`). 이 시각에서는 모바일은 고품질 share capture + lightweight follow-up만 담당하고, photo diary authoring/협업 관리/고밀도 지도 탐색은 web에 집중시키는 편이 MVP 리스크를 훨씬 줄인다. 이 반론은 기술적으로 상당히 강하다.

## Synthesis Path
완전한 web-primary로 되돌릴 필요는 없다. 대신 **capability-tiered dual-primary**로 좁혀라.
- **BOTH-MUST:** auth, folder browse/detail, invite accept, saved place/detail browse, cross-client consistency
- **MOBILE-MUST:** native share entry, intake resolve, draft/manual fix, quick saved-place edit
- **WEB-MUST:** folder/admin management, rich photo diary authoring, large-map exploration, invite management
- **DEFER unless explicitly required:** mobile photo diary authoring, full web-based share-intake creation, symmetric admin richness

이 synthesis는 사용자의 dual-primary 변경 의도를 존중하면서도, parity-driven scope explosion을 막는다. 즉 전략은 유지하되, 실행 범위만 더 냉정하게 고정하는 게 맞다.
