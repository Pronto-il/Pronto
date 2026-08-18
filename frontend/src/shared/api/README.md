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
- `categories.ts` — static mirror of the fixed 8-category list seeded by
  `V10__seed_categories.sql` (no public categories endpoint exists yet).
- `errorMessages.ts` — `GENERIC_ERROR_MESSAGE` fallback copy, and
  `getFieldErrorMessages` which maps a `400 VALIDATION_ERROR`'s `details` array to
  `{ field: hebrewMessage }` so forms can attribute errors per-field.
- `issues.ts` — `classifyIssue`, `createIssue` (Milestone 2), plus a Frontend Milestone 3
  addition: `getIssue` (`GET /api/issues/{id}`, either CUSTOMER-owner or PROFESSIONAL-
  with-an-order), returning `IssueDetailResponse` including a `latestOrder` summary.
- `bookings.ts` — **new, Frontend Milestone 3; extended, Frontend Milestone 4 (SOS) and the
  MS3/MS4 product-corrections pass.** Standard + SOS booking-flow domain: professional
  listing (`getProfessionalsForIssue`/`getSosProfessionalsForIssue`, `GET
  /api/bookings/professionals`/`.../sos-professionals`, `city`/`street`/`houseNumber`
  required query params + optional `sort: ProfessionalSort` — `'CHEAPEST' | 'RECOMMENDED' |
  'FASTEST'`), slot listing (`getProfessionalSlots`), order lifecycle (`createOrder`/
  `createSosOrder`, `acceptOrder`, `rejectOrder`, `cancelOrder`, `getOrder`, `getMyOrders`).
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
  own per-type comments, instead.
- `availability.ts` — **new, Frontend Milestone 3.** A professional's own Standard-booking
  calendar: `createAvailabilitySlot` (`POST /api/availability/slots`),
  `getMyAvailabilitySlots` (`GET /api/availability/slots/me`). Type names match the real
  backend DTO names directly (`SlotResponse`/`SlotListItem`/`SlotListResponse`/
  `CreateSlotRequest`).
- `reviews.ts` — **new, Active Booking Floating Indicator feature (2026-08-17).**
  `CreateReviewRequest`/`ReviewResponse` types + `createReview(payload)` wrapping
  `POST /api/reviews`. **First frontend consumer of this endpoint** — the backend endpoint
  itself was already implemented and QA-signed-off with no UI caller (Milestone 8); this file
  is what `features/booking/CompletionReviewPage.tsx` calls. Shapes verified directly against
  `reviews.dto.CreateReviewRequest`/`reviews.dto.ReviewResponse`, same "read the real backend
  DTOs" convention `bookings.ts`'s own header comment already established.

**As of the Active Booking Floating Indicator feature**: `bookings.ts` also gained
`expectedArrivalAt: string | null` on `OrderResponse`/`OrderDetailResponse`/`OrderSummary`
(non-`null` once an order reaches `ON_THE_WAY`), and `updatedAt: string` on `OrderSummary`
(not previously present on that lean list-mine shape — needed by
`shared/hooks/activeOrderContext.ts`'s completed-order tie-break logic). No new functions —
`getMyOrders`/`getOrder` are unchanged, only their response shapes grew.
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
entry.
