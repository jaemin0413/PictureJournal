# Deep Interview Spec: Picture Journal MVP

## Metadata
- Interview ID: 90ea6fa9-2511-4c45-8fa7-5f3aba6f241b
- Rounds: 7
- Final Ambiguity Score: 4.2%
- Type: greenfield
- Generated: 2026-07-05T08:50:00Z
- Threshold: 0.05
- Threshold Source: default
- Initial Context Summarized: yes
- Status: PASSED
- Auto-Researched Rounds: []
- Auto-Answered Rounds: []
- Architect Failures: 0
- Lateral Reviews: 0
- Lateral Panel Failures: 2
- Refined Rounds: []
- Closure Overrides: 1
- Restated Goal: 회원 기반 협업 서비스에서 사용자는 타입이 고정된 폴더를 만들고 초대 링크로 멤버를 받아, 사진일기 폴더에서는 방문한 장소의 사진 추억을 지도형 일기로 기록하고, 릴스 저장 폴더에서는 iPhone을 포함한 모바일 공유 흐름으로 아직 방문하지 않은 장소를 임시 저장·후보 선택·보정하며 함께 관리할 수 있어야 한다.

## Clarity Breakdown
| Dimension | Score | Weight | Weighted |
|-----------|-------|--------|----------|
| Goal Clarity | 0.96 | 0.40 | 0.384 |
| Constraint Clarity | 0.94 | 0.30 | 0.282 |
| Success Criteria | 0.95 | 0.30 | 0.285 |
| **Total Clarity** | | | **0.951** |
| **Ambiguity** | | | **0.049** |

## Topology
| Component | Status | Description | Coverage / Deferral Note |
|-----------|--------|-------------|--------------------------|
| 협업 폴더/공간 | active | 여러 폴더를 만들고 멤버를 초대하며 권한으로 협업하는 최상위 컨테이너 | 폴더 타입 고정, 초대 링크 기반 가입/수락, 역할 반영, 멤버 공용 조회 정책까지 확정 |
| 사진일기 폴더(가본 장소 기록) | active | 사진 업로드, EXIF 분석, 지도 위치 보정, 사진일기 CRUD로 추억을 기록 | 위치 필수 저장 정책, 폴더 멤버 전체 조회 정책, 지도/리스트/상세 흐름까지 MVP 범위 확정 |
| 릴스 저장 폴더(안 가본 장소 저장) | active | 공유된 릴스 링크/텍스트를 받아 가보고 싶은 장소를 저장·보정 | 후보 1개 확인 저장, 다수 후보 선택 저장, 후보 0개 임시 저장, iPhone 지원 필요성까지 확정 |

## Established Facts
- Round 0: 협업 폴더/공간이 최상위 컨테이너이며 기능은 그 하위에 배치된다.
- Round 0: 사진일기(가본 장소)와 릴스 저장(안 가본 장소)은 서로 다른 폴더 맥락으로 분리된다.
- Round 1: 폴더는 생성 시 타입이 고정되며 `photo_diary` 또는 `reels_place` 중 하나다.
- Round 2: 릴스 저장 MVP 성공 기준은 후보 1개면 확인 후 저장, 여러 후보면 선택 후 저장이다.
- Round 3: 사진일기 저장 MVP 성공 기준은 GPS 자동 표시 또는 사용자 위치 보정 후 저장이다.
- Round 4: 폴더 초대 MVP는 초대 링크만 지원하고, 링크 진입 후 가입/수락 시 역할이 반영되어야 한다.
- Round 5: 릴스 후보가 0개면 원본 링크/텍스트를 임시 저장하고 나중에 보정할 수 있어야 한다.
- Round 6: 사진일기 폴더에서는 폴더 멤버가 모든 사진일기를 볼 수 있고, 별도 개인 비공개 범위는 두지 않는다.
- Round 7: iPhone에서도 공유 저장이 반드시 작동해야 하며, 구현 방식은 PWA Web Share Target에 고정하지 않는다.

## Trigger Metadata
| Round | Trigger | Status | Affected | Ambiguity |
|------|---------|--------|----------|-----------|
| 1 | none | clarified | 협업 폴더 / constraints | 100% → 46.4% |
| 2 | none | clarified | 릴스 저장 / criteria | 46.4% → 32.1% |
| 3 | none | clarified | 사진일기 / criteria | 32.1% → 23.7% |
| 4 | none | clarified | 협업 폴더 / criteria | 23.7% → 18.8% |
| 5 | none | clarified | 릴스 저장 / constraints | 18.8% → 10.9% |
| 6 | none | clarified | 사진일기 / constraints | 10.9% → 4.8% |
| 7 | closure override | clarified | 릴스 저장 / constraints | 4.8% 유지, iPhone 지원 제약 해소 |

