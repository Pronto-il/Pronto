# Pronto — System Overview & Architecture

Status: **Milestones 1-7 (Auth through Hardening & QA) and the follow-on Milestone 8
(Professional Profiles, Reviews, Favorites & Matching) are all implemented on the backend
and QA-signed-off, as of 2026-08-15** (see `docs/architecture/implementation-plan.md` for
milestone-by-milestone status — this line was stale for several milestones and is corrected
here as part of Milestone 8's documentation pass). On the frontend, **Frontend Milestone 1
(auth screens) is implemented, as of 2026-08-15; Frontend Milestone 3 (Standard booking
flow) is implemented, as of 2026-08-16; Frontend Milestone 4 (SOS booking flow UI) is
implemented and QA-signed-off, as of 2026-08-17; Frontend Milestone 5 (in-app
notification bell) is implemented and QA-signed-off, as of 2026-08-18; and Frontend
Milestone 6 (professional job-status progression actions — "mark on the way" / "mark
completed") is implemented and QA-passed, as of 2026-08-18** — see §6 below and
`implementation-plan.md`'s Milestone 1 / Milestone 3 / Milestone 4 / Milestone 5 / Milestone
6 entries for full status detail; the rest of `frontend/` (favorites/reviews UI, slot
edit/delete UI) remains design-only or backend-only, pending later frontend milestones. This
is the living source of truth for architecture/decisions — keep it in sync with the actual
implementation as it lands (owned going forward by the `pronto-documentation` agent).

## 1. Consolidated understanding

Pronto is a desktop-first, Hebrew-language web platform (responsive, but not designed
mobile-first) that connects customers who have a home-service problem (plumbing,
electrical, AC, etc.) with independent service professionals. The core pain points it
addresses: customers don't know how to correctly classify their own problem and waste
time/money on the wrong tradesperson or an unresponsive search process; small
professionals struggle to get consistent, qualified leads without expensive advertising.

The end-to-end flow: a customer describes an issue (text + optional photos) → AI
suggests a category, which the customer confirms or edits → the customer books either
**Standard** (browse a list of relevant professionals, each showing their own price
offer, and pick one directly) or **SOS** (browse only professionals currently available
for urgent work, pick one) → the chosen professional accepts or rejects → on acceptance,
both paths converge into the same confirmation/tracking flow with real-time status
updates → the professional updates job status through to completion.

Two user types: **Customer** and **Service Professional** (all professional accounts are
auto-approved in v1.0 — no manual admin review gate). Booking statuses: Pending,
Confirmed, On the Way, Completed, Cancelled, Rejected, Expired — **7 statuses**. Updated
2026-08-12: this overrides the earlier settled 6-status list (which had no `Rejected`) per
direct user instruction — `Rejected` is a distinct status from `Cancelled` so a
professional's explicit decline of a still-`Pending` request is distinguishable from a
cancellation. See `docs/architecture/data-model.md` §2.9 and §3 item 10 for the full
status-transition semantics (including exactly which actor/stage produces `Cancelled` vs.
`Rejected` vs. `Expired`).

## 2. Resolved decisions (settled — do not re-litigate without a new contradiction)

| Topic | Decision | Basis |
|---|---|---|
| Frontend | React | Poster (source of truth) |
| Backend | Java + Spring Boot | Poster |
| Database | **PostgreSQL** | Poster overrides PRD's DynamoDB. PRD's §6 schema is relational (FKs) and maps directly onto Postgres. |
| Cloud | AWS, backend deployed via a managed container service (ECS or Elastic Beanstalk) rather than raw EC2 | User decision — trades a bit of AWS-concept overhead for less manual server management/easier scaling. S3 for images retained; API Gateway/SQS/Lambda from the PRD not yet confirmed as needed. |
| AI | OpenAI | Poster; PRD only said "AI for issue classification" without naming a provider. |
| Payment processing | **Out of scope for v1.0.** `Orders.final_price` is tracked/displayed only, no gateway integration. | Explicit user decision (confirmed twice), overriding the older presentation's loose mention of "payment" in the end-to-end flow. Matches PRD §10.5 out-of-scope list. |
| GPS / live location tracking | **Out of scope for v1.0 — hard exclusion**, not a "future version" maybe. | Explicit user decision (confirmed twice), matches PRD §10.3. Real-time *status* notifications (see §3.3) are in scope; a live map/GPS feed is not. |
| Interface language | Hebrew only for v1.0 | PRD §3.1.3 |
| Platform | Desktop-first responsive web (not mobile-first); no native iOS/Android, no offline mode | User decision, overriding PRD §4.1's mobile-first framing — the app should be designed primarily for desktop/laptop use, while remaining usable on mobile browsers. |
| Real-time transport | **Short-polling** (client polls every 3–5s), not WebSocket | User decision — simpler to implement than STOMP/WebSocket; PRD's ~1s target is still reachable with short polling intervals. |
| Notification channels | In-app (via polling) + email | User decision. Email also used for verification codes. SMS/push not requested by any source document. |
| Professional approval | **Auto-approved in v1.0** — no manual admin review gate | User decision, overriding PRD's admin-approval requirement. Simplifies `professionals` package: no approval workflow/admin screen needed for v1.0. |
| Chat between users | Out of scope for v1.0 | PRD §10.4 |
| Additional languages | Out of scope for v1.0 | PRD §10.6 |
| Booking statuses | 7 values: Pending, Confirmed, On the Way, Completed, Cancelled, Rejected, Expired (`Rejected` added as a 7th status) | User decision (2026-08-12), overriding the originally-settled 6-status list (PRD §3.6.1 has no "Rejected"). See `data-model.md` §2.9/§3 item 10 for the precise Rejected-vs-Cancelled-vs-Expired transition rules. |
| Professional-search distance/ETA | **Now in v1.0 scope** — dynamically computed (never persisted) same-city/different-city + peak-hour approximation, shown on Standard/SOS professional-listing cards and usable as a `sort=FASTEST` mode. | Explicit user instruction (2026-08-15), **overriding** the prior "ETA/tracking display is out of v1.0 scope, permanent (PRD §3.4.8/§3.5.5, 'future version')" ruling recorded in `data-model.md` §4. Scoped to professional search/listing only — the tracking screen gained no new field, and GPS/live-location tracking (the separate row above) remains a completely separate, still-valid, untouched exclusion. Full record: `docs/architecture/api-contract-professionals-reviews.md` §5; `data-model.md` §4 (kept, with the override noted alongside the original text, not silently rewritten). **Further overridden (2026-08-17, Active Booking Floating Indicator feature)**: the "never persisted" / "tracking screen gained no new field" clause above no longer holds for the `ON_THE_WAY` transition specifically. `orders.expected_arrival_at` (new nullable column, `V23`) is now computed once — via this same `DistanceEtaStrategy.calculate(...)` call `enrichAndSort` already makes for listing cards — and persisted by `BookingsService.onTheWay` at the moment a professional marks an order `ON_THE_WAY`, then surfaced on `OrderResponse`/`OrderDetailResponse`/`OrderSummaryResponse` and rendered as a live countdown on the tracking screen and the new floating active-order indicator. The `matching` package itself is unchanged by this — it still computes nothing to disk, owns no table, and remains pure/stateless; it is the caller (`bookings`) that now persists the *result* of one specific `calculate()` call, once, at one specific transition. GPS/live-location tracking remains untouched and still fully out of scope. Full record: `docs/architecture/active-booking-floating-indicator.md` §0.1. |
| Team roster | Poster lists 2 members (Yuval Harel, Or Cohen); other docs list 4 | Informational only — no architectural impact. |

## 3. Proposed architecture

**Style**: modular monolith. This is a two-person MVP/student project — microservices
would add operational overhead (service discovery, distributed transactions, multiple
deploy pipelines) with no corresponding benefit at this scale. A single Spring Boot
application organized into clear domain packages gets the same separation-of-concerns
benefit without the cost, and can be split later if it ever needs to.

### 3.1 Frontend
Single React SPA serving both customer and professional experiences, role-gated by route
(a professional's dashboard is simply a different set of routes after login, not a
separate app). Desktop-first responsive CSS (layouts designed for desktop/laptop screens
first, with responsive breakpoints down to mobile as a secondary concern, not the primary
design target). RTL layout for Hebrew. Talks to the backend exclusively through the REST
API described below.

### 3.2 Backend
Spring Boot REST API, layered per domain package (see §4). Stateless request handling
with token-based auth (JWT or an equivalent session token) so it scales horizontally
behind a load balancer if needed for the 1,000-concurrent-user target.

### 3.3 Real-time status updates
The PRD requires booking-status changes to reach the client within ~1 second and the
tracking screen to "refresh in real time after each status update." **Decided:
short-polling** (client polls a status endpoint every 3–5s) for the tracking screen and
the professional's incoming-request feed, rather than WebSocket/STOMP — simpler to build
and operate for a two-person team, and still meets the target in practice. WebSocket
remains a possible future upgrade if polling proves insufficient, but is not planned for
v1.0.

### 3.4 AI issue classification
Customer submits a text description plus optional images. Backend sends the description
(and images, since OpenAI's vision-capable models accept both) to the OpenAI API and
requests a category suggestion from the platform's fixed set of service categories, with
a short explanation. The suggested category + explanation are returned to the customer on
the "AI Review" screen, where they can confirm or override before continuing to Standard
or SOS booking. The AI call is server-side only (the API key never reaches the client),
wrapped in its own service so it can be tested/mocked independently of the issue-creation
flow.

### 3.5 Image uploads
Images upload to S3 (recommendation, per §2's AWS specifics) with the backend issuing
pre-signed upload URLs or proxying the upload; `IssueImages.image_url` stores the
resulting object URL. Max upload time target: 5s (PRD §5.1.4).

### 3.6 Notifications
`Notifications` table (per PRD §6) records outgoing notifications with a `channel`
field. **Decided**: in-app (surfaced via the polling status endpoints) + email. Email is
used for verification codes and for reaching users who aren't actively on the site.
SMS/push are not requested anywhere in the source documents and are out of scope for
v1.0.

### 3.7 Auth & security
Email + password registration, email verification code (delivered via email) required
before the account is usable. Professional accounts are **auto-approved in v1.0** — no
manual admin review gate; a professional can receive bookings as soon as their account is
verified. Passwords hashed (bcrypt or equivalent) — never stored in plaintext. Account
lockout after 5 failed login attempts (PRD §5.2.3). All traffic over HTTPS/TLS 1.3,
terminated at the load balancer/CDN layer. Account deletion / personal data management
supported per PRD §5.2.4.

**Milestone 1 REST contract** (register/verify/login/profile/account-deletion endpoints,
error taxonomy, JWT claims/expiry, password-hashing and email-sender decisions) is fully
specified in `docs/architecture/api-contract.md` — not duplicated here.

### 3.8 Service categories (v1.0 fixed list)
The AI classifier picks from, and the customer confirms/overrides against, this fixed set:

1. Plumbing (אינסטלציה)
2. Electrical (חשמל)
3. AC / HVAC (מיזוג אוויר)
4. Appliance Repair (תיקון מוצרי חשמל)
5. Locksmith (מנעולן)
6. Carpentry (נגרות)
7. Painting (צביעה)
8. General Handyman (הנדימן כללי)

Stored as a `Categories` reference table (id, name_he, name_en) that `Issues.category_id`
foreign-keys into, rather than a hardcoded enum, so the list can be edited without a code
change.

## 4. Proposed package / module structure

Every package listed below requires its own `.md` doc (purpose, responsibilities, key
classes, interactions, assumptions) — created/updated by whoever creates or materially
changes it, per the shared project rule.

### Backend — `backend/src/main/java/com/pronto/*`

| Package | Responsibility |
|---|---|
| `auth` | Registration, login, email verification codes, password hashing, account lockout, token issuance. |
| `users` | Shared `User` entity/profile logic used by both customer and professional roles. |
| `professionals` | Professional profile, service area/city, standing price offer, reliability score. (No approval workflow — v1.0 auto-approves.) **As of 2026-08-15**, also owns the self-service profile layer (`GET`/`PUT /api/professionals/me`, profile-image upload, public `GET /api/professionals/{id}` detail view) — its first-ever service/controller layer, previously entity+repository only. See `professionals/README.md`. |
| `availability` | Two distinct concepts, not one table used two ways: `availability_slots` (Standard advance-booking calendar) and `sos_availability` (live SOS "available for urgent work right now" on/off toggle). Decided 2026-08-12 — see `data-model.md` §2.5–§2.6 and §3 item 5. |
| `issues` | Issue creation, category selection, image metadata; orchestrates the `ai` package for classification. |
| `ai` | OpenAI client wrapper + classification service, kept separate from `issues` so it's independently testable/mockable. |
| `bookings` | `Orders` — Standard + SOS booking flows, accept/reject, status transitions. **As of 2026-08-15**, also owns the service-address snapshot/SOS-surcharge pricing on order creation, and consumes `matching` to enrich professional listings with distance/ETA and a `sort=FASTEST` mode. |
| `notifications` | Notification records + status polling endpoints, plus email dispatch. |
| `storage` | Image upload/retrieval, backend-proxied, behind a `StorageClient` abstraction swappable between a local-disk fake (dev/QA default) and real S3 (`pronto.storage.mode`). **As of 2026-08-15**, also serves professional profile images (`professionals/`-prefixed keys), publicly readable by any authenticated caller of either role — see `storage/README.md`'s "Role enforcement" section. |
| `reviews` | **New, 2026-08-15.** Customer reviews of a professional (1-5 star rating + optional comment), one per completed order. Full CRUD (`POST`/`GET`/`PUT`/`DELETE /api/reviews`). See `reviews/README.md`. |
| `favorites` | **New, 2026-08-15.** A customer's bookmarked professionals — add/remove/list (`POST`/`GET /api/favorites`, `DELETE /api/favorites/{professionalId}`). See `favorites/README.md`. |
| `matching` | **New, 2026-08-15.** Distance/ETA approximation between a professional's city and a customer's service address, plus the "fastest" sort this powers on professional-listing endpoints. Pure computation only — no table, no endpoint of its own, consumed in-process by `bookings`. Implements the ETA-scope override recorded in §2 above — see `matching/README.md`. |
| `common` | Shared exceptions, base entities/DTOs, config, cross-cutting utilities. |

### Frontend — `frontend/src/*`

| Folder | Responsibility |
|---|---|
| `features/auth` | Registration, verification, login screens. **Implemented, Frontend Milestone 1 (2026-08-15)** — see §6 below and `implementation-plan.md`'s Milestone 1 entry. |
| `features/issues` | Home/New Issue screen, AI Review + service-path-selection screen. **Implemented, Frontend Milestone 2** (see `implementation-plan.md`); `NewIssuePage`/`IssueSuccessStep` gained a Frontend Milestone 3 follow-up linking into the new booking flow, and a Frontend Milestone 4 follow-up linking the SOS branch into the new SOS booking flow. |
| `features/booking` | Standard professional list, SOS professional list, booking confirmation, tracking screen. **Standard and SOS flows both implemented** — Standard: Frontend Milestone 3 (2026-08-16), `BookingFlowPage`/`MyOrdersPage`/`OrderTrackingPage`; SOS: Frontend Milestone 4 (2026-08-17), `SosBookingFlowPage`/`SosBookingSummary`. **Extended in the MS3/MS4 product-corrections pass (2026-08-17)**: `AddressSelectionStep` (default-vs-custom address chooser), full 7-field service address, booking-draft resume. **Extended, Frontend Milestone 6 (2026-08-18)**: professional-side "mark on the way"/"mark completed" job-status progression actions on `OrderTrackingPage`. See §6 below and `implementation-plan.md`'s entries. |
| `features/professionals` | Professional card/list components shared by Standard and SOS (per PRD §7.4, SOS reuses the professional-selection component with urgent filtering rather than a fully separate screen), plus (as of Frontend Milestone 8) the standalone professional-profile detail screen and review list. **Implemented, Frontend Milestone 3 (2026-08-16)**; SOS reuse landed Frontend Milestone 4 (2026-08-17) — both flows now consume `ProfessionalCard`/`ProfessionalList` via `ProfessionalList`. **Sort-toggle reconciled in the MS3/MS4 product-corrections pass (2026-08-17)**: both flows now expose an identical 2-way `Recommended | Cheapest` chip toggle. **Grew in Frontend Milestone 8 (2026-08-18)**: `ProfessionalProfilePage.tsx`/`ReviewList.tsx` (new, `/professionals/:professionalId`), `ProfessionalCard.tsx`'s new optional `viewProfileContext` prop (primary select button unchanged). |
| `features/favorites` | Customer's saved-favorites list — add/remove/browse. **New, Frontend Milestone 8 (2026-08-18)**: `FavoritesPage.tsx` (`/favorites`, CUSTOMER-only), `FavoriteProfessionalCard.tsx` (a deliberately lean, dedicated card, not a reuse of `ProfessionalCard` — the favorites DTO has no distance/ETA fields). |
| `features/dashboard` | Professional dashboard — availability management, incoming requests, job status actions, business-profile self-service. **Partially implemented**, Frontend Milestone 3 (2026-08-16): incoming-request accept/reject, a read-only job list, availability-slot create/list; SOS-availability toggle (`SosAvailabilityToggle`) added Frontend Milestone 4 (2026-08-17). **Job-status progression (on-the-way/complete) is now built, Frontend Milestone 6 (2026-08-18)** — but lives on `features/booking/OrderTrackingPage.tsx`, not in this package; `MyJobsPage` here remains intentionally read-only/link-only. **Grew in Frontend Milestone 8 (2026-08-18)**: a 4th `ProDashboardLayout` tab, `/pro/profile` (`ProfileEditorPage.tsx` + `ProfessionalProfileImageField.tsx`), reading/writing `professionals/me` — distinct from the shared, read-only `app/ProfilePage.tsx` (`users/me`). |
| `features/notifications` | In-app notification bell: nav badge + anchored dropdown feed, consuming the backend `notifications` package via short-polling. **Implemented, Frontend Milestone 5 (2026-08-18)** — `NotificationBell.tsx`/`notificationLabels.ts`; the status-polling primitive itself (`usePolling`/`useOrderStatus`) shipped earlier, in Frontend Milestone 3, and remains consumed directly by `features/booking`/`features/dashboard` for order tracking, separate from this module's own `useNotifications` hook. No dedicated page/route — the backend feed has no pagination. |
| `shared/api` | Backend API client. **Grew in Frontend Milestone 3 (2026-08-16)**: `bookings.ts`, `availability.ts`, and a `getIssue` addition to `issues.ts`; grew again in Frontend Milestone 4 (2026-08-17): SOS-listing/order functions in `bookings.ts` and SOS-availability functions in `availability.ts`. **Grew in Frontend Milestone 5 (2026-08-18)**: `notifications.ts` (new), consuming the already-complete backend `notifications` package, no backend changes. **Grew in Frontend Milestone 8 (2026-08-18)**: `favorites.ts`/`professionals.ts` (new), `reviews.ts` gained `getReviews`. |
| `shared/components` | Reusable UI components. **Grew in Frontend Milestone 3 (2026-08-16)**: `StatusBadge`. |
| `shared/hooks` | Reusable React hooks (e.g. status-polling hook, auth context). **Grew in Frontend Milestone 3 (2026-08-16)**: `usePolling`/`useOrderStatus`. **Grew in the MS3/MS4 product-corrections pass (2026-08-17)**: booking-draft persistence (`bookingDraftContext.ts`/`BookingDraftProvider.tsx`/`useBookingDraft.ts`). **Grew in Frontend Milestone 5 (2026-08-18)**: `useNotifications.ts` (a plain polling hook wrapping `usePolling`, not a React Context — single consumer, unlike `useActiveOrder`/`useBookingDraft`). **Grew in Frontend Milestone 8 (2026-08-18)**: `AuthProvider` gained `refreshUser()`, called after a professional edits their `fullName` via `/pro/profile` (writes to the underlying `users` row) so the top-nav's cached name doesn't go stale. |
| `app` | Routing, layout, root configuration. **Updated, Frontend Milestone 3 (2026-08-16)**: `/pro` now renders a real professional dashboard instead of a placeholder; booking/tracking/orders routes added. **Updated, Frontend Milestone 5 (2026-08-18)**: `AppLayout.tsx` renders `<NotificationBell />` in the nav for both roles (CUSTOMER and PROFESSIONAL, unlike the CUSTOMER-only `ActiveOrderIndicator`); no router change (the bell is a dropdown, not a route). **Updated, Frontend Milestone 8 (2026-08-18)**: `router.tsx` gained `professionals/:professionalId`, `favorites`, and `pro/profile` routes. Same-day UX correction: `/favorites` is reached via `ProfilePage.tsx`'s "מועדפים" link, not an `AppLayout.tsx` nav link (favorites is a secondary customer feature, not primary nav). |

### Docs

`docs/architecture/overview.md` (this file) and `docs/architecture/implementation-plan.md`
are the living design/planning docs, owned by `pronto-documentation` going forward.

## 5. Draft milestones (sequencing refined in `implementation-plan.md`)

1. Foundation — repo scaffolding (Spring Boot + React project init), local dev
   environment (Postgres via docker-compose), DB migrations tooling, base package
   structure with stub docs.
2. Auth & user management — registration, verification, login, professional profile,
   account lockout. (No approval-flag step — v1.0 auto-approves professionals.)
   **Backend implemented and QA-signed-off, 2026-08-13** — see
   `implementation-plan.md`'s Milestone 1 entry for status detail. Corresponding frontend
   screens (`features/auth`) are **implemented as Frontend Milestone 1, 2026-08-15** — see
   §6 below and `implementation-plan.md`'s Milestone 1 entry for full detail.
3. Issue creation & AI classification — issue form, image upload, OpenAI integration,
   confirm/edit category, seeded against the fixed 8-category list (§3.8).
   **Backend implemented and QA-signed-off, 2026-08-13** — see
   `implementation-plan.md`'s Milestone 2 entry for status detail; corresponding frontend
   screens (`features/issues`) are deferred along with the rest of `frontend/`, not yet
   started.
4. Standard booking flow — professional listing, price offers, accept/reject,
   confirmation/tracking screen (status only).
5. SOS booking flow — urgent professional list, SOS request/accept/reject, fallback
   messaging.
6. Notifications & real-time status — polling endpoints, notification records, email
   dispatch.
7. Professional dashboard — availability management, incoming requests, job status
   updates.
8. Hardening & QA pass — performance targets, security checklist, cross-flow
   regression, docs sync.

## 6. Remaining open items

- **AWS services beyond compute**: backend deploys via ECS/Elastic Beanstalk (decided,
  §2) and S3 handles images (decided, §3.5). The PRD's API Gateway/SQS/Lambda are not yet
  confirmed as needed for v1.0 — revisit if/when an async or gateway use case actually
  arises; don't build them speculatively.
- **2026-08-13 — surfaced during Milestone 1 (Auth & user management) implementation/QA.**
  Recorded here so a future milestone doesn't rediscover these the hard way. Full detail
  for all four items lives in `docs/architecture/api-contract.md` §4; cross-referenced from
  `data-model.md` §4 for the first one, since it's schema-specific.
  - **Pre-existing schema contradiction, not fixed (out of scope for Milestone 1).**
    `backend/src/main/resources/db/migration/V5__create_availability_slots.sql` still
    implements the single-table SOS-matching design that `data-model.md` §2.6/§3 item 5
    explicitly rejected in favor of a dedicated `sos_availability` table.
    `V8__create_orders.sql`'s `order_status` `CHECK` constraint still lists only the
    superseded 6 values (no `REJECTED`), contradicting the settled 7-status decision above
    (§1/§2) and in `data-model.md` §2.9/§3 item 10. Both migrations were already applied
    before Milestone 1 and were out of bounds for this milestone to alter. Both need a
    follow-up Flyway migration: the `sos_availability` gap blocks Milestone 4 (SOS) and the
    Milestone 6 dashboard toggle; the missing `REJECTED` value blocks Milestone 3/4's
    accept/reject logic.
  - **No password-reset flow.** Not requested by any source document, but a real
    end-user gap — a customer/professional who forgets their password currently has no
    self-service recovery path.
  - **No "resend verification code" endpoint.** A user whose 15-minute verification code
    expires has no self-service recovery short of hitting `409 DUPLICATE_EMAIL` on
    re-registration.
  - **No refresh-token / logout-revocation mechanism.** JWTs expire after 24h; logout is
    client-side-discard-only. Deleted-account revocation is handled via a per-request DB
    check (§3.7/`api-contract.md` §3.1), but general token compromise isn't otherwise
    mitigated. Accepted MVP limitation, not an oversight.
- **2026-08-13 — surfaced during Milestone 2 (Issue creation & AI classification)
  implementation/QA.** Recorded here so a future milestone doesn't rediscover these the
  hard way. Full detail for every item lives in `docs/architecture/api-contract-issues.md`
  §4.
  - **AI-suggested category is not persisted anywhere — genuinely open, needs the user's
    sign-off.** `POST /api/issues` stores only the confirmed `categoryId`; nothing records
    what `/classify` originally suggested vs. what the customer confirmed or overrode. This
    was a deliberate default (no new Flyway migration required this milestone), but it
    forecloses any future "how often do customers override the AI" analysis unless a
    nullable `ai_suggested_category_id` column or a separate log table is added later — cheap
    now, more annoying to retrofit once `issues` rows without a recorded suggestion already
    exist. **Ask**: is AI-accuracy/override-rate tracking wanted from day one?
  - **S3 bucket-privacy / access-policy for issue images — genuinely open, needs a decision
    before `pronto.storage.mode=s3` goes live anywhere.** Home-issue photos are plausibly
    sensitive (interior of someone's home). Whether the bucket/prefix should be public-read,
    served via signed/expiring URLs, or proxied through the backend (mirroring the local-mode
    `GET /api/storage/images/**` retrieval endpoint) is undecided — deferred because it
    can't be meaningfully tested without real AWS credentials (not available this
    milestone), not because it's unimportant.
  - **Image-key ownership is a path-prefix convention (`customers/{callerId}/...`), not a
    real access-control record.** No DB row exists linking an uploaded object to its
    uploader until `POST /api/issues` runs, so ownership is enforced purely by parsing the
    key's embedded id. Two accepted MVP gaps: no expiry/cleanup job for orphaned uploads
    (a customer who uploads photos and abandons the New Issue flow leaves those objects in
    storage forever, untracked by any DB row); no prevention of the same `imageKey` being
    attached to two different issues (judged low-risk/low-impact, not a security or
    data-integrity problem).
  - **No rate limiting on `POST /api/issues/classify`.** Stateless/cheap-to-call-repeatedly
    by design, so nothing currently stops a customer from spamming it — a real OpenAI-cost
    exposure once `pronto.ai.mode=openai` is live (mock mode has no such cost). Flagged as a
    candidate for Milestone 7's hardening pass if AI API costs become a concern.
  - **AI category-mapping fallback is a recommendation, not confirmed.** If a real OpenAI
    response doesn't cleanly map to one of the 8 seeded `categories.code` values, the
    implemented fallback is "default to `general_handyman` with `confidence = null`, logged
    at `WARN`" — reasonable, but not specified by any source document, and not
    live-verified against real OpenAI output this milestone (no credentials available).
  - **No `GET /api/issues/{id}` endpoint yet.** Real forward dependency: Milestone 3/4's
    booking flows will need to resolve an issue by id, and that endpoint doesn't exist —
    next milestone's planning pass needs to account for it.
  - **Storage object "permanence" after issue confirmation isn't designed.** Uploaded
    objects keep their original `.../temp/...`-style key forever; nothing promotes them to
    a permanent, issue-scoped path on confirm. Functionally harmless today (the DB row's
    `image_url` is authoritative regardless of key naming), but a future S3 lifecycle/cost
    policy that assumes `.../temp/...` objects are safe to expire would be wrong once one is
    referenced by a persisted `issue_images` row.
  - **`S3StorageClient` and `OpenAiClassificationClient` are implemented and compile but
    were never live-tested this milestone** (no AWS/OpenAI credentials available) — an
    explicit, documented deferral per the task brief, not a silently-accepted gap. Both
    activate purely via config flags (`pronto.storage.mode=s3`, `pronto.ai.mode=openai`)
    once credentials exist; no code change expected to be needed, but neither has been
    proven against the real service yet.
- **2026-08-15 — surfaced while consolidating `backend/BACKEND_ARCHITECTURE.md` (a
  standalone, code-grounded "as-built" reference doc) into this canonical doc set during
  Milestone 7's closing documentation pass, then deleted once merged.** Most of that doc's
  content (entity catalog, request-flow walkthroughs, controller→service→repository
  mapping, DTO mapping, security-flow narrative) duplicated what the `api-contract-*.md`
  docs and each package's own `.md` already document in more authoritative detail, and was
  not migrated. §7 below carries forward the genuinely useful reference material
  (dependency/component diagram, environment-variable table, external-integrations table);
  `data-model.md` §6 carries forward the entity-relationship diagram. The findings below are
  the architecture observations that were **not** already documented anywhere in this doc
  set, cross-checked against `hardening-plan.md` §5 and every package's own `.md` before
  being judged unique (a few candidates — e.g. the checked-in insecure `JWT_SECRET` default
  and the missing auth rate-limiting — turned out to already be covered by
  `hardening-plan.md` §5.1/§5.2 and were correctly left out as pure duplicates; the
  `professionals` package's lack of a service/controller layer and the `Category`
  entity's placement inside `professionals` were also already covered, in
  `professionals/README.md`'s own Responsibilities/Assumptions sections, and likewise
  correctly left out):
  - **`bookings.repository.ProfessionalListingRepository` and
    `professionals.repository.ProfessionalRepository` are two separate Spring Data
    repository interfaces over the same `Professional` entity, living in two different
    packages.** Not a bug — `ProfessionalListingRepository` deliberately lives in `bookings`
    to avoid a reverse `professionals → bookings` dependency (documented in
    `bookings/README.md`'s Interactions section) — but it is a naming/discovery trap for a
    future maintainer searching for "the" `Professional` repository, worth knowing about
    up front rather than rediscovering.
  - **The `EMAIL_MODE`/`pronto.email.mode` config property is read into
    `application.yml` but never actually branched on by any `@ConditionalOnProperty`/
    `@Value` in the codebase.** Only one `EmailSender` implementation exists
    (`auth.email.LoggingEmailSender`, unconditionally `@Component`) — a real SMTP/SES
    implementation was never added despite the config scaffolding. Functionally harmless
    (the property is simply inert today) but worth knowing before assuming the config flag
    does anything yet; consistent with `api-contract-notifications.md` §4.4's own decision
    not to build a second `EmailSender` implementation this milestone.
  - **`common.exception.GlobalExceptionHandler` and
    `auth.security.JsonAuthenticationEntryPoint` are two independently hand-maintained
    implementations of the same error-envelope shape** (`ErrorResponse`/`ErrorBody`), not
    one shared helper — `JsonAuthenticationEntryPoint` exists as a separate code path
    specifically because Spring-Security-layer authentication failures never reach
    `@RestControllerAdvice`. Both are documented individually in `common/README.md` and
    `auth/README.md`, but neither doc previously flagged that they duplicate the same
    envelope-construction logic. Correct in effect, low priority to consolidate, worth
    knowing about if either envelope shape ever needs to change.
  - **Backend unit-test coverage snapshot, current as of Milestone 7's close** (this list
    goes stale quickly — treat as a snapshot, not a live source of truth): `ai`, `storage`,
    and, as of this milestone, `availability` (9 new tests added alongside the slot
    edit/delete addition, §7 below / `availability/README.md`) have unit test coverage. No
    unit tests exist for `auth`, `users`, `issues`, `bookings`, or `notifications`
    service/controller logic, `JwtService`/`JwtAuthenticationFilter`, or either
    `@Scheduled` job — despite these containing the majority of the application's business
    logic (concurrency-guarded state transitions, authorization branching, lockout logic).
    Every milestone to date has instead relied on live QA validation against a real
    Postgres instance (documented per-milestone in `implementation-plan.md`) rather than
    unit tests for this logic — not a defect by itself, but a real gap if unit-level
    regression coverage is ever wanted independent of a full QA pass.
- **2026-08-15 — Frontend Milestone 1 (auth screens) landed.** First real frontend
  milestone to ship, after `frontend/` sat design-only through Milestones 1-8 of the
  backend. Screens delivered: `/register` role chooser → `/register/customer` /
  `/register/professional`, `/verify`, `/login`, `/profile` (any authenticated role,
  read-only `GET /api/users/me`), and `/pro` (a professional-only placeholder route,
  customers redirected away). Built on the design tokens/fonts/icons bootstrap,
  `AppLayout`, a typed `httpClient` + `AuthProvider`/`useAuth`/`RequireAuth`, and the
  shared component set (`Button`, `Input`, `Select`, `Card`, `PageHeader`,
  `ImageUploadField`, `DocumentUploadField`, `AddressFormFields`).
  - The original QA pass found and fixed two defects: **(a) critical** — CORS was
    entirely unconfigured on the backend, so the browser's preflight `OPTIONS` request
    was rejected before ever reaching a controller; fixed via a `CorsConfigurationSource`
    bean in `auth.config.SecurityConfig` (see `auth/README.md`'s CORS note and §7.2's
    environment-variable table, both updated with the new `pronto.cors.allowed-origins` /
    `CORS_ALLOWED_ORIGINS` property this pass introduced). **(b) minor** — `/pro` wasn't
    actually role-gated to professionals; fixed.
  - A follow-up pass, same day, aligned this milestone with the backend `auth` package's
    real registration contract: `POST /api/auth/register` is `multipart/form-data` (a
    `data` JSON part nesting `customer`/`professional` sub-objects, per
    `RegisterRequest`/`AuthController`'s own Javadoc — "breaking change from the prior
    flat-JSON contract"), plus optional `verificationDocument`/`profilePhoto` file parts
    for professionals. `shared/api/auth.ts` was rewritten to build and send this
    correctly, and `shared/api/httpClient.ts` gained `FormData` request-body support. A
    field-name mismatch between the frontend's address type and the backend's
    `DefaultAddressRequest` (`notes` vs. `addressNotes`) was found and fixed, and nested
    validation-error paths (e.g. `customer.defaultAddress.city`) are now mapped to their
    leaf field name for display. `GET /api/users/me` was unchanged by this pass, so
    `/profile` still cannot show address/photo/document — expected, not a regression.
    Two barrel files (`shared/api/index.ts`, `shared/components/index.ts`) that were
    still stubs got filled in, and `shared/hooks/index.ts` was found to still be a stub
    too (a real gap that would have broken the build) and was fixed alongside them.
  - **Doc-drift note, flagged for `pronto-lead`**: `auth/README.md`'s Responsibilities
    section still describes `POST /api/auth/register` as "one flat JSON shape" — that
    text predates this multipart change and is now stale; not corrected as part of this
    documentation pass (out of the requested scope), but worth a follow-up edit.
  - **Incident note**: a git mistake during this work caused some in-progress files to be
    lost; they were recovered via IDE Local History plus manual reconstruction of a small
    number of low-risk files. Resolved, does not affect the shipped state described above.
- **2026-08-16 — Frontend Milestone 3 (Standard booking flow UI) landed.** Branch
  `frontend/MS3`, local only — uncommitted, not pushed/merged; that remains the user's own
  explicit git action. Built on top of backend Milestone 3's booking endpoints and backend
  Milestone 8's enriched listing/order DTOs. Screens delivered: `/issues/:issueId/booking`
  (`BookingFlowPage`, address → professional list → slot picker → confirmation → success),
  `/orders` (`MyOrdersPage`), `/orders/:orderId` (`OrderTrackingPage`, either role,
  short-polling). `/pro`'s old placeholder was replaced with a real `ProDashboardLayout`
  shell (`IncomingRequestsPage`, `MyJobsPage`, `AvailabilityPage`). New shared primitives:
  `StatusBadge`, `usePolling`/`useOrderStatus` (per §3.3's short-polling design),
  `shared/api/bookings.ts`/`availability.ts`, and a `getIssue` addition to
  `shared/api/issues.ts`. Full detail, including the feature-folder ownership split and
  every screen's behavior, is in `implementation-plan.md`'s "Frontend Milestone 3" entry
  (nested under Milestone 3) — not restated here.
  - **Doc-drift found and flagged for `pronto-lead`**: `api-contract-bookings.md` §2.2/§2.4
    predate backend Milestone 8, which changed several of the underlying DTOs in place
    (enriched `ProfessionalCard`, required service-address fields on listing/order
    creation) without that doc's prose being updated. Discovered by reading the real
    backend DTO source directly; `shared/api/bookings.ts` was written against the real
    DTOs, with the divergence recorded in that file's own comments and in
    `shared/api/README.md`. `api-contract-bookings.md` itself still needs a correcting
    addendum to §2.2/§2.4 — not done as part of this frontend-only pass.
  - **QA**: full pass, zero open bugs at final sign-off, one bug-fix round (four minor
    issues found, fixed, and re-verified — a role-unaware back button, an unmapped `409
    ISSUE_URGENCY_MISMATCH` error message, a shared loading-spinner state on the
    incoming-request card, and a missing professional "my accepted jobs" view). Method:
    live backend contract-conformance via `curl` against the real jar/Postgres plus
    code-level review, since no browser-automation tool exists in this environment.
  - **Known gaps/deferred**: SOS booking UI (Frontend Milestone 4), job-status progression
    UI for professionals (Frontend Milestone 6), slot edit/delete UI (Frontend Milestone
    7), favorites/reviews UI (not this pass, though the API already returns
    `favorited`/`averageRating`). One trivial, explicitly non-blocking cosmetic nit left as-
    is: the professional's `OrderTrackingPage` back button targets `/pro` rather than
    `/pro/jobs`, its actual only entry point.
- **2026-08-17 — Frontend Milestone 4 (SOS booking flow UI) landed, QA-signed-off.** Branch
  `frontend/MS4`, local only — uncommitted, not pushed/merged; that remains the user's own
  explicit git action. Built on top of backend Milestone 4's SOS endpoints and Frontend
  Milestone 3's shared components/primitives. Screens delivered: `/issues/:issueId/sos-booking`
  (`SosBookingFlowPage`, `CUSTOMER`-only) — a 3-step machine (address → available-now
  professional list → confirmation → success) mirroring `BookingFlowPage`'s pattern but with
  no slot-picking step, showing an "SOS פעיל" banner (DESIGN_SYSTEM.md §49) on every
  non-success step; `SosBookingSummary` owns the `POST /api/bookings/sos-orders` call and a
  price breakdown (base price + a flat, frontend-hardcoded `SOS_SURCHARGE_AMOUNT = 50`
  placeholder mirroring the backend's `BookingsService.SOS_SURCHARGE_AMOUNT`, flagged in-code
  to keep in sync). On the professional side: `SosAvailabilityToggle` (`GET`/`PUT
  /api/availability/sos-availability`), a one-off accessible `role="switch"` toggle rendered
  at the top of the existing `/pro/availability` (`AvailabilityPage`) rather than a new
  `ProDashboardLayout` tab — avoids a dead/thin nav item for a single toggle, per that page's
  own Frontend Milestone 3 reasoning; both the toggle and the Standard slot calendar are the
  same `availability` backend domain. `features/issues/IssueSuccessStep.tsx`'s SOS branch now
  routes into `/issues/${issueId}/sos-booking` instead of its previous "not available yet"
  stub. Full detail, including error-code handling and endpoint-level behavior, is in
  `implementation-plan.md`'s "Frontend Milestone 4" entry (nested under Milestone 4) and each
  affected package's own `README.md` — not restated here.
  - **QA**: full pass, **PASS**, signed off 2026-08-17. One trivial defect found and fixed:
    `features/professionals/ProfessionalCard.tsx`'s doc comment was stale, claiming SOS reuse
    of the component was "a later milestone's scope" — corrected, since both the Standard and
    SOS flows now reuse this component via `ProfessionalList`. QA also live-verified (against
    a real backend) that `OrderTrackingPage`, `MyOrdersPage`, `MyJobsPage`,
    `IncomingRequestsPage`, `useOrderStatus`, and `usePolling` needed zero code changes to
    correctly support SOS orders — all are already generic by `orderId`/`GET .../me` and
    already handle `bookedEnd: null` correctly.
  - **One non-blocking judgment-call note**: on the SOS paths, `CATEGORY_MISMATCH`/`404`/`403`
    fall back to the generic error message rather than an SOS-specific one — intentionally
    consistent with how the already-shipped Standard `BookingSummary.tsx` treats the same
    defensive-only, not-normally-reachable cases. Not treated as a gap; flagged as a possible
    future follow-up if both flows are ever upgraded together.
  - **Known gaps/deferred, carried forward unchanged**: job-status progression UI for
    professionals (Frontend Milestone 6), slot edit/delete UI (Frontend Milestone 7),
    favorites/reviews UI (not yet in scope), and the same cosmetic `OrderTrackingPage`
    back-button nit noted in Frontend Milestone 3 (still explicitly non-blocking, not
    re-litigated this pass).
- **2026-08-17 — MS3/MS4 product-corrections pass landed, QA-signed-off.** Branch
  `frontend/MS3-MS4-corrections`, local only — uncommitted, not pushed/merged. Full design
  record: `docs/architecture/ms3-ms4-corrections-design.md`. Four corrections to the
  already-shipped Standard/SOS booking flows:
  1. `GET /api/users/me` now returns a nested `defaultAddress` object (`DefaultAddressInfo`,
     new file) for a `CUSTOMER` caller with a saved default address — backend-only,
     response-shape addition, no migration. `ProfilePage.tsx` now displays it (a live QA fix
     during this pass — the page previously did not render it at all, even though the
     backend already returned it).
  2. `orders` gains 3 more service-address columns — `service_floor`/`service_entrance`/
     `service_address_notes` (`V22__alter_orders_add_service_address_details.sql`) —
     extending the existing 4-field snapshot (`V18`) to the full 7-field shape already used
     by `users.default_*` (`V20`). New frontend component `AddressSelectionStep.tsx`
     (default-saved-address vs. custom-one-off-address chooser) replaces the bare
     `AddressFormFields` both booking flows' address step previously rendered directly; all
     7 fields are now forwarded to `createOrder`/`createSosOrder` (previously only 4);
     `OrderTrackingPage.tsx` now also displays floor/entrance/notes.
  3. Professional-listing `sort` gained a genuine third value, `RECOMMENDED` (rating-based
     ranking), but the frontend exposes only a 2-way `Recommended | Cheapest` chip toggle,
     identical on both the Standard and SOS flows, both defaulting to `CHEAPEST`. This item
     required a **mid-implementation reconciliation**: a coding agent dispatched for an
     unrelated, backend-only task went out of scope and implemented a different, 3-way-ish
     sort scheme without authorization (SOS defaulting to `FASTEST`, a `Recommended |
     Fastest` chip pair for SOS instead of the 2-way spec, and an unauthorized "SOS
     prioritizes speed by default" product-decision paragraph asserted directly in
     `api-contract-professionals-reviews.md`). The user chose to keep the underlying
     `RECOMMENDED` ranking logic (grounded in `frontend/Pronto — DESIGN_SYSTEM.md` §31, a
     source the original draft of this design section hadn't consulted) but reconciled the
     chip exposure back to the originally-specified 2-way toggle, matching
     `DESIGN_SYSTEM.md` §34's chip ordering (Recommended shown first) — see the design doc's
     §3 for the full record. `FASTEST` remains a valid, working backend enum value/ranking,
     not user-facing in this pass.
  4. New booking-draft persistence (`shared/hooks/bookingDraftContext.ts`/
     `BookingDraftProvider.tsx`/`useBookingDraft.ts`, mirroring `authContext.ts`'s existing
     shape/location, wired into `App.tsx` nested inside `AuthProvider`) plus a persistent nav
     indicator (`app/BookingDraftIndicator.tsx`) — a customer's in-progress issue-creation or
     booking-flow state now survives navigation/reload and can be resumed without re-entering
     already-completed data. Supporting fix: `PhotoUploader.tsx` now also threads through the
     durable `imageUrl` from the upload response (previously discarded), so draft-persisted
     photos survive a reload.
  - **QA**: full pass, **PASS**, signed off 2026-08-17.
  - **Stale-doc corrections made as part of this pass**: `bookings/README.md` and
    `ProfessionalSort.java`'s Javadoc both still described the unauthorized "SOS defaults to
    `FASTEST`" behavior from the out-of-scope draft above — corrected to match the actual,
    reconciled code (both listing endpoints default to `CHEAPEST`).
- **2026-08-17 — Active Booking Floating Indicator feature landed, QA-passed (12/12
  checklist items, zero bugs found).** Branch `frontend/MS3-MS4-corrections`, local only —
  uncommitted, not pushed/merged. Full design record:
  `docs/architecture/active-booking-floating-indicator.md`. Two parts:
  1. **Persisted ETA, backend override.** `orders.expected_arrival_at` (new nullable
     column, `V23`) is computed once — via the same `DistanceEtaStrategy.calculate(...)`
     call `enrichAndSort` already makes for listing cards — and persisted by
     `BookingsService.onTheWay` at the moment a professional marks an order `ON_THE_WAY`,
     then surfaced on `OrderResponse`/`OrderDetailResponse`/`OrderSummaryResponse`. This
     **overrides** the 2026-08-15 "ETA never persisted / tracking screen gains no new
     field" ruling recorded in §2's professional-search-distance/ETA row above (see that
     row's own trailing override note) — the `matching` package itself is unchanged and
     still computes/persists nothing; it's the caller (`bookings`) that now persists the
     result of one specific call, once, at one specific transition. GPS/live-location
     tracking remains untouched and fully out of scope.
  2. **Frontend: a floating active-order indicator + post-completion review flow.**
     `ActiveOrderProvider`/`useActiveOrder` (new, `shared/hooks`) poll `GET
     /api/bookings/orders/me` and select at most one order to surface via a pure priority
     function (`ON_THE_WAY` > `PENDING`/`CONFIRMED` > unacknowledged `COMPLETED`;
     `CANCELLED`/`REJECTED`/`EXPIRED` never candidates) — supporting the fact that a
     customer can have more than one simultaneously-active order (the single-active-order
     invariant is per-issue, not per-customer). `ActiveOrderIndicator` (new, `app/`) renders
     as a `position: fixed` floating circular element (a sibling of `<main>`, not inside
     `<nav>` — structurally distinct from, and can coexist on-screen with,
     `BookingDraftIndicator`), gated `CUSTOMER`-only, click-through to the relevant
     order/review route. `useEtaCountdown` (new, `shared/hooks`, shared by both the
     indicator and `OrderTrackingPage`) recomputes a live countdown from the real
     `expectedArrivalAt` timestamp every second. Acknowledgement of a `COMPLETED` order
     (so it stops occupying the indicator slot) is tracked in a `pronto_ack_completed_orders`
     localStorage key, scoped/cleared per-account the same way `BookingDraftProvider`'s
     existing cross-account guard works, set on `CompletionReviewPage` mount and again after
     a successful review submission. `CompletionReviewPage` (new,
     `/orders/:orderId/review`, `CUSTOMER`-only) is the first frontend consumer of the
     already-complete backend `reviews` package (`POST /api/reviews`, via new
     `shared/api/reviews.ts`) — a one-shot fetch-and-guard-on-`COMPLETED` screen with a
     5-star rating input and optional comment. `OrderTrackingPage.tsx` also gained, beyond
     the literal ask (flagged in the design doc §9 as the author's own recommendation, not
     scope creep): a live ETA countdown while `ON_THE_WAY`, and a "leave a review" link
     while `COMPLETED` — closing a reachability gap the single-slot indicator alone would
     leave for a second, lower-priority completed order.
  - **QA**: full pass, 12/12 checklist items, zero bugs found.
  - **Known gaps/deferred**: review **editing/deletion** UI is not built — `PUT`/`DELETE
    /api/reviews/{reviewId}` exist backend-side with no frontend caller yet, only creation.
    Tie-break rules within a priority tier (soonest-ETA / most-recently-created /
    most-recently-completed) are this design's own recommendation, not a settled
    requirement from any source document.
- **2026-08-18 — Frontend Milestone 5 (in-app notification bell) landed, QA-signed-off.**
  Branch `frontend/MS5`, local only — uncommitted, not pushed/merged; that remains the user's
  own explicit git action. Built entirely on top of the already-complete, untouched backend
  `notifications` package (backend Milestone 5, above) — no backend changes this round. New:
  `shared/api/notifications.ts` (typed client for `GET /api/notifications`, `POST
  /api/notifications/{id}/read`, `POST /api/notifications/read-all`, shapes verified against
  the real backend DTOs); `shared/hooks/useNotifications.ts` (a polling wrapper around
  `usePolling`, default 4s interval — a plain hook, not a React Context, since it has exactly
  one consumer, unlike `useActiveOrder`/`useBookingDraft`); `features/notifications/`
  (`NotificationBell.tsx` — nav badge capped at `"9+"` plus an anchored dropdown panel, no
  dedicated page since the backend feed has no pagination — `notificationLabels.ts`, Hebrew
  labels for all 8 backend `messageType` values with an explicit fallback for unmapped ones).
  `app/AppLayout.tsx` renders `<NotificationBell />` in the nav for **both** roles (unlike the
  CUSTOMER-only `ActiveOrderIndicator`), since `GET /api/notifications` is an either-role,
  self-scoped feed; no `ProDashboardLayout` change was needed since it nests inside
  `AppLayout`'s route tree. Full detail, including the reachable-vs-not-yet-reachable
  `messageType` breakdown and design-decision rationale, is in `implementation-plan.md`'s
  "Frontend Milestone 5" entry (nested under Milestone 5) — not restated here.
  - **QA**: full pass, **PASS**, no bugs found, signed off 2026-08-18. Method: mixed, per this
    environment's established constraint (no browser-automation tool). RTL dropdown
    positioning and both-role nav rendering were verified via code review; the
    notification-trigger→recipient mappings, mark-read/mark-all-read persistence, empty state,
    badge-cap logic, and the `ORDER_EXPIRED` sweep path were live-verified against a real
    running backend + Postgres instance.
  - **Environment note, non-blocking**: QA's session surfaced a pre-existing local-environment
    issue on this machine — a native Windows PostgreSQL service shadows the project's own
    `docker-compose.yml` Postgres container on port 5432, so whichever starts first wins the
    port. Not a code defect; recorded here (and in `implementation-plan.md`'s Frontend
    Milestone 5 entry) so a future session doesn't lose time rediscovering it.
  - **Known gaps/deferred, not blockers**: `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` notification
    rows won't appear until Milestone 6 wires those `BookingsService` transitions to
    `recordOrderNotification(...)` — the frontend label mapping already covers them, no
    further frontend change needed when that lands. No dedicated notification page/pagination
    (matches the backend's own no-pagination design).
- **2026-08-18 — Frontend Milestone 6 (professional job-status progression actions) landed,
  QA-passed.** Branch `frontend/MS6`, local only — uncommitted, not pushed/merged; that
  remains the user's own explicit git action. Built entirely on top of the already-complete,
  untouched backend Milestone 6 job-status endpoints (`POST
  /api/bookings/orders/{orderId}/on-the-way`, `POST .../complete`, both PROFESSIONAL-only,
  see `implementation-plan.md`'s Milestone 6 entry) — no backend changes this round. Two new
  professional-only actions added to the existing shared `OrderTrackingPage.tsx` (no new
  screen/route): "mark on the way" (`יציאה לדרך`, `CONFIRMED → ON_THE_WAY`) and "mark
  completed" (`סיום העבודה`, `ON_THE_WAY → COMPLETED`), both immediate-fire (no confirmation
  dialog, matching the existing cancel button's UX), rendered in the same conditional slot as
  the existing customer-only cancel button — mutually exclusive by construction, so at most
  one of {cancel, mark-on-the-way, mark-complete} ever renders. `shared/api/bookings.ts`
  gained `markOnTheWay`/`completeOrder`, both `Promise<OrderResponse>`. `CANCEL_ERROR_MESSAGES`
  was renamed to `ORDER_ACTION_ERROR_MESSAGES` and extended with `ORDER_NOT_CONFIRMED`/
  `ORDER_NOT_ON_THE_WAY` Hebrew messages. `features/dashboard/MyJobsPage.tsx` got a
  doc-comment-only fix, removing its now-stale "no on-the-way/complete actions" claim — no
  behavioral change. Full design rationale:
  `docs/architecture/professional-status-progression-actions.md`; full detail is in
  `implementation-plan.md`'s "Frontend Milestone 6" entry (nested under Milestone 6) and
  `frontend/src/features/booking/README.md` — not restated here.
  - **QA**: passed, verified at two distinct levels (recorded separately, not blurred
    together). **Live API-level**: the real backend was run against a real Postgres DB and
    QA drove the exact HTTP calls the two buttons make through a full two-user (customer +
    professional) order lifecycle end to end, confirming real `expectedArrivalAt`
    persistence, correct 409s (`ORDER_NOT_CONFIRMED`/`ORDER_NOT_ON_THE_WAY`) on repeat/
    out-of-order calls, 403 on a customer attempting either endpoint, and correct
    `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` notification delivery via `GET /api/notifications`.
    **Code-review-level** (no browser-automation tool available in this environment,
    consistent with every prior frontend milestone): confirmed by reading the component/hook
    code that the buttons wire correctly, that `useOrderStatus`'s polling continues correctly
    through the non-terminal `CONFIRMED`/`ON_THE_WAY` states, and that
    `useEtaCountdown`/`ActiveOrderIndicator` and the notification label map already handle
    both new transitions correctly. Build (`tsc -b && vite build`) and lint (`oxlint`) both
    passed clean; no regressions found.
  - **Known gaps/deferred, not blockers**: professional-side cancellation is not built this
    pass — `canCancel` stays gated to `CUSTOMER`, an explicit decision, not an oversight.
    Slot edit/delete UI remains Frontend Milestone 7 scope. Favorites/reviews UI unchanged
    from prior frontend milestones.
- **2026-08-18 — Frontend Milestone 8 (Professional Profiles, Reviews & Favorites)
  functional/data QA-passed; documentation now closed.** Branch `frontend/MS8`, local only
  — uncommitted, not pushed/merged; that remains the user's own explicit git action. Closes
  the three leftover, never-built frontend areas of the backend feature set informally
  called "Milestone 8" (`api-contract-professionals-reviews.md`, backend-complete since that
  milestone, previously zero frontend consumption): favorites (add/remove/list, new
  `features/favorites/` module + `/favorites` CUSTOMER-only route), a professional's own
  profile self-service (bio/city/price/photo edit, new `ProfileEditorPage.tsx` as a 4th
  `ProDashboardLayout` tab, `/pro/profile`), and reviews browsing (a new
  `ProfessionalProfilePage.tsx` detail screen, `/professionals/:professionalId`, bare
  `RequireAuth`, either role, plus a co-located `ReviewList.tsx`). `ProfessionalCard.tsx`
  gained one new optional prop, `viewProfileContext`, making its identity block a secondary
  link to the new detail page (router `state`, not a query param — deliberately
  non-bookmarkable) while leaving its existing primary select button/`onSelect` behavior
  completely unchanged — zero regression to either booking flow's own selection logic. New
  `shared/api` clients: `favorites.ts`, `professionals.ts`; `reviews.ts` extended with
  `getReviews`. `AuthProvider` gained `refreshUser()`, called after a professional edits
  their `fullName` (which writes to the underlying `users` row) so the top-nav's cached name
  doesn't go stale. Full detail, including the three judgment-call resolutions (favorites
  nav placement, profile-editor location, view-profile-vs-select card affordance), is in
  `docs/architecture/frontend-ms8-design.md` and `implementation-plan.md`'s "Frontend
  Milestone 8" entry — not restated here.
  - **QA**: functional/data checks **PASS** — live API round-trip testing against a real
    backend/Postgres instance plus code review (no browser-automation tool available in this
    environment, consistent with every prior frontend milestone). Full sign-off was withheld
    pending the required per-package documentation updates, which this entry accompanies —
    no functional or data defect was found at any point.
  - **Also fixed, small/low-risk**: `formatRelativeAgeLabel`
    (`shared/utils/formatDateTime.ts`) produced grammatically incorrect Hebrew at the
    singular month/year boundary ("1 חודשים"/"1 שנים") — corrected to "חודש"/"שנה".
  - **Known gaps/deferred, not blockers**: router-`state`-loss on refresh (accepted
    degradation to a view-only detail page, not a defect); no pagination on `GET
    /api/reviews`/`GET /api/favorites` (consistent with this project's existing MVP-scale
    tolerance); empty-state copy for zero reviews/favorites was not specified by
    `DESIGN_SYSTEM.md` and was written using reasonable judgment. The
    newly-registered-professionals'-`city = NULL` gap (`api-contract-professionals-
    reviews.md` §9 item 1) is unaffected but now has a real in-app remediation path
    (`/pro/profile`) for professionals who choose to use it.

## 7. Backend architecture reference (as-built)

Merged from `backend/BACKEND_ARCHITECTURE.md` (a standalone, code-grounded reference doc,
generated by reading the actual backend source directly, deleted once its genuinely useful
content had a home here) during Milestone 7's closing documentation pass, 2026-08-15.
Verified against the current code at merge time, including the Milestone 7 `availability`
edit/delete addition and hardening fixes (`JwtSecretStartupGuard`, per-IP auth rate
limiting) that postdated the original doc.

### 7.1 Component / dependency diagram

One Spring Boot jar (a "modular monolith," per `ProntoApplication`'s own Javadoc) — no
microservice split, no JPA object-graph associations anywhere (every relationship is a
plain `@Column` FK field, navigated by repository lookups, backed by a real DB FK
constraint). State transitions go through atomic guarded `UPDATE ... WHERE <expected
state>` repository methods, not load-mutate-save, across `orders`/`issues`/
`availability_slots`/`sos_availability`/`notifications`.

```mermaid
flowchart TB
    subgraph Client
        FE[Frontend / HTTP client]
    end

    subgraph SecurityLayer["Security filter chain + interceptors (auth/common)"]
        JAF[JwtAuthenticationFilter]
        JEP[JsonAuthenticationEntryPoint]
        ARL[AuthRateLimitInterceptor\nMilestone 7]
        RRI[Per-package RoleRequiredInterceptor]
    end

    subgraph Controllers["7 REST controllers"]
        AuthC[auth] --- UsersC[users] --- AvailC[availability] --- IssuesC[issues]
        BookC[bookings] --- NotifC[notifications] --- StorC[storage]
    end

    subgraph Services["Service layer"]
        AuthS[AuthService] --- AvailS[AvailabilityService] --- IssuesS[IssuesService]
        BookS[BookingsService] --- NotifS[NotificationServiceImpl] --- StorS[StorageService]
        ClassS[ClassificationService]
    end

    subgraph Jobs["notifications.scheduler (@Scheduled)"]
        EDJ[EmailDispatchJob, 20s]
        OES[OrderExpirySweepJob, 60s]
    end

    subgraph External
        DB[(PostgreSQL)]
        S3[(AWS S3 / local disk\nStorageClient)]
        OpenAI[(OpenAI / mock\nAiClassificationClient)]
        EmailLog[LoggingEmailSender\nlogs only]
    end

    FE -->|Bearer JWT| JAF --> ARL --> RRI --> Controllers
    JAF -.401.-> JEP
    Controllers --> Services --> DB
    IssuesS --> ClassS --> S3
    ClassS --> OpenAI
    BookS -->|records notifications, same tx| NotifS
    OES -->|sweeps PENDING orders| BookS
    EDJ --> EmailLog
    AuthS --> EmailLog
    StorS --> S3
```

**Deliberate package-level dependency cycle**: `bookings → notifications`
(`NotificationService`, called after every order transition) and
`notifications → bookings` (`OrderExpirySweepJob`, which sweeps `PENDING` orders) — not a
Java-level compile cycle (no single class pair mutually imports each other), and not an
oversight; documented in both packages' own `.md` files as the direct, unavoidable
consequence of the sweep's ownership split (`data-model.md` §3 item 8). `issues ↔ bookings`
has a similar small, deliberate, documented mutual dependency (`GET /api/issues/{id}`'s
`latestOrder` field reads `bookings`; `bookings` reads `issues` as its primary direction).

### 7.2 Environment variables

All sourced from `application.yml`'s `${VAR:default}` placeholders, current as of
Milestone 7 (includes the hardening-pass addition, `PRONTO_ENVIRONMENT`) plus the
`CORS_ALLOWED_ORIGINS` addition that landed with Frontend Milestone 1 (2026-08-15, see §6
above).

| Variable | Purpose | Required? |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | PostgreSQL connection | no (all default to local-dev values) |
| `PRONTO_ENVIRONMENT` | Milestone 7 addition. Selects `local` (default) vs. anything else; consumed by `auth.security.JwtSecretStartupGuard` to decide whether to fail fast on the placeholder `JWT_SECRET`. Not a general Spring-profiles system — a deliberately minimal, single-purpose substitute. | no (defaults `local`) |
| `CORS_ALLOWED_ORIGINS` | Frontend Milestone 1 addition (2026-08-15). Comma-separated browser CORS allow-list for `/api/**`, consumed by `auth.config.SecurityConfig`'s `corsConfigurationSource()` bean. Added because cross-origin requests from the Vite dev server were being rejected on preflight before this existed. | no (defaults `http://localhost:5173`) |
| `JWT_SECRET` | HMAC-SHA256 JWT signing key (≥32 bytes) | **yes, in any real deployment** — enforced at startup by `JwtSecretStartupGuard` when `PRONTO_ENVIRONMENT != local` |
| `JWT_EXPIRATION_SECONDS` | Token TTL | no (defaults `86400` = 24h) |
| `STORAGE_MODE` | `local` \| `s3` | no (defaults `local`) |
| `STORAGE_PUBLIC_BASE_URL` | Base URL for the backend-proxied `GET /api/storage/images/**` URL, both storage modes | no (defaults `http://localhost:${server.port}`) |
| `STORAGE_LOCAL_BASE_DIR` | Filesystem root for local-mode uploads | no (defaults `./data/uploads`) |
| `STORAGE_S3_BUCKET` | Target S3 bucket | **yes, when `STORAGE_MODE=s3`** |
| `STORAGE_S3_REGION` | AWS region | no (defaults `eu-central-1`), meaningless unless `STORAGE_MODE=s3` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` (or instance role) | AWS credentials, resolved by the AWS SDK's `DefaultCredentialsProvider` chain — not read directly by this app's own config classes | **yes, when `STORAGE_MODE=s3`**. See `hardening-plan.md` §2.5: the local dev values currently in use are root-account keys, which **must** be rotated to scoped IAM credentials before any real deployment. |
| `AI_MODE` | `mock` \| `openai` | no (defaults `mock`) |
| `OPENAI_API_KEY` | OpenAI authentication | **yes, when `AI_MODE=openai`** |
| `OPENAI_MODEL` | OpenAI chat model name | no (defaults `gpt-4o-mini`) |
| `OPENAI_TIMEOUT_MS` | HTTP connect/read timeout | no (defaults `10000`) |
| `EMAIL_MODE` | Intended to select the email implementation | **not actually consumed by any `@ConditionalOnProperty`/`@Value` in the code** — only `LoggingEmailSender` exists (§6's 2026-08-15 finding above) |
| `SERVER_PORT` | HTTP listen port | no (defaults `8080`) |

### 7.3 External integrations

| Integration | Class(es) | Activated by | On-failure handling |
|---|---|---|---|
| AWS S3 (object storage) | `storage.client.S3StorageClient` | `pronto.storage.mode=s3` | `SdkException` → `502 STORAGE_SERVICE_ERROR`. **Never live-tested** (no AWS credentials in this environment, every milestone). |
| Local disk storage (default) | `storage.client.LocalDiskStorageClient` | `pronto.storage.mode=local` (default) | `IOException` → `502 STORAGE_SERVICE_ERROR`; defends against path traversal. |
| OpenAI Chat Completions API | `ai.client.OpenAiClassificationClient` | `pronto.ai.mode=openai` | Retries once, then `502 AI_SERVICE_ERROR`. **Never live-tested** (no OpenAI key in this environment). |
| Mock AI classifier (default) | `ai.client.MockAiClassificationClient` | `pronto.ai.mode=mock` (default) | Never throws — deterministic Hebrew-keyword heuristic, `general_handyman` fallback. |
| Email delivery | `auth.email.LoggingEmailSender` (sole implementation) | always | Logs at `INFO`, never throws; `EmailDispatchJob` catches any exception and marks the row `FAILED` (no retry — confirmed deferred, `hardening-plan.md` §4.2). |

No payment processor and no GPS/live-location integration exist anywhere in the codebase —
consistent with this project's permanent v1.0 exclusion (§2 above), confirmed as not
present as dead code either.
