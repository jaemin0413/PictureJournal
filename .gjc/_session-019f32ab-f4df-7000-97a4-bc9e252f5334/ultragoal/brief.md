Picture Journal MVP execution brief derived from the approved ralplan artifact at `.gjc/_session-019f3160-45c4-7000-b101-3c4de80dc410/plans/ralplan/019f3160-45c4-7000-b101-3c4de80dc410/pending-approval.md`.

Execution constraints:
- Backend uses Spring Boot + DDD and owns contracts.
- `photo_diary` and `reels_place` stay separated.
- All folder-scoped writes must pass `FolderCapabilityPolicy`.
- Mobile photo diary authoring is near-parity core flow, not a minimal shell.
- `ResolveShareIntake` must remain atomic and synchronous at the application layer.
- Native mobile share entry is mandatory.
- Tonight's local runtime may use Docker Postgres and dev-only `.env` values.
- Home-server deployment `.env` and real MinIO integration can be finalized tomorrow, but the design and test surfaces must anticipate them.

@goal: Lock the foundation and local runtime contract
Create the initial project skeleton and first-sprint foundation needed to execute safely: Spring Boot DDD structure, shared error taxonomy/enums, `FolderCapabilityPolicy` ownership boundary, OpenAPI/contracts pipeline shape, and a local development runtime contract that works with Docker Postgres and placeholder dev secrets. Produce `.env.example` plus the minimal local `.env` contract assumptions without baking deployment secrets into the codebase.

@goal: Prove the native share spike path
Define and implement the earliest viable iOS/Android native share intake spike path with explicit go/no-go criteria for warm start, cold start, unauthenticated recovery, folder continuity, and payload durability. Preserve the ability to revise the mobile integration strategy if the spike fails.

@goal: Build the collaboration core
Implement auth, folders, memberships, invites, and capability checks so folder-scoped collaboration works consistently across clients and the domain rules are enforced server-side.

@goal: Deliver near-parity photo diary core on web and mobile
Implement the core photo diary flow for both web and mobile with near-parity behavior: single photo upload path, EXIF handling, location correction, title/body/tag editing, and CRUD/list/detail/map experiences aligned to the approved MVP scope.

@goal: Deliver the saved place and share-intake core
Implement share intake creation and resolution, candidate extraction, confirm/select/manual fix flows, atomic `ResolveShareIntake`, and saved place CRUD while keeping `reels_place` separate from `photo_diary`.

@goal: Finish platform hardening and deployment readiness
Add web admin richness, mobile share polish, cross-client consistency hardening, geocode throttle/cache/backoff, observability, contract CI gates, unresolved draft monitoring, MinIO-ready storage integration seams, and the home-server deployment readiness checklist including the real `.env` values that the user must supply tomorrow.
