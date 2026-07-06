## Summary
최신 revision은 이전 BLOCK의 핵심 구멍을 대부분 메웠다. 특히 capability-tiered dual-primary 매트릭스, backend-owned contract governance, `FolderCapabilityPolicy` 단일 소유점, `ResolveShareIntake` 동기 orchestration, RN/native share spike의 kill/reconfirm 기준이 모두 실행 가능한 수준으로 구체화됐다. 다만 MVP 속도 관점에서 모바일의 `Photo Diary Authoring`을 BOTH-MUST로 둔 선택은 여전히 강한 결정이며, `location`이 bounded context라기보다 supporting domain/module에 가깝다는 점은 구현 시 엄격히 지켜야 한다. 따라서 이번 패스의 건축적 평가는 `WATCH / COMMENT`가 적절하다.

## Claims
- 최신 revision은 플랫폼별 범위를 `BOTH-MUST / WEB-MUST / MOBILE-MUST / DEFER`로 명시했고, invite/admin과 share-intake를 분리해 parity 폭주를 억제한다.
- 계약 거버넌스는 backend-owned source of truth, `contracts/*` 역할, generated client + checked wrapper, breaking-change CI gate까지 정의했다.
- `FolderCapabilityPolicy`가 폴더 타입/역할 불변성의 단일 소유점으로 선언됐고 관련 command 목록도 열거했다.
- `ResolveShareIntake`는 동기 orchestration으로, share analysis는 비동기 허용 경계로 나눠 핵심 consistency를 보호한다.
- RN/native share spike는 warm start, cold start, unauthenticated recovery, folder continuity, payload durability를 통과 기준으로 둬서 실제 go/no-go gate 형태를 갖췄다.

## Analysis
### 1. Prior blockers closure
이전 architect/critic가 막았던 다섯 축은 대부분 닫혔다.
- **범위:** capability matrix 추가로 closure.
- **계약 거버넌스:** source-of-truth와 CI gate 추가로 closure.
- **location/tile 경계:** 타일 책임을 클라이언트로 내리며 closure.
- **folder capability ownership:** `FolderCapabilityPolicy`로 closure.
- **RN share feasibility:** spike exit criteria 강화로 closure.

### 2. Remaining watch items
완전히 무위험해진 것은 아니다.
- `Photo Diary Authoring`을 BOTH-MUST로 둔 선택은 사용자의 web/app balance 의도에는 맞지만, MVP 속도 측면에서는 여전히 무거운 편이다. 다만 이미 matrix에서 admin richness, advanced parity, email invites, multi-photo를 defer 했기 때문에 현재 문서상으로는 수용 가능하다.
- `location`이 여전히 패키지 구조상 `domains/location`으로 남아 있다. 문서상 설명은 supporting service 역할로 내려왔으므로, 실행 시 이를 독립 핵심 bounded context처럼 다루지 않도록 discipline이 필요하다.
- generated client + checked wrapper 전략은 적절하지만, 실제로는 codegen 타이밍과 wrapper thinness를 지키지 않으면 다시 drift가 생길 수 있다. 이건 계획상 더 줄일 수 있는 불명확성은 아니라 execution gate에서 검증하면 충분하다.

### 3. Strongest steelman antithesis
가장 강한 반론은 여전히 `web-centered + native share layer`다. 이 대안은 mobile photo diary authoring까지 BOTH-MUST로 잡지 않아도 되므로 MVP를 더 빨리 낼 수 있다. 기술적으로는 더 보수적이고 실패 반경도 작다. 그럼에도 이번 revision은 사용자의 명시적 의도인 web/app balance를 capability-tiered 형태로 해석해 scope를 제어했으므로, 현재 사용자 의도를 더 잘 반영하는 안은 최신 revision이다.

### 4. Tradeoff tension
- **제품 균형 vs MVP 속도:** mobile diary authoring을 넣으면 균형은 좋아지지만 속도는 느려진다.
- **계약 엄격성 vs 개발 편의:** generated contract governance를 강하게 걸면 초반 마찰이 있지만, 세 클라이언트(web/mobile/backend) 구조에서는 장기적으로 필수다.
- **DDD purity vs practical packaging:** `location`을 supporting service로 운영한다면 문서상 도메인 배치와 실제 중요도를 혼동하지 않아야 한다.

### 5. Synthesis
현재 문서는 충분히 실행 가능해졌다. 추천 합성은 다음과 같다.
- capability-tiered dual-primary 유지
- mobile photo diary authoring은 single-photo + basic metadata + location correction으로만 제한
- `location`은 실행 단계에서 supporting service 취급을 문서/코드 리뷰 규칙으로 재확인
- contract governance와 folder policy ownership을 first-sprint 산출물로 승격

## Findings
1. `FolderCapabilityPolicy`, capability matrix, contract governance가 들어오면서 이전의 execution ambiguity가 크게 줄었다.
2. `ResolveShareIntake` 동기 orchestration 명시는 MVP consistency 설계로 적절하다.
3. RN/native share spike 기준은 이제 실제 go/no-go gate 역할을 할 수 있다.
4. 남은 주요 리스크는 불명확성보다 scope discipline 문제다.
5. 따라서 현재 상태는 BLOCK이 아니라 WATCH 수준이다.

## Recommendations
1. execution 단계 계획서에는 `Photo Diary Authoring (mobile)`을 single-photo/single-entry MVP로 다시 한 줄 더 제한하라.
2. `domains/location`은 supporting service로만 다룬다는 note를 final plan에도 재명시하라.
3. contract generation and CI gate를 Foundation의 첫 deliverable로 올려 backend/client 병렬 작업 전에 잠그라.

## Architectural Status
WATCH

## Code Review Recommendation
COMMENT

## Tradeoffs
| Tension | Current choice | Alternative | My read |
|---|---|---|---|
| Web/app balance vs MVP speed | capability-tiered dual-primary | web-centered + native share | 현재 사용자 의도상 전자가 맞지만 discipline이 필수 |
| Contract rigor vs early velocity | backend-owned generated contract | looser hand-maintained DTOs | 이 구조에서는 엄격 계약이 맞다 |
| DDD packaging purity vs practicality | supporting service as `domains/location` | infra/support module | 실행 때 후자처럼 다뤄야 안정적 |

## Strongest Steelman Antithesis
모바일을 1급으로 두되 photo diary authoring까지 양쪽 필수로 잡는 것은 여전히 과할 수 있다. 제품의 hardest acceptance는 share-entry이므로, 모바일은 share-intake + saved-place + browse 중심, diary authoring은 웹 우선으로 한 단계 더 줄인 plan이 출시 속도만 보면 더 유리하다.

## Synthesis Path
현 revision을 유지하되, 실행 승인 직전에는 mobile photo diary authoring의 범위를 단일 사진/기본 작성에 한정하는 문구를 더 명시하고, `location`의 supporting-service 성격을 재확인하면 된다. 그 수준이면 추가 재설계 없이 실행 승인 후보로 볼 수 있다.