## Lateral Review Panel
- 설계상 마일스톤 전환 지점에서 패널 훅을 시도했지만, 이번 런에서는 실질적인 패널 결과를 접지하지 못했다.
- `lateral_panel_failures = 2`로 기록했고, 대신 사실 확인을 직접 수행한 뒤 사용자 결정 질문으로 복귀했다.
- 특히 `share_target`의 iPhone 미지원은 외부 사실 검증 후 사용자에게 명시적으로 다시 물어 범위를 잠갔다.

## Goal
협업 가능한 타입 고정 폴더를 중심으로, 방문한 장소는 사진일기 폴더에서 지도형 추억 기록으로 남기고, 아직 방문하지 않은 장소는 릴스 저장 폴더에서 모바일 공유 기반으로 저장·보정·관리하는 서비스를 만든다.

## Constraints
- 폴더 타입은 생성 시 고정되며, `photo_diary`와 `reels_place`는 섞지 않는다.
- 초대는 MVP에서 초대 링크만 지원한다.
- 사진일기는 저장 시 위치가 반드시 있어야 한다.
- 사진일기 폴더 안에서는 폴더 멤버가 모든 사진일기를 볼 수 있다.
- 릴스 저장은 iPhone에서도 반드시 동작해야 한다.
- Android/Chromium의 PWA Web Share Target에 의존하지 않는다.
- 지도는 오픈소스/무료 기반(MapLibre GL JS + OpenFreeMap 우선)으로 설계한다.
- 지오코딩은 서버 사이드 호출, 캐싱, 속도 제한, provider adapter 구조를 지켜야 한다.

## Non-Goals
- AI 자동 일기 작성
- 사진일기와 릴스 저장 폴더를 하나의 폴더 타입으로 합치기
- 폴더 내부 개인 비공개 일기
- MVP에서 이메일 초대
- MVP에서 장소 추천 기능
- MVP에서 오프라인 지도
- MVP에서 다중 사진 업로드

## Acceptance Criteria
- [ ] 사용자는 회원가입/로그인 후 `photo_diary` 또는 `reels_place` 타입의 폴더를 생성할 수 있다.
- [ ] 폴더 owner는 초대 링크를 만들고, 링크 진입 사용자는 가입/수락 후 지정 역할로 참여할 수 있다.
- [ ] `photo_diary` 폴더에서 사용자는 사진 1장을 업로드하고 EXIF를 분석해 위치/촬영일을 자동 채우거나 직접 위치를 선택해 일기를 저장할 수 있다.
- [ ] GPS가 없는 사진은 지도 직접 선택 또는 장소 검색으로 위치를 정해야만 저장된다.
- [ ] `photo_diary` 폴더 멤버는 해당 폴더의 모든 사진일기를 지도/리스트/상세에서 조회할 수 있다.
- [ ] `reels_place` 폴더에서 사용자는 릴스 링크/공유 텍스트를 저장할 수 있다.
- [ ] 장소 후보가 1개면 확인 후 저장, 여러 개면 선택 후 저장된다.
- [ ] 장소 후보가 0개면 원본 링크/텍스트가 임시 저장되고, 이후 사용자가 장소를 보정해 확정 저장할 수 있다.
- [ ] iPhone과 Android 모두에서 “공유하기 → 앱으로 보내기” 또는 동등한 네이티브 공유 진입이 제공된다.
- [ ] 폴더별 지도 화면에서 타입에 맞는 데이터만 노출된다.

## Deferrals
- 이메일 초대
- 사진 여러 장 업로드
- 방문 완료 후 사진일기 자동 연결
- 댓글 / 반응 이모지
- 여행 코스 / 타임라인 / 달력
- 장소 추천, 푸시 알림, 오프라인 지도
- Convergence pacing deferral: 별도 최소 라운드 강제 없음, 양방향 scoring 사용

