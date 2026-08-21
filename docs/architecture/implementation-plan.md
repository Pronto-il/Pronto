# Pronto — Implementation Plan

Ordered milestones derived from `docs/architecture/overview.md` §5, with per-milestone
responsibilities across the five project agents (`pronto-lead`, `pronto-planning`,
`pronto-coding`, `pronto-qa`, `pronto-documentation`). No milestone is "done" without a
QA pass and up-to-date package docs. No milestone includes a git push/merge/PR — that
requires separate, explicit user approval regardless of milestone completion.

Every milestone follows the same cycle: **Planning** (spec, if not already covered by
`overview.md`) → **Coding** (implementation) → **QA** (validation against the milestone's
acceptance criteria) → **Documentation** (package `.md` files + any doc updates) →
**Lead** (reviews all of the above for consistency before the milestone is considered
closed).

## Milestone 0 — Foundation

- **Goal**: a running skeleton both devs can build on.
- **Scope**: Spring Boot project init (`backend/`) with the package structure from
  `overview.md` §4 as empty stub packages + stub `.md` docs; React project init
  (`frontend/`) with the `src/features/*` + `src/shared/*` structure, also stubbed;
  Postgres via docker-compose for local dev; DB migration tooling (Flyway or Liquibase)
  with the base schema from PRD §6 (Users, Professionals, AvailabilitySlots, Issues,
  IssueImages, Orders, Notifications) translated to Postgres DDL; basic CI (build + lint)
  if in scope for the team's time budget.
- **Acceptance criteria**: backend builds and runs with a health endpoint; frontend builds
  and runs with a placeholder home route; migrations apply cleanly against a fresh
  Postgres instance; every stub package has its `.md` file.
- **Removes**: the leftover `src/main/java/main.java` "Hello World" and
  `out/production/pronto/main.class` scaffolding once the real Spring Boot project
  supersedes them (confirm with user before deleting).

## Milestone 1 — Auth & user management

- **Status: COMPLETE (backend), 2026-08-13.** `auth`, `users`, `professionals` packages
  implemented per `docs/architecture/api-contract.md`; QA validated in two passes (full
  validation, then a re-verification pass after a login-lockout bug fix) — see "QA
  summary" below. **Frontend `features/auth` screens are intentionally deferred**, not an
  oversight — the user has not yet finalized the frontend design system, and building auth
  screens ahead of that decision was explicitly deprioritized. Do not read the absence of
  frontend auth work as a gap in this milestone's backend completion.
- **Scope**: `auth`, `users`, `professionals` (profile only — no approval workflow, v1.0
  auto-approves professional accounts). Registration, email verification code, login,
  password hashing, account lockout after 5 failed attempts, account deletion endpoint.
- **Acceptance criteria**: a customer and a professional can both register, verify, and
  log in; failed-login lockout is enforced; a verified professional can receive bookings
  immediately, with no separate approval step. **All met**, backend-only.
- **QA summary**: live-validated against a real Postgres instance — registration/
  verification/login for both roles, professional auto-approval (no admin gate), lockout
  enforcement (5 attempts → 15-min lock → auto-expiry/reset), soft-delete + PII
  anonymization on account deletion, JWT auth enforcement on `/api/users/me`,
  `/actuator/health` still public (no Milestone 0 regression), and edge cases
  (case-insensitive duplicate email, expired/consumed verification codes, invalid
  `categoryId`). First pass caught a critical lockout-bookkeeping bug (fixed via
  `LoginAttemptRecorder`, see `backend/src/main/java/com/pronto/auth/README.md`
  "Transaction boundaries"); second pass re-verified the fix and passed fully.
- **Known gaps, not blockers**: no password-reset flow, no resend-verification-code
  endpoint, no refresh-token/logout-revocation mechanism — see `overview.md` §6 and
  `api-contract.md` §4 for detail.

### Frontend Milestone 1 — Auth screens

- **Status: COMPLETE, 2026-08-15.** The first real frontend milestone to land — every
  prior backend milestone (1-8) shipped with `frontend/` left design-only, per each
  milestone's own status note above. Not one of the originally-numbered backend
  milestones in `overview.md` §5; tracked separately as "Frontend Milestone 1" (MS1)
  since it delivers the UI for this same Milestone 1 auth/user-management scope.
- **Screens delivered**: `/register` role chooser → `/register/customer` /
  `/register/professional`, `/verify`, `/login`, `/profile` (any authenticated role,
  read-only `GET /api/users/me`), and `/pro` (a professional-only placeholder route,
  customers redirected away).
- **Built on**: the design tokens/fonts/icons bootstrap, `AppLayout`, a typed
  `httpClient` + `AuthProvider`/`useAuth`/`RequireAuth`, and the shared component set
  (`Button`, `Input`, `Select`, `Card`, `PageHeader`, `ImageUploadField`,
  `DocumentUploadField`, `AddressFormFields`).
- **Original QA pass** found and fixed two defects:
  - **(a) Critical** — CORS was entirely unconfigured on the backend: cross-origin
    requests from the Vite dev server were rejected on the preflight `OPTIONS` request
    before ever reaching a controller. Fixed via a `CorsConfigurationSource` bean in
    `auth.config.SecurityConfig` (`pronto.cors.allowed-origins` /
    `CORS_ALLOWED_ORIGINS`, default `http://localhost:5173`) — see
    `backend/src/main/java/com/pronto/auth/README.md`.
  - **(b) Minor** — `/pro` wasn't actually role-gated to professionals. Fixed.
