## Intent Reconciliation

### Reconciled item 1 — 모바일 사진일기 작성 범위
- **Open assumption from consensus loop:** 모바일 사진일기 작성은 basic/minimal 수준이어도 MVP에 충분할 수 있다.
- **Why it mattered:** 이 가정은 dual-primary를 얼마나 강하게 해석할지, 그리고 photo diary capability matrix를 어떻게 잠글지 결정한다.
- **User reconciliation answer:** 모바일에서도 웹과 거의 비슷한 수준의 사진일기 작성 경험이 필요하다.
- **Plan change applied:** `Photo Diary Authoring`을 `BOTH-MUST (near parity)`로 승격했고, mobile에서도 단일 사진 업로드, EXIF 확인, 위치 보정, 제목/본문/태그, 수정 흐름을 제공하도록 수정했다. 대신 admin richness와 full visual parity는 계속 defer 했다.
- **Outcome:** reconciled-with-revision

### Prior-context conflict check
- deep-interview spec의 초기 권고는 `웹 중심 + 네이티브 모바일 공유 수신 레이어`였다.
- current plan은 이를 확장해 `dual-primary + mobile diary near parity`로 이동했다.
- 이 차이는 user-confirmed reconciliation으로 해소되었고, 의도 충돌이 아니라 의도 확장으로 기록한다.

### Reconciliation status
reconciled-clean
