# shared/api

> **SOS (2026-08-21).** `getSosProfessionalsForIssue`, `createSosOrder` and the
> `CreateSosOrderRequest` type were removed with the legacy browse-and-pick SOS endpoints.
> `prefetchProfessionalListing` lost its `urgencyType` parameter for the same reason — there
> is only one professional-listing endpoint now. The client for `/api/sos/**` is **`sos.ts`**,
> added with the Pronto SOS customer frontend (see below) — it covers the customer half only.

> **`sos.ts` (2026-08-21).** Pronto SOS, the only SOS flow. `createSosRequest`,
> `getMySosRequests`, `getSosRequest`, `getSosCandidates`, `getSosTimeline`,
> `selectSosProfessional`, `cancelSosRequest`, plus the `isSosTerminalStatus`/`hasSosSelection`/
> `isSosSearching` status predicates that mirror the backend enum's own helpers. Every shape was
> verified against `com.pronto.sos.dto.*`/`entity.*`/`realtime.*` directly. Two things worth
> knowing before using it: the one deadline (`matchingExpiresAt`, when scanning stops) is **absolute
> instants**, so a countdown rendered from them survives a remount or a backgrounded tab and the
> server enforces them regardless; and `SosCandidate` means "this professional is available",
> never "this professional got the job" — selection is a separate, one-shot call. The file also
> declares the `/user/queue/sos` realtime wire types (`SosRealtimeEventType`,
> `SosRealtimeMessage`), whose `data` is deliberately typed loosely because it is not a source of
> truth.
>
> **Professional half added MS2 (2026-08-21)**: `getMySosOffers`, `getSosOffer`, `acceptSosOffer`,
> `rejectSosOffer` (no ETA-revision client — MS3 locked the ETA at acceptance), plus the four selected-professional transitions
> (`confirmSosRequest`, `markSosOnTheWay`, `markSosArrived`, `completeSosRequest`), with
> `SosOfferStatus`/`SosOfferResponse`/`SosOffersListResponse` and the
> `isSosOfferOpen`/`isSosOfferResolved` predicates. Shared enums are reused, not redeclared. Two
> traps this module documents in place: **`getSosOffer` is a mutation** (opening an offer marks it
> `VIEWED` server-side, so it must not be called speculatively or in a loop), and an offer exposes
> `serviceCity` and nothing else about the location — street/house/floor/coordinates are withheld
> until selection and then served only through `getSosRequest`. `SOS_ETA_MIN_MINUTES`/
> `SOS_ETA_MAX_MINUTES` mirror the backend's `@Min(0) @Max(480)` so an out-of-range ETA is refused
> before the round trip.
>
> `API_BASE_URL` is now exported from `httpClient.ts` so `shared/realtime` can derive the
> WebSocket origin from the same configured value rather than a second env var.


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
  `docs/architecture/api-contract.md` §2.1. **As of Production Roadmap MS1 (2026-08-22,
  decisions D4/D7)**: `RegisterProfessionalPayload` and the `data` part's `professional` object
  gained two required fields, matching `auth.dto.ProfessionalRegistrationData` exactly —
  `subServiceIds: number[]` (≥1, every id belonging to `categoryId`'s own category; a
  cross-category id is refused with `400 CATEGORY_MISMATCH`) and `workingHours:
  WorkingHoursItemRequest[]` (the same record `PUT /api/availability/working-hours` takes:
  exactly 7 entries, weekday 0-6, ≥1 enabled day, `endTime > startTime` on an enabled day).
  This is a **breaking request-contract change** — registration returns `400` without them —
  and `features/auth/ProfessionalRegisterForm.tsx` is the only caller. A successful response now
  means `approval_status = PENDING`, i.e. an application submitted for review, not a live
  marketplace listing.
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
  `{ field: hebrewMessage }` so forms can attribute errors per-field. MS1 added copy for
  registration's two new leaf fields (`subServiceIds`, `workingHours`); the backend's per-row
  week errors (`weekday`/`startTime`/`endTime`) are deliberately **not** mapped here — those
  leaf names are shared with the availability-slot forms, so `ProfessionalRegisterForm`
  attributes them to its working-hours stage itself.
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
  proportionality reasoning; note its header comment's "there is no public
  `GET /api/categories`-style endpoint" claim has been stale since MS11 built exactly that, so
  **new code must use `getCategoriesWithSubServices()`**, and the static mirror must not be
  extended. Consumers of the three functions: `features/dashboard/ProfileEditorPage.tsx`'s
  sub-services checklist and, since Production Roadmap MS1,
  `features/auth/ProfessionalRegisterForm.tsx` (which builds both its category `Select` and its
  required sub-services checklist from the single `getCategoriesWithSubServices()` fetch).

  **As of Production Roadmap MS1 (2026-08-22, design §D-G)**, `ProfessionalProfileResponse`
  carries two field-level changes: `approvalStatus` is now `string | null` — disclosed on the
  **self-view only**, `null` for every other caller, so a browsing customer can't learn that a
  named professional was rejected — and a new `bookable: boolean` everyone gets, the neutral
  "is this professional marketplace-eligible" flag (approved **and** onboarding complete,
  computed per request by `professionals.ProfessionalEligibility`). `availability.ts`'s
  `SosAvailabilityResponse` gained the same `bookable` flag, independent of `isAvailable` (which
  is only the professional's own intent). `favorites.ts`'s `FavoriteProfessionalSummary` also
  carries `bookable` — **the type was added in the MS1 closure pass**, closing a real drift
  (the backend record `favorites.dto.FavoriteProfessionalSummary` has had the field since the
  implementation pass, while this file's own header comment claimed the shapes were verified
  against that DTO). Adding the field is a **type-only** change: no component reads it.
  `FavoriteProfessionalCard.tsx` deliberately still destructures every field except `bookable`,
  and rendering a customer-side "can't book right now" affordance is outside MS1's frontend
  scope — see `docs/production-roadmap/reports/MS1-report.md` Known Limitation 9 for the exact
  status of that gap (an incomplete implementation of MS1's own D-G decision, not a defect in
  the backend).

- `adminProfessionals.ts` — **new, Production Roadmap MS1 (2026-08-22, design §D-F)** — the
  client for `/api/admin/professionals/**`, the `ADMIN`-only operator surface behind professional
  verification: `listProfessionalsForReview(approvalStatus?)`, `getProfessionalReviewDetail(id)`,
  `getVerificationDocumentUrl(id)`, `approveProfessional(id)`, `rejectProfessional(id, reason)`,
  plus `REJECTION_REASON_MAX_LENGTH` (mirrors `RejectProfessionalRequest`'s `@Size(max = 500)`).
  Every shape read off `professionals.dto.*` directly.

  **Its own file rather than more functions in `professionals.ts`**, mirroring the backend's own
  prefix split — `professionals.ts` is what customer/professional screens call, this is what only
  an operator screen may call. One file to read to answer "what can an operator do", and no
  ordinary screen reaches an operator call by autocomplete.

  Two contract details that matter to callers. `approvalStatus` fields are typed **`string`, not
  the `ProfessionalApprovalStatus` union**: they carry whatever the column holds, and an operator
  screen must render an unrecognized value as "unknown" rather than crash or print the raw code
  (`features/admin/approvalPresentation.ts` owns that mapping). And
  `VerificationDocumentUrlResponse.url` is a **bearer capability** — anyone holding it can fetch a
  private compliance document without authenticating until it expires — so it is never logged,
  never stored, and never rendered into the DOM; see
  `features/admin/VerificationDocumentAction.tsx`.

- `auth.ts`'s `UserRole` — **as of Production Roadmap MS1 (2026-08-22, design §D-F)** — gained a
  third member, `'ADMIN'` (`ck_users_role` permits it as of `V40`). A new
  `RegisterableRole = Exclude<UserRole, 'ADMIN'>` narrows the register request's `role` field to
  the two self-service roles, matching `AuthService`'s explicit refusal of `role = ADMIN` at
  registration — an operator account is created by a deliberate operational step, never through
  the public API. Widening the union touched one exhaustive map, `app/ProfilePage.tsx`'s
  `ROLE_LABELS`.

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

## `prefetchProfessionalListing` (2026-08-20)

A single-entry prefetch cache in `bookings.ts`, added for the profession-matching transition:
that screen starts the professional-listing request while its animation plays, and the booking
flow it hands off to adopts the in-flight promise instead of issuing the same request again.

Deliberately minimal — this codebase has no query-cache library and this was not the place to
introduce one. The entry is **keyed on the exact request path** (so a different issue, address
or sort never reads another request's result), **single-use** (`takePrefetched` removes it, so
changing the sort on the listing screen re-hits the network exactly as before), **TTL-bounded**
at 30s (a customer who lingers can never be served a stale list), and **rejection-safe** (a
failed prefetch drops out of the cache so the real caller retries and owns its own error
state). `getProfessionalsForIssue`/`getSosProfessionalsForIssue` consult it; nothing else does.

## `setUnauthorizedHandler` (2026-08-23)

`httpClient` now reports one thing back to the app besides the response: a request that was sent
**with** a token and answered `401` means the token the app is holding is dead (expired, or its
user row is gone). `setUnauthorizedHandler(fn)` registers the callback for that case;
`shared/hooks`' `AuthProvider` registers it alongside `setAuthTokenGetter` and ends the session.

Scoped deliberately to requests that carried a token: a `401` on a token-less request is a login
failure (`POST /api/auth/login` → `401 INVALID_CREDENTIALS`) and must stay the caller's own error
to render. The `ApiError` is still thrown either way, so no screen's error handling changed.

This is what the professional dashboard's "UNAUTHORIZED on `PATCH`/`DELETE
/api/availability/blocks/{id}`" report turned out to be: `usePolling` keeps the last successful
response on screen when a tick fails, so a calendar whose token had expired kept rendering its
stale segments, and every write attempted from it failed behind a generic error banner with no
way back to login.

## `getAvailabilityBlock` (2026-08-23)

`GET /api/availability/blocks/{blockId}` — a block's own row, unclipped. The calendar's `BLOCKED`
segments are derived per day, so a multi-day block arrives as several day-sized segments sharing
one `blockId`; the block editor loads the real range through this before editing, or it would
shrink a multi-day block to the day it was opened from.

## MS4 (2026-08-24) — `serviceAreas.ts`, and why it is a fetch

`serviceAreas.ts` is new: `GET /api/service-areas` (the closed Israeli region/city catalogue),
plus the pure helpers every consumer filters with — `citiesForRegion()`, `allCities()`,
`regionForCity()`, `cityNames()`.

**It is a fetch, unlike `categories.ts`, and that difference is deliberate.** The backend already
owns the region/city list: `professionals.service_region_id` and `professional_service_cities`
are foreign keys into it and registration validates against it. A static TS mirror would be a
*second* copy of a list the server enforces — which is exactly the cost `categories.ts`
demonstrates, being a hand-maintained copy of `V10`/`V31` that must be edited whenever a
migration touches categories, with nothing failing if somebody forgets.

`citiesForRegion()` **is** the region→city filter (MS4 §3). No form component holds a region→city
map of its own; the registration wizard and the profile editor both call this.

DTO shapes that changed with MS4:

| File | Was | Is |
|---|---|---|
| `professionals.ts` | `categoryId`, `serviceArea`, `city` | `categoryIds[]`, `serviceRegionId`/`serviceRegionNameHe`, `baseCityId`/`city`, `serviceCityIds[]`/`serviceCityNamesHe[]` |
| `auth.ts` | `categoryId`, `serviceArea` | `categoryIds[]`, `serviceRegionId`, `serviceCityIds[]`, `baseCityId` |
| `bookings.ts` | `serviceArea` | `serviceRegion` (nullable), plus `categoryIds[]` on the card |
| `favorites.ts`, `users.ts`, `adminProfessionals.ts`, `sos.ts` | `serviceArea`/`categoryId` | `serviceRegion`/`categoryIds[]` |

`categories.ts` also gained `getCategoryNamesHe()` and `formatCategorySummary()` — the latter is
MS4 §7's compact form ("אינסטלציה +2") for cards with room for one line.


## Mobile upload performance (2026-08-27) — `httpClient.upload`, `uploadImage`

`httpClient` gained one method, `upload(path, formData, { onProgress, signal })`, and it is the
only thing in this package that does not use `fetch`. The reason is narrow and not a matter of
taste: **`fetch` exposes no upload-progress signal.** (`ReadableStream` request bodies would, and
are not supported on Safari, which is most of this app's traffic.) `XMLHttpRequest` has
`upload.onprogress`, so a multi-second photo upload on a phone's uplink can show a real
percentage instead of a spinner indistinguishable from a hung request.

Everything else is deliberately identical to `request`: same base URL, same bearer token, same
error envelope, and — via the extracted `toApiError` — the same `401` dead-session and
`PHONE_VERIFICATION_REQUIRED` handlers. Extracting that helper is the point: two transports with
two copies of those global side effects is how one of them ends up missing a redirect.

`onProgress` reports bytes handed to the OS, so it reaches 1 when the last byte is *sent*, before
the backend has forwarded them to S3 and answered. Callers should read 1 as "uploaded, now
waiting on the server".

`uploadImage(file, options?)` now routes through it. `options` is optional and omitting it
behaves exactly as the previous `post`-based call did. It deliberately does **not** compress on
its own — `ImageUploadField`'s registration and profile-photo flows also reach this api layer,
and silently re-encoding every image every caller ever passes would be a far wider behavioural
change than the one being made. Compression is the caller's decision;
`shared/components/PhotoUploader` makes it via `shared/lib/imageCompression.ts`.