## Assumptions Exposed & Resolved
| Assumption | Challenge | Resolution |
|------------|-----------|------------|
| 사진일기와 릴스 저장은 같은 폴더일 수도 있다 | 폴더 구조를 최상위 토폴로지로 먼저 검증 | 서로 다른 폴더 타입으로 분리 |
| PWA Web Share Target이면 모바일 공유는 충분하다 | iPhone 지원 사실 검증 | iPhone 지원을 위해 네이티브 Share Extension/래퍼 범위 허용 |
| 릴스 저장은 후보가 확실할 때만 의미 있다 | 후보 0개 케이스를 별도로 질문 | 임시 저장 후 나중 보정 허용 |
| 공유 폴더 안에서도 일기 비공개가 필요할 수 있다 | ACL 정책을 직접 질문 | MVP에서는 폴더 멤버 전체 공개 |

## Product Proposal
### 핵심 제품 구조
- 서비스는 하나지만, 콘텐츠 도메인은 `사진일기 폴더`와 `릴스 저장 폴더`로 명확히 분리한다.
- 홈에서는 두 타입의 폴더를 함께 보여주되, 폴더 카드에 타입 배지를 둔다.
- 각 폴더는 단일 지도와 단일 리스트를 가지지만, 그 안에서 다루는 엔티티는 폴더 타입에 따라 고정된다.
- iPhone 공유 저장 제약 때문에 제품은 **웹 중심 + 네이티브 모바일 공유 수신 레이어** 구조로 가는 것이 가장 현실적이다.

### 추천 사용자 흐름
1. 회원가입 → 홈 진입
2. 폴더 생성 시 타입 선택
3. `photo_diary` 폴더: 사진 업로드 → EXIF 분석 → 위치 보정 → 제목/본문/태그 입력 → 저장
4. `reels_place` 폴더: Instagram 등에서 공유 → 앱 수신 → 자동 분석 → 후보 확인/선택 → 저장
5. 멤버 초대 → 링크 수락 → 공동 편집/조회

## Screen Design
### 1. 랜딩 / 홈
- 비로그인: 서비스 소개, 사진 기반 기록 소개, 릴스 저장 소개, 시작하기
- 로그인: 폴더 탭(전체 / 사진일기 / 릴스 저장), 최근 활동, 새 폴더 만들기, 빠른 저장 진입

### 2. 폴더 생성 모달
- 필드: 폴더명, 설명, 폴더 타입(`photo_diary` / `reels_place`)
- 경고: 폴더 타입은 생성 후 변경 불가

### 3. 폴더 대시보드
- 공통: 폴더명, 설명, 멤버, 초대 버튼, 지도 미리보기
- `photo_diary`: 최근 사진일기, 사진 추가 CTA
- `reels_place`: 최근 저장 장소, 링크 저장 CTA

### 4. 사진 업로드 / 일기 작성
- 사진 미리보기
- EXIF 분석 결과 카드(GPS / 날짜 / 시간 / 기기)
- 지도 선택기 + 장소 검색
- 입력: 제목, 본문, 장소명, 태그
- 에러 상태: 위치 미설정, EXIF 없음, 업로드 실패

### 5. 사진일기 상세
- 상단 이미지, 제목, 날짜, 장소명
- 본문
- 지도 카드
- 작성자, 태그, 수정/삭제

### 6. 릴스 공유 수신 화면
- 네이티브 공유 진입 후 로딩
- 수신 데이터 표시(url/title/text)
- 분석 상태(`queued/analyzing/needs_confirmation/needs_manual_fix/resolved`)
- 폴더 선택
- 후보가 1개면 확인 카드, 여러 개면 선택 리스트, 0개면 임시 저장 + 수동 보정 폼

### 7. 저장 장소 상세
- 장소명, 카테고리, 주소/지역, 지도, 추천 이유, 키워드, 원본 링크, 저장자, 저장일, 방문 상태, 메모

### 8. 멤버 초대/수락
- 초대 링크 생성
- 역할 선택(owner 제외 editor/viewer)
- 링크 상태(활성/만료/사용됨)
- 링크 진입 시 가입/로그인 → 초대 수락