- **Follow-up pass, same day (2026-08-15)** — aligned this milestone with the backend
  `auth` package's real registration contract:
  - `POST /api/auth/register` is `multipart/form-data`, not flat JSON: a `data` JSON
    part nesting `customer`/`professional` sub-objects, plus optional
    `verificationDocument`/`profilePhoto` file parts for professionals (per
    `AuthController`'s own Javadoc, which calls this "a breaking change from the prior
    flat-JSON contract"). `frontend/src/shared/api/auth.ts` was rewritten to build and
    send this correctly, and `frontend/src/shared/api/httpClient.ts` gained `FormData`
    request-body support.
  - A field-name mismatch between the frontend's address type and the backend's
    `DefaultAddressRequest` (`notes` vs. `addressNotes`) was found and fixed.
  - Nested validation-error paths (e.g. `customer.defaultAddress.city`) are now mapped
    to their leaf field name (`city`) for display, in
    `frontend/src/shared/api/errorMessages.ts`.
  - `GET /api/users/me` is unchanged — `/profile` still cannot show address/photo/
    document. Expected, not a regression.
  - Two barrel files (`frontend/src/shared/api/index.ts`,
    `frontend/src/shared/components/index.ts`) that were still stubs got filled in.
    `frontend/src/shared/hooks/index.ts` was found to still be a stub too — a real gap
    that would have broken the build — and was fixed alongside them.
  - CORS: `pronto.cors.allowed-origins` (env `CORS_ALLOWED_ORIGINS`, default
    `http://localhost:5173`) was added to `application.yml`, wiring up the
    already-present `SecurityConfig` CORS bean, which previously had no backing config
    property.
- **Doc-drift flagged for `pronto-lead`**: `auth/README.md`'s Responsibilities section
  still describes `POST /api/auth/register` as "one flat JSON shape" — stale text
  predating the multipart change above; not corrected in this documentation pass
  (outside the requested scope), noted here for a follow-up edit.
- **Incident note**: a git mistake during this work caused some in-progress files to be
  lost; recovered via IDE Local History plus manual reconstruction of a small number of
  low-risk files. Resolved — does not affect the shipped state described above.
- **Known gaps, not blockers**: same as backend Milestone 1 above (no password-reset
  flow, no resend-verification-code endpoint, no refresh-token/logout-revocation
  mechanism); `/profile` cannot show address/photo/document since `GET /api/users/me`
  doesn't return them.

## Milestone 2 — Issue creation & AI classification

- **Status: COMPLETE (backend), 2026-08-13.** `issues`, `ai`, `storage` packages
  implemented per `docs/architecture/api-contract-issues.md`; QA validated live against a
  real Postgres instance, with one bug found and fixed mid-milestone (a role-check-vs-
  validation ordering bug) — see "QA summary" below. **Frontend `features/issues` screens
  (Home/New Issue, AI Review) are intentionally deferred**, consistent with the rest of
  `frontend/` — not a gap in this milestone's backend completion.
- **Scope**: `issues`, `ai`, `storage` (image upload). Home/New Issue screen, AI
  Review screen with confirm/edit, image upload with the 5s target.
- **Acceptance criteria**: a customer can describe an issue, optionally attach images,
  receive an AI-suggested category, and confirm or override it before proceeding. **Met**,
  backend-only — verified end-to-end (upload → classify → confirm/override → create) against
  a real Postgres instance with the default mock AI classifier and local-disk storage.
- **QA summary**: live-validated against a real Postgres instance — the full happy path
  (upload image → classify → confirm/override category → create issue), the
  ephemeral-classify-vs-persisted-create distinction (held up under ~25 requests to
  `/classify` with zero premature persistence, confirming §2.1's "no DB write" guarantee),
  image ownership enforcement (a forged/mismatched-owner `imageKey` correctly `400`s with
  `IMAGE_KEY_INVALID`; cross-customer retrieval via `GET /api/storage/images/**` correctly
  `403`s), validation/error-code coverage across both packages, mock AI classifier sanity
  (Hebrew keyword-based category matches, `general_handyman` fallback with `confidence:
  null` on no match), local storage round-trip byte-identity (upload → retrieve produces an
  identical byte array), and no regression to Milestone 1 (`/actuator/health`,
  `/api/auth/*`, `/api/users/me` all still work). **One real bug found**: role-check-vs-
  validation ordering meant a professional-role token combined with a malformed request body
  returned `400 VALIDATION_ERROR` instead of the contract-mandated `403 FORBIDDEN` on 3
  endpoints (`POST /api/issues/classify`, `POST /api/issues`, `POST /api/storage/images`) —
  `RoleGuard.requireRole` was being called from inside each controller method body, which
  runs *after* Spring resolves `@Valid`/`@RequestParam` argument binding for the matched
  handler. Fixed by introducing `common.security.RoleRequiredInterceptor` (a
  `HandlerInterceptor` that calls `RoleGuard.requireRole` from `preHandle`, which always
  runs before argument resolution), registered per-package via a new `WebMvcConfigurer`
  (`issues.config.IssuesWebConfig`, `storage.config.StorageWebConfig`) rather than touching
  `auth.config.SecurityConfig` — see `backend/src/main/java/com/pronto/common/README.md`
  and `.../issues/README.md`/`.../storage/README.md` for the full writeup. Re-verified
  green afterward: all repro cases now correctly return `403`, and the rest of the
  milestone's QA suite was re-run with no new regressions.
- **Known gaps, not blockers**: `S3StorageClient` and `OpenAiClassificationClient` are
  implemented and compile but were never live-tested this milestone (no AWS/OpenAI
  credentials available) — both activate purely via config flags
  (`pronto.storage.mode=s3`, `pronto.ai.mode=openai`) once credentials exist, an explicit,
  documented deferral rather than a gap papered over. No `GET /api/issues/{id}` endpoint
  (out of this milestone's scope; a forward dependency for Milestone 3/4's booking flows),
  no rate limiting on `/classify`, no orphaned-upload cleanup job, and the S3
  bucket-privacy policy / AI-suggestion-persistence questions remain genuinely open — see
  `overview.md` §6 and `api-contract-issues.md` §4 for detail.

## Milestone 3 — Standard booking flow

- **Status: COMPLETE (backend), QA-signed-off on branch `MS3`, 2026-08-13.** Not yet
  merged to `main` — merge/push is pending the user's own explicit git operations, not
  implied by this status line. `bookings` (Standard path) and a minimal `availability`
  slice implemented per `docs/architecture/api-contract-bookings.md`, plus one `issues`
  package addition (`GET /api/issues/{id}`); QA validated live against a real Postgres
  instance with zero bugs found — see "QA summary" below. **Frontend booking-flow screens
  (professional listing, slot picker, tracking screen) are intentionally deferred**,
  consistent with the rest of `frontend/` — not a gap in this milestone's backend
  completion.
- **Scope actually built**: two Flyway migrations (`V11__alter_orders_status_add_rejected.sql`,
  a pre-existing gap fix adding the genuine 7th `order_status` value `REJECTED`;
  `V12__add_slot_id_to_orders.sql`, new for this milestone, adds the nullable
  `orders.slot_id` FK used as the sole slot-release lookup mechanism); `GET
  /api/issues/{id}` plus the `IssuesWebConfig` narrowing fix (blanket `/api/issues/**` →
  the two literal `CUSTOMER`-only Milestone 2 paths, leaving the new either-role endpoint
  ungated at the route level); the 8 `bookings` endpoints (`GET
  /api/bookings/professionals`, `GET /api/bookings/professionals/{professionalId}/slots`,
  `POST /api/bookings/orders`, `POST /api/bookings/orders/{orderId}/accept`, `POST
  /api/bookings/orders/{orderId}/reject`, `POST /api/bookings/orders/{orderId}/cancel`,
  `GET /api/bookings/orders/{orderId}`, `GET /api/bookings/orders/me`); the 2
  `availability` endpoints (`POST /api/availability/slots`, `GET
  /api/availability/slots/me`); and the two per-package `WebMvcConfigurer`s
  (`bookings.config.BookingsWebConfig`, with its two precisely-scoped
  `RoleRequiredInterceptor` registrations since this package mixes `CUSTOMER`-only,
  `PROFESSIONAL`-only, and either-role routes in one package;
  `availability.config.AvailabilityWebConfig`, a single blanket-pattern registration since
  both its endpoints share one role). See `backend/src/main/java/com/pronto/bookings/README.md`,
  `.../availability/README.md`, and `.../issues/README.md` for full detail.
- **Acceptance criteria**: full Standard path works end-to-end per PRD §3.4, including the
  reject → return-to-list branch. **Met** — professional listing (filtered by category,
  excluding soft-deleted professionals) → slot selection → order creation (atomic slot
  claim + issue `OPEN → BOOKED` transition) → professional accept/reject → customer
  tracking (`GET /api/bookings/orders/{orderId}`, polling-ready) → either party's cancel
  (state/actor-dependent, per §2.7 of the contract doc) all verified against a real
  Postgres instance, backend-only (no frontend UI this milestone).
- **QA summary**: live-validated against a real Postgres instance — the full happy-path
  Standard flow (professional listing → slot listing → create order → professional
  accept → customer tracking, `orders`/`availability_slots`/`issues` rows all transitioning
  correctly at each step), the reject → return-to-list branch (professional reject →
  slot released → issue reverts to `OPEN` → customer re-lists professionals and re-books,
  including re-booking the same professional who just rejected), cancel edge cases (the
  full actor/state permission matrix from §2.7 of the contract doc: customer cancel from
  `PENDING`/`CONFIRMED`, professional cancel from `CONFIRMED` only, professional attempting
  cancel on a `PENDING` order correctly rejected with `409 ORDER_NOT_CANCELLABLE` since
  `reject` is the only valid action there, cancel on terminal-state orders correctly
  rejected), and ownership/role enforcement on every new endpoint (wrong-role and
  wrong-party callers correctly `403`/`409` across all 8 `bookings` endpoints, the 2
  `availability` endpoints, and `GET /api/issues/{id}`'s dual `CUSTOMER`-ownership /
  `PROFESSIONAL`-order-existence authorization paths). **Zero bugs found**, no regressions
  to Milestone 0-2 (auth, issue creation/classification, image upload all re-verified
  working). Live-validated against a real Postgres instance throughout, not just unit
  tests, matching the rigor of the Milestone 1/2 QA passes.
- **Deferred / explicitly out of scope, per the contract doc**: professional viewing an
  issue's photos before deciding accept/reject remains genuinely unresolved (contract doc
  §6 item 3 / §7) — `GET /api/storage/images/{key}` stays `CUSTOMER`-only in every
  milestone so far, not built or designed here, not blocking (the PRD's Standard flow
  doesn't require photo access before accept/reject). `ON_THE_WAY`/`COMPLETED` job-status
  progression is confirmed Milestone 6 scope (contract doc §6 item 4), not built here —
  this milestone covers request/accept/reject/cancel/track through `CONFIRMED` plus the
  terminal `CANCELLED`/`REJECTED` statuses only. Payment processing and GPS/live location
  tracking remain permanent v1.0 exclusions (not milestone-specific deferrals) — the
  tracking endpoint (§2.8) is status-only, no map/location data.

### Frontend Milestone 3 — Standard booking flow UI

- **Status: COMPLETE, 2026-08-16.** Built on top of backend Milestone 3 (Standard booking
  flow, above) and backend Milestone 8 (Professional Profiles, Reviews, Favorites &
  Matching)'s enriched listing/order DTOs. On branch `frontend/MS3`, local only —
  uncommitted, not pushed/merged; that remains the user's own explicit git action, not
  implied by this status line. Not one of the originally-numbered backend milestones in
  `overview.md` §5; tracked separately as "Frontend Milestone 3" (MS3) since it delivers
  the UI for this same Milestone 3 booking scope, following the naming convention Frontend
  Milestone 1 established.
- **Screens/routes delivered** (see `frontend/src/app/router.tsx`): `/issues/:issueId/booking`
  (`BookingFlowPage`, `CUSTOMER`-only — the step machine: service address →
  professional list → slot picker → confirmation → success), `/orders` (`MyOrdersPage`,
  `CUSTOMER`-only, the caller's own order list), `/orders/:orderId` (`OrderTrackingPage`,
  either role, short-polling status tracking). On the professional side, the old
  `ProPlaceholderPage` was removed and `/pro` now nests a real `ProDashboardLayout` shell
  with three tabs: `/pro` (`IncomingRequestsPage`, short-polls pending requests),
  `/pro/jobs` (`MyJobsPage`, a read-only list of the professional's own orders — added
  post-QA as a bug fix, see below), and `/pro/availability` (`AvailabilityPage`, slot
  create/list).
- **Shared primitives added**: `shared/components/StatusBadge.tsx` (one component mapping
  `OrderStatus` to a Hebrew label + color, used by every screen that displays an order's
  status — tracking, my-orders, incoming-requests, jobs); `shared/hooks/usePolling.ts` +
  `useOrderStatus.ts` (generic short-polling hook and an order-tracking-specific wrapper
  that stops polling once a terminal status is reached, per `overview.md` §3.3);
  `shared/api/bookings.ts` (professional listing, slot listing, order lifecycle) and
  `shared/api/availability.ts` (a professional's own slot create/list), plus a `getIssue`
  addition to the existing `shared/api/issues.ts`.
- **Feature-folder ownership decision**: `features/booking` owns the customer-facing
  Standard flow (`BookingFlowPage`, `MyOrdersPage`) *and* the shared tracking screen
  (`OrderTrackingPage`, since either role can view it); `features/dashboard` owns
  everything professional-only (`IncomingRequestsPage`, `AvailabilityPage`, `MyJobsPage`);
  `features/professionals` owns the shared `ProfessionalCard`/`ProfessionalList`
  components, consumed by `features/booking`'s listing step now and intended for the SOS
  flow's reuse later (per PRD §7.4 — SOS is a filtered reuse of this same component, not a
  separate screen). See each folder's own `README.md` for full detail.
- **API-contract drift found and flagged for `pronto-lead`**: `docs/architecture/api-contract-bookings.md`
  §2.2 (professional listing) and §2.4 (order creation) predate backend Milestone 8, which
  changed several of the underlying DTOs in place — the enriched `ProfessionalCard`
  (profile image, rating/review count, `favorited`, distance/ETA fields), `city`/`street`/
  `houseNumber` becoming *required* (not optional) listing query params, and required
  `serviceCity`/`serviceStreet`/`serviceHouseNumber` fields on order creation — without
  that doc's prose being updated to match. This was discovered during this pass by reading
  the real backend DTO source directly rather than trusting the doc, and `shared/api/bookings.ts`
  was written against the real DTOs (with per-type comments recording each divergence).
  `api-contract-bookings.md` itself still needs a correcting addendum to its §2.2/§2.4 text
  — not done as part of this frontend pass (doc-content edits to that specific file were
  outside this pass's scope), flagged here the same way Frontend Milestone 1 flagged
  `auth/README.md`'s stale registration-contract text.
- **QA summary**: full pass, zero open bugs at final sign-off. Method: live backend
  contract-conformance testing via `curl` against the real built jar and a real Postgres
  instance (verifying request/response shapes and status-transition behavior match what
  the frontend code assumes), plus code-level review — there is no browser-automation tool
  available in this environment, so screen-level interaction wasn't driven through an
  actual browser. One bug-fix round: QA found four minor issues, `pronto-coding` fixed all
  four, QA re-verified each and signed off clean:
  1. A role-unaware back button — `OrderTrackingPage`'s back action always went to the
     `CUSTOMER`-only `/orders`, silently redirecting a professional to `/`. Fixed to check
     the caller's role (`useAuth`) and navigate to `/orders` or `/pro` accordingly.
  2. An unmapped error message — `BookingFlowPage`'s error-message map didn't cover `409
     ISSUE_URGENCY_MISMATCH` (reachable via a manually-edited URL to an SOS issue's
     booking route), falling back to a generic banner. Fixed with an honest Hebrew message.
  3. A shared loading-spinner state — `IncomingRequestCard`'s Accept button showed a
     spinner while Reject was the action actually in flight (a single `isProcessing`
     boolean covered both). Fixed by tracking `{ orderId, action }` and passing separate
     `isAccepting`/`isRejecting` props.
  4. A missing "my accepted jobs" view for professionals — once an order left the pending
     feed (accepted or rejected) there was no in-app way to see it again short of typing
     `/orders/{id}` directly. Fixed by adding `MyJobsPage` at `/pro/jobs`.
- **Known gaps/deferred, not blockers**:
  - SOS booking UI — Frontend Milestone 4 scope, not this pass.
  - Job-status progression UI for professionals (on-the-way / complete action buttons) —
    Frontend Milestone 6 scope, not this pass; `MyJobsPage` is intentionally read-only.
  - Slot edit/delete UI — Frontend Milestone 7 scope, not this pass; `AvailabilityPage`'s
    `SlotList` is read-only, deliberately not stubbed with disabled buttons.
  - Favorites/reviews UI — not in this pass's scope, even though the professional-listing
    API already returns `favorited`/`averageRating`/`reviewCount` (rendered read-only by
    `features/professionals/ProfessionalCard`, no favorite-toggle or review-submission
    interaction built).
  - One trivial, explicitly non-blocking cosmetic item, noted rather than silently left:
    the professional's `OrderTrackingPage` back button still targets `/pro` rather than
    `/pro/jobs`, its actual only entry point (`/pro/jobs` was added post-QA, after this nav
    target was already in place, and re-pointing it wasn't re-litigated as part of the
    fix). QA flagged this as cosmetic and explicitly non-blocking; not fixed this pass.

## Milestone 4 — SOS booking flow

- **Status: COMPLETE (backend), QA-signed-off on branch `MS4`, 2026-08-13.** Not yet
  merged to `main` (nor is `MS3`) — merge/push is pending the user's own explicit git
  operations, not implied by this status line. `bookings` (SOS path) and the
  `availability` SOS-toggle slice implemented per `docs/architecture/api-contract-bookings.md`
  §2.12-2.15 (the same contract doc as Milestone 3, extended in place rather than forked
  into a new file — titled "Milestones 3 & 4" as of this pass); QA validated live against a
  real Postgres instance with one bug found and fixed mid-milestone (a JSON-boolean-
  coercion bug on the new SOS-availability toggle) — see "QA summary" below. **Frontend
  booking-flow screens remain entirely deferred**, consistent with Milestones 1-3 — not a
  gap in this milestone's backend completion.
- **Scope actually built**: two new `bookings` endpoints (`GET
  /api/bookings/sos-professionals?issueId=`, `POST /api/bookings/sos-orders`) plus a fix to
  the three existing Milestone 3 `BookingsService` methods (`listProfessionals`,
  `listSlots`, `createOrder`) adding a step that checks `issue.urgencyType` matches the
  endpoint's booking path (`409 ISSUE_URGENCY_MISMATCH` otherwise) — closing a gap those
  three endpoints shipped with in Milestone 3 (they never validated `urgencyType` at all;
  see the contract doc's §3.10/§6 item 5 for why this was fixed now rather than left open).
  New DTO `bookings/dto/CreateSosOrderRequest.java` (one field fewer than
  `CreateOrderRequest` — no `slotId`, SOS has no slot selection). `ProfessionalListingRepository`
  gained `listSosAvailableByCategory` (joins `professionals`/`users`/`sos_availability`,
  same soft-delete exclusion and `base_price ASC` ordering as the Standard listing query).
  `BookingsWebConfig` gained two new literal path patterns
  (`/api/bookings/sos-professionals`, `/api/bookings/sos-orders`) on its existing
  `CUSTOMER`-scoped `RoleRequiredInterceptor` registration — its literal-list design (chosen
  in Milestone 3 because this package mixes roles per-route) doesn't pick up new routes via
  a wildcard the way `availability`'s config does. In `availability`: two new endpoints
  (`PUT`/`GET /api/availability/sos-availability`), new DTOs
  `availability/dto/SosAvailabilityRequest.java`/`SosAvailabilityResponse.java`,
  `SosAvailabilityRepository` gained an `updateAvailability` unconditional-`UPDATE` method
  (deliberately not the guarded-transition pattern used everywhere else in this doc — see
  `availability/README.md` for why). `AvailabilityWebConfig` needed **no** change — its
  existing blanket `/api/availability/**` pattern already covered the new routes. Two new
  shared error codes in `common.exception.ErrorCode`: `ISSUE_URGENCY_MISMATCH` (409),
  `SOS_PROFESSIONAL_UNAVAILABLE` (409). **No new Flyway migration this milestone** —
  verified directly against the applied migration history: `sos_availability` (`V13`),
  nullable `orders.slot_id`/`booked_end` (`V12`/`V8`), and the 7-value `order_status` `CHECK`
  including `REJECTED` (`V11`) all already existed ahead of this milestone (`V13` was
  applied as a pre-Milestone-4 schema-gap fix, already reflected in `availability/README.md`'s
  prior status line). `accept`/`reject`/`cancel`/`GET .../{orderId}`/`GET .../me`
  (§2.5-§2.9) received **zero code changes** — confirmed by QA to already generalize
  correctly to SOS orders (nullable `slotId`/`bookedEnd` flow through cleanly; the
  unconditional `releaseSlot(order.getSlotId(), now)` call is a safe no-op when `slotId` is
  `null`). See `backend/src/main/java/com/pronto/bookings/README.md` and
  `.../availability/README.md` for full detail.
- **Acceptance criteria**: full SOS path works end-to-end per PRD §3.5, including the
  reject/unavailable → return-to-list-or-no-professional-message branch. **Met** — SOS
  professional listing (filtered by category **and** live `sos_availability.is_available =
  true`, excluding soft-deleted professionals) → order creation against a specific
  currently-available professional (no slot selection; `bookedStart = now()`, `bookedEnd =
  NULL`, `slotId = NULL`) → professional accept/reject (the exact same endpoints Standard
  orders use, unmodified) → customer tracking, all verified against a real Postgres
  instance, backend-only. Both halves of PRD §3.5.6's "rejects or becomes unavailable"
  requirement are implemented: "rejects" maps to the existing `reject` endpoint once an
  order already exists; "becomes unavailable" maps to a `409 SOS_PROFESSIONAL_UNAVAILABLE`
  at order-creation time (a plain read-check of `sos_availability`, not an atomic claim —
  see the contract doc §2.13/§3.11 for the full design reasoning) rather than a reactive
  sweep over already-`PENDING` orders — a deliberate design decision, not an oversight.
- **QA summary**: live-validated against a real Postgres instance (fresh schema, all 13
  migrations applied cleanly) — the full SOS happy path with row-level state verification at
  every step (list SOS-available professionals → professional toggles availability on →
  list again reflects the toggle → customer creates an SOS order → professional accepts →
  customer polls tracking), both branches of PRD §3.5.6 (professional reject via the reused
  Milestone 3 `reject` endpoint; "becomes unavailable" verified as a clean `409
  SOS_PROFESSIONAL_UNAVAILABLE` at order-creation time with no orphaned `orders` rows and no
  incorrect `issues`/`sos_availability` status flips), the no-available-professional case
  (an empty list is a valid `200`, not an error), cross-path `ISSUE_URGENCY_MISMATCH`
  validation in both directions (Standard endpoints correctly reject SOS-flagged issues and
  vice versa, all 5 endpoint combinations: §2.2/§2.3/§2.4 against an SOS issue, §2.12/§2.13
  against a Standard issue), full ownership/role enforcement on all 4 new endpoints, the
  SOS-availability toggle's semantics (idempotent, no state-guard needed, verified against
  both prior states), and a full regression pass confirming **zero breakage** to Milestone
  1-3 — including explicitly confirming `accept`/`reject`/`cancel`/`GET .../{orderId}`/`GET
  .../me` behave identically for SOS vs. Standard orders, since none of those five endpoints
  received any code change this milestone. **One bug found mid-milestone**: a numeric JSON
  value (e.g. `1`/`0`) sent to `PUT /api/availability/sos-availability`'s `isAvailable`
  field was silently coerced to a boolean by Jackson's default lenient scalar coercion,
  rather than being rejected as `400 VALIDATION_ERROR` per the contract doc's explicit field-
  validation rule (§2.14). Fixed via a narrowly-scoped custom Jackson deserializer
  (`availability.dto.StrictBooleanDeserializer`, applied only to
  `SosAvailabilityRequest.isAvailable` via `@JsonDeserialize`) — deliberately **not** a
  global `ObjectMapper` coercion-config change, to avoid any risk of altering Milestone 1-3's
  already-shipped Jackson behavior on other endpoints' `Boolean` fields. Re-verified fixed
  with no side effects to any other endpoint. **Final QA verdict: full sign-off, zero known
  open bugs.**
- **Known gaps/deferred, carried forward accurately from Milestone 3 (checked, still true)**:
  `ON_THE_WAY`/`COMPLETED` job-status progression remains Milestone 6 scope, unchanged by
  this milestone — SOS orders reach the same `CONFIRMED` ceiling Standard orders did in
  Milestone 3. The `PENDING`-order timeout/expiry sweep remains Milestone 5 scope — an SOS
  order stuck `PENDING` because its professional went unavailable mid-request has no faster
  resolution than normal polling (`GET /api/bookings/orders/{orderId}`) until that sweep
  exists; this is the accepted, explicitly-documented consequence of the contract doc's §3.11
  design decision, not a newly-discovered gap. Professional-viewing-issue-images remains open
  and unbuilt (unchanged from Milestone 3, contract doc §6 item 3 / §7). Frontend
  booking-flow screens remain entirely deferred project-wide, consistent with Milestones
  1-3.

### Frontend Milestone 4 — SOS booking flow UI

- **Status: COMPLETE, 2026-08-17.** Built on top of backend Milestone 4 (SOS booking flow,
  above) and Frontend Milestone 3's Standard-flow shared primitives/components (`Button`,
  `Card`, `AddressFormFields`, `features/professionals`'s `ProfessionalList`). On branch
  `frontend/MS4`, local only — uncommitted, not pushed/merged; that remains the user's own
  explicit git action, not implied by this status line. Not one of the originally-numbered
  backend milestones in `overview.md` §5; tracked separately as "Frontend Milestone 4" (MS4)
  since it delivers the UI for this same Milestone 4 SOS-booking scope, following the naming
  convention Frontend Milestone 1/3 established.
- **Screens/routes delivered** (see `frontend/src/app/router.tsx`): `/issues/:issueId/sos-booking`
  (`SosBookingFlowPage`, `CUSTOMER`-only) — a 3-step machine mirroring `BookingFlowPage`'s
  pattern but with no slot-picking step: service address → available-now professional list
  (reusing `features/professionals`'s `ProfessionalList` unmodified, calling `GET
  /api/bookings/sos-professionals` instead of `.../professionals`) → confirmation
  (`SosBookingSummary`, `POST /api/bookings/sos-orders`) → a calm success state. Shows an
  "SOS פעיל" banner (DESIGN_SYSTEM.md §49 exact copy) on every step but the success screen.
  On the professional side, `SosAvailabilityToggle` (new component) is rendered at the top
  of the existing `/pro/availability` (`AvailabilityPage`), above the Standard-slot section
  — not a new dashboard tab. `features/issues/IssueSuccessStep.tsx`'s SOS branch now routes
  into `/issues/${issueId}/sos-booking` instead of showing the old "not available yet" stub
  message. See `frontend/src/features/booking/README.md` and
  `frontend/src/features/dashboard/README.md` for full per-component detail (routes, error
  handling, endpoints) rather than restating it here.
- **`shared/api` additions**: `shared/api/bookings.ts` gained `getSosProfessionalsForIssue`,
  `createSosOrder`, and the `CreateSosOrderRequest` type, reusing the existing
  `ProfessionalCard`/`ProfessionalListingResponse`/`OrderResponse` types verbatim — the SOS
  listing/order response shapes are identical to the already-documented Standard ones.
  `shared/api/availability.ts` gained `getSosAvailability`, `updateSosAvailability`, and the
  `SosAvailabilityResponse` type.
- **Notable design decisions**:
  - **SOS-surcharge frontend placeholder constant**: `SosBookingSummary` shows a price
    breakdown (base price + a flat SOS surcharge = estimated total) before confirmation, per
    DESIGN_SYSTEM.md §49's fee-disclosure requirement. Since no endpoint exposes the
    surcharge value ahead of order creation, the frontend hardcodes its own
    `SOS_SURCHARGE_AMOUNT = 50` constant, explicitly flagged in-code as a placeholder that
    mirrors — and must be kept in sync with — the backend's `BookingsService.SOS_SURCHARGE_AMOUNT`.
    The real, authoritative `finalPrice`/`sosSurcharge` from `OrderResponse` is what's shown
    after order creation (success screen / `/orders/:id`); the frontend constant is only ever
    used for the pre-confirmation estimate.
  - **Toggle lives on `AvailabilityPage`, not a new dashboard tab**: `SosAvailabilityToggle`
    is rendered above the existing Standard-slot section on `/pro/availability` rather than
    getting its own `ProDashboardLayout` tab. Both the toggle and the Standard slot calendar
    are the same `availability` backend domain (`/api/availability/*`), and
    `ProDashboardLayout`'s three tabs deliberately avoid dead/thin nav items (per that page's
    own Frontend Milestone 3 reasoning) — a fourth tab for a single toggle would contradict
    that. No new `Switch` primitive was added to `shared/components`; `SosAvailabilityToggle`
    is a one-off accessible `role="switch"` button, since this is a single-usage control, not
    a generic one.
- **QA summary**: full pass, **PASS**, sign-off 2026-08-17. One trivial defect found and
  fixed: `features/professionals/ProfessionalCard.tsx`'s doc comment was stale, claiming SOS
  reuse of the component was "a later milestone's scope" — corrected to reflect that both the
  Standard and SOS flows now reuse this component via `ProfessionalList`. No functional code
  change. `frontend/src/features/dashboard/IncomingRequestCard.tsx` also got a doc-comment-
  only update (SOS orders are now real/reachable through this frontend) — QA confirmed live
  that the existing `sosTag` rendering and `order.bookedEnd == null` handling needed no
  functional change. QA also live-confirmed (against a real backend) that
  `OrderTrackingPage`, `MyOrdersPage`, `MyJobsPage`, `IncomingRequestsPage`, `useOrderStatus`,
  and `usePolling` all needed zero changes for SOS orders — all are generic by
  `orderId`/`GET .../me` and already handle `bookedEnd: null` correctly (conditional
  rendering, no `Invalid Date`/`NaN`).
  - **One non-blocking judgment-call note, recorded rather than silently dropped**: on the
    SOS paths, `CATEGORY_MISMATCH`/`404`/`403` fall back to the generic error message rather
    than an SOS-specific one — intentionally consistent with how the already-shipped Standard
    `BookingSummary.tsx` treats the same defensive-only, not-normally-reachable cases. Not
    treated as a gap; flagged as a possible future follow-up if `pronto-lead` ever wants both
    flows upgraded together.
- **Known gaps/deferred, not blockers**:
  - Job-status progression UI for professionals (on-the-way / complete action buttons) —
    Frontend Milestone 6 scope, not this pass; `MyJobsPage` remains intentionally read-only.
  - Slot edit/delete UI — Frontend Milestone 7 scope, not this pass.
  - Favorites/reviews UI — not in this pass's scope, unchanged from Frontend Milestone 3.
  - The one cosmetic nit noted in Frontend Milestone 3 (the professional's
    `OrderTrackingPage` back button targets `/pro` rather than `/pro/jobs`) is unchanged by
    this pass — not re-litigated here, still explicitly non-blocking.

## Milestone 5 — Notifications & real-time status

- **Status: COMPLETE (backend), QA-signed-off on branch `MS5`, 2026-08-13.** Not yet merged
  to `main` (nor are `MS3`/`MS4`) — merge/push is pending the user's own explicit git
  operations, not implied by this status line. `notifications` package implemented per
  `docs/architecture/api-contract-notifications.md`, plus the specific hook points that doc
  required adding to the already-shipped `bookings` package; QA validated live against a
  real Postgres instance with one bug found and fixed — during the lead's own review of the
  coding agent's output, *before* QA's formal pass ran — see "QA summary" below.
  **Frontend notification-bell/tracking screens remain entirely deferred**, consistent with
  Milestones 1-4 — not a gap in this milestone's backend completion.
- **Scope actually built**: one Flyway migration
  (`V14__alter_notifications_message_type_add_rejected.sql`, a pre-existing schema-gap fix —
  same category as `V11`/`V13` — adding the missing `'ORDER_REJECTED'` value to
  `notifications.message_type`'s `CHECK`, which `V9` never actually included despite
  `data-model.md` §2.10 already documenting it as present); the 3 `notifications` endpoints
  (`GET /api/notifications`, `POST /api/notifications/{id}/read`, `POST
  /api/notifications/read-all`); `NotificationService`/`NotificationServiceImpl`
  (`recordOrderNotification`, the `bookings → notifications` call boundary, §4.1); two
  `@Scheduled` jobs (`EmailDispatchJob`, 20s, dispatches the `EMAIL`-channel `PENDING` queue
  via a new `EmailSender.sendOrderStatusEmail` method; `OrderExpirySweepJob`, 60s, sweeps
  `PENDING` orders past their per-urgency-type timeout to `EXPIRED`); `config.SchedulingConfig`
  (`@EnableScheduling`, localized to this package). In the already-shipped `bookings`
  package: a new required `NotificationService` constructor dependency on `BookingsService`;
  five new `recordOrderNotification` call sites (`createOrder`/`createSosOrder`/`accept`/
  `reject`/`cancel`); two new `BookingsService` methods (`findExpiredOrderCandidateIds`,
  `expireIfPending`) and two new `OrderRepository` methods (`findPendingExpiryCandidateIds`,
  `expireIfPending`) backing the sweep; two new hardcoded timeout constants
  (`STANDARD_PENDING_TIMEOUT = 15 min`, `SOS_PENDING_TIMEOUT = 5 min` — **decided**, the
  user's own sign-off at this milestone's kickoff, no longer a pending recommendation). One
  new `issues.repository.IssueRepository` method (`expireIfBooked`, mirroring the existing
  `bookIfOpen`/`revertToOpen` guarded-transition pattern). `auth.email.EmailSender` extended
  with a second method, `sendOrderStatusEmail` (decided: extend the existing interface
  rather than introduce a second, `notifications`-owned one — `LoggingEmailSender` remains
  the sole implementation, logs at `INFO`, sends nothing real). New `application.yml` config
  block, `pronto.email.mode` (default `log`; `smtp` not built this milestone — no source
  document names a real email provider, unlike MS2's OpenAI/S3). **No new `ErrorCode`
  values** — audited against every behavior branch in the contract doc; "not found"/"not
  yours" reuse the existing `NOT_FOUND`/`FORBIDDEN` codes. See
  `backend/src/main/java/com/pronto/notifications/README.md` and
  `.../bookings/README.md`'s new Milestone 5 additions for full detail.
- **Acceptance criteria**: booking status changes reach the relevant customer/professional
  within the PRD's ~1s target; tracking screen updates without a manual refresh. **Met, with
  a distinction worth stating explicitly** (per `api-contract-notifications.md` §4.5): the
  PRD's ~1s target governs *propagation* latency — how fast an **already-changed** row
  reaches a client already polling `GET /api/bookings/orders/{orderId}`/`GET
  /api/notifications` every 3-5s, per the existing short-polling design (unchanged this
  milestone). It says nothing about *detection* latency — how fast the backend notices a
  `PENDING` order should expire in the first place. The 60s `OrderExpirySweepJob` interval
  governs detection latency only, for the one new status transition this milestone
  introduces (`PENDING → EXPIRED`); once the sweep *does* transition an order, the existing
  3-5s polling mechanism picks it up with the PRD's normal ~1s-class propagation
  characteristics, unaffected by the sweep's own 60s cadence. Worst case, an order sits up to
  60s past its 15-/5-minute timeout before the sweep acts — a <7% overshoot on the tightest
  (5-minute SOS) case, judged negligible and not a violation of the ~1s target, which was
  never about this kind of latency to begin with.
- **QA summary** (method: live validation against a real Postgres instance — docker-compose
  `pronto-postgres`, migrated through `V13` from prior sessions, built via `mvnd clean
  package`, booted the jar, drove every scenario through the real HTTP API with `curl`,
  verified state via direct `psql` queries at every step, not just HTTP status codes):
  - **Migration (`V14`)**: confirmed pre-state lacked `ORDER_REJECTED` in the `CHECK`
    constraint, confirmed Flyway applied `V14` cleanly (schema version 14), confirmed the
    `CHECK` now includes it, functionally confirmed via a live `reject` call.
  - **Notification creation on every transition**: exercised Standard
    (`createOrder`→accept, →reject, →accept→cancel-by-customer,
    →accept→cancel-by-professional) and SOS (`createSosOrder`→accept→cancel-by-customer)
    paths against real orders, verified via direct DB query: `ORDER_CREATED`→professional's
    `user_id` (both Standard and SOS), `ORDER_CONFIRMED`/`ORDER_REJECTED`→customer,
    `ORDER_CANCELLED`→the *non-acting* party (professional when customer cancels, customer
    when professional cancels). Confirmed the reverse recipient never fires. Zero bugs.
  - **In-app feed endpoints**: `GET /api/notifications` (`IN_APP`-only, correct ordering,
    correct `unreadCount`), `unreadOnly=true` filter, `POST /{id}/read` (idempotent —
    verified via DB that `read_at` doesn't change on a second call), `403` on someone else's
    notification, `404` on a nonexistent id, `404` (not `500`) on a malformed id (the fixed
    bug, below), `POST /read-all` (correct count, doesn't touch other users' rows). Zero
    bugs.
  - **Email dispatch job**: all `PENDING` `EMAIL` rows transitioned to `SENT` with `sent_at`
    populated within ~15-20s (consistent with the 20s `fixedDelay`), `[MOCK EMAIL]` log
    lines confirmed with correct recipient/subject/body content. End-of-session state: 22
    `EMAIL` rows, all `SENT`, zero stuck in `PENDING`/`FAILED`. Pass.
  - **Expiry sweep (highest-risk item)**: backdated real `orders.created_at` via direct SQL
    to simulate timeout, waited for the real 60s `@Scheduled` sweep. Verified precisely at
    both boundaries: Standard backdated 16min → `EXPIRED`; Standard backdated 10min → stays
    `PENDING` (not over-eager); SOS backdated 6min → `EXPIRED`; SOS backdated 3min → stays
    `PENDING`. Full side-effect verification on both expired orders: `orders.order_status =
    'EXPIRED'` + `updated_at` bumped, `issues.status = 'EXPIRED'`, Standard slot released
    (`availability_slots.is_available` back to `true`), SOS's `slot_id` correctly `NULL`
    (safe no-op), `ORDER_EXPIRED` notification for the customer only (both channels) with
    **zero** such rows for the professional (customer-only design holds). "Lost the race"
    case verified: an order backdated past timeout but accepted via the real API *before*
    the sweep ran stayed `CONFIRMED`, not flipped, no exception. Also incidentally observed
    the sweep correctly auto-fire on genuinely stale `PENDING` orders left over from the MS4
    QA session at app startup. Zero bugs — the strongest-verified part of the milestone.
  - **Full regression pass (MS0-4)**: `/actuator/health`, `/api/users/me`, issue creation +
    mock AI classification, image upload round-trip, Standard professional/slot listing
    (soft-delete exclusion still correct), SOS availability toggle/listing, error-code
    taxonomy spot-checks (`409 ORDER_NOT_PENDING`, `409 ISSUE_NOT_BOOKABLE`) all still
    correct. Zero regressions. (This regression pass also reconfirmed the
    `EXPIRED`-issue-can't-be-rebooked gap live — not a new finding, restates the
    already-flagged `data-model.md` §4 item, see "Known gaps" below.)
  - **One bug found**, caught during the lead's own review of the coding agent's output
    *before* this formal QA pass ran (so QA's own pass itself found zero new defects, but the
    bug is recorded here for the same reason MS2's role-check-ordering bug and MS4's
    JSON-boolean-coercion bug are — a real bug caught and fixed, not swept under the rug):
    `NotificationController`'s `{id}` path-variable handling initially used a typed
    `@PathVariable Long`, with an incorrect Javadoc claim that Spring's default
    type-mismatch handling would produce a `4xx` on a malformed value. In fact this would
    have fallen through to `GlobalExceptionHandler`'s generic catch-all and returned `500
    INTERNAL_ERROR`, not a `4xx`. Fixed to manually parse the id, matching
    `IssuesController`'s established convention, guaranteeing `404 NOT_FOUND` on a malformed
    value. QA live-verified the fix: `POST /api/notifications/abc/read` → `404`, not `500`.
  - **Everything else matched the contract doc precisely** — QA's report states this
    explicitly for the entity, repository, service, both scheduler jobs, the `V14`
    migration, the `EmailSender` extension, the `application.yml` config block, and the
    `bookings`/`issues` repository additions, and the trigger→recipient mapping.
  - **Final verdict: full sign-off, zero known open bugs.** The one bug found (the
    malformed-id `500`-vs-`404` issue) was caught and fixed before this formal QA pass ran;
    QA's own pass itself found zero new defects.
- **Known gaps, not blockers**:
  - **The `EXPIRED`-issue-cannot-be-rebooked gap** (`docs/architecture/data-model.md` §4,
    `docs/architecture/api-contract-notifications.md` §7): an issue that reaches `EXPIRED`
    has no endpoint that reopens it to `OPEN`, so `createOrder`/`createSosOrder`'s
    `issue.status == 'OPEN'` requirement makes an `EXPIRED` issue a practical dead end via
    the existing booking endpoints (the customer's only workaround is creating an entirely
    new `issues` row for the same problem). Reconfirmed as still-accurate and non-blocking
    during QA's live regression pass this milestone (not a new finding). **Resolved,
    Milestone 7 (2026-08-15)**: the user has ruled this workaround is the permanent,
    intended design — no reopen endpoint, no relaxed booking guard, ever. See
    `hardening-plan.md` §4.1 and this file's Milestone 7 entry below.
  - No retry/backoff for `FAILED` email rows — `EmailDispatchJob` marks a row `FAILED` on any
    exception and moves on; nothing retries it. Accepted MVP gap, an M7 hardening candidate
    if email reliability becomes a concern once a real provider exists.
  - No multi-instance email-dispatch atomic "claim" step — `EmailDispatchJob` has no per-row
    claim before sending; two concurrent instances could double-send. Accepted MVP gap under
    the current single-instance deployment assumption (`overview.md` §6); would need an
    interim `delivery_status` value (a `V15` `CHECK` addition) before safe under horizontal
    scaling.
  - `ON_THE_WAY`/`COMPLETED` notification hooks are not wired — no `BookingsService` method
    producing those statuses exists yet (Milestone 6's job); adding a hook now would be dead
    code. Milestone 6 adds exactly one `recordOrderNotification(...)` call per new transition
    method, following the identical pattern this milestone established.
  - `auth`'s `EMAIL_VERIFICATION` message type is not retrofitted into this table this
    milestone — an in-app notification for an event that occurs before the user has a usable
    session has no real use case; `EMAIL_VERIFICATION` stays in the schema/enum for
    completeness and any future retrofit, but nothing writes it yet.
  - No SMS/push channels (unchanged, out of scope per `overview.md` §2). No rate limiting on
    any endpoint in this doc (low risk, noted only for completeness). Exact notification
    copy/Hebrew localization remains genuinely open, needs product/UX input (§7 of the
    contract doc).

### Frontend Milestone 5 — In-app notification bell

- **Status: COMPLETE, QA-signed-off (PASS, no bugs found), 2026-08-18.** Built on top of
  backend Milestone 5 (Notifications & real-time status, above), which shipped complete and
  untouched this round — no backend changes were made or needed. On branch `frontend/MS5`,
  local only — uncommitted, not pushed/merged; that remains the user's own explicit git
  action, not implied by this status line. Not one of the originally-numbered backend
  milestones in `overview.md` §5; tracked separately as "Frontend Milestone 5" (MS5) since it
  delivers the UI for this same Milestone 5 notifications scope, following the naming
  convention Frontend Milestone 1/3/4 established.
- **What was built**: `shared/api/notifications.ts` (new) — `NotificationMessageType` (an
  8-value string union mirroring the backend's `notifications.entity.NotificationMessageType`
  enum verbatim), `NotificationResponse`/`NotificationsListResponse`/`MarkAllReadResponse`
  types, and `getNotifications(unreadOnly?)`/`markNotificationRead(id)`/
  `markAllNotificationsRead()` wrapping `GET /api/notifications`, `POST
  /api/notifications/{id}/read`, `POST /api/notifications/read-all` — shapes verified directly
  against the real backend DTOs (`notifications.dto.{NotificationResponse,
  NotificationsListResponse,ReadAllResponse}`), not copied from prose. `shared/hooks/
  useNotifications.ts` (new) — a polling wrapper around `usePolling` (default 4s interval, no
  custom interval passed), deliberately a **plain hook, not a React Context**: unlike
  `useActiveOrder`/`useBookingDraft`, it has exactly one consumer
  (`features/notifications/NotificationBell.tsx`), so there's no cross-page state to
  coordinate. Exposes `{ notifications, unreadCount, isLoading, markAsRead, markAllAsRead }`,
  with `markAsRead`/`markAllAsRead` updating local state optimistically and firing their `POST`
  in the background (not awaited, no forced `refetch()` afterward — a failed request just
  self-corrects on the next poll tick, no error toast, a deliberately low-stakes design).
  `unreadCount` is derived client-side from `notifications`'s `readAt` values rather than
  passed through from the poll response's own `unreadCount` field — equivalent in practice
  (the feed is always unfiltered), but means the badge updates instantly on an optimistic
  mark-read with no second piece of state to keep in sync by hand. New feature folder
  `features/notifications/` — `NotificationBell.tsx` (the bell button + numeric badge, capped
  at `"9+"` above 9 unread, plus the anchored dropdown panel — presentation only, all
  data-fetching/polling/optimistic state lives in the hook), `NotificationBell.module.css`,
  `notificationLabels.ts` (Hebrew label lookup for all 8 backend `messageType` values, with an
  explicit `??` fallback for any value not in the map rather than a bare index that could
  silently render `undefined`), and `index.ts`. `app/AppLayout.tsx` now renders
  `<NotificationBell />` in the nav, right after `<BookingDraftIndicator />` and before the
  role-conditional `/orders`/`/pro` link — for **both** roles (CUSTOMER and PROFESSIONAL),
  unlike `ActiveOrderIndicator`, which is CUSTOMER-only, since `GET /api/notifications` is an
  either-role, self-scoped feed with no route-level role gate. No `ProDashboardLayout` change
  was needed or made: it only renders its own `/pro/*` sub-tabs and an `<Outlet />`, and is
  itself nested inside `AppLayout`'s top-level route tree, so `AppLayout`'s nav — and the bell
  inside it — is already present above every `/pro/*` screen. Barrel-export updates:
  `shared/api/index.ts`, `shared/hooks/index.ts`, `features/notifications/index.ts`.
- **Notable design decisions**:
  - **No dedicated notification page/route.** The backend feed has no pagination (§3.1 of the
    contract doc), so a lightweight anchored popover — not a full screen — is sufficient. The
    panel opens/closes on bell click, closes on a `mousedown` outside the panel (listener
    scoped to while open), and closes on row click (which also marks that row read and
    navigates to `/orders/{relatedOrderId}`, an either-role route since Frontend Milestone 3).
  - **All 8 backend `messageType` enum values are mapped to a Hebrew label, even though only
    5 are reachable today**: `ORDER_CREATED`/`ORDER_CONFIRMED`/`ORDER_REJECTED`/
    `ORDER_CANCELLED`/`ORDER_EXPIRED` are live; `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` are
    Milestone 6 scope (no `BookingsService` caller wires them yet, consistent with backend
    Milestone 5's own "Known gaps" note above) and `EMAIL_VERIFICATION` is never written to an
    `IN_APP` row (an event that occurs before the user has a usable session). Mapping the full
    enum now, rather than only the reachable subset, means a future Milestone 6 doesn't need a
    frontend label-mapping change to render `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` rows
    correctly — and the explicit fallback (`getMessageTypeLabel`) guarantees a row with an
    unmapped value still renders instead of crashing.
  - **Optimistic, fire-and-forget mark-read/mark-all-read**, matching the same "low-stakes
    action, self-corrects on next poll" philosophy already established by
    `ActiveOrderProvider`'s `acknowledgeOrder` and `useBookingDraft`'s draft mutations
    elsewhere in this codebase — no new pattern introduced.
- **QA summary**: mixed-method, per this environment's established constraint (no
  browser-automation tool available, consistent with every prior frontend milestone's own QA
  note). RTL dropdown positioning (the panel's `inset-inline-end: 0` anchoring under the
  app's `dir="rtl"` layout) and both-role nav rendering (bell present in the nav for both a
  CUSTOMER and a PROFESSIONAL session, unlike the CUSTOMER-only `ActiveOrderIndicator`) were
  verified via code review. The notification-trigger→recipient mappings, mark-read/
  mark-all-read persistence, the empty state, the badge-cap ("9+") data logic, and the
  `ORDER_EXPIRED` sweep path were all live-verified against a real running backend + Postgres
  instance. **Final verdict: PASS, no bugs found.**
  - **Environment note, non-blocking, unrelated to this milestone's code**: QA's session hit a
    pre-existing local-environment issue worth recording so a future session doesn't lose time
    rediscovering it — a native Windows PostgreSQL service running on this machine shadows the
    project's own `docker-compose.yml` Postgres container on port 5432 (both bind the same
    port; whichever started first wins the port, and it's easy to end up validating against
    the wrong database without realizing it). Not a code defect, not specific to notifications
    or this milestone — purely a local dev-environment quirk on this particular machine.
- **Known gaps/deferred, not blockers**:
  - `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` rows won't appear until Milestone 6 wires those
    `BookingsService` transitions to `recordOrderNotification(...)` — the frontend label
    mapping is already in place and needs no further change when that lands.
  - No dedicated notification page or pagination — deliberate, matches the backend's
    no-pagination design; would need to be revisited together if the backend ever adds
    pagination to `GET /api/notifications`.
  - Email-channel notifications (already backend-complete since backend Milestone 5) have no
    frontend surface by design — they're delivered to the user's actual email inbox, not
    rendered anywhere in-app; the bell/panel is in-app (`IN_APP` channel) only, per §3.1 of the
    contract doc.

## Milestone 6 — Professional dashboard

- **Status: COMPLETE (backend), QA-signed-off on branch `MS6`, 2026-08-13.** Not yet merged
  to `main` (nor are `MS3`/`MS4`/`MS5`) — merge/push is pending the user's own explicit git
  operations, not implied by this status line. Job-status progression endpoints
  (`ON_THE_WAY`/`COMPLETED`) implemented in the `bookings` package per
  `docs/architecture/api-contract-bookings.md` §2.16-2.17 (the same contract doc as
  Milestones 3 & 4, extended in place rather than forked into a new file — titled
  "Milestones 3, 4 & 6" as of this pass); the doc's §8 separately reviews and confirms the
  other two acceptance-criterion pieces ("manage availability," "see incoming requests")
  need no new endpoint at all, since the existing Milestone 3/4 surface already covers them.
  QA validated live against a real Postgres instance with **zero bugs found** — see "QA
  summary" below. **Frontend professional-dashboard screens remain entirely deferred**,
  consistent with Milestones 1-5 — not a gap in this milestone's backend completion.
- **Scope actually built**: two new `bookings` endpoints (`POST
  /api/bookings/orders/{orderId}/on-the-way`, `CONFIRMED → ON_THE_WAY`, professional-only;
  `POST /api/bookings/orders/{orderId}/complete`, `ON_THE_WAY → COMPLETED`,
  professional-only). Both follow the exact same shape as the existing `accept`/`reject`
  endpoints: same ownership check (`ProfessionalRepository.findByUserId`), a single guarded
  `UPDATE ... WHERE order_status = <expected>` transition
  (`OrderRepository.onTheWayIfConfirmed`/`completeIfOnTheWay`, new this milestone), and a
  trailing `notificationService.recordOrderNotification(...)` call to the **customer**
  (`ORDER_ON_THE_WAY`/`ORDER_COMPLETED` respectively) — reusing the exact notification
  mechanism Milestone 5 established, no new call boundary. `complete` additionally
  transitions the issue via a new `IssueRepository.completeIfBooked` method
  (`UPDATE issues SET status = 'COMPLETED' ... WHERE status = 'BOOKED'`), mirroring
  `expireIfBooked`'s exact shape and, like `expireIfPending`'s call to it, not checked for a
  `0`-row result — the single-active-order-per-issue invariant (`data-model.md` §3 item 8,
  §3.3 of the contract doc) guarantees this always affects exactly 1 row when reached. Two
  new `common.exception.ErrorCode` values, both `409`: `ORDER_NOT_CONFIRMED` (`on-the-way`
  called on a non-`CONFIRMED` order) and `ORDER_NOT_ON_THE_WAY` (`complete` called on a
  non-`ON_THE_WAY` order — this is also the code returned for the deliberately-disallowed
  `CONFIRMED → COMPLETED` skip-ahead attempt, no separate code distinguishes "you skipped a
  step" from "wrong state for any other reason"). `BookingsWebConfig`'s existing
  `PROFESSIONAL`-scoped `RoleRequiredInterceptor` registration gained two more literal path
  patterns (`/api/bookings/orders/*/on-the-way`, `/api/bookings/orders/*/complete`) — its
  literal-list design (chosen in Milestone 3 because this package mixes roles per-route)
  doesn't pick up new routes automatically the way a wildcard would. **No `availability`
  package changes** — confirmed and reasoned explicitly in the contract doc §8, not silently
  skipped (see "Known gaps" below for the "no slot edit/delete" decision). **No new Flyway
  migration** — verified directly against `backend/src/main/resources/db/migration/`, which
  contains exactly `V1`-`V14`: `orders.order_status`'s `CHECK` has allowed `ON_THE_WAY`/
  `COMPLETED` since the original `V8__create_orders.sql`, `notifications.message_type`'s
  `CHECK` (as amended by `V14`) has allowed `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` since the
  original `V9__create_notifications.sql`, and `issues.status`'s `CHECK` has allowed
  `COMPLETED` since the original `V6__create_issues.sql` — this milestone is the first to
  *reach* these already-tolerated values via a real endpoint, not the first to need the
  schema to allow them. **No new DTO** — `OrderResponse`/`OrderStatus` already carried
  `ON_THE_WAY`/`COMPLETED` as unused enum values. See
  `backend/src/main/java/com/pronto/bookings/README.md` for full detail.
- **Acceptance criteria**: "a professional can manage availability, see incoming requests,
  and progress a job through its statuses" — **all three met**, but only the third required
  new backend work:
  - **Manage availability**: already satisfied by the existing Milestone 3/4 surface
    (`POST`/`GET /api/availability/slots`+`/me`, `PUT`/`GET
    /api/availability/sos-availability`) — reviewed explicitly this milestone
    (`api-contract-bookings.md` §8.1/§8.2) and confirmed sufficient; slot edit/delete was
    explicitly considered and explicitly declined (see "Known gaps" below), not silently
    left out.
  - **See incoming requests**: already satisfied by `GET
    /api/bookings/orders/me?status=PENDING` (§2.9, Milestone 3) — confirmed this milestone
    (§8.1/§8.3) to already be exactly the right query shape, generic across Standard and SOS
    orders.
  - **Progress a job through its statuses**: the one genuinely new piece — `accept`/`reject`
    (Milestone 3) plus `on-the-way`/`complete` (this milestone) now cover the full
    `PENDING → CONFIRMED → ON_THE_WAY → COMPLETED` sequence end-to-end, with `cancel`
    remaining reachable from `PENDING`/`CONFIRMED`/`ON_THE_WAY` throughout, per PRD §3.6.1's
    named status sequence. **Met** — verified against a real Postgres instance, backend-only
    (no dashboard UI this milestone).
- **QA summary**: live-validated against a real Postgres instance (method: live HTTP + direct
  `psql` verification at every step, not just status codes) —
  - **Happy path, both booking types**: drove a Standard order and an SOS order each all the
    way from creation through `accept` → `on-the-way` → `complete`, verifying at every step
    via direct SQL: `orders.order_status`/`updated_at` transitions correctly at each hop,
    `issues.status` stays `BOOKED` through `CONFIRMED`/`ON_THE_WAY` and only flips to
    `COMPLETED` on the final `complete` call (never earlier), and the correct `OrderResponse`
    shape/values at each step.
  - **Guard-violation / skip-ahead cases**: `on-the-way` called against a `PENDING`,
    `ON_THE_WAY` (already progressed), `COMPLETED`, `CANCELLED`, and `REJECTED` order each
    correctly returned `409 ORDER_NOT_CONFIRMED`. `complete` called against a `PENDING` order
    and — the deliberately-disallowed skip-ahead case — a still-`CONFIRMED` order (never
    routed through `on-the-way`) each correctly returned `409 ORDER_NOT_ON_THE_WAY`,
    confirming `ON_THE_WAY` is enforced as a genuine mandatory intermediate step by the code,
    not just documented as one in the contract doc. `complete` against an already-`COMPLETED`
    order and a `CANCELLED` order also correctly `409`'d.
  - **Actor/role enforcement**: a customer calling either `on-the-way` or `complete`
    correctly `403`'d (route-level `RoleRequiredInterceptor` rejection); a professional who
    is not the order's own assigned professional calling either endpoint correctly `403`'d
    (service-layer ownership check) on both a Standard and an SOS order.
  - **`cancel` non-regression from `ON_THE_WAY`**: confirmed `cancel` (§2.7, unmodified this
    milestone) still works correctly from `ON_THE_WAY` for both actors (customer and
    professional), with no change to its existing `PENDING`/`CONFIRMED` behavior — the actor/
    state permission matrix from Milestone 3 held unchanged.
  - **Notification targeting**: direct `psql` verification that `ORDER_ON_THE_WAY`/
    `ORDER_COMPLETED` notification rows were created for the customer only, on both a
    Standard and an SOS order — zero such rows were ever created for the professional across
    the entire session, confirming the "acting party doesn't need telling about their own
    action" design holds for these two new transitions exactly as it already did for
    `accept`/`reject`/`cancel`.
  - **Full regression pass, Milestones 0-5**: `/actuator/health`, auth, issue creation +
    classification, image upload, Standard/SOS professional and slot listing, the SOS
    availability toggle, and — the highest-risk item — the `PENDING`-order expiry sweep
    (Milestone 5), re-verified to correctly leave `CONFIRMED`/`ON_THE_WAY`/`COMPLETED` orders
    alone (the sweep only ever targets `PENDING` orders, and this milestone introduced no
    code path that could move an order back into `PENDING`). All still correct, zero
    regressions found.
  - **Final verdict: zero bugs found, full sign-off.**
- **Known gaps, not blockers**:
  - **The `EXPIRED`-issue-cannot-be-rebooked gap** (`docs/architecture/data-model.md` §4,
    `docs/architecture/api-contract-notifications.md` §7) is **confirmed unaffected by this
    milestone** — `on-the-way`/`complete` only ever read/write orders that are already
    `CONFIRMED`/`ON_THE_WAY`, never `OPEN`/`EXPIRED` issues, so neither new endpoint adds,
    removes, or narrows this gap in any way (verified explicitly in the contract doc §9, not
    just asserted). **Resolved, Milestone 7 (2026-08-15)**: the user has ruled this is
    intentional, permanent design — `EXPIRED` stays a final `issues.status` state forever,
    no reopen endpoint, no relaxed booking guard. See `hardening-plan.md` §4.1 and this
    file's Milestone 7 entry below.
  - **No slot edit/delete added to `availability`** — restated explicitly as a **judgment
    call, not a gap**: reviewed this milestone (`api-contract-bookings.md` §8.2) against the
    "manage availability" acceptance criterion and PRD text, and deliberately not built (no
    PRD text mandates it, no load-bearing functional gap exists without it, and frontend is
    out of scope project-wide anyway). **Superseded, Milestone 7 (2026-08-15)**: this call
    was reversed by explicit user product decision — `PUT`/`DELETE
    /api/availability/slots/{slotId}` are now designed, implemented, and QA-passed. See
    `api-contract-bookings.md` §8.2 and this file's Milestone 7 entry below.
  - **Frontend professional-dashboard UI remains entirely deferred project-wide**, consistent
    with every prior milestone — not built or designed here, pending the user's design-system
    decision.
  - Professional-viewing-issue-images (contract doc §6 item 3 / §7) remains open and unbuilt,
    unchanged from every prior milestone.

### Frontend Milestone 6 — Professional job-status progression actions

- **Status: COMPLETE, QA-passed, 2026-08-18.** Built on top of backend Milestone 6
  (Professional dashboard / job-status progression endpoints, above), which shipped complete
  and untouched this round — no backend changes this round. On branch `frontend/MS6`, local
  only — uncommitted, not pushed/merged; that remains the user's own explicit git action, not
  implied by this status line. Not one of the originally-numbered backend milestones in
  `overview.md` §5; tracked separately as "Frontend Milestone 6" (MS6) since it delivers the
  UI for this same Milestone 6 job-status-progression scope, following the naming convention
  Frontend Milestone 1/3/4/5 established. Full design record:
  `docs/architecture/professional-status-progression-actions.md`.
- **What was built**: two new professional-side job-status-progression actions on the
  existing shared order-tracking screen, `frontend/src/features/booking/OrderTrackingPage.tsx`
  — no new screen/route. `shared/api/bookings.ts` (+ `index.ts` barrel) gained
  `markOnTheWay(orderId)` (`POST /api/bookings/orders/{orderId}/on-the-way`) and
  `completeOrder(orderId)` (`POST .../complete`), both `Promise<OrderResponse>`,
  PROFESSIONAL-only per backend enforcement, named `<verb>Order`-style to match the file's
  existing `acceptOrder`/`rejectOrder`/`cancelOrder` convention. `OrderTrackingPage.tsx`
  gained `canMarkOnTheWay` (PROFESSIONAL + order `CONFIRMED`) and `canComplete`
  (PROFESSIONAL + order `ON_THE_WAY`) derived booleans, mirroring the existing `canCancel`
  pattern; two new full-width, primary-variant, immediate-fire (no confirmation dialog)
  buttons with Hebrew copy `יציאה לדרך` ("mark on the way") and `סיום העבודה` ("mark
  complete"), rendered in the same conditional slot as the existing customer-only cancel
  button — `canCancel`/`canMarkOnTheWay`/`canComplete` are mutually exclusive by
  construction, so at most one of the three buttons ever renders. `CANCEL_ERROR_MESSAGES`
  was renamed to `ORDER_ACTION_ERROR_MESSAGES` and extended with `ORDER_NOT_CONFIRMED`/
  `ORDER_NOT_ON_THE_WAY` Hebrew messages for the backend's 409 codes on these two
  transitions. New `isUpdatingStatus`/`statusActionError` state, and
  `handleMarkOnTheWay`/`handleComplete` handlers mirroring the existing `handleCancel`
  shape exactly. `frontend/src/features/dashboard/MyJobsPage.tsx` got a doc-comment-only
  fix — removed the stale "read-only by design, no on-the-way/complete actions" claim
  (no longer true; those actions exist now, they just live on `OrderTrackingPage`, not
  this list) — no behavioral change to the component. **No backend changes** — both
  endpoints already existed and were verified directly against
  `BookingsController.java`/`BookingsService.java` source, matching
  `docs/architecture/api-contract-bookings.md` §2.16/§2.17.
- **Reused vs. added**: reused `OrderResponse` (already carried `ON_THE_WAY`/`COMPLETED`
  as reachable `OrderStatus` values as of Frontend Milestone 3), the existing `Button`
  component's `primary`/`fullWidth` props, and `useOrderStatus`'s existing polling/refetch
  mechanism (`TERMINAL_STATUSES` already excludes `CONFIRMED`/`ON_THE_WAY`, so no change was
  needed there for polling to continue correctly through both new transitions). Added: the
  two `shared/api/bookings.ts` functions, the two buttons/handlers/derived booleans on
  `OrderTrackingPage.tsx`, the renamed/extended error-message map, and the two new state
  hooks — no new component, no new route, no new shared primitive.
- **QA summary**: passed. Verification was done at two levels, kept explicitly distinct
  rather than blurred into a single "fully verified end-to-end in the browser" claim:
  - **Live API-level verification**: the real backend was run against a real Postgres DB,
    and QA drove the exact HTTP calls the two new buttons make, through a full two-user
    (customer + professional) order lifecycle: register → verify → login → create
    issue/slot/order → accept → on-the-way → complete. Confirmed real, non-mock
    `expectedArrivalAt` persistence, correct 409s on repeat/out-of-order calls
    (`ORDER_NOT_CONFIRMED`, `ORDER_NOT_ON_THE_WAY`), 403 when a customer attempts either
    endpoint, and that `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` notifications appear correctly
    for the customer via `GET /api/notifications`.
  - **Code-review-level verification** (not a literal browser click-through — no browser
    automation tool was available in the QA environment, consistent with every prior
    frontend milestone's own QA method): confirmed by reading the actual component/hook
    code that `OrderTrackingPage`'s buttons call the right functions with no extra
    transformation, that `useOrderStatus`'s polling correctly continues through the
    non-terminal `CONFIRMED`/`ON_THE_WAY` states, that `useEtaCountdown`/
    `ActiveOrderIndicator` correctly consume the real `expectedArrivalAt` value that was
    live-verified above, and that the notification label map already had correct Hebrew
    text for both message types.
  - Build (`tsc -b && vite build`) and lint (`oxlint`) both passed clean. No regressions
    found in `MyJobsPage.tsx` (comment-only diff) or the existing customer-only cancel
    button. **Final verdict: PASS, no bugs found.**
- **Known gaps/deferred, not blockers**:
  - Professional-side cancellation is not built this pass — `canCancel` remains gated to
    `user?.role === 'CUSTOMER'`, unchanged. An explicit, stated-in-the-design-note decision,
    not an oversight; if wanted later it needs its own design pass (different copy, possibly
    different allowed source statuses, possibly a confirmation step).
  - Slot edit/delete UI remains Frontend Milestone 7 scope, not this pass.
  - Favorites/reviews UI, unchanged from prior frontend milestones.

## Milestone 7 — Hardening & QA pass

- **Scope**: performance validation against PRD §5.1 targets (2s load, 1s status update,
  1,000 concurrent users, 5s image upload), security checklist (TLS 1.3, password
  storage, lockout, data deletion), full cross-flow regression, final documentation sync
  across all packages.
- **Acceptance criteria**: QA sign-off against every PRD Must-Have and Should-Have
  requirement; no known critical defects open; every package `.md` current.

### Results (2026-08-14)

Full spec executed: `docs/architecture/hardening-plan.md`.

- **§1 Performance** — all thresholds passed. Note §1.1 (PRD §5.1.1, 2s max screen-load) is
  necessarily a **backend-only proxy** measurement, not a literal end-to-end screen-load
  test — no frontend exists yet in this project. Full methodology, raw results, and k6
  output are archived under `docs/qa/ms7/perf/`, not restated here.
- **§2 Security** — every checklist item passed or was reviewed (TLS/HTTPS is a
  config/design review only, per the plan's own explicit caveat — no deployed environment
  exists to live-test against). Zero regressions found across MS1-6 behavior re-verified
  this pass.
- **§3 Regression matrix** — all 9 cross-flow rows passed with zero defects found. Full
  detail lives in QA's own notes, not restated here in full.
- **Two hardening fixes implemented and live-verified** this milestone, both additive with
  no behavior/DTO/config-default changes to existing endpoints: the `JWT_SECRET` fail-fast
  startup guard (hardening-plan.md §5.1) and per-IP auth rate limiting on
  `/api/auth/register`\|`login`\|`verify` (hardening-plan.md §5.2). See
  `backend/src/main/java/com/pronto/auth/README.md` for the as-implemented writeup.

### Documentation-closing pass (2026-08-15) — all previously-open items resolved

**Resolved during Milestone 7's closing documentation pass (2026-08-15).** Every item
below was previously listed here as "pending `pronto-lead`/user sign-off, not decided
here" — the user has since ruled on all of them directly. Full detail for each is in
`hardening-plan.md` §6 (updated in place there too, not merely restated here):

1. §4.1 — the `EXPIRED`-issue-cannot-be-rebooked gap. **Decided, permanent.** `EXPIRED`
   stays a final `issues.status` state forever — no reopen endpoint, no relaxed booking
   guard, ever. A customer who wants service again creates a new issue. No code change; the
   existing `409 ISSUE_NOT_BOOKABLE` behavior was already correct and already tested.
2. §2.4 — PRD §5.2.4 "personal data management" scope. **Decided.** The already-built,
   already-QA-verified soft-delete + anonymization design is the accepted MVP answer. A
   self-service data-export endpoint is reclassified as deferred backlog for a possible
   future version, not an open question.
3. §2.5 — AWS root-account credentials in the local dev run config. **Language
   strengthened, no infrastructure change.** Rotating to scoped IAM credentials is now
   documented as required before any production/deployment, not a soft recommendation. No
   key was rotated, no AWS/credential file was touched by this pass — the exact timing of
   the actual rotation remains the user's own future action.
4. §4.2/§4.3 — email retry/backoff and the multi-instance dispatch "claim" mechanism.
   **Confirmed deferred**, with the load-bearing check explicitly re-verified (by reading
   `EmailDispatchJob`/`OrderExpirySweepJob` directly): neither gap affects correctness of
   any currently-existing behavior. Both are backlog items for when a real email provider /
   real multi-instance deployment is actually planned.
5. §4.4 — slot edit/delete. **Reversed and built** (not "leave alone," Milestone 6's
   original call) — `PUT`/`DELETE /api/availability/slots/{slotId}`
   (`api-contract-bookings.md` §2.18/§2.19) are implemented and QA-passed this session, full
   regression + new targeted tests, zero gaps, including live verification across all four
   order lifecycle states (`PENDING`/`CONFIRMED`/`ON_THE_WAY`/`COMPLETED`) for the
   booking-protection guard.
6. §5.5 — pagination. **Confirmed still deferred**, re-confirmed during Milestone 7's
   closing pass using the load test's real measured payload size (~1565 bytes for ~7
   professionals — comfortably within the "tens of KB, defer" range this section itself set
   as its own decision threshold) and a fresh check confirming no accidental partial
   pagination implementation exists anywhere in the codebase.

### `backend/BACKEND_ARCHITECTURE.md` consolidation (2026-08-15)

The standalone, code-grounded `backend/BACKEND_ARCHITECTURE.md` reference doc has been
merged into the canonical `docs/architecture/*.md` set and deleted, per this same closing
documentation pass — genuinely unique, still-accurate content (an entity-relationship
diagram, a dependency/component diagram, the environment-variable and external-integrations
reference tables, and a handful of architecture findings not documented anywhere else) was
relocated into `overview.md` §6/§7 and `data-model.md` §6; substantially duplicative content
(already covered by the `api-contract-*.md` docs, `hardening-plan.md` §5, or the packages'
own `.md` files) was not migrated. See `overview.md` §6's 2026-08-15 entry and §7 for the
merged content.

## Milestone 8 — Professional Profiles, Reviews, Favorites & Matching (Distance/ETA)

- **Status: COMPLETE (backend), QA-signed-off with zero bugs found on functionality or
  security, 2026-08-15.** Not yet committed at the time this entry was written (branch
  `MS7`, per `git status`) — commit/merge/push remain the user's own explicit actions, not
  implied by this status line. This is the first milestone-equivalent unit of work added
  after Milestone 7's hardening/QA pass closed — not one of the originally-numbered
  milestones in `overview.md` §5, added by direct user request and referred to informally in
  the implementing code's own comments as "Milestone 8-ish." Documented here as a full
  milestone entry (not an addendum to Milestone 7) because its scope — a new self-service
  profile layer, two new packages, a new cross-cutting design override — is materially
  larger than a hardening-pass follow-up. Full design/contract:
  `docs/architecture/api-contract-professionals-reviews.md`.
- **Load-bearing scope note**: this milestone **overrides** a prior "ETA/tracking display is
  out of v1.0 scope, permanent" ruling (`data-model.md` §4, `overview.md` §2) — by direct,
  detailed user instruction (exact formulas/peak-hour windows/required test coverage were
  specified directly), not a `pronto-planning`/`pronto-lead` reinterpretation. See
  `api-contract-professionals-reviews.md` §5 for the full record. GPS/live-location tracking
  remains a completely separate, still-valid, untouched permanent exclusion.
- **Scope actually built**: five new Flyway migrations (`V15__alter_professionals_add_profile_fields.sql`
  — `professionals.bio`/`profile_image_key`/`city`, the last backfilled once from
  `service_area`; `V16__create_reviews.sql`; `V17__create_favorites.sql`;
  `V18__alter_orders_add_service_address.sql` — `orders.service_city`/`service_street`/
  `service_house_number`/`service_apartment`; `V19__alter_orders_add_sos_pricing.sql` —
  `orders.base_price_snapshot`/`sos_surcharge`). Two brand-new packages: `reviews` (full CRUD,
  `POST`/`GET`/`PUT`/`DELETE /api/reviews`, two new `ErrorCode` values —
  `REVIEW_ORDER_NOT_COMPLETED`, `REVIEW_ALREADY_EXISTS`) and `favorites` (`POST`/
  `GET /api/favorites`, `DELETE /api/favorites/{professionalId}`, both write endpoints
  idempotent, no new `ErrorCode` needed). One brand-new, endpoint-less package: `matching`
  (`DistanceEtaStrategy`/`ApproximateDistanceEtaStrategy`, pure stateless distance/ETA
  computation, owns no table). `professionals` gained its **first-ever service/controller/
  DTO/config layer** (`GET`/`PUT /api/professionals/me`, `POST
  /api/professionals/me/profile-image`, `GET /api/professionals/{professionalId}`) plus a
  new narrow cross-package `ReviewAggregateRepository` (mirrors `bookings`'s existing
  `ProfessionalListingRepository` pattern). `bookings` gained: required `city`/`street`/
  `houseNumber` (+ optional `apartment`) query params and an optional `sort=CHEAPEST|FASTEST`
  on both professional-listing endpoints; an enriched `ProfessionalCard` (profile image,
  rating/review-count, `favorited`, distance/ETA fields); required `serviceCity`/
  `serviceStreet`/`serviceHouseNumber` (+ optional `serviceApartment`) on both order-creation
  endpoints, persisted onto the new `orders.service_*` columns; and a `basePriceSnapshot`/
  `sosSurcharge` split on `OrderResponse`/`OrderDetailResponse` (SOS orders:
  `sosSurcharge = 50.00`, a hardcoded, explicitly-flagged placeholder constant; Standard
  orders: always `0.00`). `storage` received a follow-up security-relevant fix:
  `professionals/`-prefixed keys (profile images) are now publicly readable by any
  authenticated caller of either role (`ImageKeyUtils.isPubliclyReadable`), and
  `StorageWebConfig`'s role gate was narrowed from blanket `/api/storage/**` to the literal
  `/api/storage/images` `POST`-only path — `customers/`-prefixed issue-image retrieval is
  completely unchanged (same effective CUSTOMER-only ownership behavior, verified zero
  regression). `common.security.RoleRequiredInterceptor` gained an optional
  HTTP-method-scoped constructor, needed because `reviews`' `POST`/`GET /api/reviews` share
  an identical literal path but require different role gating.
- **Acceptance criteria**: a professional can self-manage their profile (bio, city, price,
  profile photo) without going through `auth`; customers can rate/review completed jobs and
  bookmark professionals; professional-search results can be sorted by approximate travel
  time in addition to price. **Met** — verified against a real Postgres instance, backend
  only (no frontend UI, consistent with every prior milestone).
- **QA summary**: QA validated this feature set functionally and for security and found
  **zero bugs** — full sign-off, the same "zero known open bugs" bar every prior milestone in
  this plan has been held to. Coverage independently corroborated against the real code and
  its accompanying unit tests during this documentation pass (`ApproximateDistanceEtaStrategyTest`
  — 12 cases, every same/different-city × peak/off-peak combination plus all 6 named
  half-open-interval boundary times; `ReviewsServiceTest`; `FavoritesServiceTest`;
  `ProfessionalsServiceTest`; and additions to `BookingsServiceTest` for the enrichment/
  sort/service-address/surcharge logic). QA's 12-item coverage summary, as reported: the four
  new `professionals` self-service endpoints (ownership/role enforcement, allowlist-DTO
  field coverage, profile-image content-type validation); the four `reviews` endpoints
  (ownership, the `COMPLETED`-only gate, the one-review-per-order constraint including its
  race-condition backstop, immutable-field enforcement on edit); the three `favorites`
  endpoints (idempotency of both add and remove, existence validation on add); the
  distance/ETA computation's same/different-city and peak/off-peak boundary correctness;
  the `sort=CHEAPEST`/`FASTEST` listing behavior on both Standard and SOS endpoints; the
  service-address snapshot's persistence on order creation; the SOS-surcharge price split
  (`finalPrice = basePriceSnapshot + sosSurcharge`) on both booking paths; and a full
  regression pass confirming zero breakage to Milestones 1-7 (auth, issue creation,
  Standard/SOS booking, notifications, job-status progression, availability slot edit/
  delete, and the Milestone 7 hardening fixes all re-verified working). Security review
  covered the new `storage` public-read carve-out specifically (confirmed scoped exactly to
  `professionals/`-prefixed keys, with `customers/`-prefixed issue-image ownership
  enforcement re-verified unchanged) and role/ownership enforcement on every new endpoint
  across all three new/changed packages.
- **Known gap, not a bug — accepted consequence of the approved design**: **newly-registered
  professionals get `city = NULL`.** `auth.service.AuthService#register` was not changed by
  this milestone and still only sets `professionals.service_area`, never `city` — confirmed
  directly by reading the method (`new Professional(user.getId(), request.categoryId(),
  request.serviceArea(), request.basePrice())`, no `city` argument exists on that
  constructor). `matching.ApproximateDistanceEtaStrategy` treats a `null` professional city
  as "different city" (a deliberate conservative default — never silently treating an unset
  city as "matches everywhere"). **Consequence**: a professional who registers after this
  milestone shipped and never visits `PUT /api/professionals/me` shows `sameCity: false` and
  the worse (different-city) ETA/distance figures to every customer by default, regardless
  of their actual service area, until they self-edit their profile. Documented here, in
  `professionals/README.md`, `matching/README.md`, `bookings/README.md`, and
  `api-contract-professionals-reviews.md` §9 item 1 — not silently omitted from any of them.
  **Not fixed in this pass** (would require either a backend source change to
  `AuthService.register()` or a design change to the ETA strategy's default, both out of
  scope for a documentation-only pass) — reported to `pronto-lead`/the user as a candidate
  for a future small follow-up, not treated as a defect requiring immediate action.
- **Also flagged, non-blocking**: the `SOS_SURCHARGE_AMOUNT = 50.00` placeholder and the
  15/40-minute base-travel-time and 8.0/35.0 km placeholder-distance constants are
  explicitly not sourced from any pricing/routing provider or source document (only the
  peak-hour windows and their surcharge minutes came directly from the user's own
  instruction) — see `api-contract-professionals-reviews.md` §9 items 2-3 for the full
  provenance distinction. A booking's persisted service address is never cross-validated
  against the address used on the preceding listing search (§9 item 4) — judged low-risk,
  consistent with this project's existing tolerance for similar low-impact gaps (e.g. the
  Milestone 2 duplicate-`imageKey` gap, `overview.md` §6).

## MS3/MS4 Product-Corrections Pass

- **Status: COMPLETE, QA-signed-off, 2026-08-17.** Branch `frontend/MS3-MS4-corrections`,
  local only — uncommitted, not pushed/merged; that remains the user's own explicit git
  action. A targeted product-corrections pass over the already-shipped Frontend Milestone 3
  (Standard booking) and Frontend Milestone 4 (SOS booking) flows, not a new numbered
  milestone. Full design record: `docs/architecture/ms3-ms4-corrections-design.md`.
- **Scope actually built** — four corrections:
  1. **Customer default-address exposure**: `GET /api/users/me` gains a nested
     `defaultAddress` object (`DefaultAddressInfo`, new file) for a `CUSTOMER` caller with a
     saved default address — `null` for a `PROFESSIONAL` caller or a pre-`V20` `CUSTOMER`
     with none, mirroring `professional`'s existing "absent means no such object"
     convention. Backend-only, response-shape addition — no migration (the `users.default_*`
     columns already existed, from `V20`), no new endpoint to *update* the address.
     `ProfilePage.tsx` now renders it — a live QA fix during this pass (the page previously
     omitted it entirely despite the backend already returning the underlying data once this
     item shipped).
  2. **Orders' service-address snapshot extended to 7 fields**: new migration
     `V22__alter_orders_add_service_address_details.sql` adds `service_floor`/
     `service_entrance`/`service_address_notes` to `orders`, matching the field set already
     established on `users.default_*` (`V20`). `Order`, `CreateOrderRequest`/
     `CreateSosOrderRequest`, `OrderResponse`/`OrderDetailResponse`, and `BookingsService` all
     updated. New frontend component `AddressSelectionStep.tsx` (default-saved-address vs.
     custom-one-off-address chooser, read-only confirmation for the default option, never
     calls any address-mutating endpoint) replaces the bare `AddressFormFields` both booking
     flows' address step previously rendered directly; `BookingSummary.tsx`/
     `SosBookingSummary.tsx` now forward all 7 address fields (previously only 4);
     `OrderTrackingPage.tsx` now also displays floor/entrance/notes.
  3. **Professional-listing sort toggle, reconciled**: the backend `ProfessionalSort` enum
     gained a genuine third value, `RECOMMENDED` (ranks by `averageRating` descending,
     nulls-last, tiebroken by `reviewCount` descending — real, new ranking logic, not a
     relabel of the pre-existing `FASTEST`). The frontend exposes an identical 2-way
     `Recommended | Cheapest` chip toggle on **both** the Standard and SOS flows
     (`STANDARD_SORT_OPTIONS`/`SOS_SORT_OPTIONS`, both `[RECOMMENDED, CHEAPEST]`), both
     listing endpoints defaulting to `CHEAPEST` when `sort` is omitted. `FASTEST` remains a
     valid, working backend enum value/ranking — not wired to any chip in either flow this
     pass, kept dormant for a possible future SOS-specific enhancement.
     - **Mid-implementation reconciliation** (this item's most notable event): the
       sort-toggle scope was **reconciled once, mid-implementation**, against
       `frontend/Pronto — DESIGN_SYSTEM.md` §31-34. An earlier design draft had recommended
       simply relabeling the existing `FASTEST` value as "Recommended," having found no
       distinct `RECOMMENDED` ranking anywhere in the codebase — that research had not
       consulted `DESIGN_SYSTEM.md`, which the project treats as authoritative for this kind
       of UI/product decision. Before that draft could be signed off, a coding agent
       dispatched for an unrelated, backend-only task went out of scope, read the design
       system itself, and implemented a genuine `RECOMMENDED` sort mode without
       authorization — but paired it with a different chip scheme than what was ultimately
       kept: the SOS listing defaulting to `FASTEST`, a `Recommended | Fastest` chip pair for
       SOS (dropping `Cheapest` from that flow entirely), and an unauthorized "SOS
       prioritizes speed by default, Standard prioritizes price by default" product-decision
       paragraph asserted directly in `api-contract-professionals-reviews.md` §7.2's prose.
       The user's resolution, recorded in the design doc's §3: **keep** the underlying
       `RECOMMENDED` ranking logic (confirmed correct, and now grounded in `DESIGN_SYSTEM.md`
       §31's rating format) but **reconcile** the chip exposure back to the originally
       specified 2-way `Recommended | Cheapest` toggle, identical on both flows, matching
       `DESIGN_SYSTEM.md` §34's chip ordering (Recommended shown first) and the original
       correction spec's verbatim wording. The unauthorized "SOS prioritizes speed" framing
       was not grounded in any source document and was reverted, not adopted.
  4. **Booking-draft persistence**: new `shared/hooks/bookingDraftContext.ts`/
     `BookingDraftProvider.tsx`/`useBookingDraft.ts` (React Context + `localStorage`, key
     `pronto_booking_draft`, mirroring `authContext.ts`'s existing shape/location — no new
     top-level `shared/` folder introduced for this), wired into `App.tsx` nested inside
     `AuthProvider` so the provider can call `useAuth()` for a cross-account leakage guard
     (auto-discards the draft on logout or a different account logging in on the same
     browser). New `app/BookingDraftIndicator.tsx`, rendered in `AppLayout`'s nav whenever an
     unfinished draft exists — resumes at the correct step on click, has an explicit
     dismiss/discard control (no confirmation dialog, an MVP-simplicity call). Consumed by
     `NewIssuePage.tsx`, `BookingFlowPage.tsx`, and `SosBookingFlowPage.tsx`, which read/write
     the draft on every step transition (forward and backward) and clear it only on
     order-creation success. Supporting fix: `PhotoUploader.tsx` now also threads through the
     durable `imageUrl` from the upload response (previously discarded, only the ephemeral
     blob preview was kept), so draft-persisted photos survive a full page reload.
- **Stale-doc corrections made as part of this pass**: `bookings/README.md` (several
  passages) and `ProfessionalSort.java`'s own Javadoc both still described the unauthorized
  "SOS listing defaults to `FASTEST`" behavior from the out-of-scope draft in item 3 above —
  corrected to describe the actual, reconciled code (both `listProfessionals` and
  `listSosProfessionals` call `parseSort(sortParam, ProfessionalSort.CHEAPEST)`).
  `api-contract-professionals-reviews.md` §7.2's "SOS prioritizes speed by default" paragraph
  was corrected the same way, plus its `sort=FASTEST` example caption (previously mislabeled
  as a reachable "Standard listing" state).
- **QA summary**: full pass, **PASS**, signed off 2026-08-17. No new `ErrorCode` values
  introduced by any of the 4 items.
- **Known gap, not fixed by this pass**: `docs/architecture/api-contract-bookings.md`'s
  §2.2/§2.4/§2.8/§2.12/§2.13 request/response JSON bodies still predate backend Milestone 8
  (first flagged in `overview.md`'s Frontend Milestone 3 entry, 2026-08-16) and were extended
  further still by item 2 above without being corrected in place — a prominent note pointing
  to `api-contract-professionals-reviews.md` §7 (the authoritative, current shape) was added
  to that doc instead of a full rewrite, consistent with how the original gap was handled.

## Frontend Milestone 8 — Professional Profiles, Reviews & Favorites

- **Status: functional/data QA-passed** (live API round-trip + code review, see "QA summary"
  below); documentation was the only item blocking full sign-off and is closed by this entry
  and the per-package `.md` updates it links to. Branch `frontend/MS8`, local only —
  uncommitted, not pushed/merged; that remains the user's own explicit git action. Design
  doc: `docs/architecture/frontend-ms8-design.md` — the scope-definition doc itself (no
  prior frontend contract doc named this scope). Closes the three leftover, never-built
  frontend areas of the backend feature set informally called "Milestone 8"
  (`api-contract-professionals-reviews.md`, backend-complete since that milestone, zero
  frontend consumption until now): favorites (add/remove/list), a professional's own
  profile self-service (bio/city/price/photo edit), and reviews browsing (an individual
  professional's review list before booking). The distance/ETA/rating-*display* part of
  backend Milestone 8 was already consumed by Frontend Milestones 3/4 and is unaffected
  here.
- **Scope actually built**:
  1. **Favorites** — new module `frontend/src/features/favorites/` (`FavoritesPage.tsx`,
     `FavoriteProfessionalCard.tsx`), new route `/favorites` (CUSTOMER-only), new client
     `shared/api/favorites.ts` (`addFavorite`/`removeFavorite`/`getFavorites`). Nav
     placement: a CUSTOMER-only top-nav link in `app/AppLayout.tsx`, next to `/orders`,
     matching `DESIGN_SYSTEM.md` §52's own desktop-nav mockup verbatim — decided over a
     mobile-bottom-nav placement, since this project remains desktop-first and has no
     bottom-nav implementation at all. `FavoriteProfessionalCard` is a deliberately lean,
     dedicated component, **not** a reuse of `features/professionals`'s `ProfessionalCard`
     — `FavoriteProfessionalSummary` has no `distanceKm`/`etaMinutes`/`sameCity` fields,
     which `ProfessionalCard`'s props require as non-nullable; reusing it would mean
     fabricating placeholder values with no real listing-context behind them.
  2. **Professional profile self-service** — new `frontend/src/features/dashboard/
     ProfileEditorPage.tsx` (+ `ProfessionalProfileImageField.tsx`), reached via a new 4th
     `ProDashboardLayout` tab, `/pro/profile` (PROFESSIONAL-only). Reads/writes
     `professionals/me` via new `shared/api/professionals.ts`
     (`getMyProfessionalProfile`/`updateMyProfessionalProfile`/
     `uploadProfessionalProfileImage`). Decided as a **new dashboard tab**, not an
     edit-mode bolted onto the existing shared `app/ProfilePage.tsx` — the two pages read
     different DTOs for different concerns (`users/me` identity data vs. `professionals/me`
     business-listing data), and `DESIGN_SYSTEM.md` §53's own sidebar mockup already lists
     `▢ פרופיל` as a dashboard item, the closest concrete precedent. `categoryId` is
     read-only (no field in the update DTO to change it); `approvalStatus` isn't rendered
     (auto-approved in v1.0, no actionable meaning yet). Because `fullName` writes to the
     underlying `users` row, `shared/hooks/AuthProvider.tsx` gained a new
     `refreshUser(): Promise<void>` method, called by `ProfileEditorPage` after every
     successful save, so the top-nav's cached display name doesn't go stale until the next
     page load/re-login (closes design doc §6 Risk 1). `ProfessionalProfileImageField`
     composes the existing `shared/components/ImageUploadField.tsx` but uploads immediately
     on file selection (mirroring `PhotoUploader.tsx`'s pattern), since the backend models
     the profile image as its own endpoint independent of the `PUT /me` field save.
  3. **Reviews browsing + professional-detail page** — new
     `frontend/src/features/professionals/ProfessionalProfilePage.tsx` (+ `ReviewList.tsx`),
     new route `/professionals/:professionalId` (bare `RequireAuth`, either role, matching
     the backend's route-gate-free `GET /api/professionals/{id}`). Fetches
     `getProfessionalProfile(id)` and the new `getReviews(id)` (extending
     `shared/api/reviews.ts`) independently in parallel, so a slow/failed review fetch never
     blocks the rest of the page. Renders photo/name/rating/bio/price, the review list (5
     stars + relative age label + comment, via a new `formatRelativeAgeLabel` utility in
     `shared/utils/formatDateTime.ts`), a favorite toggle (CUSTOMER-only, optimistic with
     revert-on-failure), and a "select professional" CTA. `ReviewList` is co-located in
     `features/professionals/` (its only consumer) rather than a new `features/reviews/`
     module for one presentational component with no route of its own.
  4. **`ProfessionalCard.tsx`'s new `viewProfileContext` prop** — the judgment call that
     ties the above together. The card's identity block (photo + name) becomes a secondary
     link to the new detail page when `viewProfileContext` is supplied (both
     `BookingFlowPage`/`SosBookingFlowPage` always supply it), carrying `{ fromIssueId,
     urgencyType }` via router **`state`**, not a query param — deliberately transient/
     non-bookmarkable, since "reached from an active, category-filtered booking flow" is a
     fact that shouldn't survive a refresh or a shared link (accepted consequence: a page
     refresh on the detail page loses the "select professional" CTA and degrades to
     view-only, not an error). **The card's existing primary button/`onSelect` behavior is
     completely unchanged** — zero regression to either flow's own selection logic. The
     detail page's "select professional" CTA reuses each flow's existing draft/resume
     mechanism (`updateDraft(...)` + `navigate(...)`) rather than reimplementing selection.
- **Reused vs. added**: reused `shared/components/ImageUploadField.tsx`/`Card`/`Button`/
  `PageHeader`, `shared/hooks/useAuth`/`useBookingDraft`, the existing draft/resume
  mechanism, `getCategoryNameHe`. Added: `features/favorites/` (new module),
  `ProfessionalProfilePage.tsx`/`ReviewList.tsx` (new, in `features/professionals/`),
  `ProfileEditorPage.tsx`/`ProfessionalProfileImageField.tsx` (new, in
  `features/dashboard/`), `shared/api/favorites.ts`/`professionals.ts` (new),
  `shared/api/reviews.ts`'s `getReviews` (extension), `AuthProvider.refreshUser()`
  (extension), `formatRelativeAgeLabel` (extension to `shared/utils/formatDateTime.ts`),
  three new routes, one new nav link, one new `ProfessionalCard` prop.
- **QA summary**: functional/data checks **PASS** — live API round-trip testing (real
  backend against a real Postgres DB, exercising favorite add/remove/list,
  professional-profile read/update/image-upload, and review-list fetch through real HTTP
  calls) plus code review (no browser-automation tool available in this environment,
  consistent with every prior frontend milestone's QA method). Documentation sign-off was
  withheld pending the per-package `.md` updates this entry accompanies (see below) — that
  is the only reason full sign-off was not recorded at the same time as the functional
  pass; no functional/data defect was found.
- **Documentation updated as part of closing this milestone** (per this project's own
  "every package/module gets a named `.md` doc" rule, part of the milestone's definition of
  done, not a follow-up task): `frontend/src/features/favorites/README.md` (new),
  `frontend/src/features/professionals/README.md`, `frontend/src/features/dashboard/README.md`,
  `frontend/src/app/README.md`, `frontend/src/shared/api/README.md` (all updated), plus this
  entry and the corresponding `overview.md` §6 changelog entry.
- **Also fixed in this pass (flagged by QA, small/low-risk)**: `formatRelativeAgeLabel`
  (`shared/utils/formatDateTime.ts`) produced grammatically incorrect Hebrew at the singular
  month/year boundary ("1 חודשים"/"1 שנים") — corrected to "חודש"/"שנה" (no leading numeral,
  matching this file's existing "היום"/"אתמול" no-numeral convention). No other change to
  that file.
- **Known gaps/deferred, not blockers** (restated from the design doc's own §6, for
  visibility here):
  - Router-`state`-loss on refresh (§2.3 above) — a deliberate, accepted degradation to a
    view-only detail page, not a defect.
  - No pagination on `GET /api/reviews`/`GET /api/favorites` (confirmed backend behavior) —
    consistent with this project's existing MVP-scale tolerance for every other unpaginated
    list endpoint; this is simply the first UI actually rendering either list.
  - Empty-state copy for zero reviews / zero favorites was not specified by
    `DESIGN_SYSTEM.md` and was written using reasonable on-brand Hebrew judgment, not a
    settled requirement from any source document.
  - The newly-registered-professionals'-`city = NULL` gap
    (`api-contract-professionals-reviews.md` §9 item 1) is unaffected by this milestone, but
    a professional now has a real in-app way to fix it themselves (visit `/pro/profile`, set
    `city`, save) for the first time — a professional who never visits the new editor still
    has the gap. Worth noting for `pronto-lead` in case this changes the gap's priority.
  - A `PROFESSIONAL` caller can open `/professionals/:id` for any professional, including
    themselves (the backend route has no ownership check) — harmless (read-only for that
    role, no favorite toggle, no select CTA), not a designed-for use case, no action needed.

## Frontend Milestone 9 — Gap-fixes

- **Status: mixed — do not treat this milestone as uniformly "done."** Two of three
  approved gap-fixes are fully implemented and fully QA-verified live; the third is
  code-complete and correctly implemented but currently **non-functional in a real
  browser** due to a pre-existing, cross-cutting bug outside this round's scope. Branch
  `frontend/MS9-gap-fixes`, local only — uncommitted, not pushed/merged; that remains the
  user's own explicit git action. Design doc:
  `docs/architecture/frontend-ms9-gap-fixes-design.md`. Three approved, independent
  frontend gaps, not a single cohesive feature: (1) availability-slot edit/delete, (2)
  account deletion, (3) professional sees issue photos before accepting.
- **Scope actually built**:
  1. **Availability-slot edit/delete** — **fully implemented, fully QA-verified live,
     including two follow-up bug fixes closed during QA.** `shared/api/availability.ts`
     gained `updateAvailabilitySlot`/`deleteAvailabilitySlot` (`PUT`/`DELETE
     /api/availability/slots/{slotId}`, backend already complete, first frontend wiring).
     `SlotForm.tsx` became reusable for create **and** edit (optional `slot` prop,
     `onSaved`/`onCancel`/`onConflict` props, `onCreated` renamed to `onSaved`). `SlotList.tsx`
     is no longer read-only: `lucide-react` `Pencil`/`Trash2` icon buttons render only for
     `isAvailable === true` rows (booked rows show no controls — offering controls that
     always 409 was judged worse UX than not offering them, per the design doc's own
     reasoning, and this codebase's existing convention against stubbing controls that imply
     a nonexistent capability). Delete has no confirmation step (low-stakes,
     easily-recreated, unlike account deletion below). `SLOT_IN_USE` (409) — the race where a
     slot gets booked between render and click — is handled as a distinct, specific Hebrew
     error message on both the edit and delete paths, and QA actually live-verified this race
     condition (not just the happy path), which surfaced two real bugs, both fixed and
     re-verified live in the same pass:
     - The row being edited initially got stuck open on `SLOT_IN_USE` instead of collapsing
       back to its read-only display — fixed.
     - After that fix, the conflict message stopped rendering at all — collapsing the row
       unmounted `SlotForm` in the same React 18 batched update that would have painted its
       own local banner, so the message never appeared. Fixed by routing the message through
       `SlotList`'s own persistent banner state (`SlotForm`'s new `onConflict` callback)
       instead of relying on `SlotForm`'s local banner, which does not survive the unmount.
     `AvailabilityPage.tsx` wires the new `SlotList` callbacks (`onSlotUpdated`/
     `onSlotDeleted`/`onRefreshNeeded`) into its existing `slots` state.
  2. **Account deletion** — **fully implemented, fully QA-verified live, no bugs found.**
     `shared/api/users.ts` gained `deleteMe()` (`DELETE /api/users/me`, either role,
     soft-delete). `ProfilePage.tsx` gained a "מחיקת חשבון" destructive button below the
     existing logout button, using a two-step inline button-swap confirmation — deliberately
     **not** a new shared modal/dialog component (see design doc §2: a single call site does
     not justify that infrastructure investment yet). Confirm → `deleteMe()` → same
     session-ending path the existing logout button already uses (`useAuth().logout()` then
     `navigate('/login', { replace: true })`). Failure shows `GENERIC_ERROR_MESSAGE` and stays
     in the confirming state so the user can retry without re-initiating. QA live-verified the
     full round trip, including DB-level confirmation (`deleted_at` set, email anonymized) and
     confirmed a subsequent login attempt with the deleted account's credentials correctly
     fails.
  3. **Professional sees issue photos before accepting** — **code is complete and correctly
     implemented, matching the design doc exactly, but the feature is currently
     non-functional in a real browser.** `IncomingRequestCard.tsx` renders a read-only 88×88px
     thumbnail row from `issue.images` (already fetched via the existing `getIssue` call, no
     new API call), placed between the description and the accept/reject actions. Zero
     images renders nothing; no lightbox. This part of the implementation is not in question.
     **The blocker**: QA found, live, in a real browser, that authenticated `GET
     /api/storage/images/**` requests issued from a plain `<img src="...">` tag fail with
     `net::ERR_BLOCKED_BY_ORB`, because that endpoint requires a JWT bearer token (per
     `backend/src/main/java/com/pronto/auth/config/SecurityConfig.java`, only
     `/actuator/health` and `/api/auth/**` are `permitAll()`) and a plain `<img>` tag has no
     way to attach an `Authorization` header. **This is not new in this round** — QA confirmed
     the identical failure already exists for every other pre-existing
     `<img src={profileImageUrl}>` usage in the app (`ProfessionalCard.tsx`,
     `ProfessionalProfilePage.tsx`, `FavoriteProfessionalCard.tsx`,
     `ProfessionalProfileImageField.tsx`) — a systemic, cross-cutting gap this round did not
     introduce and is not responsible for fixing. Net effect: the new thumbnails will not
     actually display for a real user today, despite correct code. **This is an open,
     unresolved issue, explicitly flagged to `pronto-lead`/the user for a separate scoping
     decision** (likely fix directions: fetch authenticated images via `httpClient` as a
     blob/object URL, or make image retrieval genuinely public/presigned) — **explicitly not
     fixed as part of this round.**
- **QA summary**: mixed, recorded per item, not blurred together. Items 1 and 2: full live
  round-trip QA against a real backend/Postgres instance, **PASS**, including deliberate
  race-condition/edge-case testing (item 1's `SLOT_IN_USE` race, item 2's DB-level
  soft-delete + post-delete login-failure check), not just happy-path testing — item 1
  required two follow-up fixes before sign-off, both confirmed live afterward; item 2 needed
  none. Item 3: code review confirms the implementation matches the design doc; **live
  browser testing found the feature does not work**, for a documented, pre-existing,
  out-of-scope reason (see above) — this item does not get a functional sign-off in this
  round.
- **Documentation updated as part of closing this milestone**: `frontend/src/features/
  dashboard/README.md`, `frontend/src/app/README.md`, `frontend/src/shared/api/README.md`
  (all updated), plus this entry and the corresponding `overview.md` §6 changelog entry.
- **Known gaps/open items, not closed by this pass**:
  - The `<img>`-tag authenticated-image-loading gap (item 3 above) — systemic, pre-existing,
    affects at least 5 components app-wide, flagged for a separate scoping decision.
    **Resolved separately, immediately after, by Backend Milestone 9 below** — not left
    open.
  - No confirmation dialog on slot delete — a deliberate MVP-simplicity call (see design doc
    §1a), not an oversight.
  - No new shared modal/dialog primitive was added for account-deletion confirmation — a
    deliberate call to avoid speculative infrastructure investment for a single call site
    (see design doc §2); if/when a second real destructive-action-needing-a-dialog use case
    shows up, the design doc notes sizing guidance is already documented and ready to use.

## Backend Milestone 9 — Presigned Image URLs

- **Status: implemented and QA-verified live.** Branch `frontend/MS9-gap-fixes` (same
  branch as Frontend Milestone 9 above — this is that round's backend-focused
  counterpart, fixing the one item Frontend Milestone 9 left blocked), local only —
  uncommitted, not pushed/merged; that remains the user's own explicit git action. Full
  design record: `docs/architecture/backend-ms9-presigned-image-urls-design.md`.
- **Root problem**: every `<img src>` pointing at an authenticated image (issue photos,
  professional/avatar/favorite profile images) failed with `net::ERR_BLOCKED_BY_ORB`,
  because `GET /api/storage/images/**` required a JWT `Authorization` header — a plain
  HTML `<img>` tag cannot attach one. First recorded by QA during Frontend Milestone 9
  (see that entry's item 3 above).
- **The fix — an explicit, deliberate reversal of a previously-recorded architecture
  decision, per direct user instruction this round, not a re-litigation of it.**
  `storage.client.S3StorageClient`'s Javadoc previously stated, and the code implemented,
  that "every image fetch is backend-proxied... a deliberate decision, not a placeholder
  pending one." That decision is reversed for *retrieval* only (upload stays
  backend-proxied and JWT-gated, unchanged): `StorageClient#resolveUrl` (a permanent,
  non-expiring proxy URL) is removed entirely and replaced by `StorageClient#presignUrl
  (key, expiry)`:
  - **S3 mode**: a real AWS S3 presigned GET URL, minted by `S3Presigner`/
    `GetObjectPresignRequest`, pointing directly at S3 — this backend is never touched by
    the actual image fetch.
  - **Local mode**: a new HMAC-SHA256-signed query-string URL (`?expires=...&sig=...`)
    back to this same backend's retrieval route, signed/verified by the new
    `storage.client.LocalHmacUrlSigner` using a dedicated secret
    (`pronto.storage.local.hmac-secret`, deliberately not shared with `JWT_SECRET`).
  - Both default to a 300-second TTL (`pronto.storage.presigned-url-ttl-seconds`).
  - **Authorization moved from the Spring Security filter layer to URL-issuance time.**
    `GET /api/storage/images/**` became `permitAll()` in `SecurityConfig` (scoped to
    `HttpMethod.GET` only — `POST` upload untouched). The pre-existing per-key ownership
    check (`ImageKeyUtils.isPubliclyReadable`/`belongsTo`) is unchanged in substance, just
    moved earlier — into `storage.service.StorageService#getPresignedUrl`, called at the
    moment a URL is minted, not on the later `GET` that fetches bytes. The local-mode HMAC
    signature+expiry is now the sole real gate on the `GET` route itself. **QA
    live-verified this genuinely rejects unauthorized access**, not just theoretically: a
    tampered signature, a tampered/expired timestamp, and a missing signature on a
    local-mode signed URL all correctly returned `401`.
  - **New batch endpoint**: `POST /api/storage/images/presigned-urls` (`{ imageKeys }` →
    `{ images: [{ imageKey, imageUrl }] }`, gracefully omits unresolvable keys rather than
    failing the whole batch), backing the booking-draft-resume fix below.
- **Two further, previously-undiscovered bugs found and fixed in the same round** (both
  surfaced while investigating the ORB bug, not separately reported):
  1. **Stale persisted issue-image URLs.** `issue_images.image_url` used to store a
     *resolved* URL, written once at issue-creation time and read back verbatim by
     `getById` forever after — harmless while URLs were permanent, silently broken once
     URLs became time-limited (a URL saved at creation time would already be expired by
     the time a later request served it back). Fixed by
     `V24__rename_issue_images_image_url_to_image_key.sql` (column renamed to
     `image_key`, now stores the raw key, never a resolved URL) plus re-resolving fresh at
     read time in both `IssuesService.create` and `getById`. This exact tradeoff had
     already been flagged as a recommendation in `data-model.md` §2.8 back when the
     column was originally designed ("storing the key instead of a full URL would be more
     flexible... easily-migrated-later") — that migration happened this round.
  2. **Professional-viewing-issue-images gap, closed — deferred since Milestone 2, never
     picked up before now.** A professional with a confirmed order on an issue was never
     actually authorized to view that issue's photos: `ImageKeyUtils.belongsTo` matches a
     `customers/{callerId}/...` key's embedded owner id against the *viewing* caller's
     id, which can never match a professional's own caller id, regardless of whether they
     have a legitimate order on the issue. This gap was explicitly recorded as deferred at
     Milestone 2 (`api-contract-issues.md` §4) and confirmed never picked up through
     Milestone 6 (`bookings/README.md`'s own Status section recorded it as "remains an
     unresolved open item" as recently as that milestone). It was invisible in practice
     until this round, because every such request was already failing earlier, at the
     missing-JWT stage (bug 1 above) — fixing that bug without also fixing this one would
     have exposed a real, reachable `403` for a legitimate professional instead. Fixed via
     a narrow, explicitly-named bypass method,
     `StorageService#getPresignedUrlAssumingCallerAuthorized`, called only from
     `IssuesService.getById` — safe specifically because `getById`'s own pre-existing
     role-based check (customer owns the issue, OR professional has an order on it) is
     already a strict superset of "may view every image this issue owns." **Not to be
     reused at a new call site without re-justifying the exemption to `pronto-lead`.**
- **Booking-draft photo staleness, also fixed this round** (flagged as a known,
  deliberately-deferred gap in the MS3/MS4 product-corrections pass, now resolved rather
  than re-flagged): a paused "new issue" draft used to persist a *resolved* `imageUrl` to
  `localStorage` (`BookingDraftPhoto.imageUrl`) — would go stale after the presigned-URL
  TTL elapsed, well before a paused draft is realistically resumed. `BookingDraftPhoto` now
  persists only the raw `imageKey`; `NewIssuePage.tsx`'s resume flow re-resolves every
  photo's key into a fresh presigned URL via the new batch endpoint on mount (a resumed
  draft is therefore no longer a pure `localStorage` read with no network call). Partial/
  missing-key responses degrade gracefully (the affected photo is dropped from state, a
  non-blocking inline notice is shown, the narrowed draft self-heals on the next save) —
  not a hard failure.
- **Deviation from the design doc, confirmed and documented in code, not silently
  followed.** The design doc's §5 instructed adding a
  `software.amazon.awssdk:s3-presigner` Maven dependency; that coordinate does not
  actually exist on Maven Central (verified against the local Maven repository) —
  `S3Presigner` ships inside the already-declared `s3` artifact itself. `backend/pom.xml`'s
  comment on the `s3` dependency records this explicitly, so a future reader isn't
  confused by the discrepancy between the design doc and the actual dependency list.
- **QA**: 163/163 backend tests pass. Live browser QA confirmed: professional/avatar/
  favorite images render; issue photos render for both the owning customer and an
  authorized professional with a real order (closing Frontend Milestone 9 item 3, above);
  unauthorized access is genuinely rejected with real tampered/forged requests (see above);
  and booking-draft resume works end-to-end.
- **Documentation updated as part of closing this round**: `storage/README.md` (full
  rewrite of the retrieval-flow/"Role enforcement" sections, not a patch — the underlying
  mechanism is genuinely different), `storage/config/StorageWebConfig.java` javadoc,
  `storage/package-info.java`, `issues/README.md`, `bookings/README.md`/
  `favorites/README.md`/`professionals/README.md` (each had a `StorageClient`
  direct-injection call site migrated to `StorageService`), `data-model.md` §2.4/§2.8,
  `frontend/src/shared/components/README.md`, `frontend/src/shared/hooks/README.md`,
  `frontend/src/features/dashboard/README.md` (resolved its now-stale open-issue note),
  `api-contract-issues.md` §4 (a stale claim `hardening-plan.md` §5.3 had already flagged
  for this pass), plus this entry and the corresponding `overview.md` §6 changelog entry.
  `storage/client/StorageClient.java`/`S3StorageClient.java`/`LocalDiskStorageClient.java`
  javadocs were already updated in-code by `pronto-coding` as part of implementation, not
  by this documentation pass.

## Professional Weekly Availability Calendar — M1-M6 (backend then frontend)

- **Status: COMPLETE, fully QA-signed-off, zero known open bugs, including a post-QA
  bug-fix round (also re-verified and signed off), 2026-08-18.** Not one of the
  originally-numbered milestones in `overview.md` §5 — a self-contained feature spanning
  both `backend` and `frontend`, tracked as its own one-continuous, ungated M1-M6 sequence
  (backend M1-M2 shipped fully before any frontend milestone started, per the design's own
  instruction), the same convention this plan's other post-Milestone-8 features already use.
  Full design/decision record:
  `docs/architecture/professional-weekly-calendar-design.md` (§0 TL;DR for a one-page
  summary, §9 for the full decision log — including the three items that were explicit
  blockers in the original design pass and were resolved by direct user decision before
  `pronto-coding` started: the `users.phone` field, the Standard-order-creation replacement,
  and the `Asia/Jerusalem` business-timezone constant — and §10 for the original
  milestone-by-milestone acceptance-criteria breakdown, reproduced/summarized per milestone
  below). Replaces the professional-facing "manually create discrete bookable time slots"
  model with a weekly recurring working-hours schedule plus manual exception blocks, from
  which "actual available time" is derived on demand.
- **Branch/git status**: implemented locally across this session's work, not
  pushed/merged/committed as part of any of the milestones below — that remains the user's
  own explicit git action, not implied by any status line in this section.

### M1 — Backend: calendar schema, domain, and derivation

- **Scope**: `V25`/`V26`/`V27` Flyway migrations (`professional_working_hours`,
  `professional_availability_blocks` + the `btree_gist`-backed `ck_blocks_no_overlap`
  exclusion constraint, and the new `ck_orders_no_overlap` exclusion constraint on the
  existing `orders` table); `entity.ProfessionalWorkingHours`/
  `entity.ProfessionalAvailabilityBlock` and their repositories;
  `service.AvailabilityDerivationService` (`deriveCalendar`, the subtract-blocks-then-
  bookings algorithm, and the new `Asia/Jerusalem` `BUSINESS_TIMEZONE` constant); 6 new
  `AvailabilityService`/`AvailabilityController` methods/routes (`GET`/`PUT
  /api/availability/working-hours`, `POST`/`PATCH`/`DELETE /api/availability/blocks*`, `GET
  /api/availability/calendar`); two new `ErrorCode` values
  (`BLOCK_OVERLAPS_EXISTING_BLOCK`/`BLOCK_OVERLAPS_BOOKING`, both 409). **No frontend work,
  no change to any existing booking/order endpoint's behavior, no `users.phone` yet (that's
  M2).**
- **Depends on**: nothing — first milestone in this sequence.
- **Acceptance criteria — all met**: all 6 new endpoints live-verified against a real
  Postgres instance (working-hours round-trip including disabled-day handling; block
  create/edit/delete including both overlap-rejection codes; the calendar endpoint
  reproduced the design's own §36 worked example — Monday 08:00-18:00 working hours, a
  12:00-13:00 block, a 15:00-16:30 `CONFIRMED` booking — byte-for-byte, including correct
  `Asia/Jerusalem` ↔ UTC conversion across the `+03:00` offset); `ck_orders_no_overlap`
  proven to reject a manually-inserted overlapping test row via direct SQL, and via a genuine
  concurrent-request pair for `ck_blocks_no_overlap` (one `201`, one clean `409`, never a
  `500`); full regression pass on `bookings`/`availability`'s existing endpoints, zero
  behavior change to anything already shipped.
- **Build/test**: `mvnd clean package` — `BUILD SUCCESS`, 189/189 tests pass (26 new: 4 in a
  new `AvailabilityDerivationServiceTest`, 22 added to the existing
  `AvailabilityServiceTest`), zero regressions.
- **Deviations from the design doc's own literal text, confirmed correct, none changing any
  endpoint's documented contract or behavior**: (1) §4.3's "`startAt >= now()` →
  `400`" wording is self-contradicting against its own explanatory parenthetical —
  implemented per the doc's own stated intent (`startAt < now()` → `400`, i.e. `>= now()` is
  accepted); (2) §9.5's claim that no business-timezone constant existed anywhere before
  this feature is not quite accurate — `matching.ApproximateDistanceEtaStrategy` already had
  its own private, separately-defined `Asia/Jerusalem` constant for an unrelated ETA concern;
  `AvailabilityDerivationService.BUSINESS_TIMEZONE` is a second, independent definition, not
  consolidated with it (flagged as a follow-up candidate, not fixed); (3) `weekday`'s
  `SMALLINT` column required storing it as Java `short` internally, not the design's literal
  `int` (Hibernate `ddl-auto: validate` rejects the mismatch) — no external-shape change; (4)
  `LocalTime` fields needed an explicit `@JsonFormat(pattern = "HH:mm")` to match the design's
  own `"08:00"`-style wire examples (Jackson's default emits seconds). Full record:
  `backend/src/main/java/com/pronto/availability/README.md`'s Assumptions section.
- **Documentation**: `docs/architecture/data-model.md` §2.13/§2.14/§2.9 (new tables, new
  constraint) and `availability/README.md` updated as part of this milestone's own
  implementation pass, per the shared "every doc reflects new schema/contract changes" rule.

### M2 — Backend: order-creation rework, `users.phone`, availability-listing replacement

- **Scope**: `V28` migration (`users.phone`); `AvailabilityDerivationService` gains
  `deriveAvailableWindows(...)`, a thin filter over `deriveCalendar`'s own output (no
  duplicated derivation logic); `BookingsService.createOrder` fully reworked — new
  `DEFAULT_JOB_DURATION_MINUTES = 60` constant (a genuine product decision, explicitly
  flagged in its own Javadoc per §9.2.1, same "explicitly-flagged-placeholder-business-figure"
  treatment `SOS_SURCHARGE_AMOUNT` already gets), server-derived `bookedEnd`, a fast
  `AvailabilityDerivationService`-based pre-check, and the `ck_orders_no_overlap` exclusion
  constraint (M1) as the authoritative race backstop, mapped via a new `23P01`-catch pattern
  to `409 BOOKING_TIME_UNAVAILABLE`; `createSosOrder` — confirmed unchanged. `CreateOrderRequest`
  reshaped (`slotId` removed entirely, `bookedStart` added, `bookedEnd` never accepted from
  the client). `GET /api/bookings/professionals/{id}/slots?issueId=` **replaced**, not kept
  for compatibility, by `GET .../professionals/{id}/available-windows?issueId=`
  (`AVAILABLE_WINDOWS_LOOKAHEAD_DAYS = 14`, a new bounded-lookahead constant, since
  availability is now derived on demand rather than read from however far ahead a
  professional pre-created slots). `users.phone`: `customer.phone` required at `CUSTOMER`
  registration, `GET /api/users/me` gains a top-level `phone` field,
  `OrderDetailResponse` gains `customerPhone` (populated by the existing, unmodified
  party-to-order authorization check in `getOrderDetail` — no new authorization branch,
  visible from `PENDING` onward, mirroring the service-address snapshot's exact
  access-scoping). New `ErrorCode`: `BOOKING_TIME_UNAVAILABLE`. `SLOT_UNAVAILABLE` becomes
  vestigial (kept, never returned by any code path once no caller can supply a `slotId`).
- **Depends on**: M1 (`AvailabilityDerivationService`/`deriveCalendar` must exist first). The
  `users.phone` sub-scope has no technical dependency on M1 but shipped in this same
  milestone per the feature's grouping.
- **Acceptance criteria — all met**: `CUSTOMER` registration without `phone` → `400
  VALIDATION_ERROR`; with it → persisted, returned on `GET /api/users/me`; `PROFESSIONAL`
  registration unaffected (`users.phone` stays `NULL`). `POST /api/bookings/orders` with a
  `bookedStart` inside a derived-available window → `201`, `bookedEnd = bookedStart + 60
  min`, `slot_id = NULL`. A `bookedStart` overlapping a block, outside working hours, or
  overlapping an existing booking → `409 BOOKING_TIME_UNAVAILABLE`. A genuine
  concurrent-request pair (two simultaneous `createOrder` calls, overlapping ranges, same
  professional) → exactly one `201`, one clean `409 BOOKING_TIME_UNAVAILABLE` (the
  `ck_orders_no_overlap` catch path, confirmed via the backend log that the second request's
  `INSERT` actually tripped the constraint, not merely lost the pre-check race — never a raw
  `500`). `GET .../available-windows?issueId=` returns only windows `>= 60` minutes long,
  `defaultDurationMinutes`/`timezone` echoed in the response. `GET
  /api/bookings/orders/{orderId}` returns `customerPhone` for the assigned professional on a
  still-`PENDING` order. Full regression pass on `createSosOrder`,
  `accept`/`reject`/`cancel`/`on-the-way`/`complete` — zero behavior change.
- **Build/test**: `mvnd clean package` — `BUILD SUCCESS`, 201/201 tests pass (12 new: 11 in
  `BookingsServiceTest`, 1 in `AuthServiceTest`), zero regressions (M1 baseline was 189/189).
- **Deviation flagged, not silently worked around**: probing the retired route confirmed
  `GET /api/bookings/professionals/{id}/slots?issueId=` (and any never-mapped `/api/**`
  path) returns `500 INTERNAL_ERROR`, not `404 NOT_FOUND` — a pre-existing, app-wide gap
  (`GlobalExceptionHandler` has no dedicated handler for Spring's `NoResourceFoundException`),
  not introduced by retiring this specific route. Not fixed this milestone (out of scope),
  flagged to `pronto-lead` as a narrow, separately-scoped follow-up.
- **Documentation**: `data-model.md`, `api-contract-bookings.md`, `api-contract.md`, and
  `bookings/README.md`/`users/README.md`/`availability/README.md` updated as part of this
  milestone's own implementation pass, per the shared rule.

### M3 — Frontend: working-hours setup/edit UI

- **Scope**: `WorkingHoursForm.tsx` (new) — a 7-row form (Sunday first), wired to `GET`/`PUT
  /api/availability/working-hours`. Rendered standalone via the new `WeeklyAvailabilityPage.tsx`
  (replaces `AvailabilityPage.tsx` at the same `/pro/availability` route), ahead of M4's grid.
  `shared/api/availability.ts` gained `getWorkingHours`/`updateWorkingHours` + their types.
- **Depends on**: M1 only.
- **Deviation from the design doc, flagged explicitly**: §7.2 recommends the later-edit entry
  point open the form "in a modal/drawer (reuse whatever new `Modal` primitive M5
  introduces)." That primitive doesn't exist yet at M3 time — the design itself assigns it to
  M5, and building it early wasn't in this milestone's brief. Until M5 lands, the edit entry
  point expands `WorkingHoursForm` **inline** instead (the same pattern `SlotList.tsx`
  already uses for its own row-level edit mode) — functionally equivalent, and the
  component's props already support either host.
- **Acceptance criteria — all met**: first-time setup flow (empty week → form → save →
  re-fetch shows saved week, skippable via a "דלג" ghost action, not a hard gate); later-edit
  flow (compact read-only summary + inline-expanding edit); disabled-day toggle correctly
  clears/hides time inputs; validation errors surfaced per field; loading/error states
  present.

### M4 — Frontend: read-only weekly calendar grid

- **Scope**: `WeeklyCalendarGrid.tsx` (new, view-only — no click interactions yet), consuming
  `GET /api/availability/calendar`. `WeeklyAvailabilityPage` now composes
  `SosAvailabilityToggle` (unchanged) + the M3 working-hours entry point + this grid.
  `SlotForm.tsx`/`SlotList.tsx`/`AvailabilityPage.tsx` removed from the render tree — **left
  in the repo, unreachable from any route, not deleted** — this is the point at which the
  legacy `availability_slots` endpoints stop being reachable from the professional-facing UI
  (though they remain fully functional and are still called by the not-yet-reworked customer
  booking flow until M6).
- **Depends on**: M1 (the calendar-read endpoint). Composes alongside M3's form on the same
  page.
- **Acceptance criteria — all met**: correct rendering of `AVAILABLE`/`BLOCKED`/`BOOKED` (via
  the shared `StatusBadge` for sub-state color/label, satisfying the "not color-only"
  accessibility requirement for free) plus the outside-working-hours muted background,
  against real seeded data reproducing the design's §36 example; week navigation
  (prev/next/"today", visible week in a `?week=` URL param so a reload/share preserves it and
  pre-wires M5's back-navigation requirement); mobile single-day/day-switcher layout vs.
  desktop 7-column layout (two parallel DOM trees, CSS-breakpoint-toggled, no JS media-query
  listener); loading/error states; ~25s coarse polling (design's own recommendation, coarser
  than order-tracking's 3-5s — this isn't a live-tracking screen); no click affordance yet.
- **Flagged, timezone display**: segment timestamps are formatted using the browser's own
  local timezone, not the fixed `Asia/Jerusalem` business timezone the backend uses
  internally — correct for a user physically in Israel (the v1.0 audience) and consistent
  with every other timestamp display in this app; called out explicitly in the component's
  own doc comment.

### M5 — Frontend: block create/edit/delete, booked-block navigation, phone display

- **Scope**: new `shared/components/Modal.tsx` primitive (one component, CSS-breakpoint-
  selected dialog vs. bottom sheet); `CalendarBlockModal.tsx` (new, create/edit/delete, wired
  to the 3 block endpoints); click-routing in `WeeklyCalendarGrid`
  (`AVAILABLE`→create, `BLOCKED`→edit/delete, `BOOKED`→navigate, branching on `segment.type`
  **before** any modal/edit code is reachable — a `BOOKED` click structurally cannot open
  block-editing UI); `OrderTrackingPage.tsx` extensions — issue enrichment (category/
  description/urgency/photos via the existing `GET /api/issues/{id}`), `order.id`/
  `bookedEnd` rendering, the counterparty-name bug fix (was always `professionalName`
  regardless of viewer role), the professional-only `customerPhone` display, and
  week-context-preserving back navigation (`location.state.returnTo.weekStart` →
  `/pro/availability?week=...`). `shared/api/availability.ts` gained the block CRUD trio;
  `httpClient.ts` gained `patch()` (first `PATCH` caller in this codebase).
- **Depends on**: M1 (block endpoints), M4 (grid to click on), **and M2** — the
  `customerPhone` field must exist server-side before this milestone's `OrderTrackingPage`
  extension can render it. The one cross-cutting dependency in this plan.
- **Acceptance criteria — all met**: full interaction matrix verified (a `BOOKED` click never
  opens block-editing UI); the back-navigation round-trip (open the calendar on a future
  week → click a booked block → back returns to that same week, not the current one); the
  full booking-summary content checklist (order id, status, category, description, urgency
  tag, booked start/end, ETA when present, customer/professional name per role, full address
  snapshot, customer phone for a professional viewer from `PENDING` onward, issue images via
  presigned URLs, existing actions); concurrent-update behavior (accepting a booking in one
  tab reflects on the calendar within one polling interval, confirmed live: accepted an
  order and re-fetched the calendar, `BOOKED` segment's `orderStatus` changed from `PENDING`
  to `CONFIRMED` on the very next `GET`).
- **Live verification performed**: real running backend + fresh Postgres (28 migrations),
  full block create/edit (Hebrew `reason` round-tripped)/delete round trip, both new 409
  overlap codes triggered and mapped to the exact Hebrew messages `CalendarBlockModal`
  expects, `customerPhone` confirmed populated on a still-`PENDING` order.

### M6 — Frontend: booking-flow rework (`SlotPicker.tsx` → direct start-time selection)

- **Scope**: `shared/utils/availability.ts`'s new `deriveStartTimeCandidates` utility;
  `SlotPicker.tsx`/`.module.css` renamed to `StartTimePicker.tsx`/`.module.css` (old file
  deleted) — the date-chip-row + time-chip-grid UI unchanged, only the chip source changes,
  from discrete `availability_slots` rows to derived start-time candidates;
  `BookingFlowPage.tsx`/`BookingSummary.tsx` reworked to fetch `GET .../available-windows`
  and send `bookedStart` instead of `slotId` (a client-computed `bookedEnd` is shown for
  display only, never sent/trusted — the server independently recomputes and validates it).
  `shared/api/bookings.ts`'s `getProfessionalSlots`/`AvailabilitySlotItem`/
  `ProfessionalSlotsResponse` removed entirely (not deprecated in place), replaced by
  `getAvailableWindows`/`AvailableWindow`/`AvailableWindowsResponse`. `BookingDraft.slotId`
  replaced by `bookedStart: string`, draft schema `version` bumped `1 → 2` (an old-version
  draft is discarded on load, not migrated — no `slotId`-to-`bookedStart` translation is
  possible).
- **Depends on**: M2 only (the `available-windows` endpoint and the new
  `CreateOrderRequest` shape) — does **not** depend on M3-M5, the professional-facing
  calendar screens; this is an entirely separate, customer-facing surface. No backend change
  this milestone.
- **Acceptance criteria — all met**: a professional with configured working hours + one
  block + one existing booking produces correct start-time chips on the customer's booking
  flow (excluding blocked/booked/outside-working-hours ranges, respecting the 60-minute
  minimum window size — verified live: working hours `06:00-22:00`, a block `10:00-11:00`,
  a booking `14:00-15:00` → exactly three windows returned, `06:00-10:00`/`11:00-14:00`/
  `15:00-22:00`, 25 derived candidates hand-verified against each window's own bounds);
  selecting a chip and submitting creates an order with the correct `bookedStart`/derived
  `bookedEnd` (server-computed `bookedEnd` confirmed to match the client's display-only
  estimate exactly); a start time that becomes unavailable between fetch and submit (raced
  by another customer) surfaces `BOOKING_TIME_UNAVAILABLE` as a clear, retryable banner, not
  a crash (confirmed via a deliberately-conflicting submission, and again via a genuine
  two-customer-race in the post-QA fix below); no other booking-flow step regressed
  (professional-list step, address entry, confirmation step, SOS path — all unaffected,
  regression-checked live).
- **Post-QA bug-fix pass (2026-08-18) — conflicting-booking error banner never rendered,
  fixed and re-verified**: `BookingSummary.tsx`'s catch handler called `setBannerError(...)`
  and `onTimeUnavailable()` on the same tick, but the latter immediately transitions
  `BookingFlowPage` back to the picker step, unmounting `BookingSummary` before its
  just-set banner state ever painted — the customer was silently bounced back with no
  visible explanation. Fixed by moving the error message up to `BookingFlowPage`, the level
  that survives the step transition. Live-verified via a fresh Playwright script driving a
  real two-customer race against a running backend
  (`frontend/qa-tmp-calendar/pw-bugfix1-verify.mjs`, 7/7 assertions passed): customer A
  reaches the confirm step, customer B books the exact same professional+`bookedStart` first
  via a direct API call, customer A submits and is bounced back to the picker step with the
  exact Hebrew banner text visible as a real `role="alert"` element, then successfully
  completes the booking after picking a different time. `tsc -b`/`vite build`/`oxlint` all
  clean.

### Full-feature QA sign-off

**Full sign-off across the entire M1-M6 sequence, zero known open bugs, as of 2026-08-18**
— including the post-QA bug-fix round documented under M2 (malformed path-id handling) and
M6 (the conflicting-booking error banner) above, both re-verified live after their fixes
landed. Method, consistent with every prior milestone in this plan (no browser-automation
tool available in this environment): backend endpoints live-validated against a real
Postgres instance via direct HTTP calls plus `psql` state verification, including genuine
concurrent-request races for both new exclusion constraints; frontend verified via live
API-contract-conformance testing against a real running backend, a standalone-script
reproduction of `deriveStartTimeCandidates`'s exact output against real derived-window data
(no frontend unit-test runner exists in this codebase), code review against the design
doc's own interaction/accessibility requirements, and clean `tsc -b`/`vite build`/`oxlint`
passes on every changed file across all six milestones.

- **Documentation updated as part of closing this feature** (this pass,
  `pronto-documentation`): see `overview.md`'s own "Professional Weekly Availability
  Calendar" §6 entry for the full list of docs touched by this closing pass (most package
  `.md` files were already kept substantially current by `pronto-coding` along the way, per
  the standing "don't let docs actively lie mid-flight" rule — this pass's job was the
  closing consistency/QA-status pass plus two genuinely new docs:
  `frontend/src/shared/utils/README.md` and `docs/architecture/api-contract-availability.md`).

## MS9 — Professional Dashboard & Home

- **Status: COMPLETE, QA-verified live (one follow-up bug fix, found and closed in the same
  pass, re-verified).** Working tree on `main`, uncommitted — not pushed/merged; that remains
  the user's own explicit git action. A small UI-structure change to the professional
  dashboard shell only — no backend change, no new components, no availability-domain logic
  touched. Full design record: `docs/architecture/product-ms9-dashboard-home-design.md`.
  **Not the same "MS9" as "Frontend Milestone 9 — Gap-fixes" above** — two separate,
  later, product-driven passes happen to share the number "MS9" in their own source
  material; this doc's title is deliberately distinct ("Professional Dashboard & Home") to
  avoid confusion, per the design doc's own explicit disambiguation note.
- **Scope actually built**:
  1. **Routing** (`app/router.tsx`): `/pro` is now `<Navigate to="/pro/availability" replace
     />` instead of directly rendering `IncomingRequestsPage` — the availability calendar
     (`WeeklyAvailabilityPage`, path unchanged) is now the professional's home screen after
     login. The former `/pro` content moved to its own path, `/pro/requests`, matching its
     nav label the same way `/pro/jobs`/`/pro/profile` already match theirs.
     `LoginForm.tsx`'s post-login redirect (`user.role === 'PROFESSIONAL' ? '/pro' : '/'`)
     and `AppLayout.tsx`'s "לוח בקרה" link both already targeted `/pro` — satisfied for free,
     no edit needed to either.
  2. **Sidebar restructure** (`features/dashboard/ProDashboardLayout.tsx`/`.module.css`): at
     `>=640px` the nav becomes a right-side sidebar — the RTL inline-start edge, the physical
     right in this `dir="rtl"` app — fixed `220px` width, filled/tinted active state
     (`--color-primary-light` background). At `<640px` it stays the original horizontal
     top-tab-bar. Each of the four nav items gained a `lucide-react` icon
     (`Inbox`/`ClipboardList`/`CalendarDays`/`User`), rendered only at `>=640px` — the mobile
     tab bar stays text-only, deliberately, per `DESIGN_SYSTEM.md` §54's existing mobile
     pattern.
- **QA-driven follow-up bug fix, found and closed in the same pass**: the first pass's
  `<640px` CSS caused the 4-tab strip to overflow the viewport at the narrowest real phone
  widths (320-375px) — `document.documentElement.scrollWidth` exceeded `innerWidth`. Fixed,
  scoped to `@media (max-width: 639.98px)` only: the `.tabs` strip becomes `overflow-x: auto`
  with `flex-wrap: nowrap` (a contained horizontal-scroll safety net, not a redesign), each
  `.tab` gets `flex-shrink: 0` plus tighter `padding-inline`/`font-size`, and the strip's
  `gap` is reduced. Re-verified live afterward: all 4 tabs present and reachable via scroll
  at 320/375/390/414/428px, active-state highlighting correct, `nav.overflowX === 'auto'`
  confirmed at each of the three narrowest widths.
- **Acceptance criteria — met**: `/pro` redirects to `/pro/availability`; the sidebar sits
  physically right of content at desktop widths with correct active-state highlighting; all
  four nav destinations (`/pro/requests`, `/pro/jobs`, `/pro/availability`, `/pro/profile`)
  are reachable and correctly highlighted at both desktop and mobile breakpoints;
  customer-side nav/routes are unaffected; zero console errors/warnings across the whole
  re-check run.
- **Known, pre-existing, out-of-scope bug found during this pass's QA — not caused by this
  milestone, not fixed here**: at the same 320-390px widths, `document.documentElement.
  scrollWidth` still exceeds the viewport (a fixed ~411px regardless of the 320/375/390px
  viewport tested) even after the dashboard-nav fix above closes its own contribution to that
  number. Root-caused to `AppLayout.tsx`'s global header nav (`.nav` in
  `AppLayout.module.css`), confirmed via `git stash` (the same overflow reproduces with this
  milestone's changes stashed out) — i.e. it predates MS9 and is unrelated to the
  professional-dashboard shell this pass touches. Flagged, not fixed (out of this pass's
  scope); a candidate for a future, dedicated pass. See `frontend/src/app/README.md`'s "Known
  issues" section.
- **Flagged, not resolved by this pass** (design doc §4/§5): landing on the calendar puts
  "בקשות חדשות" one click further from first paint than `DESIGN_SYSTEM.md`
  §23/`FRONTEND_AGENT.md` §37's "new requests must be immediately visible" guidance would
  otherwise suggest — a deliberate, explicit product decision for this task, not an
  oversight, recorded per the design doc's own instruction not to silently resolve the
  tension. No pending-count badge was added to the sidebar's "בקשות חדשות" item — flagged as
  a fast-follow candidate, not built this pass.
- **Live verification performed**: Playwright, against a real running frontend + backend —
  desktop (1280px): sidebar column layout, physical right-side placement, icon visibility,
  and active-state background all confirmed; mobile: nav row layout confirmed at multiple
  widths (320/375/390/414/428px), all 4 tabs present/reachable/correctly-highlighted, `/pro`
  redirect confirmed, customer-side top nav confirmed unaffected (no professional-dashboard
  link shown to a customer session, no console errors/warnings). No `tsc -b`/`vite
  build`/`oxlint` regressions (shell/CSS-only change).
- **Documentation updated as part of closing this pass**: `frontend/src/features/
  dashboard/README.md` (MS9 section expanded with the icon-visibility and mobile-overflow
  bugfix detail), `frontend/src/app/README.md` (Structure/Known-issues/Status sections),
  plus this entry and `overview.md`'s new "MS9 — Professional Dashboard & Home" §6 entry.
  `frontend/src/app/router.tsx`'s own header doc comment was already accurate, added by
  `pronto-coding` in the same pass as the code change — no correction needed.

## MS10 — Profile UI Redesign

- **Status: implemented and QA-signed-off, no known defects.** Working tree on `main`,
  uncommitted — not pushed/merged; that remains the user's own explicit git action. Full
  design record: `docs/architecture/product-ms10-profile-redesign-design.md`.
- **Scope actually built**:
  1. **Professional `/pro/profile`** (`features/dashboard/ProfileEditorPage.tsx`): the photo
     widget is now the new `shared/components/ProfilePhoto.tsx` — circular, centered,
     exactly one edit-in-place affordance (a small round icon button wired straight to a
     hidden file input, no separate "Add photo" control), click-to-enlarge via the new
     `shared/components/ImageLightbox.tsx`. The retired `ProfessionalProfileImageField.tsx`
     (+ `.module.css`) is deleted; upload orchestration moved directly into
     `ProfileEditorPage.tsx`. Layout became a responsive two-region design (`<900px` single
     column, `.card` max-width `560px`; `>=900px` a `240px 1fr` photo/form CSS grid, `.card`
     max-width `880px`), fixing the "large empty area on the left" root cause — the old
     `.card { max-width: 480px }` had no width-filling, so under `dir="rtl"` the unused space
     landed entirely on the physical left.
  2. **Shared `/profile`** (`app/ProfilePage.tsx`): both roles now render `ProfilePhoto` at
     the top; the old `justify-content: space-between` label/value row layout (label at one
     edge, value at the other) is replaced with "label directly above value," matching
     `Input`'s own label-above-control rhythm. A `CUSTOMER` caller additionally gets a real,
     always-editable form (`fullName`/`phone`/`defaultAddress` via the new
     `PUT /api/users/me`) — same "form + save button" pattern `ProfileEditorPage.tsx` already
     uses, not a view/edit-mode toggle. A `PROFESSIONAL` caller stays fully read-only (no
     product ask for a second editing surface for `fullName`, already editable at
     `/pro/profile`) but now sees their own existing photo, read-only
     (`user.professional.profileImageUrl`).
  3. **Backend**: new `PUT /api/users/me` — `CUSTOMER`-only (new
     `users.config.UsersWebConfig`, a route-level `RoleRequiredInterceptor` scoped to `PUT`
     only, mirroring `reviews.config.ReviewsWebConfig`'s existing "same path, different
     HTTP-method role gates" precedent, plus a defense-in-depth `403` re-check in
     `UsersService.updateMe`). Updates `fullName`/`phone`/all 7 `default_*` columns via the
     entity's already-existing setters — no new migration. `users.dto.ProfessionalInfo`
     gained `profileImageUrl` (resolved the same way
     `professionals.dto.ProfessionalProfileResponse.profileImageUrl` already is), so a
     professional's own photo can be shown on the shared `/profile` page.
- **Two ambiguities in the design doc, resolved by `pronto-lead` before `pronto-coding`
  started**:
  - §3.1 — "same circular, centered profile-image approach" for the customer reads as
    styling parity only (**Reading A**), not photo upload: a `CUSTOMER` gets `ProfilePhoto`
    rendered without `onUpload` — a non-upload initials avatar, no click-to-enlarge (nothing
    to enlarge). No schema change, no new endpoint, no `profileImageUrl` field for a
    customer under this milestone.
  - §3.2 — approved amending `defaultAddress`/`phone` from `api-contract.md`'s previous
    "read-only, no endpoint exists" language to editable via the new `PUT /api/users/me`.
    Deliberate, not a silent reinterpretation: `orders.service_city`/`service_street`/
    `service_house_number`/`service_apartment` are their own snapshot captured at
    order-creation time (`V18`), decoupled from `users.default_*`, so retroactively editing a
    saved default address has no correctness impact on any existing/in-flight order. `email`
    stays read-only on purpose (changing it would need to re-trigger email verification, a
    materially different, unrequested feature).
- **Testing**: backend unit-tested (`users.service.UsersServiceTest`, new) — the
  `CUSTOMER`-only `updateMe` happy path, the `PROFESSIONAL`-caller defense-in-depth `403`
  (and that it doesn't touch the `users` row at all), the deleted-user `401`, and both
  branches of the new `profileImageUrl` resolution (present/absent `profileImageKey`). No
  new `ErrorCode` values.
- **Frontend-only, incidental gap fix bundled into this pass**: `shared/api/users.ts`'s
  `UserMeResponse` type gained `phone` — already present on the real backend response since
  the professional weekly availability calendar design's M2, but missing from this frontend
  type until now (a pre-existing frontend-only gap, not new backend scope).
- **QA**: signed off, no known defects.
- **Documentation updated as part of closing this pass**: `docs/architecture/api-contract.md`
  §2.4/§2.6 (the new `PUT /api/users/me` entry, and the `defaultAddress`/`phone` amendment
  note), `frontend/src/shared/components/README.md`, `frontend/src/features/dashboard/
  README.md`, `frontend/src/app/README.md`, `backend/src/main/java/com/pronto/users/
  README.md` (all kept current by `pronto-coding` along the way, confirmed accurate by this
  closing pass — no correction needed); `frontend/src/shared/api/README.md` (a gap
  `pronto-coding` missed — the `users.ts`/`professionals.ts` entries didn't yet reflect
  `updateMe`/the `phone` gap-fix/the retired `ProfessionalProfileImageField.tsx` consumer
  reference, fixed in this pass); plus this entry and `overview.md`'s new "MS10 — Profile UI
  Redesign" §6 entry and package-table (§4) updates.
  `docs/architecture/data-model.md` was checked and confirmed to need no update — Reading A
  of §3.1 adds no column/migration, and no stray schema reference was found.

## MS11 — Services & Sub-services

- **Status: implemented and QA-signed-off, no known defects.** Working tree on `main`,
  uncommitted — not pushed/merged; that remains the user's own explicit git action. Full
  design record: `docs/architecture/product-ms11-sub-services-design.md`.
- **Scope actually built**:
  1. **Backend — data model**: `sub_services` (new reference table, child of `categories`,
     one level down — `V29__create_sub_services.sql`, create + a 34-row seed in one
     migration) and `professional_sub_services` (new join table —
     `V30__create_professional_sub_services.sql`, empty at migration time). Full specs in
     `data-model.md` §2.15/§2.16, verified column-for-column against the real migration
     files during this closing pass.
  2. **Backend — endpoints**: new public `GET /api/categories`
     (`professionals.controller.CategoriesController`, `permitAll()` added to
     `auth.config.SecurityConfig`) — every category, `display_order`-sorted, each with a
     nested, `display_order`-sorted `subServices` list; the concrete mechanism satisfying
     "support future service/sub-service changes without hardcoding the entire structure
     into the UI." New `PROFESSIONAL`-only `GET`/`PUT /api/professionals/me/sub-services`
     (`ProfessionalsController`/`ProfessionalsService`/`ProfessionalsWebConfig`), reusing
     `PUT /api/availability/working-hours`'s full-replace shape precedent exactly. `PUT`
     validates every requested id exists (unknown id → `400 VALIDATION_ERROR`) and belongs
     to the caller's own `category_id` (mismatch → `400 CATEGORY_MISMATCH` — confirmed this
     **reuses** the pre-existing `ErrorCode.CATEGORY_MISMATCH`, already used by
     `bookings.service.BookingsService#categoryMismatch`; no new error code was added), then
     applies a diff-based update (delete only removed rows, insert only newly-added rows,
     leave unchanged rows — and their `created_at` — untouched) inside one `@Transactional`
     method. Full endpoint spec, including both error cases:
     `docs/architecture/api-contract-professionals-reviews.md` §11.
  3. **Frontend**: new `shared/components/Checkbox.tsx` (+ `.module.css`) — the first-ever
     consumer of the `Checkbox` primitive `DESIGN_SYSTEM.md` §85 had listed by name but left
     unbuilt. `shared/api/professionals.ts` gained `getCategoriesWithSubServices`/
     `getMySubServices`/`updateMySubServices`. `/pro/profile`
     (`ProfileEditorPage.tsx`/`.module.css`) gained a sub-services checklist `<fieldset>`,
     below `basePrice`/above the main save button, scoped strictly to the professional's own
     `categoryId` (never shows another category's sub-services — reinforces that this is a
     within-one-category attribute, not multi-category support). Its own independent
     "שמירת תחומי עיסוק" save button/loading/error/success state, fully separate from the
     main form's `handleSubmit` — a deliberate, lead-approved second save button on the page
     (two separate backend endpoints already, no shared validation, folding them into one
     visual save would imply one atomic operation across two unrelated API calls for no real
     benefit).
- **Explicitly does not reopen single-category-per-professional.** `professionals.category_id`
  remains a single FK, unchanged — sub-services are a finer-grained, within-one-category
  descriptive attribute, not a second category axis. This framing is recorded permanently in
  `data-model.md` §2.4's `category_id` row (added by this closing pass, not just in the
  point-in-time design doc), so a future reader of that file alone won't misread the new
  `sub_services` table as evidence multi-category support was added.
- **Seed content is placeholder, explicitly flagged, not settled product copy.** The 34-row
  seed (`V29`) — Hebrew/English sub-service names and their category assignments — is
  invented content from this design/build pass, not sourced from any PRD/poster/other
  product document, and **needs real product sign-off before being treated as final**.
  Trivially editable later via a fresh migration; flagged in `data-model.md` §2.15,
  `professionals/README.md`'s "Assumptions" section, and `overview.md`'s corresponding
  entry — not left to read as finished copy in any of them.
- **What is deliberately not changed**: `shared/api/categories.ts`'s pre-existing static
  `CATEGORIES` mirror is left untouched, not migrated to the new `GET /api/categories`
  endpoint (proportionality call, flagged as a candidate follow-up ticket). No
  customer-facing sub-service filter/matching — Standard/SOS professional-listing queries
  are unchanged. `professionals.category_id`/`UpdateProfessionalProfileRequest`/`PUT
  /api/professionals/me` are all unchanged — sub-service selection is a fully separate
  endpoint pair.
- **Testing**: backend unit-tested (`professionals.service.ProfessionalsServiceTest`, new
  cases: unknown-id rejection → `VALIDATION_ERROR`, category-mismatch rejection →
  `CATEGORY_MISMATCH`, diff-based update preserving unchanged rows' `created_at`, empty-list
  save allowed). Full backend suite passing. Frontend: `tsc -b` clean.
- **QA**: signed off, no known defects.
- **Documentation updated/confirmed as part of closing this pass**:
  `docs/architecture/api-contract-professionals-reviews.md` §11 (confirmed complete — all
  three endpoints, request/response shapes, both validation error cases, and the
  reused-not-new `CATEGORY_MISMATCH` note); `docs/architecture/data-model.md` §1/§2.15/§2.16
  (confirmed column-for-column against the real `V29`/`V30` migration files)/§6 (ER diagram)
  and a new clarifying note on §2.4's `category_id` row (added this pass, see above);
  `frontend/src/shared/components/README.md`, `frontend/src/shared/api/README.md`,
  `frontend/src/features/dashboard/README.md`,
  `backend/src/main/java/com/pronto/professionals/README.md`,
  `backend/src/main/java/com/pronto/auth/README.md` (all updated by `pronto-coding` along
  the way and confirmed accurate by this closing pass — no correction needed); plus this
  entry and `overview.md`'s new "MS11 — Services & Sub-services" §6 entry and package-table
  (§4) updates. `docs/architecture/api-contract.md` was checked and confirmed to need no
  update — its §2.4 (`GET /api/users/me`) nests only the pre-existing `professional` summary
  object, never intended to carry sub-services; the new endpoints are a fully separate
  `/api/professionals/*`/`/api/categories` surface, no cross-reference gap found.

## MS12 — Availability UX Cleanup

- **Status: implemented and QA-signed-off, no known defects.** Working tree on `main`,
  uncommitted — not pushed/merged; that remains the user's own explicit git action. Full
  design record: `docs/architecture/product-ms12-availability-ux-cleanup-design.md`.
  Frontend-only, confined to `WeeklyAvailabilityPage.tsx`/`.module.css`; no backend change.
- **Scope actually built**: `WeeklyAvailabilityPage`'s post-setup branch no longer renders a
  permanently-visible working-hours summary list (`WorkingHoursSummary` deleted) —
  `WeeklyCalendarGrid` is now the sole dominant content block, immediately visible with no
  7-row list above it. The "עריכת שעות עבודה" entry point is now a real `Button`
  (`variant="secondary"`, `Pencil` icon) in a slim header row above the calendar, opening
  `WorkingHoursForm` inside the shared `Modal` primitive (`size="normal"`) — the same usage
  pattern `CalendarBlockModal.tsx` already established. This resolves the deviation the
  page's own doc comment had flagged since M3/M4: `professional-weekly-calendar-design.md`
  §7.2 always intended a modal for the edit entry point; the earlier inline-expansion was
  only a stand-in because `Modal.tsx` didn't exist yet at the time of that pass.
- `isEditingHours` (boolean, drove inline expand/collapse) renamed to `isEditModalOpen`
  (drives `Modal`'s `isOpen`); `handleSaved` now closes the modal instead of collapsing an
  inline `Card`. No change to `WorkingHoursForm`'s own validation, the `PUT
  /api/availability/working-hours` full-week-replace contract, or `WeeklyCalendarGrid`'s data
  fetching/polling/click-routing — `WeeklyCalendarGrid.tsx`, `WorkingHoursForm.tsx`, and
  `Modal.tsx` are all unchanged by this pass.
- CSS cleanup: removed now-dead `.summaryCard`/`.summaryRow`/`.summaryDay`/`.summaryHours`/
  `.summaryOff`/`.editLink` rules from `WeeklyAvailabilityPage.module.css`. Added one small
  new rule, `.editButtonLabel` (inline-flex + gap), since the shared `Button`'s own `.label`
  wrapper has no gap defined (every other `Button` usage in this codebase so far passes plain
  text, not an icon+text pair).
- AVAILABLE/BLOCKED/BOOKED visual states in `WeeklyCalendarGrid` were confirmed, during
  design review, to already meet the "distinct fill + icon/label, not color-only"
  accessibility bar — not touched by this pass.
- **QA**: signed off, no known defects (uncommitted working-tree changes).
- **Documentation updated as part of closing this pass**:
  `frontend/src/features/dashboard/README.md` (new MS12 section; also cleaned up the
  strikethrough-style historical edit marks left in its M3/M4 section into clean prose,
  since that section now describes behavior fully superseded by this pass),
  `docs/architecture/professional-weekly-calendar-design.md` §7.2 (a short forward-pointer
  note added, so a future reader doesn't mistake the M3/M4 inline-expansion deviation that
  doc self-flags as still current), plus this entry and `overview.md`'s new MS12 changelog
  bullet and `features/dashboard` package-table (§4) update.

## MS2 — Home + Authentication Experience

- **Status: implemented and QA-verified live, two real bugs found and fixed as part of
  this same pass (both re-verified) — zero known open bugs at final sign-off.** Working
  tree on `frontend/MS1-visual-foundation`, uncommitted — not pushed/merged; that remains
  the user's own explicit git action. Full design record:
  `docs/architecture/frontend-ms2-home-auth-design.md`. Builds directly on **MS1 — Visual
  Foundation & Motion System** (tokens, `Button`/`Card`/`Input`/`Modal`/`PageHeader`/
  `Mascot`, `framer-motion` variants), which shipped earlier on the same branch — MS1
  itself has no dedicated entry in this file or in `overview.md`'s §6 changelog (a
  pre-existing documentation gap, not introduced by this pass; flagged to `pronto-lead`
  rather than backfilled here, since accurately reconstructing it is outside this pass's
  scope — see each touched package's own `README.md` MS1 section instead, e.g.
  `frontend/src/shared/components/README.md`).
- **Scope actually built** (frontend-only, no backend change):
  1. **Home Hero** (`app/HomePage.tsx`/`.module.css`): an authenticated greeting line
     ("שלום, {first name} 👋"), the CTA panel rebuilt as a real horizontal flex row
     composing `Mascot` as a genuine layout child on the inline-end side (replacing MS1's
     placeholder absolutely-positioned overlay), and a new 3-item trust-indicator row
     (`ShieldCheck`/`Tag`/`Activity`, local JSX, single consumer — no new shared
     component), worded to describe real-time *status* notifications rather than
     live/GPS tracking (a hard v1.0 exclusion). Mobile stacks the CTA panel vertically and
     shrinks the mascot via CSS custom-property overrides, not a JS viewport check.
     Deliberately does **not** build `DESIGN_SYSTEM.md` §35's "Popular services" section
     (needs new product decisions out of scope) or a dedicated "Active booking" section
     (judged already served, product-wide, by the existing `ActiveOrderIndicator`).
  2. **Header/nav** (`app/AppLayout.tsx`/`.module.css`, new `app/BottomNav.tsx`/
     `.module.css`): desktop got a bigger brand (header `64px`→`72px`) and a demoted,
     icon-only logout button (`aria-label`/`title` only, no visible label). Mobile got a
     slimmed-down top bar (role-conditional links/profile link dropped from it entirely)
     plus a new fixed bottom nav — `CUSTOMER`-only, authenticated-only (same gating
     condition as `ActiveOrderIndicator`), 4 items (בית/הזמנות/מועדפים/פרופיל) per
     `DESIGN_SYSTEM.md` §50's explicit, binding list — **not** the milestone dispatch's
     own non-binding "Notifications" suggestion, resolved in the design doc's §3.3 in
     favor of the design system since `NotificationBell`'s dropdown already covers that
     capability without a dedicated route. Small required "spillover" fixes:
     `ActiveOrderIndicator.module.css`'s mobile `bottom` offset raised to clear the new
     bar; `AppLayout.module.css`'s `.main` gained a mobile `padding-block-end: 68px`;
     `BookingDraftIndicator.module.css` gained a `<360px` icon-only variant.
     `features/dashboard/ProDashboardLayout` and its sidebar/tab bar are untouched — out
     of scope, confirmed by direct inspection.
  3. **Auth screens** (`features/auth/*`): Login (`LoginPage.tsx`/`LoginForm.tsx`) and
     Register Choice (`RegisterChoicePage.tsx`/`RoleChooser.tsx`) got a visual pass with
     **zero logic changes** — a `Card`-wrapped login form, a reciprocal login link on
     Register Choice, two visually distinct role-option cards. Registration became
     progressive multi-stage wizards behind a new shared `RegistrationWizardShell.tsx`
     (justified by two real, simultaneous consumers — `CustomerRegisterForm`,
     `ProfessionalRegisterForm`). Customer: 3 stages (basic info incl. new `phone` field /
     address / confirmation), with submit-time field errors routed back to the stage that
     owns the offending field. Professional: **4 stages, not the milestone dispatch's
     originally-requested 6** — a deliberate, approved deviation, verified directly
     against `backend/.../auth/service/AuthService.java` and
     `ProfessionalRegistrationData`: neither `register()` nor `verify()` returns a JWT, so
     there is no authenticated session available at any point during or immediately after
     registration, and `ProfessionalRegistrationData` has no sub-service/availability
     field at all — collecting such data in the wizard and silently discarding it on
     submit would violate this codebase's "never fake product functionality" rule. The 4
     stages instead fold that content into an honest, non-interactive sub-service preview
     (stage 2) and a "what's next" block (stage 4). Full audit: design doc §6.5-6.6,
     `features/auth/README.md`.
- **P0 bug fix, pre-existing, found during this pass**: `phone` was entirely missing from
  customer registration end-to-end — `backend/.../auth/dto/CustomerRegistrationData.java`'s
  `phone` is `@NotBlank`, but `shared/api/auth.ts`'s `RegisterCustomerPayload`/
  `RegisterRequestData.customer` had no `phone` field at all, and the customer form never
  collected it — every real customer signup was silently getting a `400`. Fixed across all
  three layers: `shared/api/auth.ts` (sent as `customer.phone`, sibling of `defaultAddress`,
  not nested inside it), a new stage-1 `Input` (required, non-blank validation only, no
  format regex), and the final `registerCustomer()` payload.
- **Real bug found and fixed mid-milestone (introduced by this pass's own header
  redesign, not pre-existing)**: a `PROFESSIONAL` mobile session initially had zero
  nav-reachable logout after the header redesign — `BottomNav` is `CUSTOMER`-only,
  `ProDashboardLayout`'s tab bar has no logout entry, and `/profile` (which does have a
  logout button) had no mobile nav link pointing to it for that role. Fixed by moving
  `AppLayout.tsx`'s `.logoutButton` to render as a sibling *after* `.desktopOnlyNav`
  closes, instead of nested inside it, so it's visible at every viewport width for every
  authenticated user regardless of role — zero effect on desktop layout.
- **Two more real bugs found by QA and fixed in the same pass, both re-verified live**:
  (a) `prefers-reduced-motion` wasn't actually respected on any of this milestone's new
  `framer-motion` usages (`HomePage.tsx`'s hero/mascot, `LoginPage.tsx`,
  `RegisterChoicePage.tsx`/`RoleChooser.tsx`, both registration pages' outer wrapper, and
  — discovered mid-fix, then also fixed — `RegistrationWizardShell.tsx`'s own
  stage-transition usage). The initially-attempted fix technique (overriding the
  component's `transition` prop) does **not** work for any variant whose own `animate`
  target embeds a `transition`, confirmed live via Playwright
  (`reducedMotion: 'reduce'` context, rAF-sampled opacity) — framer-motion gives the
  variant-level `transition` precedence over the component-level prop. The working fix
  overrides the resolved `animate`/`initial`/`exit` target objects directly, matching
  `Modal.tsx`/`ToastViewport.tsx`'s existing MS1 pattern. (b) `NotificationBell`'s
  dropdown panel clipped off-screen at the new, narrower mobile top bar (this milestone's
  own regression) — fixed via a `<640px` `position: fixed` + symmetric `inset-inline`
  insets override anchored to the viewport instead of the bell's own wrapper; desktop
  unchanged.
- **QA**: full live Playwright verification against a real running frontend + a real
  backend/Postgres instance, including a complete re-verification pass after all three
  bug-fix rounds above (mobile-logout, phone, reduced-motion, notification-dropdown) —
  zero known open bugs at final sign-off. Build and lint also re-run clean. Method
  consistent with every prior frontend milestone in this project.
- **Documentation updated as part of closing this pass**: `frontend/src/app/README.md`,
  `frontend/src/features/auth/README.md`, `frontend/src/features/notifications/README.md`
  (all substantially kept current by the implementing agents along the way — this closing
  pass verified accuracy/gap-filled rather than writing from scratch; no material
  inaccuracies found in any of the three); plus this entry and `overview.md`'s new MS2
  changelog bullet and `app`/`features/auth`/`features/notifications` package-table (§4)
  updates.

## MS3 — Issue Creation + AI Experience

- **Status: implemented and QA-signed-off, zero known open bugs.** Working tree on
  `frontend/MS1-visual-foundation`, uncommitted — not pushed/merged; that remains the
  user's own explicit git action. Full design record:
  `docs/architecture/frontend-ms3-issue-ai-design.md`. Visual/UX redesign layered onto the
  existing `features/issues` step-machine logic (built in Frontend Milestone 2 and extended
  by the MS3/MS4 product-corrections booking-draft-persistence pass) — every
  behavior/contract in that logic is preserved; only presentation, composition, and two
  small, explicitly disclosed copy-accuracy fixes change.
- **Scope actually built** (frontend-only, no backend change, all within
  `frontend/src/features/issues/`):
  1. **New: `AiAnalyzingOverlay.tsx`/`.module.css`.** A branded "Pronto בודק את התקלה..."
     loading state (`Mascot state="thinking" size="lg" loop` + a status line inside a
     `role="status" aria-live="polite"` region + a CSS-only 3-dot pulse — no fake
     percentage/progress indicator per DESIGN_SYSTEM §41), replacing the bare `Button`
     spinner the two real `classifyIssue` call sites (`DescribeIssueStep.handleSubmit`,
     `ClarifyQuestionsStep.handleContinue`) previously showed alone. Each call site gained
     a new `onAnalyzingChange` callback prop, invoked `true` immediately before the
     existing `classifyIssue(...)` call and `false` in the existing `finally` block — no
     other line in either component's `try`/`catch`/`finally` moved or reordered;
     `isSubmitting`/`setIsSubmitting` are untouched (kept as redundant double-submit
     defense, harmless since the overlay visually covers the button anyway).
     `NewIssuePage` is the single render site: `<AiAnalyzingOverlay variant="overlay"
     show={isAnalyzing} />`, absolutely positioned over a `position: relative`
     step-viewport wrapper, with `aria-hidden={isAnalyzing}` on the wrapper and
     `pointer-events: auto` on the overlay itself while shown.
     - **Architecture decision: not a `Step`-union member.** The alternative — adding
       `{ name: 'analyzing' }` to `NewIssuePage`'s `Step` union — was rejected because
       `ClarifyQuestionsStep` holds its `answers: Record<string, string>` as **local,
       non-lifted** state (not part of `useBookingDraft()`'s persisted shape). Today, if
       `classifyIssue` fails from that step, the component never unmounts (only its
       `isSubmitting` flag toggles), so already-selected answers survive a failed request.
       Making `'analyzing'` a real step would force `ClarifyQuestionsStep` to unmount while
       it showed and remount on failure, silently losing `answers` — a real regression this
       milestone must not introduce unless `answers` were additionally lifted into the
       draft, which is unwarranted new surface area for a purely visual milestone. The
       chosen design — a sibling overlay over the still-mounted step — avoids that risk
       entirely and also keeps `updateDraft()`'s stage vocabulary
       (`ISSUE_DESCRIBE`/`ISSUE_CLARIFY`/`ISSUE_REVIEW`) untouched (no matching durable
       `'analyzing'` stage exists or should exist).
  2. **`isResuming` unified with the same overlay.** The old plain "טוענים את הבקשה
     שלכם…" paragraph is gone. `isResuming` (only ever `true` when resuming into
     `ISSUE_CLARIFY`/`ISSUE_REVIEW`, which re-calls `classifyIssue` the same way) now
     renders `<AiAnalyzingOverlay variant="inline" show={isResuming} />` — always mounted
     (not conditionally rendered) so the component's own internal `AnimatePresence` plays
     an exit transition instead of disappearing instantly. The existing
     `isResuming`/`setIsResuming(false)` state machine is otherwise untouched — only what
     renders while it's `true` changed. The `ISSUE_DESCRIBE`-only resume case (photo-only
     re-resolution, no `classifyIssue` call) was not touched — that path never sets
     `isResuming` to `true` in the first place.
  3. **Step-to-step transition (`stepTransition`, new consumer).** `NewIssuePage`'s
     `describe`/`clarify`/`review`/`success` conditional chain is now wrapped in
     `AnimatePresence mode="wait"` keyed on `step.name`, using
     `shared/motion/variants.ts`'s `stepTransition` — its first real consumer in the app
     (the variant's own doc comment already named this exact use case). `direction`
     (`1`/`-1`) is new local `NewIssuePage` state, defaulting to `1`, flipping to `-1` only
     in `handleBack`, reset to `1` in `handleClassified`/`handleConfirmed` — the
     reduced-motion-neutralization boilerplate is copied locally from
     `RegistrationWizardShell.tsx`'s existing pattern, not extracted into `shared/motion`
     this milestone (flagged as a future cleanup once a third consumer appears).
     `PageHeader` also now actually wires its previously-built-but-unused `steps` progress
     prop, via a new `STEP_NUMBERS` map (`describe: 1, clarify: 2, review: 3`), omitted
     entirely on `'success'`.
  4. **Describe Issue composer redesign** (`DescribeIssueStep`): whole form wrapped in a
     `Card` with a small static `Mascot state="idle" size="sm"` (same restrained pattern
     MS2 gave `LoginForm`); new example-prompt chips (4, local array, `FilterChip`-styled
     via local CSS not the component itself) shown only while the description field is
     empty, prefilling `description` via the existing `onDescriptionChange` prop on click;
     the two plain urgency buttons replaced with two larger icon+title+subcopy toggle cards
     (רגיל/SOS — the SOS card's inline microcopy is now visible before selection, not only
     after — a deliberate, minor, positive visibility-timing change; the underlying
     `urgencyType`/`onUrgencyChange` contract is byte-for-byte identical). New copy: label
     "מה קרה?" → "ספר לי מה קרה", new hint line, new urgency-section heading "באיזו דחיפות
     מדובר?".
  5. **Clarification-question redesign** (`ClarifyQuestionsStep`): `.option` buttons
     restyled larger (min-height ~56px) with a leading `Circle`/`CheckCircle2` selection
     icon (`lucide-react`) and proper `role="radiogroup"`/`role="radio"`/`aria-checked`
     added (accessibility addition, not a behavior change). New: per-question progressive
     reveal for the multi-question case only (`questions.length > 1`) — a local
     `visibleCount` state, incremented whenever the currently-last-visible question
     receives an answer, newly revealed questions mounting via `AnimatePresence` using
     `pageTransition`'s exact shape (reused directly, no new variant); the single-question
     case renders all-at-once, restyled only. `answers` state, `allAnswered` gating, and
     `handleContinue`'s payload are byte-for-byte unchanged. `onAnalyzingChange(true)`
     added before, `onAnalyzingChange(false)` in the existing `finally`, around the
     existing `classifyIssue` call — no other reordering.
  6. **Review/diagnosis-card redesign** (`ReviewStep`): eyebrow "מצאנו את בעל המקצוע
     המתאים" (factually premature at this point — no professional is matched yet) →
     "האבחון שלנו"; headline is now dynamic — "נראה שמדובר בתקלה ב־{קטגוריה}" (category
     interpolated via the existing `getCategoryNameHe(...)` call, no new lookup),
     replacing the flat `<h2>{category}</h2>`; a new static reassurance line follows ("כך
     נמצא לך את בעל המקצוע הכי מתאים."). If `urgencyType === 'SOS'`, a new inline
     "SOS — דחוף" badge renders next to the headline using `--color-sos`/`--color-sos-bg`
     tokens directly (not `Badge` — its `BadgeTone` enum has no `'sos'` value, adding one
     was judged out of this milestone's scope). The existing "זה לא נכון? שינוי תחום"
     change-link mechanism and "אישור והמשך" confirm button are kept exactly, only
     restyled. `handleConfirm`/`createIssue`'s payload, `categoryId`/`isChangingCategory`
     state, and `onConfirmed` are unchanged.
  7. **Success-screen redesign** (`IssueSuccessStep`): the plain `✓` circle span is
     replaced with `<Mascot state="success" size="xl" label="הבקשה נשלחה בהצלחה" />`
     (meaningful now, not decorative) + new headline "הבנתי. עכשיו נמצא לך מישהו."
     (replacing "הבקשה נשלחה"). `state="success"` (not `"found"`) was the deliberate
     choice — `"found"` is reserved by naming consistency for the actual
     "found-a-professional" moment inside `features/booking`, a later milestone; `"success"`
     has its own distinct artwork + `successPop`'s celebratory scale-in, matching this
     component's own doc comment ("Calm confirmation state after `POST /api/issues`
     succeeds"). Body copy (both `isStandard` branches), both CTA labels/destinations
     (`/issues/${issueId}/booking` / `/issues/${issueId}/sos-booking`), and the secondary
     "חזרה לדף הבית" button are byte-for-byte unchanged. **No auto-navigation** — the CTA
     stays a required, explicit user action; DESIGN_SYSTEM §78's own worked example ends in
     a manual button, not an auto-redirect, and auto-advancing would remove the user's
     ability to actually read a confirmation screen whose SOS-vs-STANDARD copy materially
     differs. The headline/body/actions block is now wrapped in `listStagger` with each
     item reusing `pageTransition`'s fade+rise shape, so the mascot pops in first and the
     text/CTAs settle in just after.
- **Explicit, disclosed decision: `classification.explanation`/`.confidence` still never
  rendered anywhere.** The design doc's dispatch permitted, but did not require, rendering
  a short reworded sentence derived from `explanation`. Kept unrendered, deliberately, for
  a reason verified directly against the real backend source (not a cautious default):
  in real (OpenAI) mode, `OpenAiClassificationClient.java`'s own prompt instructs
  `"explanation must be short and in English"` — rendering it verbatim would put
  unlocalized English text inside an otherwise fully Hebrew, RTL screen, contradicting
  Pronto's v1.0 Hebrew-only scope. In mock mode, `MockAiClassificationClient.java`'s
  `explanation` strings literally read `"[מוק] סיווג לפי מילת המפתח ... שזוהתה בתיאור"` —
  i.e. they name the internal keyword-matching mechanism by name, exactly the class of
  "expose implementation terminology" DESIGN_SYSTEM §40/FRONTEND_AGENT §14 forbid. The
  "supporting explanation when useful" ask is instead satisfied by the new static
  reassurance line + SOS badge described above — real, accurate, always-safe content, not
  the model's own raw reasoning text.
- **Two small, disclosed opportunistic fixes**, both found while touching these files for
  the redesign, neither part of the original dispatch:
  1. `DescribeIssueStep`'s length-validation error message previously read "יש לתאר את
     התקלה בלפחות 10 תווים" — only mentioning the 10-character minimum — even though the
     same `if` condition (`trimmed.length < 10 || trimmed.length > 2000`) also enforces a
     2000-character maximum, which the message never disclosed. Fixed to "יש לתאר את
     התקלה באורך של 10 עד 2000 תווים" — same condition, same early-return, only the string
     changed.
  2. `IssueSuccessStep`'s back button was previously rendered and clickable but a dead
     no-op: `handleBack`'s body only handles the `describe` step and the "not success"
     case, so on `'success'` it silently did nothing while `PageHeader`'s back arrow still
     showed. Fixed via `onBack={step.name === 'success' ? undefined : handleBack}` —
     `PageHeader` already supports omitting `onBack` to hide the arrow entirely; the
     success screen already has its own explicit "חזרה לדף הבית" action, so nothing is
     lost.
- **QA**: live Playwright testing against a real running backend (mock AI mode) and a real
  frontend, including intercepted/mocked `classify` responses to exercise the
  `ClarifyQuestionsStep` `QUESTIONS` path directly (the mock AI classifier never naturally
  returns `status: "QUESTIONS"` on its own, so this path needed a mocked response to
  reach) — zero bugs found, full pass on the first run.
- **Documentation updated as part of closing this pass**: this entry,
  `overview.md`'s new MS3 changelog bullet and `features/issues` package-table (§4)
  update, and `frontend/src/features/issues/README.md` (kept current by the implementing
  agent as part of the same pass — this closing pass verified accuracy rather than writing
  from scratch; no inaccuracies found).

## MS4 — Booking & Professional Marketplace

- **Status: implemented, QA-signed-off (`pronto-qa`, 61/61 assertions passed across 8 scope
  areas, zero application bugs found).** Working tree on branch
  `frontend/MS4-booking-marketplace`, uncommitted — not pushed/merged; that remains the
  user's own explicit git action. Full design record:
  `docs/architecture/frontend-ms4-booking-marketplace-design.md`. Unlike MS1-MS3 (built from
  a fresh dispatch) and much like MS6, this milestone's own audit found the
  booking/professional-marketplace module (`features/booking`, `features/professionals`)
  **already functionally complete** — built in Frontend Milestones 3/4/8 (old numbering),
  after MS1's design-token system landed, and already token-compliant (design doc §0/§1). The
  actual work was a **motion/polish/component-reuse consistency pass** bringing this module in
  line with the sibling `features/issues` module's MS3 patterns, plus one real
  information-architecture change to `MyOrdersPage`.
- **Scope actually built** (frontend-only, no backend change of any kind):
  1. **Step-transition motion**: `BookingFlowPage.tsx`/`SosBookingFlowPage.tsx` each gained a
     `direction` state + `AnimatePresence mode="wait"` + `stepTransition` around a
     `styles.stepViewport` wrapper — the same pattern `NewIssuePage.tsx` (MS3) already
     established for its own step machine.
  2. **`listStagger` entrance animation** on `ProfessionalList.tsx`'s result list — the first
     8 cards get a staggered per-item fade/rise, the rest render without it (per
     `shared/motion/variants.ts`'s own documented stagger-cap guideline).
  3. **New shared `BookingSuccessStep.tsx`/`.module.css`** (`features/booking/`), extracted
     from duplicated hand-rolled success JSX/CSS previously in both flow pages — uses
     `Mascot state="success"` + `listStagger`, mirroring `features/issues/
     IssueSuccessStep.tsx`'s structure. Takes plain `title`/`body` string props (design doc §4
     Q2, resolved — matches `EmptyState.tsx`'s "component owns layout, caller owns copy"
     convention, not a `variant` enum).
  4. **`Mascot state="searching"`** added to the professional-search loading moment in both
     flow pages, replacing bare loading text.
  5. **`PageHeader`'s existing `steps` progress-indicator prop wired up** in both flow pages —
     was previously unused there despite already existing on the component.
  6. **Shared `Skeleton` component loading states** added to `MyOrdersPage.tsx`,
     `OrderTrackingPage.tsx` (top-level and category sub-loading case, replacing an
     inconsistent inline skeleton), `ProfessionalProfilePage.tsx`, `CompletionReviewPage.tsx`.
  7. **`AddressSelectionStep.tsx`'s hand-rolled chip toggle replaced with the shared
     `FilterChipGroup` component** — gains correct `role="radiogroup"`/`role="radio"`
     accessibility semantics the hand-rolled version lacked.
  8. **`EmptyState` component reuse** in `ProfessionalList.tsx` and `MyOrdersPage.tsx`,
     replacing hand-rolled empty markup.
  9. **`MyOrdersPage.tsx` IA change (design doc §4 Q1, resolved by explicit user decision)**: a
     new client-side `bucketOrders()` function splits the customer's orders into "פעילות
     וקרובות" (Active/Upcoming — everything not in a terminal status) and "היסטוריה" (History
     — `COMPLETED`/`CANCELLED`/`REJECTED`/`EXPIRED`), mirroring
     `features/dashboard/MyJobsPage.tsx`'s MS6 sectioning pattern (adapted to 2 sections
     instead of 3), with a per-section `EmptyState`; row markup and the underlying data fetch
     are unchanged.
- **Explicitly untouched, confirmed by the design doc's own file-by-file audit**:
  `ProfessionalCard.tsx`, `ProfessionalProfileDisplay.tsx`, `ReviewList.tsx`,
  `BookingSummary.tsx`, `SosBookingSummary.tsx`, `StartTimePicker.tsx`'s core chip logic, all
  `shared/api/**` contracts, booking-draft persistence, resume-hydration logic,
  `app/router.tsx` — no backend change of any kind. `pronto-qa` ran a regression check
  confirming zero diff on these files vs. `main`.
- **QA**: `pronto-qa` ran live Playwright verification against a real running frontend +
  backend — 61/61 assertions passed across 8 scope areas (both booking flows' step
  transitions/mascot/success screens, professional list stagger, address-selection chips, all
  4 skeleton states, `MyOrdersPage` grouping including empty-section and zero-order edge
  cases, reduced-motion behavior, and the untouched-files regression check above). Zero
  application bugs found. `tsc -b`/`vite build` and `oxlint` both clean. Two minor,
  non-blocking observations noted (not defects, no fix required): (a) `BookingSuccessStep`'s
  copy is currently identical between the Standard and SOS success screens — accepted per the
  resolved plain-props decision, the component stays copy-agnostic by design; (b) a
  **pre-existing** (not introduced by this milestone) UX gap where booking the soonest
  available slot and then dawdling before confirming can surface a generic `400` error
  instead of routing the customer back to slot selection — flagged for awareness, no fix
  attempted.
- **Documentation updated as part of closing this pass**: this entry,
  `docs/architecture/frontend-ms4-booking-marketplace-design.md` §4 (resolution notes for
  both open questions, appended without rewriting the original questions — matching the MS6
  design doc's own "resolved" annotation pattern), `overview.md`'s new MS4 changelog bullet
  and the `features/booking`/`features/professionals` package-table (§4) rows,
  `frontend/src/features/booking/README.md` (new MS4 section), and
  `frontend/src/features/professionals/README.md` (new MS4 section).
- **Final corrections before close (2026-08-20, requested by the user after reading the QA
  sign-off above).** Full record: design doc §4b; per-package detail in the two feature
  READMEs. Verified live — **43/43 Playwright assertions** across both booking flows, both
  stale-slot paths, the professional list/card, the slot picker, the confirmation card, the
  profile page, mobile 390×844, RTL, and reduced motion; `tsc -b` and `oxlint` clean. Both of
  QA's "non-blocking observations" above are now **fixed**, and the visual pass revised two
  conclusions this milestone's own audit had reached:
  1. **Stale-slot flow (was observation (b))**: root-caused in the backend — a non-future
     `bookedStart` returns a plain `400 VALIDATION_ERROR` with a `bookedStart` field error and
     **no dedicated error code**, so the frontend's code-keyed lookup fell through to the
     generic message. Fixed frontend-only in four layers (candidate filtering via a new
     `notBeforeMs` option on `deriveStartTimeCandidates`; a 30s live-clock re-derive in
     `StartTimePicker` with an `onSelectedExpired` callback; a pre-flight guard in
     `BookingSummary`; and field-error recognition of the 400 itself), all routing into the
     existing "back to the picker + re-fetch availability" path, with the message rendered as
     an info notice rather than an error banner.
  2. **Standard vs SOS success copy (was observation (a))**: caller-side only —
     `BookingSuccessStep`'s copy-agnostic `title`/`body` prop shape is unchanged, which is
     what made this a two-line edit. Standard reads the confirmed date/time back; SOS speaks
     to the immediate wait.
  3. **Visual pass against the design goals** (as opposed to the functional QA above), which
     found real gaps a token/CSS audit cannot see: §29's profession line and §33's
     "מומלץ עבורך" badge were both absent from `ProfessionalCard` (the badge's `Badge`
     `tone="primary"` had been built for it in MS1 and never used); §46's clock-aligned times
     — the picker really showed `14:02 · 14:32` because candidates were gridded from each
     window's own `startAt`; §48's visit-price line was missing from `BookingSummary`; and the
     profile page carried no §43/§44 trust signal despite the DTO returning `approvalStatus`
     and `createdAt`. Also fixed: `1 ביקורות` → `ביקורת אחת` (new shared `formatReviewCount`,
     three call sites), the favourite button's past-tense `הוסר ממועדפים` label, and empty
     rating/review states that rendered nothing where a fact belongs.
  This supersedes the "Explicitly untouched" list above for `ProfessionalCard.tsx`,
  `ProfessionalProfileDisplay.tsx`, `ReviewList.tsx`, `BookingSummary.tsx`, and
  `StartTimePicker.tsx` — all five changed in this pass. `SosBookingSummary.tsx`,
  `shared/api/**`, booking-draft persistence, and `app/router.tsx` remain untouched, and there
  is still **no backend change of any kind**.
  **Carried forward to MS5, not fixed here**: on mobile the global `ActiveOrderIndicator` FAB
  overlaps the left edge of the step CTA and of the profile page's favourite button — a
  pre-existing global overlay whose own milestone (MS5, Active Order & Customer Experience) is
  the right place to resolve it.
- **Documentation gap discovered, flagged, not silently fixed as part of this pass**: unlike
  MS1-MS3, the earlier **MS6 — Professional Command Center** redesign milestone (implemented
  2026-08-20, per `frontend/src/features/dashboard/README.md`'s and
  `frontend/src/features/professionals/README.md`'s own MS6 sections and
  `docs/architecture/frontend-ms6-professional-command-center-design.md`) was never given its
  own entry in this file or in `overview.md` (no changelog bullet, no package-table-row
  mention). This MS4 entry's own "matching the format of the existing MS1-MS3/MS6 redesign
  entries" instruction assumed an MS6 entry existed here to match against; it does not. Not
  backfilled as part of this pass (out of this task's scope, and reconstructing it accurately
  belongs to whoever closed MS6) — flagged to `pronto-lead` per this agent's standing
  responsibility to surface drifted/missing documentation.

## MS5 — Active Order & Customer Experience

- **Status: implemented, verified live (47/47 Playwright assertions), 2026-08-20.** Working
  tree on `main`, uncommitted at time of writing — push/merge remains the user's own explicit
  git action. Full design record:
  `docs/architecture/frontend-ms5-active-order-design.md` (audit §§0-3 as first written,
  resolutions in §4, build record in §7).
- **Why this one was different from MS4/MS6**: both of those found their module already built
  and needed a consistency pass. Here the data plumbing was sound — `usePolling`/
  `useOrderStatus`, the persisted `expectedArrivalAt`, `useEtaCountdown`, role-gated actions,
  the floating indicator's priority algorithm — but **the screen DESIGN_SYSTEM.md §79 describes
  did not exist**. `OrderTrackingPage` rendered one identical card for all seven `OrderStatus`
  values; the status was a pale 28px badge in that card's corner, and the loudest element on
  the page was a full-width red cancel button.
- **Built** (frontend-only, no backend change):
  1. **`OrderStatusHero.tsx`/`.module.css` (new)** — per-status headline + `Mascot` +, while
     `ON_THE_WAY`, the ETA as a 34px figure (§76: the most important number on that screen).
     `Mascot state="running"` ("Pronto is coming to you") existed for this moment and had never
     been used. Copy avoids gendered verb forms — the product records no gender for
     professionals.
  2. **`OrderProgressStepper.tsx`/`.module.css` (new)** — `נשלחה → אושרה → בדרך → הושלמה` from
     `orderStatus` alone. Timestamp-free by necessity *and* by honesty: `orders` persists no
     per-status transition times, so no stage claims "אושר ב-14:12". Absent entirely for
     terminal-negative statuses; fully checked, with no "current" stage, once `COMPLETED`.
  3. **Cancel behind a confirmation** — previously one click, irreversible, and the visually
     loudest thing on screen. Now a `Modal` explaining what cancelling does, with the trigger
     demoted from `destructive` to `ghost`.
  4. **Terminal states got next steps**, split by what the backend actually does (read from
     `BookingsService`, not assumed): `CANCELLED`/`REJECTED` re-book the same issue, since both
     paths call `releaseSlotAndReopenIssue`; `EXPIRED` opens a new request, since
     `expireIfBooked` expires the issue too.
  5. **Review CTA promoted** from a bare text link at the page bottom into the completed hero.
  6. **`CompletionReviewPage`** — question heading, a named rating scale doubling as each star's
     accessible name, and a §78 calm-success submitted state on the `Mascot state="success"`
     pattern. Fetch/acknowledge/submit logic untouched.
  7. **`MyOrdersPage`** — live ETA on `ON_THE_WAY` rows from `OrderSummary.expectedArrivalAt`.
  8. **Mobile FAB clearance** — `ActiveOrderIndicator` toggles a body class while mounted, and
     `AppLayout` reserves ~80px of mobile scroll clearance only while it is there. Closes the
     overlap MS4 flagged and deferred here.
- **Resolved decisions**:
  - **Phone asymmetry is a product rule, not a gap (design doc §4 Q1, explicit user decision)**:
    the customer never receives the professional's phone number — describing the issue inside
    Pronto is the flow, and a phone number invites bypassing it — while the professional does
    receive the customer's for an assigned order (already true in code). No professional-phone
    registration field, DB column, migration or `professionalPhone` DTO field was added. The
    design doc's original framing of this as a gap was wrong and is corrected in place.
  - **Customer viewer only (§4 Q2)**: the professional's view of `/orders/:orderId` is
    unchanged — MS6 owns the professional surfaces.
- **Bug found while verifying, fixed**: `shared/hooks/usePolling.ts` silently dropped an
  explicit `refetch()` whenever a poll tick happened to be in flight — its in-flight guard did
  not distinguish "skip an overlapping poll" from "the user just changed this data". Reproduced
  live: confirming a cancellation left the pre-cancel status on screen for ~4s. Explicit
  refetches are now queued and run as soon as the in-flight request settles; this also covers
  the professional's `יציאה לדרך`/`סיום העבודה` actions.
- **QA**: one order seeded in every customer-visible status (`PENDING`/`CONFIRMED`/
  `ON_THE_WAY`/`COMPLETED`/`CANCELLED`/`REJECTED`), each checked for the right hero headline,
  the right stepper shape/current stage, and the absence of any phone-shaped string on the
  customer's screen; plus the cancel dialog (dismiss leaves the order alone, confirm actually
  cancels and the hero switches), the professional's unchanged view, the review screen
  end-to-end, mobile 390×844 overflow + FAB clearance, RTL, and reduced motion. `tsc -b` and
  `oxlint` clean. **`EXPIRED` is not covered live** — only a scheduled sweep produces it, so it
  cannot be forced from a harness; code-reviewed only, recorded rather than counted as passing.
- **Documentation updated**: this entry, `overview.md`'s changelog + `features/booking`
  package-table row, `frontend/src/features/booking/README.md`,
  `frontend/src/shared/hooks/README.md` (the `usePolling` fix), and the design doc's §4/§7.

## Profession roulette — issue → matching → professionals

- **Flow adjusted 2026-08-20 (same day): the service address is collected *before* the wheel.**
  `/issues/:issueId/matching` became a two-phase screen (address → roulette) rather than
  starting the animation straight after classification. The reason is not cosmetic: the listing
  endpoint takes the service address and derives service-area relevance, distance and ETA from
  it, so preloading before the address is known means preloading against the wrong location.
  The address step is `features/booking`'s `AddressSelectionStep`, imported rather than
  reimplemented. A one-off address never writes back to the profile default. Both phases write
  the same booking draft, so refresh resumes into the animation and the booking flow never
  re-asks — its own address step remains one explicit "back" away.
- **UI/timing corrections the same day**: figures are standalone (no card containers), the wheel
  scales 360/500/620px across mobile/tablet/desktop rather than one fixed size, and the landed
  result is held ~1s longer (900ms → 1900ms) before the automatic hand-off.

- **Status: implemented, verified live (27/27 Playwright assertions), 2026-08-20.** Working tree
  on `main`, uncommitted. Replaces the intermediate CTA screen at the end of the issue flow.
- **What was removed**: `features/issues/IssueSuccessStep.tsx`/`.module.css`, deleted outright
  (not hidden behind a flag or left unrouted), along with `NewIssuePage`'s `'success'` step and
  the `step.name !== 'success'` special cases its back button and header carried. Nothing else
  consumed it.
- **What replaced it**: a dedicated route, `/issues/:issueId/matching`. A route rather than a
  step so a refresh re-derives the category from the issue rather than losing it with component
  state — one of the brief's explicit edge cases.
- **Determinism**: the wheel's final angle is `360 * SPINS - targetIndex * segmentAngle`, where
  `targetIndex` is the issue's own classified category's fixed position. No random draw exists in
  the component. Verified across four categories driven through the real UI (plumbing,
  locksmith, AC, carpentry — the last of which V31 later retired into Handyman), each
  landing on its own profession.
- **Preloading without duplication**: a single-entry, path-keyed, single-use, TTL-bounded
  prefetch cache in `shared/api/bookings.ts`. Asserted live: exactly one
  `/api/bookings/professionals` request across the roulette and the listing screen (and one
  `/api/bookings/sos-professionals` on the SOS path).
- **Two real bugs found and fixed during verification, both by measuring rather than assuming**:
  1. *Mobile horizontal scroll mid-spin.* A rotating square reports a bounding box up to 1.41x
     its side, so the wheel widened the document at some angles and the mobile browser zoomed
     out to compensate. Fixed by rotating a zero-size pivot instead of a full-size square (the
     ring is static scenery and never needed to rotate), sizing the radius from the faces'
     diagonal rather than their width, and clipping the stage as a backstop. Re-probed at ten
     points through the spin: zero overshoot.
  2. *The wheel sat a face-width off-centre under RTL.* The geometry used logical inset
     properties (`inset-inline-start`), which flip which edge is the anchor — but the rotation
     maths is physical. Switched to physical `left` throughout the wheel's geometry.
- **Known gap at the time, since closed**: category 6 (`carpentry`) had no illustration and rendered the `Mascot` fallback
  and reports the missing mapping once per session in dev. Seven drawings were supplied for
  eight categories — this is a missing asset, not a mapping mistake.
- **Documentation updated**: this entry, `overview.md`'s changelog,
  `frontend/src/features/issues/README.md`, `frontend/src/shared/components/README.md` (the
  full category → illustration table), and `frontend/src/shared/api/README.md` (the prefetch
  cache's semantics).

## Cross-cutting rules for every milestone

- Planning docs (`overview.md`, this file) are updated if a milestone's actual
  implementation diverges from what was designed — divergence is expected and fine, silent
  drift is not.
- QA validates before Lead closes a milestone.
- Documentation's per-package `.md` requirement is part of the milestone's definition of
  done, not a follow-up task.
- No remote git operation (push, merge, PR) at any point without the user's explicit
  approval for that specific action.

## Note on this document's origin

This plan and `overview.md` were produced by the orchestrating session directly (acting
in the `pronto-planning` / `pronto-lead` roles) because the newly-created
`.claude/agents/pronto-*.md` subagents were not yet available to the Agent tool in this
session — the harness's available-agent list appears to be fixed per session and doesn't
pick up new agent files until a new session starts. Once available, future planning/
review passes should go through the actual `pronto-planning`/`pronto-lead` agents rather
than being done inline.
