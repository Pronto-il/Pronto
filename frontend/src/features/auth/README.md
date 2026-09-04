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
- `CustomerRegisterForm` — a **2-stage** wizard (design doc §6.2, address stage removed by the
  address-flow redesign): (1) account details — full name, email, password, confirm password,
  **phone** (see "phone" note below); (2) read-only confirmation summary + the real submit
  (`registerCustomer()` — one `POST /api/auth/register`, now sending `customer: null`).
  **Registration collects no address.** An address is a property of a job, not of an account:
  the booking flow asks for it after AI classification, immediately before anything needs it,
  and a customer saves it to their profile only by ticking "הפוך את זה לכתובת הבית" there or by
  editing it on `/profile`. Asking at registration bought a mandatory extra screen and — for
  anybody booking on a parent's behalf — a saved default that was wrong on day one. The backend
  makes this safe rather than merely convenient: `customer.defaultAddress` is optional on
  `POST /api/auth/register` and `users.default_*` has always been nullable. A submit-time field error
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

## MS4 (2026-08-24) — registration stage 2 and stage 5

**Stage 2** ("profession + service area") became "service categories + service region + service
cities":

- The single category `Select` is now a `MultiSelectField` — a professional may register as a
  plumber *and* a handyman.
- The free-text "אזור שירות" `Input` is gone, replaced by a region `Select` and a searchable city
  `MultiSelectField`, both fed by `GET /api/service-areas` via `shared/api/serviceAreas.ts`.
  **There is no field left on this form a registrant can type a place name into** — `'תל אביב'`,
  `'תל-אביב'` and `'Tel Aviv'` used to be three different service areas.
- Changing the region re-scopes the city list through the shared `citiesForRegion()` helper; this
  component holds no region→city map of its own. Cities outside the new region are dropped
  silently here, because registration has persisted nothing yet and there is nothing to warn
  about — the profile editor, which *is* editing saved data, warns and names them instead.
- The base city is the first city chosen in catalogue order. Asking a registrant to additionally
  nominate a "main" city would be a question with no obvious right answer; the profile editor
  exposes it as an explicit field for anyone who wants to change it.

**Stage 3** groups the sub-service checklist under one heading per selected category, so a
registrant who chose two trades can tell which trade each item belongs to. A single-category
registrant sees one heading, which costs nothing.

**Stage 5** turns on `WeeklyHoursFields`' `showApplyToAll` — MS4 §11's "החל על הכל". The week is
still blank until the registrant acts (nothing is pre-filled on their behalf, per MS1), every day
stays independently editable afterwards (§12), and the times are the same 24-hour `TimeField` the
availability dashboard uses (§13).


## Registration validation — everything is settled before the confirmation screen

`CustomerRegisterForm`'s stage 2 used to be where a customer discovered that their phone number was
malformed, or that the address they had just chosen a password for was already registered: the whole
form filled in, reviewed, submitted, and only then bounced back to stage 1. Nothing is deferred to
submit any more, and the work splits along one line — **what the browser can know on its own, and
what only the server can**.

### Locally answerable rules — `registrationValidation.ts`

Name length, email shape, phone shape, password length, confirmation match. Each is a named function
so the wizard's "may I advance?" question has one answer instead of one inline `if` per field. They
run **on blur** (so the error attaches to the field the customer just left) **and again on
Continue** (so a field they never focused cannot slip through).

The phone rule is deliberately **shape only** — `+972…`, `00972…`, `0…` with a plausible digit
count. Whether a number is a real, assignable, SMS-capable line is a question about a numbering
plan, `auth.service.PhoneNumberNormalizer` answers it with libphonenumber, and a copy of the Israeli
mobile-prefix list living in this feature would start silently rejecting legitimate customers the
day a new prefix is allocated. The precise verdict arrives from the availability check below — still
on blur, still well before the summary.

### Uniqueness — `useContactAvailability.ts`

No browser can know whether an address is registered, and this hook does not pretend otherwise: it
asks `POST /api/auth/availability` and puts the answer under the field.

**Blur, not keystroke.** That endpoint is rate limited at 20 requests per 10 minutes per client —
tightly, on purpose, because it is the cheapest form of an account-existence disclosure (see the
backend `auth` README). A debounced per-character caller would spend that entire budget on one slow
typist and then start receiving `429`s on the checks that matter. "The customer finished entering the
field" is exactly what `blur` means, and it is one request per value. Settled answers are cached per
trimmed value so tabbing back over an unchanged field re-asks nothing.

**A failed check does not block.** `unknown` — offline, rate limited, 5xx — is explicitly
non-blocking, and is not cached (it is the absence of an answer, not an answer). Registration
performs its own duplicate checks and is the authoritative gate; refusing to let somebody register
because a convenience endpoint was unreachable would trade a UX improvement for an outage.

**Continue awaits both checks** rather than racing them, so pressing it the instant the last field is
filled waits for the verdict instead of sailing past a request still in flight.

### `DUPLICATE_EMAIL`/`DUPLICATE_PHONE` handling at submit is still there, and is not redundant

