# features/auth

## Purpose
Customer and professional registration, email verification, and login screens.

## Responsibilities
- Registration forms for both user types (customer, professional), each a progressive,
  multi-stage wizard (see "Registration wizards" below).
- Email verification code entry flow (no resend — the backend has no resend endpoint).
- Login form and session/token handling on the client side (via `shared/hooks`'
  `AuthProvider`).
- Surfacing account-lockout state after repeated failed logins (backend enforces the
  5-attempt lockout; `AccountLockoutBanner` displays the resulting error state).

## Structure
Feature components (composed by the pages below):
- `RoleChooser` — "אני לקוח / אני בעל מקצוע" chooser, two distinct links to
  `/register/customer` and `/register/professional` (not a toggle on one shared form). Each
  option is a visually distinct card (icon, tint, subcopy, 2 value-prop tags) driven by a
  small local config array — not a new shared component (single consumer, MS2 design doc
  §5.6).
- `RegistrationWizardShell` — the one new shared component MS2 introduces (design doc §6.1),
  justified by two real, simultaneous consumers (`CustomerRegisterForm`,
  `ProfessionalRegisterForm`). Owns the `PageHeader` (title + `steps` progress track), the
  `AnimatePresence`/`stepTransition`-animated per-stage viewport, and the back/primary footer
  button row. Does **not** own field state, per-field validation, or the API call — the
  caller wraps it in its own `<form onSubmit=...>` and owns the stage content.
