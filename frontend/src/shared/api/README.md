# shared/api

## Purpose
Backend API client — the single place that knows how to talk to the Spring Boot REST
API described in `docs/architecture/overview.md` §3.2.

## Responsibilities
- HTTP client setup (base URL, auth token attachment, error normalization) — `httpClient.ts`.
- Typed request/response functions consumed by feature modules, so features don't call
  `fetch`/HTTP libraries directly.

## Structure
- `httpClient.ts` — thin `fetch` wrapper. Base URL defaults to `http://localhost:8080`
  (backend's `server.port` default), overridable via `VITE_API_BASE_URL`. Attaches
  `Authorization: Bearer <token>` via an injectable token-getter (`setAuthTokenGetter`,
  called by `shared/hooks`' `AuthProvider` at startup — keeps this module framework-
  agnostic). Parses the standard error envelope
  (`{ timestamp, path, error: { code, message, details } }`, verified against
  `backend/.../common/exception/GlobalExceptionHandler.java` and `common/dto/*`) into a
  typed `ApiError` (`code`, `message`, `details`, `status`).
- `auth.ts` — `registerCustomer`, `registerProfessional`, `verifyEmail`, `login`.
  `POST /api/auth/register` is `multipart/form-data` (Backend Milestone 7's registration
  flow separation): both register functions build a `FormData` with a `data` part (a
  `Blob`, `application/json`, matching `RegisterRequest.java`'s nested
  `customer`/`professional` shape) plus, for professionals, `verificationDocument`
  (required) and `profilePhoto` (optional) file parts. See
  `docs/architecture/api-contract.md` §2.1.
- `users.ts` — `getMe` (`GET /api/users/me`). **As of the MS3/MS4 product-corrections pass**:
  `UserMeResponse` gained a nested `defaultAddress` (`UserMeDefaultAddress | null`) field —
  `null` for a `PROFESSIONAL` caller or a pre-`V20` `CUSTOMER` with no recorded default
  address, mirroring `professional`'s existing "absent means no such object" convention.
  **As of Frontend Milestone 9 (2026-08-18)**: gained `deleteMe()` (`DELETE /api/users/me`,
  either role, soft-deletes the caller's account server-side) — first and only consumer:
  `app/ProfilePage.tsx`'s account-deletion confirmation. Fully QA-verified live, no bugs
  found (see `app/README.md`'s Frontend Milestone 9 section for what was checked).
  **As of the MS10 profile redesign (2026-08-19,
  `docs/architecture/product-ms10-profile-redesign-design.md` §4.5)**: gained `updateMe`
  (`PUT /api/users/me`, `CUSTOMER`-only) plus its `UpdateUserMeRequest` type
  (`fullName`/`phone`/`defaultAddress`, the last always required in full — no partial-address
  update); first and only consumer: `app/ProfilePage.tsx`'s new customer edit form. Also two
  response-shape additions: `ProfessionalInfo` gained `profileImageUrl: string | null` (§6 of
  the design doc, so a professional's own `/pro/profile` photo can be shown read-only on the
  shared `/profile` page), and `UserMeResponse.phone` — already present on the real backend
  response since the professional weekly availability calendar design's M2, but missing from
  this frontend type until now (a pre-existing frontend-only gap fixed incidentally in this
  pass, not new backend scope — `api-contract.md` §2.4 already documented `phone`).
- `categories.ts` — static mirror of the fixed 8-category list seeded by
  `V10__seed_categories.sql` (no public categories endpoint exists yet).
- `errorMessages.ts` — `GENERIC_ERROR_MESSAGE` fallback copy, and
  `getFieldErrorMessages` which maps a `400 VALIDATION_ERROR`'s `details` array to
  `{ field: hebrewMessage }` so forms can attribute errors per-field.
- `storage.ts` — `uploadImage(file)` (`POST /api/storage/images`, Milestone 2). **As of
  backend MS9 (presigned image URLs, 2026-08-18)**: gained `getPresignedImageUrls(imageKeys)`
  (`POST /api/storage/images/presigned-urls`), a batch key-to-presigned-URL lookup used
  exclusively by `features/issues/NewIssuePage.tsx`'s draft-resume flow — a paused booking
  draft only ever persists a photo's `imageKey` (never a URL, see `shared/hooks/
  bookingDraftContext.ts`'s `BookingDraftPhoto`, also changed this round), since backend MS9
  made every image URL this app issues time-limited (300s TTL) rather than permanent. Returns
  `{ images: { imageKey, imageUrl }[] }`, possibly fewer entries than requested (a
  missing/stale key is simply dropped, not an error — see
  `docs/architecture/backend-ms9-presigned-image-urls-design.md` §12.2/§12.5). Shapes verified
  directly against the real backend DTOs (`storage.dto.PresignedImageUrls{Request,Response}`,
  `PresignedImageUrlEntry`).
- `issues.ts` — `classifyIssue`, `createIssue` (Milestone 2), plus a Frontend Milestone 3
  addition: `getIssue` (`GET /api/issues/{id}`, either CUSTOMER-owner or PROFESSIONAL-
  with-an-order), returning `IssueDetailResponse` including a `latestOrder` summary.
- `bookings.ts` — **new, Frontend Milestone 3; extended, Frontend Milestone 4 (SOS), the
  MS3/MS4 product-corrections pass, and the professional weekly availability calendar
  feature M6.** Standard + SOS booking-flow domain: professional listing
  (`getProfessionalsForIssue`/`getSosProfessionalsForIssue`, `GET
  /api/bookings/professionals`/`.../sos-professionals`, `city`/`street`/`houseNumber`
  required query params + optional `sort: ProfessionalSort` — `'CHEAPEST' | 'RECOMMENDED' |
  'FASTEST'`), available-start-time-window listing (`getAvailableWindows`, see below), order
  lifecycle (`createOrder`/`createSosOrder`, `acceptOrder`, `rejectOrder`, `cancelOrder`,
  `getOrder`, `getMyOrders`).
  **As of the MS3/MS4 product-corrections pass**: `CreateOrderRequest`/
  `CreateSosOrderRequest`/`OrderResponse`/`OrderDetailResponse` all gained 3 optional
  fields — `serviceFloor`/`serviceEntrance`/`serviceAddressNotes` — bringing the
  service-address shape to the full 7 fields (`V22`); `ProfessionalSort` gained its third
  value, `RECOMMENDED` (only `RECOMMENDED`/`CHEAPEST` are reachable via either flow's UI
  chips, see `features/professionals/README.md`). **Important nuance**: per this file's own
  header comment, its shapes were verified directly against the real backend DTO source,
  not copied from `docs/architecture/api-contract-bookings.md`'s prose — that doc's
  §2.2/§2.4/§2.8/§2.12/§2.13 predate Milestone 8 (Professional Profiles, Reviews, Favorites
  & Matching), which changed several of these DTOs in place (the enriched
  `ProfessionalCard`, the required service-address query params/body fields), and were
  extended further still by the MS3/MS4 corrections pass, without either update being
  reflected in that doc's own JSON examples (a prominent note was added there instead of a
  full rewrite — see that doc's header). A future reader should not trust that doc's
  §2.2/§2.4/§2.8/§2.12/§2.13 text at face value — read the real backend DTOs, or this file's
  own per-type comments, instead. **As of the professional weekly availability calendar
  feature M6 (2026-08-18)**: `getProfessionalSlots`/`AvailabilitySlotItem`/
  `ProfessionalSlotsResponse` (the client for the retired `GET
  .../professionals/{id}/slots?issueId=`, itself removed backend-side in M2) are **removed
  entirely, not deprecated in place** — replaced by `getAvailableWindows`/`AvailableWindow`/
  `AvailableWindowsResponse`, calling the new `GET
  .../professionals/{id}/available-windows?issueId=` (`{ professionalId, issueId,
  defaultDurationMinutes, timezone, windows: [{ startAt, endAt }] }`). `CreateOrderRequest`
  lost its `slotId: number` field and gained `bookedStart: string` (required) — `bookedEnd` is
  deliberately not a request field at all, always computed server-side. Shapes verified
  directly against the real backend records (`bookings.dto.AvailableWindow`/
  `AvailableWindowsResponse`/`CreateOrderRequest`). First and only consumer of
  `getAvailableWindows`: `features/booking/BookingFlowPage.tsx` (via the renamed
  `StartTimePicker.tsx`, formerly `SlotPicker.tsx`) — see that package's README for the full
  M6 record and live-verification detail. `createOrder`/`getOrder`/`getMyOrders`/the SOS
  functions are all unchanged.
- `availability.ts` — **new, Frontend Milestone 3.** A professional's own Standard-booking
  calendar: `createAvailabilitySlot` (`POST /api/availability/slots`),
  `getMyAvailabilitySlots` (`GET /api/availability/slots/me`). Type names match the real
  backend DTO names directly (`SlotResponse`/`SlotListItem`/`SlotListResponse`/
  `CreateSlotRequest`). **As of Frontend Milestone 9 (2026-08-18)**: gained
  `updateAvailabilitySlot(slotId, payload)` (`PUT /api/availability/slots/{slotId}`) and
  `deleteAvailabilitySlot(slotId)` (`DELETE /api/availability/slots/{slotId}`), both
  PROFESSIONAL-only/owner-only — backend was already complete; this is the first frontend
  wiring. Consumers: `features/dashboard/SlotForm.tsx` (edit mode) and
  `features/dashboard/SlotList.tsx` (delete). Fully QA-verified live, including the
  `SLOT_IN_USE` (409) race-condition path — see `features/dashboard/README.md`'s Frontend
  Milestone 9 section for the two follow-up bug fixes QA's live testing surfaced and closed
  on that path (both in `SlotForm.tsx`/`SlotList.tsx`, not in this file — this file's two
  functions themselves needed no changes after their initial addition). **As of the
  professional weekly availability calendar feature, M3/M4 (2026-08-18)**: gained
  `getWorkingHours`/`updateWorkingHours` (`GET`/`PUT /api/availability/working-hours`) and
  `getAvailabilityCalendar(from, to)` (`GET /api/availability/calendar?from=&to=`), plus their
  `WorkingHoursItem`/`WorkingHoursItemRequest`/`WorkingHoursListResponse`/`SegmentType`/
  `CalendarSegment`/`CalendarResponse` types. Shapes verified directly against the real
  backend DTOs (`availability.dto.WorkingHoursItem`/`WorkingHoursItemRequest`/
  `WorkingHoursListResponse`/`WorkingHoursUpdateRequest`/`CalendarResponse`/`CalendarSegment`/
  `SegmentType`) and live-verified against a running backend + fresh Postgres instance (all
  28 migrations, `V25`-`V28` included) — see `features/dashboard/README.md`'s corresponding
  section for the full verification record (including a reproduction of the design doc's §36
  worked example). Consumers: `features/dashboard/WorkingHoursForm.tsx` and
  `features/dashboard/WeeklyCalendarGrid.tsx`. **As of M5 (2026-08-18)**: gained the block
  CRUD trio — `createAvailabilityBlock` (`POST /api/availability/blocks`),
  `updateAvailabilityBlock(blockId, payload)` (`PATCH /api/availability/blocks/{id}`,
  first `PATCH` call in this codebase — `httpClient.ts` gained a `patch()` method alongside
  `get`/`post`/`put`/`delete` to support it), `deleteAvailabilityBlock(blockId)` (`DELETE
  /api/availability/blocks/{id}`), plus `CreateBlockRequest`/`BlockResponse` types verified
  directly against the real backend DTOs. Consumer: `features/dashboard/CalendarBlockModal.tsx`
  (new, M5). Live-verified against a running backend, including both 409 overlap codes
  (`BLOCK_OVERLAPS_EXISTING_BLOCK`/`BLOCK_OVERLAPS_BOOKING`) — see
  `features/dashboard/README.md`'s M5 section for the full record.
- `reviews.ts` — **new, Active Booking Floating Indicator feature (2026-08-17); extended,
  Frontend Milestone 8 (2026-08-18).** `CreateReviewRequest`/`ReviewResponse` types +
  `createReview(payload)` wrapping `POST /api/reviews`. **First frontend consumer of this
  endpoint** — the backend endpoint itself was already implemented and QA-signed-off with no
  UI caller (backend Milestone 8); this file is what `features/booking/CompletionReviewPage.tsx`
  calls. Shapes verified directly against `reviews.dto.CreateReviewRequest`/
  `reviews.dto.ReviewResponse`, same "read the real backend DTOs" convention `bookings.ts`'s
  own header comment already established. **Frontend Milestone 8** added `ReviewListResponse`
  (`{ professionalId, averageRating, reviewCount, reviews }`) + `getReviews(professionalId)`
  wrapping `GET /api/reviews?professionalId=` (either role, no route gate, no pagination) —
  first and only consumer: `features/professionals/ReviewList.tsx`, reached from
  `ProfessionalProfilePage.tsx`.
- `favorites.ts` — **new, Frontend Milestone 8 (2026-08-18).** `favorites` domain
  (`backend/src/main/java/com/pronto/favorites/`), all three endpoints CUSTOMER-only:
  `FavoriteProfessionalSummary`/`FavoritesListResponse` types, `addFavorite(professionalId)`
  (`POST /api/favorites`, idempotent — `204` even if already favorited),
  `removeFavorite(professionalId)` (`DELETE /api/favorites/{id}`, idempotent), `getFavorites()`
  (`GET /api/favorites`, `created_at DESC`, no pagination). Shapes verified directly against
  `favorites.dto.FavoritesListResponse`/`FavoriteProfessionalSummary`. Consumers:
  `features/favorites/` (list + remove) and `features/professionals/ProfessionalProfilePage.tsx`
  (the actual favorite/unfavorite toggle — `addFavorite` is only ever called from there).
- `professionals.ts` — **new, Frontend Milestone 8 (2026-08-18)** — the `professionals`
  package's first frontend client, mirroring `bookings.ts`'s pattern of a dedicated file per
  backend package. `ProfessionalProfileResponse` (`favorited: boolean | null`, populated
  only by `getProfessionalProfile()` for a `CUSTOMER` caller, `null` everywhere else),
  `UpdateProfessionalProfileRequest` (an allowlist DTO — `fullName`/`serviceArea`/`city`/
  `bio?`/`basePrice`, deliberately excludes `id`/`categoryId`/`approvalStatus`/rating
  fields/`profileImageKey`), `ProfileImageUploadResponse` types. Functions:
  `getMyProfessionalProfile()`/`updateMyProfessionalProfile(payload)` (`GET`/`PUT
  /api/professionals/me`, PROFESSIONAL-only), `uploadProfessionalProfileImage(file)` (`POST
  /api/professionals/me/profile-image`, multipart field `file`, PROFESSIONAL-only),
  `getProfessionalProfile(professionalId)` (`GET /api/professionals/{id}`, either role, no
  route gate). Shapes verified directly against `professionals.dto.ProfessionalProfileResponse`/
  `UpdateProfessionalProfileRequest`/`ProfileImageUploadResponse`. Consumers:
  `features/dashboard/ProfileEditorPage.tsx` (the `/me` functions) and
  `features/professionals/ProfessionalProfilePage.tsx` (`getProfessionalProfile`). **As of
  the MS10 profile redesign (2026-08-19)**: `uploadProfessionalProfileImage`'s call site
  moved from the now-deleted `ProfessionalProfileImageField.tsx` wrapper directly into
  `ProfileEditorPage.tsx` itself (see `features/dashboard/README.md`'s MS10 section) — no
  change to this file's own exports. **As of MS11 — Services & Sub-services (2026-08-19,
  `docs/architecture/product-ms11-sub-services-design.md`)**: gained
  `getCategoriesWithSubServices()` (`GET /api/categories`, public/unauthenticated but called
  authenticated here like every other call on this page, returns
  `CategoryWithSubServicesResponse[]` — each category with a nested, `display_order`-sorted
  `subServices` list), `getMySubServices()` (`GET /api/professionals/me/sub-services`,
  PROFESSIONAL-only, `MySubServicesResponse` — ids only, not full objects), and
  `updateMySubServices(subServiceIds)` (`PUT /api/professionals/me/sub-services`,
  PROFESSIONAL-only, full-replace, empty array allowed, same `MySubServicesResponse` return
  shape). Shapes verified directly against `professionals.dto
  .CategoryWithSubServicesResponse`/`SubServiceResponse`/`MySubServicesResponse`/
  `UpdateSubServicesRequest`. **Note**: `categories.ts`'s pre-existing static `CATEGORIES`
  mirror is deliberately left untouched by this addition, not migrated to the new endpoint —
  see that file's own header comment and the design doc §3.3/§6 item 5 for the full
  proportionality reasoning. First and only consumer of all three new functions:
  `features/dashboard/ProfileEditorPage.tsx`'s new sub-services checklist section.

**As of the Active Booking Floating Indicator feature**: `bookings.ts` also gained
`expectedArrivalAt: string | null` on `OrderResponse`/`OrderDetailResponse`/`OrderSummary`
(non-`null` once an order reaches `ON_THE_WAY`), and `updatedAt: string` on `OrderSummary`
(not previously present on that lean list-mine shape — needed by
`shared/hooks/activeOrderContext.ts`'s completed-order tie-break logic). No new functions —
`getMyOrders`/`getOrder` are unchanged, only their response shapes grew.

**As of the professional weekly availability calendar feature, M5 (2026-08-18)**:
`OrderDetailResponse` gained `customerPhone: string | null` (design §9.1), mirroring the real
backend DTO — visible to the order's own customer and to the assigned professional from
`PENDING` onward, same party-to-order authorization as everything else on this DTO, no new
client-side gating. First and only consumer: `features/booking/OrderTrackingPage.tsx` (renders
it for a `PROFESSIONAL` viewer only — see that package's README). `getOrder` itself is
unchanged, only the response shape grew, same pattern as the ETA-field addition above.
- `notifications.ts` — **new, Frontend Milestone 5.** In-app notification bell domain:
  `NotificationMessageType` (string union mirroring the backend's 8-value
  `notifications.entity.NotificationMessageType` enum), `NotificationResponse`,
  `NotificationsListResponse`, `MarkAllReadResponse` types, plus `getNotifications
  (unreadOnly?)` (`GET /api/notifications`), `markNotificationRead(id)` (`POST
  /api/notifications/{id}/read`), `markAllNotificationsRead()` (`POST
  /api/notifications/read-all`). Shapes verified directly against the real backend DTOs
  (`notifications.dto.{NotificationResponse,NotificationsListResponse,ReadAllResponse}`),
  same convention as `bookings.ts`/`reviews.ts`. First and only frontend consumer:
  `shared/hooks/useNotifications.ts`.

## Status
Implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`), covering the `auth`/`users` endpoints. Grows
with each subsequent milestone as new backend endpoints ship — **Frontend Milestone 3
(Standard booking flow, 2026-08-16)** added `bookings.ts`, `availability.ts`, and
`issues.ts`'s `getIssue`. **MS3/MS4 product-corrections pass (2026-08-17)**: `users.ts`
gained `defaultAddress`; `bookings.ts` gained the 3 new service-address fields and the
`RECOMMENDED` sort value (see above). **Active Booking Floating Indicator feature
(2026-08-17)**: `reviews.ts` is new (first frontend consumer of `POST /api/reviews`);
`bookings.ts` gained `expectedArrivalAt`/`OrderSummary.updatedAt` (see above). QA-passed
(12/12 checklist items, zero bugs); full design record:
`docs/architecture/active-booking-floating-indicator.md`. **Frontend Milestone 5 —
Notifications (2026-08-18)**: `notifications.ts` is new, consuming the already-complete
backend `notifications` package (read-only, no backend changes). QA-signed-off, PASS, no bugs
found; full detail in `docs/architecture/implementation-plan.md`'s "Frontend Milestone 5"
entry. **Frontend Milestone 8 — Professional Profiles, Reviews & Favorites (2026-08-18)**:
`favorites.ts` and `professionals.ts` are new; `reviews.ts` gained `getReviews`/
`ReviewListResponse` (see above) — all three consuming already-complete backend endpoints,
no backend changes. QA-passed (live API round-trip + code review); full detail in
`docs/architecture/implementation-plan.md`'s "Frontend Milestone 8" entry and
`docs/architecture/frontend-ms8-design.md`. **Frontend Milestone 9 — gap-fixes
(2026-08-18)**: `availability.ts` gained `updateAvailabilitySlot`/`deleteAvailabilitySlot`;
`users.ts` gained `deleteMe` (see above) — both consuming already-complete backend
endpoints, no backend changes. Both additions are fully QA-verified live, no bugs left open
in this file (the `SLOT_IN_USE` follow-up fixes needed were in the calling components, not
here — see `features/dashboard/README.md`). Note this milestone's third item (issue-photo
thumbnails in `IncomingRequestCard`) made **no** `shared/api` change at all — it consumes
`issue.images`, already returned by the existing `getIssue` call — and that item is
currently non-functional in-browser for reasons unrelated to this package (a pre-existing
image-auth gap; see `features/dashboard/README.md`'s Frontend Milestone 9 section). That
gap is fixed by backend MS9 (below) — **backend MS9 — presigned image URLs (2026-08-18)**:
`storage.ts` gained `getPresignedImageUrls` (see above); no other file in this package
changed, since every other consumer of `imageUrl` fields returned by existing endpoints
(`getIssue`, `getMyProfessionalProfile`, `getFavorites`, etc.) needed no frontend change —
those endpoints' response shapes are unchanged, only how the backend computes the `imageUrl`
string inside them changed (now a presigned URL, not a permanent backend-proxy URL). Full
design record: `docs/architecture/frontend-ms9-gap-fixes-design.md`. **Professional weekly
availability calendar, M3/M4 (2026-08-18)**: `availability.ts` gained
`getWorkingHours`/`updateWorkingHours`/`getAvailabilityCalendar` (see above) — consuming
already-complete backend endpoints (M1/M2), no backend changes in this pass. Live-verified
against a running backend (full API-contract-conformance pass via `curl`, including a
reproduction of the design doc's §36 worked example) — see
`features/dashboard/README.md`'s corresponding section for the full record. No browser was
available in this environment; interactive rendering was validated via code review plus a
clean `tsc -b && vite build`/`oxlint` pass, not a live browser session. **Professional weekly
availability calendar, M5 (2026-08-18)**: `availability.ts` gained the block CRUD trio and
`httpClient.ts` gained `patch()` (see above); `bookings.ts` gained `OrderDetailResponse.
customerPhone` (see above) — consuming already-complete backend endpoints (M1/M2), no backend
changes in this pass either. Live-verified against a running backend, including both new 409
overlap codes and a live `PENDING`-stage `customerPhone` read — see
`features/dashboard/README.md`'s M5 section for the full record. Same "no browser available"
caveat as M3/M4 above. **Professional weekly availability calendar, M6 (2026-08-18, final
implementation milestone) — frontend-only, no backend change** (M2 already shipped the
backend side this milestone consumes): `bookings.ts` lost `getProfessionalSlots`/
`AvailabilitySlotItem`/`ProfessionalSlotsResponse` (removed, not deprecated) and gained
`getAvailableWindows`/`AvailableWindow`/`AvailableWindowsResponse`;
`CreateOrderRequest.slotId` was replaced by `CreateOrderRequest.bookedStart` (see above).
`shared/utils/availability.ts` is new — `deriveStartTimeCandidates`, a pure utility with no
`shared/api` dependency of its own beyond importing `AvailableWindow`'s type, consumed by
`features/booking/StartTimePicker.tsx` (renamed from `SlotPicker.tsx`). Live-verified against
a running backend (working hours + one manual block + one existing booking → confirmed
`available-windows` correctly excludes both and enforces the 60-minute minimum; a real order
submitted against a derived candidate succeeded with the correct server-derived `bookedEnd`;
a deliberately-conflicting submission returned `409 BOOKING_TIME_UNAVAILABLE`; the SOS path
and the old retired route were regression-checked) — see `features/booking/README.md`'s M6
section for the full record, including the `deriveStartTimeCandidates` cross-check
methodology (no frontend unit-test runner exists in this codebase). Same "no browser
available" caveat as every prior milestone above.

**MS11 — Services & Sub-services (2026-08-19)**: `professionals.ts` gained
`getCategoriesWithSubServices`/`getMySubServices`/`updateMySubServices` (see above) —
consuming newly-built backend endpoints (`GET /api/categories`,
`GET`/`PUT /api/professionals/me/sub-services`), first frontend wiring for all three. Shapes
verified directly against the real backend DTOs. `categories.ts` itself is unchanged, by
deliberate design-doc decision (see above). Full design record:
`docs/architecture/product-ms11-sub-services-design.md`.
