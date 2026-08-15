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

## Status
Implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`), covering the `auth`/`users` endpoints. Grows
with each subsequent milestone as new backend endpoints ship.
