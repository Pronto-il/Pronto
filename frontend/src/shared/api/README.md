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
- `users.ts` — `getMe` (`GET /api/users/me`).
- `categories.ts` — static mirror of the fixed 8-category list seeded by
  `V10__seed_categories.sql` (no public categories endpoint exists yet).
- `errorMessages.ts` — `GENERIC_ERROR_MESSAGE` fallback copy, and
  `getFieldErrorMessages` which maps a `400 VALIDATION_ERROR`'s `details` array to
  `{ field: hebrewMessage }` so forms can attribute errors per-field.
- `issues.ts` — `classifyIssue`, `createIssue` (Milestone 2), plus a Frontend Milestone 3
  addition: `getIssue` (`GET /api/issues/{id}`, either CUSTOMER-owner or PROFESSIONAL-
  with-an-order), returning `IssueDetailResponse` including a `latestOrder` summary.
- `bookings.ts` — **new, Frontend Milestone 3.** Standard booking-flow domain: professional
  listing (`getProfessionalsForIssue`, `GET /api/bookings/professionals`, `city`/`street`/
  `houseNumber` required query params + optional `sort`), slot listing
  (`getProfessionalSlots`), order lifecycle (`createOrder`, `acceptOrder`, `rejectOrder`,
  `cancelOrder`, `getOrder`, `getMyOrders`). **Important nuance**: per this file's own
  header comment, its shapes were verified directly against the real backend DTO source,
  not copied from `docs/architecture/api-contract-bookings.md`'s prose — that doc's §2.2
  and §2.4 predate Milestone 8 (Professional Profiles, Reviews, Favorites & Matching),
  which changed several of these DTOs in place (the enriched `ProfessionalCard`, the
  required service-address query params/body fields) without the doc being updated. A
  future reader should not trust that doc's §2.2/§2.4 text at face value — read the real
  backend DTOs, or this file's own per-type comments, instead.
- `availability.ts` — **new, Frontend Milestone 3.** A professional's own Standard-booking
  calendar: `createAvailabilitySlot` (`POST /api/availability/slots`),
  `getMyAvailabilitySlots` (`GET /api/availability/slots/me`). Type names match the real
  backend DTO names directly (`SlotResponse`/`SlotListItem`/`SlotListResponse`/
  `CreateSlotRequest`).

## Status
Implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`), covering the `auth`/`users` endpoints. Grows
with each subsequent milestone as new backend endpoints ship — **Frontend Milestone 3
(Standard booking flow, 2026-08-16)** added `bookings.ts`, `availability.ts`, and
`issues.ts`'s `getIssue`.
