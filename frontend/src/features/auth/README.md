# features/auth

## Purpose
Customer and professional registration, email verification, and login screens.

## Responsibilities
- Registration forms for both user types (customer, professional).
- Email verification code entry flow (no resend — the backend has no resend endpoint).
- Login form and session/token handling on the client side (via `shared/hooks`'
  `AuthProvider`).
- Surfacing account-lockout state after repeated failed logins (backend enforces the
  5-attempt lockout; `AccountLockoutBanner` displays the resulting error state).

## Structure
Feature components (composed by the pages below):
- `RoleChooser` — "אני לקוח / אני בעל מקצוע" chooser, two distinct links to
  `/register/customer` and `/register/professional` (not a toggle on one shared form).
- `CustomerRegisterForm` — full name, email, password, confirm password (client-side
  match check only, never sent to the backend), plus the full `AddressFormFields` group.
  See the "Known gap" note below for what actually gets submitted.
- `ProfessionalRegisterForm` — full name, email, password, confirm password, service
  category (`Select`, sourced from `shared/api/categories.ts`), service area, base price,
  optional profile photo (`ImageUploadField`), required-to-select verification document
  (`DocumentUploadField`). Same submission gap as above for the photo/document.
- `VerifyCodeForm` — single 6-digit numeric input, submits to `POST /api/auth/verify`; on
  success routes to `/login` (verify does not issue a JWT, so no auto-login).
- `LoginForm` — email + password; maps `401 INVALID_CREDENTIALS`/`403
  EMAIL_NOT_VERIFIED`/`423 ACCOUNT_LOCKED` to distinct Hebrew copy (`AccountLockoutBanner`
  for `423`; the `403` case also shows a link to `/verify?email=...` so an unverified user
  can complete verification without retyping their email); no "forgot password" link (out
  of scope).
- `AccountLockoutBanner` — renders `423 ACCOUNT_LOCKED`'s `details.retryAfterSeconds` as
  a static "try again in ~N minutes" message (no live countdown).

Pages (composed into `app/router.tsx`): `RegisterChoicePage`, `CustomerRegisterPage`,
`ProfessionalRegisterPage`, `VerifyPage`, `LoginPage`.

## Known gap — register payload omits address/photo/document
`POST /api/auth/register` (`backend/.../auth/dto/RegisterRequest.java`) has no
`address`/`photo`/`verificationDocument` fields and is a plain JSON endpoint (not
multipart, `FAIL_ON_UNKNOWN_PROPERTIES = true`). Both register forms collect that data
fully in the UI (with real client-side validation) but do not send it — see the comments
on `registerCustomer`/`registerProfessional` in `shared/api/auth.ts`. Pending Backend
Milestone 7; only the submission call needs to change once that lands, not the UI.

## Status
Implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`), against the `auth`/`users`/`professionals`
backend packages.