The availability answer is true when given and can be false a minute later while the customer picks a
password. The 409 handling is that race, and it routes back to stage 1 where the field lives.

Tests: `CustomerRegisterForm.test.tsx` (21 cases), covering each blocked-advance path, both
duplicates reported on blur with no registration attempt, the one-request-per-value budget, the
unreachable-check case, and the submit-time race.

## Where registration ends is the server's answer, not the page's assumption

`CustomerRegisterPage` and `ProfessionalRegisterPage` both used to `navigate('/verify', {state})`
unconditionally on success. That was correct while `POST /api/auth/register` always answered
`VERIFY_EMAIL` with a challenge, and it broke the moment verification could be switched off: with
`OTP_VERIFICATION_ENABLED=false` the backend creates the account and answers `AUTHENTICATED` with a
real **session** and `challenge: null`, and `AuthChallengePage` treats a challengeless state as "no
active flow" — so a successful registration rendered *"התהליך פג / נדרשת התחלה מחדש"* and threw a
valid token away.

`useRegistrationLanding` is the fix, and it is a shared hook rather than a copy in each page for the
reason `useSessionLanding`'s own Javadoc gives — two copies is how one of them forgets a case. It
handles the three answers the endpoint can give, exactly as `AuthChallengePage.advance` already did:

| `nextStep` | what it means | where the user goes |
|---|---|---|
| `AUTHENTICATED` + `session` | verification is off, or already satisfied | adopted via `useSessionLanding` → the role's landing screen (or the booking draft) |
| anything with a `challenge` | the ordinary verified flow | `/verify`, challenge in router state |
| `LOGIN` | account complete, no session returned | `/login` |

## The phone-capture screen asks two questions, not one

`PhoneCapturePage` used to redirect only on `user.phoneVerified`. With verification switched off that
flag stays `false` — correctly, because the number genuinely was not proved — so the screen would
have offered to send a code the backend has switched off (`AuthService#capturePhone` refuses it under
that policy).

`GET /api/users/me` therefore returns **`phoneVerificationRequired`** alongside `phoneVerified`, and
the screen redirects when either "already proved" or "nobody is asking" holds. The backend
deliberately did **not** solve this by making `phoneVerified` report `true` under the policy: that
would put a lie in the one record that decides who gets asked to verify when verification is turned
back on.

Tests: `useRegistrationLanding.test.tsx` (7) — all three landing branches plus the regression
assertion that an `AUTHENTICATED` response never reaches `/verify`, and the three capture-screen
redirect cases.

## Professional registration validates as early as customer registration

Stage 1 of `ProfessionalRegisterForm` now runs the **same** rules and the **same** availability
mechanism the customer wizard uses — `registrationValidation.ts` and `useContactAvailability`, not a
second implementation. Registration rules are identical for both roles (one `RegisterRequest`, one
`@Email`, one `PhoneNumberNormalizer`, one 8-character password), so there was nothing for a
separate copy to express — and the copies had already drifted: this form checked only that the phone
field was non-empty, so `12345` advanced here while the customer flow rejected it.

Local rules and the blur-driven availability check both run before Continue, and Continue awaits any
in-flight check rather than racing it.

### Why `DUPLICATE_PHONE` used to become "משהו השתבש, נסו שוב"

The catch block had a branch for `DUPLICATE_EMAIL` and none for `DUPLICATE_PHONE`. The unhandled
code fell through to `getFieldErrorMessages`, which returns `null` for anything that is not
`VALIDATION_ERROR`, and landed on the generic banner — so a registrant who had just completed six
stages was told nothing about what was wrong.

`mapDuplicateContactError` (in `registrationValidation.ts`) is now the single mapping both forms
call, so neither can know about fewer error codes than the other. `VALIDATION_ERROR` still falls
through to `getFieldErrorMessages` for per-field attribution; the generic banner keeps exactly one
job — failures there is nothing specific to say about.

A duplicate reported at final submit routes back to stage 1 via `routeFieldErrors` with every other
stage's answers still in state, so only the offending field needs correcting.


## `AuthGateModal` (2026-09-04) — the account question, asked in place

Hosts the **existing** `LoginForm`, `CustomerRegisterForm` and OTP step over whichever screen needs
a session, instead of navigating to `/login`. No new auth UI: same components, endpoints, validation
and error treatments the `/login`, `/register/customer` and `/verify` routes render.

Three seams made that possible, all small:

- **`AuthChallengeStep`** — extracted from `AuthChallengePage`. With `AUTH_OTP_REQUIRED=true` a
  password submit answers with a challenge, so the modal has to host the code step too or the guest
  is navigated away mid-flow. The page is now the route around the step (router state, heading,
  refresh recovery); the interaction lives in the component both hosts render.
- **`LoginForm.onChallenge`** — optional. Absent, it navigates to `/verify` exactly as before.
- **`useSessionLanding`** — checks the gate before its draft-route navigation. A non-`CUSTOMER` who
  signs in through the gate is landed on their own dashboard instead (resuming a customer booking
  for them would only earn a 403), and the gate is closed on the way out.

Registration inside the gate is the **customer** form only: this gate exists on the customer booking
journey, and `/register` remains the way to choose a role.