## DB Design
### 핵심 엔티티
| Table | Key columns | Notes |
|------|-------------|------|
| users | id, email, display_name, avatar_url, created_at | 인증 사용자 |
| folders | id, owner_user_id, type, name, description, cover_image_url, created_at | `type in ('photo_diary','reels_place')` |
| folder_memberships | id, folder_id, user_id, role, joined_at | `role in ('owner','editor','viewer')`, unique(folder_id,user_id) |
| folder_invites | id, folder_id, token, role, status, invited_by_user_id, expires_at, accepted_by_user_id | `status in ('pending','accepted','revoked','expired')` |
| media_assets | id, uploader_user_id, storage_key, mime_type, width, height, exif_json, taken_at, camera_make, camera_model | 사진 원본/메타 저장 |
| diary_entries | id, folder_id, author_user_id, media_asset_id, title, body, place_name, latitude, longitude, captured_at, visibility_mode, created_at, updated_at | `visibility_mode='folder_members'`만 허용 |
| diary_tags | id, folder_id, name, normalized_name | 폴더 범위 태그 |
| diary_entry_tags | diary_entry_id, diary_tag_id | 다대다 |
| share_intake_items | id, source_app, raw_url, raw_title, raw_text, platform, received_via, status, received_by_user_id, received_at, normalized_url, failure_reason | 네이티브 공유/웹 붙여넣기 공통 inbox |
| saved_places | id, folder_id, creator_user_id, share_intake_item_id, name, category, address, region_text, latitude, longitude, summary, why_recommended, highlights_json, keywords_json, visit_status, saved_at, resolved_at | `visit_status in ('want_to_go','visited','on_hold','not_good')` |
| place_candidates | id, share_intake_item_id, provider, name, address, latitude, longitude, confidence, raw_payload_json | 분석 후보 저장 |
| geocode_cache | id, provider, query_key, response_json, cached_at, expires_at | 서버 캐시 |
| activity_logs | id, actor_user_id, folder_id, entity_type, entity_id, action, payload_json, created_at | 감사/최근 활동 |

### 설계 원칙
- `folders.type`으로 엔티티 혼합을 막는다.
- `share_intake_items`와 `saved_places`를 분리해, 후보 0개 임시 저장 상태를 표현한다.
- 파일 메타는 `media_assets.exif_json`에 원본 보존, 주요 필드만 정규화한다.