- `CustomerRegisterForm` — a **3-stage** wizard (design doc §6.2): (1) basic info — full
  name, email, password, confirm password, **phone** (see "phone" note below); (2) address
  (`AddressFormFields`, unchanged: city/street/house number required, apartment/floor/
  entrance/notes optional); (3) read-only confirmation summary + the real submit
  (`registerCustomer()`, unchanged — still one `POST /api/auth/register`, all fields
  collected across the 3 UI stages then sent together). A submit-time field error
  (`DUPLICATE_EMAIL`, or a `VALIDATION_ERROR`'s field-level messages) routes the wizard back
  to whichever stage owns the offending field (design doc §6.3) rather than only setting
  local state on stage 3, where the submit button lives.
- `ProfessionalRegisterForm` — a **6-stage** wizard (MS1; see "MS1: sub-services and working
  hours are now collected at registration" below): (1) personal details — full name, email,
  password, confirm password, **no phone** (not a backend field for `PROFESSIONAL`); (2)
  profession + service area — service category (`Select`, options built from the fetched
  catalog, not from `shared/api/categories.ts`'s static mirror), service area (`Input`); (3)
  **sub-services** — "באילו תחומים אתה נותן שירות?", a required multi-select `Checkbox` list
  showing only the selected category's sub-services, at least one required; (4) pricing +
  documents — base price, optional profile photo (`ImageUploadField`), required verification
  document (`DocumentUploadField`); (5) **weekly working hours** — "באילו ימים ושעות תרצה לקבל
  הזמנות?", the shared `WeeklyHoursFields` editor starting from a completely blank week, at
  least one enabled day required; (6) read-only summary (including the chosen sub-services and
  each enabled day's hours) + the real submit (`registerProfessional()`) + an honest "what's
  next" block. Same submit-time error routing principle as the customer wizard, extended: a
  `CATEGORY_MISMATCH` clears the sub-service selection and routes back to stage 3, and the
  backend's per-row week errors (`weekday`/`startTime`/`endTime`) fold into stage 5's single
  error slot.
- `VerifyCodeForm` — single 6-digit numeric input, submits to `POST /api/auth/verify`; on
  success routes to `/login` (verify does not issue a JWT, so no auto-login).
- `LoginForm` — email + password; maps `401 INVALID_CREDENTIALS`/`403
  EMAIL_NOT_VERIFIED`/`423 ACCOUNT_LOCKED` to distinct Hebrew copy (`AccountLockoutBanner`
  for `423`; the `403` case also shows a link to `/verify?email=...` so an unverified user
  can complete verification without retyping their email); no "forgot password" link (out
  of scope). MS2 wraps the fields in a `Card` for visual hierarchy — no state/handler
  changes.
- `AccountLockoutBanner` — renders `423 ACCOUNT_LOCKED`'s `details.retryAfterSeconds` as
  a static "try again in ~N minutes" message (no live countdown).

Pages (composed into `app/router.tsx`): `RegisterChoicePage`, `CustomerRegisterPage`,
`ProfessionalRegisterPage`, `VerifyPage`, `LoginPage`. `CustomerRegisterPage`/
`ProfessionalRegisterPage` are thin wrappers (design doc §6.1) — `RegistrationWizardShell`
(rendered inside the form components) now owns the page header, so these pages no longer
render a standalone `PageHeader`; each just wraps its form in the shared `pageTransition`
motion and passes an `onExit` callback (`() => navigate('/register')`) used only by stage 1's
back button — stage 2+'s back button moves to the previous stage internally, not out of the
flow. **MS2 also added reciprocal footer links between the two entry pages** (design doc §9
item 5, a trivial low-risk follow-on flagged while designing Login): `LoginPage.tsx` gained
"אין לכם חשבון? <Link to="/register">הרשמה</Link>" below the login `Card`, and
`RegisterChoicePage.tsx` gained "כבר יש לך חשבון? <Link to="/login">התחברות</Link>" below
`RoleChooser`. Both are page-level additions (not inside `LoginForm`/`RoleChooser`
themselves), plain navigation with no state/handler involvement.

## `phone` — was missing end-to-end, fixed in MS2
Confirmed by direct inspection: `backend/.../auth/dto/CustomerRegistrationData.java`'s
`phone` is `@NotBlank`, but `shared/api/auth.ts`'s `RegisterCustomerPayload`/
`RegisterRequestData.customer` had no `phone` field at all, and `CustomerRegisterForm`
never collected it — every real customer signup got a silent `400`. Fixed across all three
layers: `RegisterCustomerPayload.phone` (sibling of `address`, matching the backend's
`CustomerRegistrationData(defaultAddress, phone)` shape — sent as `customer.phone`, **not**
nested inside `customer.defaultAddress`), a new stage-1 `Input` (`autoComplete="tel"`,
required, non-blank client validation only — no format regex, matching this codebase's
existing minimal-validation style, e.g. `ProfilePage.tsx`'s own phone field), and wired into
the final `registerCustomer()` payload.

## MS1: sub-services and working hours are now collected at registration (2026-08-22)
This supersedes "Why 4 stages, not 6" below, whose audit was correct at the time: back then
`ProfessionalRegistrationData` had exactly three fields and neither sub-services nor working
hours were writable at registration time, so the wizard covered them as honest informational
content rather than collecting data it would have to discard.

MS1 (Playbook §MS1, decisions D4/D7; `docs/architecture/ms1-professional-verification-design.md`
§D-C) changed the backend contract: `professional.subServiceIds` and `professional.workingHours`
are **required** on `POST /api/auth/register`, persisted by `AuthService` in the same
transaction as the professional row, and a professional is marketplace-eligible only when
approved **and** onboarding is complete. Registration without them now returns `400` with both
fields flagged. So the two informational placeholders became two real stages:

- **Stage 3 — sub-services.** One `getCategoriesWithSubServices()` fetch (public
  `GET /api/categories`) backs both the stage-2 category `Select` and this checklist, so a
  category and its sub-services can never disagree, and `shared/api/categories.ts`'s static
  `CATEGORIES` mirror is no longer used by this form. Only the selected category's sub-services
  are offered, and **changing the main category clears the selection** — the backend refuses a
  cross-category id with `400 CATEGORY_MISMATCH`
  (`professionals.service.SubServiceSelectionValidator`), so carrying stale ids forward would
  guarantee a failed submit. Because sub-services are required, a catalog fetch failure is a
  real blocking error with a retry action, not a silently omitted extra.
- **Stage 5 — weekly working hours.** Renders `shared/components`' `WeeklyHoursFields` — the
  same editor `/pro/availability`'s `WorkingHoursForm` renders — and serializes with the same
  `toWeeklyHoursRequest()`, so registration always sends exactly the 7-entry shape
  `availability.service.WorkingHoursValidator` demands. The week starts **completely blank**:
  no day enabled, no times pre-filled (Playbook MS1: "do not invent default working hours"),
  unlike the dashboard's edit surface which seeds 08:00-18:00 into a weekday the server hasn't
  configured. Client-side validation (times required on an enabled day, end after start, at
  least one enabled day) is UX only — the backend re-runs all of it, and the database
  `CHECK` forbids an overnight range, which is why no UI here implies 22:00→02:00 is possible.

The result state is honest about what registration produced: a successful submit creates a
**`PENDING`** professional, so the primary button reads "שליחת הבקשה" and the final stage's
"what's next" block says the application is awaiting review and that the account is not shown to
customers until it is approved — it no longer tells the registrant to go complete sub-services
and availability later (they just did), and it never says they are live.

## Why 4 stages, not 6 (professional wizard) — superseded by MS1, kept for the audit trail
The milestone dispatch originally asked for 6 stages (personal details, profession/
sub-services, service area, pricing, availability, profile completion). Verified directly
against source rather than assumed: `ProfessionalRegistrationData` has exactly 3 fields
(`categoryId`, `serviceArea`, `basePrice`) — no sub-service field. Sub-services are only
settable via the authenticated, `PROFESSIONAL`-only `PUT /api/professionals/me/sub-services`.
There is no working-hours/availability field or endpoint reachable at registration time.
Most importantly, `AuthService.register()`/`verify()` return no JWT — **there is no
authenticated session available at any point during or immediately after registration**; a
professional must register → verify by email → log in, only then does a bearer token exist
that any `/pro/*` or `/api/professionals/me/*` route could use. Collecting sub-service
selections in the wizard and then silently discarding them on submit would violate
`FRONTEND_AGENT.md` §10/§53 ("never fake product functionality" / "no placeholder
completion") — worse than not asking. So sub-services/availability are covered as honest
informational content (a real-data preview in stage 2, a "what's next" block in stage 4)
folded into the stages that do have real, backend-writable content, rather than as two
content-free or fake-data-collecting filler stages. See
`docs/architecture/frontend-ms2-home-auth-design.md` §6.5-6.6 for the full audit.

## MS2 QA bugfix: `prefers-reduced-motion` not actually respected (2026-08-20)
QA found `LoginPage.tsx` (`pageTransition`), `RegisterChoicePage.tsx` (`pageTransition`),
`RoleChooser.tsx` (`listStagger` + per-card `pageTransition`), and — by code-review, then
confirmed — `CustomerRegisterPage.tsx`/`ProfessionalRegisterPage.tsx`'s outer
`pageTransition` wrapper all ignored OS-level reduced motion. The initial fix attempt copied
this package's own `RegistrationWizardShell.tsx` pattern (`useReducedMotion()` +
`transition={shouldReduceMotion ? { duration: 0 } : undefined}` alongside `variants`), but
live Playwright verification (`reducedMotion: 'reduce'` context, rAF-sampled computed
opacity) showed it has **no effect**: `pageTransition.animate`/`listStagger`'s target objects
embed their own `transition`, and framer-motion gives that variant-level transition
precedence over the component's `transition` prop — the prop is silently ignored whenever the
variant defines one. The technique that actually works (confirmed to match `Modal.tsx`/
`ToastViewport.tsx`'s real runtime behavior, same live-verification method): override the
`animate` **target object** itself, not the `transition` prop —
`animate={shouldReduceMotion ? { ...variant.animate, transition: { duration: 0 } } : 'animate'}`.
Applied to all five files above. `RegistrationWizardShell.tsx`'s own `stepTransition` usage
was initially **not** touched (out of that bugfix's scope, since it wasn't one of the
QA-flagged surfaces), but flagged as likely sharing the same latent issue — confirmed true on
follow-up, and fixed as part of the same reduced-motion pass. Since `stepTransition.initial`/
`exit` are direction-dependent **functions** (`custom={direction}`), not static objects like
`pageTransition`/`listStagger`, the fix follows `Modal.tsx`'s `panelVariants` pattern instead
of the simpler "override just `animate`" one used above: a memoized `stageVariants` wraps
`initial`/`animate`/`exit`, forcing each resolved target's `transition` to `{ duration: 0 }`
under reduced motion while still calling through to the original `direction`-aware
`initial`/`exit` functions. Verified live (same rAF-sampled technique, both forward
`direction=1` and back `direction=-1`) that the stage transition now jumps straight to its
settled state under reduced motion instead of ramping, and still animates normally with
reduced motion off.

## Status
Implemented in **Milestone 1 — Auth & user management**
(`docs/architecture/implementation-plan.md`), against the `auth`/`users`/`professionals`
backend packages. Visual/UX redesign into progressive multi-stage wizards, the `phone` fix,
and the `RegistrationWizardShell` shared component landed in **Frontend MS2 — Home +
Authentication Experience** (`docs/architecture/frontend-ms2-home-auth-design.md`). The
reduced-motion bugfix above landed in the same MS2 pass, as a QA-driven correction, not
separate scope. The professional wizard's two new required stages (sub-services, weekly working
hours) and the `PENDING` result copy landed in **Production Roadmap MS1 — Professional
Verification & Marketplace Eligibility**
(`docs/architecture/ms1-professional-verification-design.md`).
