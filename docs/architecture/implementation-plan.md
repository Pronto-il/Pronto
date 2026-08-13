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

## Milestone 4 — SOS booking flow

- **Scope**: `bookings` (SOS path), reusing the professional-selection component with
  urgent filtering per `overview.md` §4 (not a separate screen).
- **Acceptance criteria**: full SOS path works end-to-end per PRD §3.5, including the
  reject/unavailable → return-to-list-or-no-professional-message branch.

## Milestone 5 — Notifications & real-time status

- **Scope**: `notifications` package, short-polling status endpoints (per `overview.md`
  §3.3), notification records on status transitions, email dispatch, **and** a
  background/scheduled sweep job (e.g. Spring `@Scheduled`) that flips `orders` rows stuck
  in `PENDING` past their timeout to `EXPIRED` (cascading to `issues.status` per
  `data-model.md` §3 item 8) — ownership of this job was assigned to this milestone by
  `pronto-lead` rather than Milestone 3/4, since its purpose centers on producing the
  `ORDER_EXPIRED` notification/status change a polling client observes. Timeout duration
  is a flagged recommendation pending sign-off (proposed: 15 min `STANDARD` / 5 min `SOS`
  — see `data-model.md` §3 item 8), not yet decided.
- **Acceptance criteria**: booking status changes reach the relevant customer/professional
  within the PRD's ~1s target; tracking screen updates without a manual refresh.

## Milestone 6 — Professional dashboard

- **Scope**: availability management (`availability` package + UI), incoming-requests
  view, job-status update actions.
- **Acceptance criteria**: a professional can manage availability, see incoming requests,
  and progress a job through its statuses.

## Milestone 7 — Hardening & QA pass

- **Scope**: performance validation against PRD §5.1 targets (2s load, 1s status update,
  1,000 concurrent users, 5s image upload), security checklist (TLS 1.3, password
  storage, lockout, data deletion), full cross-flow regression, final documentation sync
  across all packages.
- **Acceptance criteria**: QA sign-off against every PRD Must-Have and Should-Have
  requirement; no known critical defects open; every package `.md` current.

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