## API Design
### Auth
- `POST /api/v1/auth/signup`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/logout`
- `GET /api/v1/me`

### Folders & Members
- `GET /api/v1/folders?type=`
- `POST /api/v1/folders`
- `GET /api/v1/folders/:folderId`
- `PATCH /api/v1/folders/:folderId`
- `POST /api/v1/folders/:folderId/invites`
- `GET /api/v1/invites/:token`
- `POST /api/v1/invites/:token/accept`
- `GET /api/v1/folders/:folderId/members`

### Photo Diary
- `POST /api/v1/media/upload-url` 또는 `POST /api/v1/media/direct-upload`
- `POST /api/v1/media/:mediaId/exif-extract`
- `POST /api/v1/folders/:folderId/diary-entries`
- `GET /api/v1/folders/:folderId/diary-entries?tag=&place=&from=&to=`
- `GET /api/v1/diary-entries/:entryId`
- `PATCH /api/v1/diary-entries/:entryId`
- `DELETE /api/v1/diary-entries/:entryId`

### Saved Places / Share Intake
- `POST /api/v1/share-intake`  
  - caller: iOS Share Extension / Android share receiver / web paste form
  - body: raw_url, raw_title, raw_text, source_app, platform, folder_id(optional)
- `POST /api/v1/share-intake/:id/analyze`
- `GET /api/v1/share-intake/:id`
- `POST /api/v1/share-intake/:id/resolve`  
  - choose candidate or manual correction, then create `saved_place`
- `POST /api/v1/share-intake/:id/save-draft`  
  - 후보 0개 임시 저장
- `GET /api/v1/folders/:folderId/saved-places?category=&status=&tag=&region=`
- `GET /api/v1/saved-places/:placeId`
- `PATCH /api/v1/saved-places/:placeId`
- `DELETE /api/v1/saved-places/:placeId`

### Search / Geocoding
- `GET /api/v1/places/search?q=`
- `GET /api/v1/geocode/reverse?lat=&lng=`
- provider adapter: `nominatim`, later `photon`, `pelias`, self-hosted

## Share Ingestion Architecture Recommendation
### 결론
순수 PWA는 iPhone 공유 시트 수신 요구사항과 충돌하므로, **웹 앱 + 얇은 네이티브 모바일 컴패니언**이 MVP 최적안이다.

### 권장 구조
- `apps/web`: Next.js 웹 앱
- `apps/mobile`: Expo React Native 앱
- iOS: Share Extension으로 Instagram/Reels 공유 수신
- Android: Share Intent receiver 사용
- 두 모바일 진입점은 공통 `POST /api/v1/share-intake`로 서버에 raw payload를 보낸다.
- 분석/후보 추출/저장은 웹/모바일 공통 백엔드 로직을 사용한다.

### 왜 이 구조가 맞는가
- iPhone 공유 시트 진입을 만족한다.
- 웹 앱 UX와 모바일 공유 UX를 분리할 수 있다.
- Android에서도 Web Share Target보다 네이티브 share receiver가 더 일관적이다.
- 서버가 intake를 표준화하므로 플랫폼별 raw payload 차이를 흡수할 수 있다.

## Tech Stack Recommendation
### Frontend Web
- Next.js 15 App Router
- TypeScript
- Tailwind CSS + shadcn/ui
- TanStack Query
- MapLibre GL JS + OpenFreeMap

### Mobile
- Expo React Native
- Expo Router
- iOS Share Extension / Android share receiver integration
- Native secure storage for auth token

### Backend
- Next.js Route Handlers 또는 별도 Node service layer (same monorepo)
- PostgreSQL
- Prisma ORM
- Zod for request/response validation
- Background jobs: BullMQ + Redis (share analysis, EXIF extraction retries, geocode throttling)

### Infra
- Supabase Postgres or managed PostgreSQL
- S3-compatible object storage (Cloudflare R2 / Supabase Storage / AWS S3)
- Redis for queue + geocode throttle/cache
- Vercel or Fly.io for web/API
- Sentry for error monitoring

### Libraries
- EXIF: `exifr`
- Image processing: `sharp`
- AI extraction: structured JSON output via provider abstraction
- Rate limit: server middleware + Redis token bucket

## Detailed Feature Logic
### 사진일기 저장
1. 이미지 업로드
2. 서버 EXIF 파싱
3. GPS 있으면 지도 자동 표시
4. GPS 없으면 저장 버튼 비활성 + 위치 선택/검색 유도
5. 위치 확정 후 일기 저장

### 릴스 저장
1. 공유 수신 raw payload 저장
2. URL 정규화
3. AI/규칙 기반 장소 후보 추출
4. 후보 1개: 확인 화면
5. 후보 N개: 선택 화면
6. 후보 0개: draft 저장 + 수동 보정
7. 확정 시 `saved_places` 생성 또는 draft 승격

## Exception Handling
### 인증 / 권한
- 초대 링크 만료 → 재발급 안내
- viewer의 수정 시도 → 403 + 읽기 전용 안내
- owner 자기 자신 role 변경/삭제 → 차단

### 사진일기
- EXIF 파싱 실패 → 메타 없음 상태로 진행, 위치 수동 입력 요구
- 업로드 실패 → 재시도/취소
- 지도 위치 검색 실패 → 수동 핀 배치 허용
- 잘못된 이미지 형식 → MIME/type 검증 후 거절

### 릴스 저장
- 비공개 릴스 / 죽은 링크 → 원본 링크는 저장, 상태 `needs_manual_fix`
- 후보 0개 → draft 저장
- 후보 여러 개 모두 불확실 → 사용자 선택 강제
- AI timeout → 규칙 기반 fallback, 그래도 실패하면 draft
- 동일 링크 중복 저장 → 동일 폴더 내 중복 경고 후 계속 저장 또는 기존 항목 열기

### 지오코딩 / 지도
- provider rate limit → 캐시 우선, 백오프, 사용자에게 잠시 후 재시도 안내
- reverse geocode 실패 → 좌표는 저장, 주소는 nullable
- tile provider 장애 → provider adapter로 다른 타일 소스로 전환 가능하게 유지

## Implementation Order
1. **Foundation**
   - monorepo 구성 (`web`, `mobile`, `shared`)
   - auth, users, folders, memberships, invites 스키마
   - 기본 권한 가드
2. **Risk spike first**
   - iOS Share Extension + Android share receiver에서 raw payload를 백엔드로 보내는 최소 흐름 검증
   - pure PWA share target은 보조 옵션으로만 취급
3. **Photo Diary Core**
   - 이미지 업로드, EXIF 추출, 위치 보정, diary CRUD
   - 지도/리스트/상세 화면
4. **Saved Place Core**
   - share intake inbox
   - 후보 추출, 후보 선택, draft 저장, saved place CRUD
5. **Folder Collaboration UX**
   - 초대 링크 생성/수락
   - 최근 활동, 멤버 목록, 역할 표시
6. **Search / Filtering / Polishing**
   - 태그/카테고리/상태/날짜 필터
   - 에러 메시지, 빈 상태, 중복 안내
7. **Ops / QA**
   - 로그/모니터링, rate limit, 캐시, E2E 시나리오

## Verification Plan
- 권한 테스트: owner/editor/viewer별 읽기/쓰기 행위
- 폴더 타입 테스트: `photo_diary` 폴더에 saved place 생성 불가, 반대도 동일
- EXIF 테스트: GPS 있음/없음/깨짐
- 릴스 저장 테스트: 후보 1개 / 여러 개 / 0개 / 링크 실패 / AI timeout
- 초대 테스트: 링크 만료 / 비회원 가입 후 수락 / 이미 멤버인 경우
- 모바일 테스트: iPhone share extension → intake 성공, Android share receiver → intake 성공

## Technical Context
- 현재 저장소에는 제품 소스가 아직 없고, `.gjc` 인터뷰 상태만 존재한다. 따라서 이번 산출물은 greenfield 기준 설계다.
- iPhone 공유 시트 수신을 필수로 요구했기 때문에, 원래의 순수 PWA 가정은 유지할 수 없다.
- 지도는 MapLibre GL JS + OpenFreeMap 우선, 지오코딩은 서버 사이드 adapter 패턴으로 시작하는 것이 맞다.

## Ontology (Key Entities)
| Entity | Type | Fields | Relationships |
|--------|------|--------|---------------|
| User | core domain | account, membership | User creates many Folders; User joins many FolderMemberships |
| Folder | core domain | name, type, description | Folder has many FolderMemberships; Folder contains PhotoDiaryEntries or ReelsSavedPlaces depending on type |
| FolderMembership | supporting | role, invite status | FolderMembership links User and Folder |
| PhotoDiaryEntry | core domain | photo, captured_at, location, title, body | PhotoDiaryEntry belongs to a photo-diary Folder |
| ReelsSavedPlace | core domain | reels_url, shared_text, place candidate, visit status | ReelsSavedPlace belongs to a reels Folder |
| FolderType | supporting | photo_diary, reels_place | FolderType constrains what content a Folder can contain |

## Ontology Convergence
| Round | Entity Count | New | Changed | Stable | Stability Ratio |
|-------|-------------|-----|---------|--------|----------------|
| 1 | 6 | 6 | - | - | - |
| 2 | 6 | 0 | 0 | 6 | 100% |
| 3 | 6 | 0 | 0 | 6 | 100% |
| Final | 6 | 0 | 0 | 6 | 100% |

## Interview Transcript
<details>
<summary>Full Q&A (7 rounds + topology + restate)</summary>

### Round 0
**Q:** 최상위 컴포넌트가 무엇인지 확인했다.
**A:** 협업 폴더가 상위이고, 사진일기는 그 하위다. 사진일기와 릴스 저장은 다른 폴더로 분리한다.
**Ambiguity:** not scored yet

### Round 1
**Q:** 폴더를 어떤 방식으로 나눌지 물었다.
**A:** 폴더 생성 시 타입을 고정한다.
**Ambiguity:** 46.4%

### Round 2
**Q:** 릴스 저장 MVP 최소 성공 기준을 물었다.
**A:** 후보 1개면 확인 후 저장, 여러 후보면 선택 후 저장.
**Ambiguity:** 32.1%

### Round 3
**Q:** 사진일기 저장 MVP 최소 성공 기준을 물었다.
**A:** GPS가 있으면 자동 위치 표시, 없으면 사용자가 지도 선택/장소 검색 후 저장.
**Ambiguity:** 23.7%

### Round 4
**Q:** 초대 기능 MVP 최소 성공 기준을 물었다.
**A:** 초대 링크만 지원하고, 링크 진입 후 가입/수락하면 역할 반영.
**Ambiguity:** 18.8%

### Round 5
**Q:** 릴스 후보가 0개일 때 처리 방식을 물었다.
**A:** 원본 링크와 텍스트를 임시 저장하고 나중에 보정.
**Ambiguity:** 10.9%

### Round 6
**Q:** 공유 폴더 안의 사진일기 공개 범위를 물었다.
**A:** 폴더 멤버라면 모두 볼 수 있고 개인 비공개 범위는 두지 않는다.
**Ambiguity:** 4.8%

### Round 7
**Q:** iPhone에서 Web Share Target 미지원이라는 사실을 제시하고 MVP 범위를 다시 물었다.
**A:** iPhone에서도 반드시 작동해야 하며, 방법은 고정하지 말고 가장 좋은 방식을 추천해 달라.
**Ambiguity:** 4.2%

### Restate Gate
**Q:** 목표를 한 줄로 재진술했다.
**A:** 확정.

</details>
