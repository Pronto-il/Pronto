# Pronto — REST API Contract: Milestones 3, 4 & 6 (Standard + SOS Booking Flows, Job-Status Progression)

Status: **FINALIZED for Milestone 3 — implemented, QA-signed-off** (see
`backend/.../bookings/README.md` / `.../availability/README.md`). **Milestone 4 (SOS
booking flow) design added 2026-08-13, ready for `pronto-coding` — no open sign-off items
block it.** All four decisions raised in the original Milestone-3 draft's §6 were resolved
by `pronto-lead` (2026-08-13): `orders.slot_id` is approved as a new column (`V12`, decided
— see §1), a minimal `availability` slice is approved for Milestone 3 (option (a) — see
§2.10/§2.11 for the full endpoint contract), the professional-viewing-images question
stays explicitly out of scope (tracked as an open item, §7, not designed or built here),
and the `ON_THE_WAY`/`COMPLETED`-progression-belongs-to-Milestone-6 reading is confirmed
correct. Every place below that previously described a fallback/conditional design now
states the decided design directly — see §6 for the resolution record.

**Milestone 4 addition**: §2.12–§2.15 add the SOS-path creation/listing
endpoints and the professional's own SOS-availability toggle, extending this same doc in
place rather than forking a new file — §3.7 (written during Milestone 3) already predicted
almost exactly this shape and is now cross-referenced as fulfilled. §6 items 5–8 record the
Milestone-4-specific decisions (the pre-existing `urgencyType` validation gap, the
"professional becomes unavailable" semantics, the migration verdict, and the role-gating
config verdict). No new file was created — this is the same contract doc MS3 QA already
signed off on, extended.

**Milestone 6 addition (this pass, 2026-08-13, `pronto-planning`), backend design only —
ready for `pronto-coding`, no open sign-off items block it.** §2.16–§2.17 add the
`ON_THE_WAY`/`COMPLETED` job-status progression endpoints this doc has deferred since
Milestone 3 (§6 item 4) — the last two `orders.order_status` values with no producing
endpoint. §6 items 9–13 record the Milestone-6-specific decisions (`ON_THE_WAY` as a
mandatory intermediate step, the notification-recipient choice, the `issues.status →
COMPLETED` transition mechanism, confirmation that `cancel` needs no change, and the
no-new-migration verdict). §8 (new) separately verifies — per this milestone's other two
scope items — that the existing `availability` (§2.10/§2.11/§2.14/§2.15) and `GET
/api/bookings/orders/me` (§2.9) endpoints are sufficient for a professional dashboard's
"manage availability" / "see incoming requests" needs, with **no new endpoint added** for
either. As with the Milestone 4 pass, no new file was created and no UI is designed or
built here (frontend remains deferred project-wide pending the design-system decision).

**Milestone 7 addition (this pass, 2026-08-15, `pronto-planning`), backend design only —
ready for `pronto-coding`, no open sign-off items block it.** §8.2's Milestone-6 "not
building slot edit/delete" call is **reversed by explicit user decision** (not
re-litigated on the merits — the user overruled it directly, see the updated §8.2 below).
§2.18–§2.19 (new) add `PUT /api/availability/slots/{slotId}` (edit) and `DELETE
/api/availability/slots/{slotId}` (delete) to the `availability` package, following the
existing §2.10/§2.11 conventions and the atomic guarded-`UPDATE`/`DELETE` pattern (§3.2)
already used everywhere else in this doc. One new error code is introduced,
`SLOT_IN_USE` (409) — see the new "Error code taxonomy — Milestone 7 additions" table
below and §2.18/§2.19 for the exact semantics. No new Flyway migration is required (§1.6).
No other section of this doc's Milestone 3/4/6 design is changed by this pass.

**Professional weekly availability calendar, M2 (2026-08-18), implemented.** §2.3 and §2.4
are **rewritten in place** (not left stale behind a "known gap" note, unlike the Milestone 8
fields below) — `GET .../professionals/{id}/slots?issueId=` is retired entirely and replaced
by `GET .../professionals/{id}/available-windows?issueId=` (§2.3), and `POST
/api/bookings/orders` (§2.4) drops `slotId` for a direct `bookedStart` (server-derives
`bookedEnd`, pre-checks via the new `AvailabilityDerivationService`, and is now authoritatively
protected by the `ck_orders_no_overlap` exclusion constraint rather than an atomic slot claim).
§2.8 gains `customerPhone`. One new error code, `BOOKING_TIME_UNAVAILABLE` (409) — see the new
"Error code taxonomy — professional weekly availability calendar M2 addition" table below;
`SLOT_UNAVAILABLE` becomes vestigial (kept, never returned). One new migration,
`V28__alter_users_add_phone.sql` (owned by `users`, not this package — adds `users.phone`).
`createSosOrder`/`accept`/`reject`/`cancel`/`onTheWay`/`complete`/`GET .../me` — **confirmed
unchanged**. Full design: `docs/architecture/professional-weekly-calendar-design.md` §9.1/
§9.2/§10 (M2 entry). Implementation record: `backend/.../bookings/README.md`'s dedicated M2
section.

**Known gap, still open**: §2.2/§2.4/§2.8/§2.12/§2.13's request/response JSON bodies below
**predate backend Milestone 8** (professional profiles/reviews/favorites/matching) and were
never corrected in place — first flagged in `overview.md`'s Frontend Milestone 3 entry
(2026-08-16), still true as of the MS3/MS4 product-corrections pass (2026-08-17), which
extended the same order-creation/response shapes further still (3 more optional
`serviceFloor`/`serviceEntrance`/`serviceAddressNotes` fields, `V22`). The **authoritative,
current** shape for the professional-listing endpoints (§2.2/§2.12) and the order-creation/
response bodies (§2.4/§2.8/§2.13) — required `city`/`street`/`houseNumber`/optional
`apartment` listing query params, `sort`, the enriched `ProfessionalCard`, the full 7-field
`serviceCity`/`serviceStreet`/`serviceHouseNumber`/`serviceApartment`/`serviceFloor`/
`serviceEntrance`/`serviceAddressNotes` request/response fields, and the
`basePriceSnapshot`/`sosSurcharge` split — lives in
`docs/architecture/api-contract-professionals-reviews.md` §7 ("amends this doc's §2.2/§2.12
in place," and by extension §2.4/§2.8/§2.13 for the order-address/pricing fields), and is
mirrored in `frontend/src/shared/api/bookings.ts`'s own doc comments (written directly
against the real backend DTOs, not this file's prose). Rewriting §2.2/§2.4/§2.8/§2.12/§2.13
below in place remains a follow-up, not done as part of either the Milestone 8 pass or this
corrections pass (both frontend-adjacent, documentation-only passes that found the gap
rather than caused it).

**Same known gap, one more field, Active Booking Floating Indicator feature (2026-08-17)**:
§2.4/§2.5/§2.6/§2.8/§2.9/§2.16/§2.17's JSON response examples below are now *also* missing
`expectedArrivalAt` (added to `OrderResponse`/`OrderDetailResponse`/`OrderSummaryResponse`,
nullable — non-`null` only once an order has reached `ON_THE_WAY`) and, for
`OrderSummaryResponse` specifically (§2.9's `GET /api/bookings/orders/me` array entries), a
new `updatedAt` field (not previously on that lean summary shape at all). Both are computed/
persisted at the `ON_THE_WAY` transition (§2.16) — see
`docs/architecture/active-booking-floating-indicator.md` §0.1/§2 for the authoritative
field-by-field record and `docs/architecture/data-model.md` §2.9 for the backing column
(`expected_arrival_at`, `V23`). Not rewritten into the JSON examples below, same deferral as
the Milestone 8 gap immediately above.

Written by `pronto-planning`. Builds on:
- `docs/architecture/data-model.md` §2.5 (`availability_slots`), §2.7 (`issues`), §2.9
  (`orders`), §3 items 5/8/9/10 (SOS-vs-Standard availability split, `issues.status`
  lifecycle, nullable `booked_end`, the `REJECTED` decision) — status-transition semantics
  below implement these exactly, not re-litigated.
- `docs/architecture/api-contract.md` (Milestone 1 — JWT/error-envelope/role-gating
  mechanism, reused verbatim) and `docs/architecture/api-contract-issues.md` (Milestone
  2 — same conventions, plus the `RoleRequiredInterceptor` fix noted in §3.1 below).
- The already-applied `V1`–`V10` migrations. **`V8__create_orders.sql` is not edited in
  place** — see §1 for the two new migrations this doc requires: `V11` (adds `REJECTED`
  to `order_status`, a pre-existing gap fix, decided independently of this doc) and `V12`
  (adds `orders.slot_id`, new for this milestone, approved 2026-08-13). §1 also states the
  required apply order and confirms there is no ordering dependency between them beyond
  that convention.
- **Milestone 6 addition**: `docs/architecture/data-model.md` §3 item 8 (the `issues.status`
  lifecycle table this pass's `COMPLETED` transition implements exactly) and
  `docs/architecture/api-contract-notifications.md` §4.1/§4.2/§4.6 (the
  `NotificationService.recordOrderNotification(...)` call boundary this pass's two new
  endpoints call into, and §4.6's explicit prediction of exactly this shape). The already-
  applied `V1`–`V14` migrations (confirmed directly against
  `backend/src/main/resources/db/migration/`, §1.5 below) — no `V15` is introduced.

Scope: the `bookings` package, **the Standard path (Milestone 3, §2.2–§2.11), the SOS path
(Milestone 4, §2.12–§2.15), job-status progression (Milestone 6, §2.16–§2.17), and slot
edit/delete (Milestone 7, §2.18–§2.19)**; plus the `availability` package slices this
implies — Milestone 3's narrow slot-creation/self-listing slice (§2.10/§2.11), Milestone
4's SOS-availability toggle/read (§2.14/§2.15), Milestone 6's explicit confirmation (§8)
that no new `availability` *listing/toggle* endpoint was needed at the time, and Milestone
7's slot edit/delete endpoints (§2.18/§2.19, added after §8.2's original "not needed" call
was overruled by explicit user decision — see the updated §8.2). Also specifies one small,
necessary addition to the `issues` package (`GET /api/issues/{id}`, §2.1) — reasoning in
that section. Still **not** covered by this doc: the professional dashboard **UI**
(frontend remains deferred project-wide, pending the design-system decision), the
`EXPIRED`-issue-reopen gap (§9 — confirmed still open, not touched by this pass), and
email/in-app notification dispatch mechanics (owned by
`docs/architecture/api-contract-notifications.md`, though this pass does specify the two
new `recordOrderNotification(...)` call sites `bookings` makes into that package, per
§2.16/§2.17 and its own §4.6's prediction).

This doc is a **precise contract spec** (request/response JSON shapes, status codes, error
codes, field-level validation), not literal Java code — writing the controllers/services is
`pronto-coding`'s job.

---

## 0. Conventions (reused verbatim from `api-contract.md` §0 / `api-contract-issues.md` §0)

| Convention | Choice |
|---|---|
| Base paths | `/api/issues/{id}` (new, `issues` package) for issue lookup; `/api/bookings/*` (`bookings` package) for the booking flow; `/api/availability/*` (`availability` package, new this milestone, §2.10/§2.11) for the minimal slot-creation slice. See §3.9 for why professional-listing/slots-for-booking live under `/api/bookings/*` rather than `/api/professionals/*` — `/api/availability/*` is a different case (the professional managing their *own* slots, not a customer-facing listing), which is why it stays under `availability`'s own base path instead. |
| Request/response bodies | JSON, `camelCase`. |
| Auth header | `Authorization: Bearer <jwt>` — every endpoint in this doc requires auth. |
| Timestamps in JSON | ISO-8601 / RFC 3339 with offset. |
| Money fields | JSON number, ≤2 decimal places, maps to `NUMERIC(10,2)`. |
| Language of error messages | English; Hebrew presentation is the frontend's job. |
| **New convention this milestone**: path-referenced vs. body-referenced entity ids | An id that names *the resource the URL is about* (`GET /api/issues/{id}`, `GET /api/bookings/orders/{orderId}`, `GET /api/bookings/professionals/{professionalId}/slots`) → **`404 NOT_FOUND`** if it doesn't resolve. An id that's a *field inside a request body* referencing some other entity (`categoryId` in M1/M2, `professionalId`/`slotId` in `POST /api/bookings/orders` below) → **`400 VALIDATION_ERROR`**, consistent with M1/M2's `categoryId` precedent. Stated explicitly here because this milestone is the first to mix both patterns in one doc. |

### 0.1 Role gating pattern (reused, with the M2 fix in mind — corrected against the real
M2 implementation during this finalization pass)

Every endpoint below states its required role(s) explicitly. Implement role checks via the
`common.security.RoleRequiredInterceptor` (`HandlerInterceptor`, runs in `preHandle`)
pattern introduced during Milestone 2 QA — **not** an in-controller-method `RoleGuard`
call — registered per-package via a `bookings.config.BookingsWebConfig`
(`WebMvcConfigurer`), mirroring `issues.config.IssuesWebConfig`/
`storage.config.StorageWebConfig` in *mechanism*. This is a correctness requirement, not a
style preference: calling the role check from inside a controller method body runs *after*
Spring resolves `@Valid`/`@RequestParam` binding, which is exactly the bug M2 QA found and
fixed (`implementation-plan.md` Milestone 2 QA summary).

**Correction found during this finalization pass, checked against the real
`backend/src/main/java/com/pronto/issues/config/IssuesWebConfig.java` and
`.../storage/config/StorageWebConfig.java`/`common/security/RoleRequiredInterceptor.java`
implementations** (the original draft described this loosely enough to be misleading —
fixed here, no behavior change to any endpoint's actual access rule):

- `RoleRequiredInterceptor`'s real constructor takes exactly **one** `requiredRole` string
  and `RoleGuard.requireRole` checks the caller's role against that single value — there is
  **no** "assert role is one of {CUSTOMER, PROFESSIONAL}" mode. The original draft's "the
  interceptor only needs to assert role is CUSTOMER or PROFESSIONAL" description doesn't
  match how the class actually works.
- It doesn't need to. `auth.config.SecurityConfig` already applies
  `.requestMatchers("/actuator/health", "/api/auth/**").permitAll()` /
  `.anyRequest().authenticated()` to the whole app — so *any* request reaching a
  `/api/bookings/**` route past Spring Security has already been proven to carry a valid
  JWT for **some** authenticated user, and v1.0 has exactly two roles. "Reachable by either
  role" is therefore already true for free, with **no `RoleRequiredInterceptor`
  registration at all** — for `cancel` (§2.7), `GET .../orders/{orderId}` (§2.8), and
  `GET .../orders/me` (§2.9), `BookingsWebConfig` registers nothing, and the *real*
  authorization (is this caller a party to this specific order?) happens entirely in the
  service layer once the order is loaded, exactly as the draft already said — only the
  "how the route-level gate abstains" mechanism needed correcting, not that conclusion.
- `IssuesWebConfig`/`StorageWebConfig` could get away with **one** interceptor registered
  on a single blanket pattern (`/api/issues/**` / `/api/storage/**`) because *every*
  Milestone 2 endpoint in each package required the same single role (`CUSTOMER`).
  `bookings` doesn't have that luxury — its endpoints mix `CUSTOMER`-only,
  `PROFESSIONAL`-only, and either-role routes in the same package. `BookingsWebConfig`
  therefore needs **two** separate interceptor registrations, each scoped to precise path
  patterns (not one catch-all `/api/bookings/**`):
  ```java
  registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
          .addPathPatterns("/api/bookings/professionals", "/api/bookings/professionals/*/slots",
                            "/api/bookings/orders");
  registry.addInterceptor(new RoleRequiredInterceptor(UserRole.PROFESSIONAL.name()))
          .addPathPatterns("/api/bookings/orders/*/accept", "/api/bookings/orders/*/reject");
  ```
  (`/api/bookings/orders` as an exact literal pattern matches only `POST .../orders`
  itself — §2.4's create-order call — not `/api/bookings/orders/{id}` or
  `/api/bookings/orders/me`, which are longer paths and don't match a pattern with no
  trailing segment. `addPathPatterns` matches on path only, not HTTP method, which is why
  the create-order pattern must be the bare literal path rather than a `/**` wildcard —
  a wildcard would also swallow the either-role sub-paths it must not gate.)
- **A second, more consequential correction found by this same sanity check**: §2.1 below
  adds `GET /api/issues/{id}` to the **existing** `issues` package, and states its role as
  **either `CUSTOMER` or `PROFESSIONAL`**. But `IssuesWebConfig` (as it exists today, from
  Milestone 2) registers `RoleRequiredInterceptor(CUSTOMER)` on the blanket pattern
  `/api/issues/**` — which would incorrectly `403` a professional calling the new
  `GET /api/issues/{id}`, contradicting §2.1's own access rule. **`pronto-coding` must
  narrow `IssuesWebConfig`'s existing registration** from `/api/issues/**` to the two
  literal Milestone 2 paths it actually needs to restrict to `CUSTOMER`
  (`/api/issues/classify`, `/api/issues` exact) when adding the new endpoint, and register
  no interceptor for `GET /api/issues/{id}` (same "either role, real check is in the
  service layer" reasoning as above — `SecurityConfig`'s blanket `authenticated()` already
  covers it). This is a real, previously-unflagged conflict between this doc's §2.1 and the
  as-built Milestone 2 code, not a hypothetical — flagging explicitly rather than letting
  `pronto-coding` discover it by a failing test.
- §2.10/§2.11 (`POST /api/availability/slots`, `GET /api/availability/slots/me`) live in
  the **`availability` package**, not `bookings` — they need their own
  `availability.config.AvailabilityWebConfig` (`WebMvcConfigurer`), registering a single
  `RoleRequiredInterceptor(PROFESSIONAL)` on `/api/availability/**` (both endpoints share
  the same single role, so — unlike `bookings` — one blanket-pattern registration is
  correct here, the same simple case `IssuesWebConfig`/`StorageWebConfig` were in for
  Milestone 2).

**Milestone 4 role-gating config changes — verified against the real, as-built config
classes (`backend/src/main/java/com/pronto/bookings/config/BookingsWebConfig.java`,
`.../availability/config/AvailabilityWebConfig.java`), not assumed:**

- **`bookings.config.BookingsWebConfig` needs a real change.** Both new `bookings`
  endpoints (§2.12 `GET /api/bookings/sos-professionals`, §2.13 `POST
  /api/bookings/sos-orders`) are `CUSTOMER`-only, same role as the existing
  `CUSTOMER`-scoped registration. The as-built config registers that interceptor on an
  explicit literal-path list (`"/api/bookings/professionals",
  "/api/bookings/professionals/*/slots", "/api/bookings/orders"`), not a wildcard — so the
  two new routes do **not** get picked up automatically and must be added as two more
  literal entries to that same list:
  ```java
  registry.addInterceptor(new RoleRequiredInterceptor(UserRole.CUSTOMER.name()))
          .addPathPatterns("/api/bookings/professionals", "/api/bookings/professionals/*/slots",
                            "/api/bookings/orders", "/api/bookings/sos-professionals",
                            "/api/bookings/sos-orders");
  ```
  Both new patterns are bare literals (`GET /api/bookings/sos-professionals` takes its
  `issueId` as a query param, not a path segment; `POST /api/bookings/sos-orders` has no
  path variable either) — no `/*` wildcard segment needed, and neither string is a prefix
  of any existing pattern or vice versa, so there is no risk of one pattern accidentally
  swallowing another. The existing `PROFESSIONAL`-scoped registration
  (`accept`/`reject`) is untouched — nothing new in this doc is `PROFESSIONAL`-gated inside
  the `bookings` package. `accept`/`reject`/`cancel`/`GET .../{orderId}`/`GET .../me`
  (§2.5–§2.9) need **no** config change at all, consistent with §3.7's claim that they work
  unchanged for SOS orders — verified directly against `OrderRepository`'s
  `acceptIfPending`/`rejectIfPending`/`cancelIfStatus` (no `urgency_type`/`slot_id`
  branching in any of the three `@Query` bodies) and `Order`'s constructor (already accepts
  nullable `slotId`/`bookedEnd`), not merely re-asserted.
- **`availability.config.AvailabilityWebConfig` needs no change at all.** It already
  registers `RoleRequiredInterceptor(PROFESSIONAL)` on the blanket wildcard pattern
  `"/api/availability/**"` (confirmed by reading the file directly, not assumed from the
  README's prose) — `/api/availability/sos-availability` (§2.14/§2.15, also
  `PROFESSIONAL`-only) is already covered by that existing `/**` wildcard the same way
  `/api/availability/slots`/`/api/availability/slots/me` are. This is the "already-covered
  for free" case, not the "needs a new literal pattern" case `bookings` is in — the
  difference is entirely because `availability`'s existing registration already used a
  wildcard while `bookings`'s used a literal list (§0.1 above explains why each package
  made that choice).

**Milestone 6 role-gating config change — one addition, same literal-list pattern.**
`bookings.config.BookingsWebConfig`'s existing `PROFESSIONAL`-scoped
`RoleRequiredInterceptor` registration (currently `.addPathPatterns("/api/bookings/orders/*/accept",
"/api/bookings/orders/*/reject")`) needs two more literal entries for §2.16/§2.17's new
routes:
```java
registry.addInterceptor(new RoleRequiredInterceptor(UserRole.PROFESSIONAL.name()))
        .addPathPatterns("/api/bookings/orders/*/accept", "/api/bookings/orders/*/reject",
                          "/api/bookings/orders/*/on-the-way", "/api/bookings/orders/*/complete");
```
Same reasoning as every prior addition to this list: the package's literal-pattern design
(chosen because it mixes roles per-route, §0.1) does not pick up new routes automatically
the way a wildcard would. The `CUSTOMER`-scoped registration and `availability`'s
`AvailabilityWebConfig` need **no** change — neither new route is `CUSTOMER`-gated or lives
under `/api/availability/*`.

---

## 1. Prerequisite migrations — `V11` and `V12` (both decided, apply in this order)

Two migrations, both required before `pronto-coding` builds anything in this doc. **Apply
`V11` first, then `V12`** — see the ordering note at the end of this section for why, and
confirmation that there is no *functional* dependency between them (the order is a
convention, not a requirement the SQL itself enforces).

### 1.1 `V11__alter_orders_status_add_rejected.sql` — pre-existing gap fix, decided
independently of this doc

**Confirmed requirement, not a recommendation.** `V8__create_orders.sql` (already applied)
only allows the superseded 6-value `order_status` list — missing `REJECTED`, contradicting
`data-model.md` §2.9/§3 item 10. `V8` must **not** be edited in place. New migration,
next free slot (`V1`–`V10` already exist):

```sql
-- V11__alter_orders_status_add_rejected.sql
--
-- Adds 'REJECTED' as a genuine 7th orders.order_status value, per
-- docs/architecture/data-model.md §2.9 / §3 item 10 (user override, 2026-08-12).
-- V8__create_orders.sql (already applied against existing databases) only allowed the
-- superseded 6-value list and must not be edited in place.

ALTER TABLE orders DROP CONSTRAINT ck_orders_status;

ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (order_status IN
    ('PENDING', 'CONFIRMED', 'ON_THE_WAY', 'COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'));
```

Note: `ck_orders_cancelled_by` (also in `V8`) **already** allows `('CUSTOMER',
'PROFESSIONAL', 'SYSTEM')` — no change needed there; only `ck_orders_status` was wrong.
`V11` touches only this one constraint, not the `slot_id` column added by `V12` below — kept
as two separate migrations so each has one clear purpose.

### 1.2 `V12__add_slot_id_to_orders.sql` — new for this milestone, **APPROVED**
(`pronto-lead`, 2026-08-13)

Gives `orders` an unambiguous reference to the `availability_slots` row it consumed, so
`accept`/`reject`/`cancel` can release it precisely rather than matching on copied
timestamps (§5.4 below implements this as the sole, decided release mechanism — the
timestamp-heuristic fallback described in earlier drafts no longer applies). Nullable
because SOS orders (Milestone 4) never consume an `availability_slots` row at all
(`data-model.md` §2.6/§3 item 5).

```sql
-- V12__add_slot_id_to_orders.sql
--
-- Adds orders.slot_id, an FK to the availability_slots row a Standard order consumed, so
-- accept/reject/cancel can release the correct slot precisely rather than matching on
-- copied (professional_id, booked_start, booked_end) timestamps. Nullable because SOS
-- orders (Milestone 4) never consume an availability_slots row -- data-model.md
-- §2.6/§3 item 5. Approved by pronto-lead, 2026-08-13 -- see
-- docs/architecture/api-contract-bookings.md §6 item 1.

ALTER TABLE orders ADD COLUMN slot_id BIGINT NULL;
ALTER TABLE orders ADD CONSTRAINT fk_orders_slot FOREIGN KEY (slot_id)
    REFERENCES availability_slots (id) ON DELETE SET NULL;
CREATE INDEX idx_orders_slot ON orders (slot_id);
```

### 1.3 Ordering note

`V11` must be numbered/applied before `V12` — this is Flyway's normal sequential
convention (next free version numbers, applied in order), not a functional requirement of
the SQL itself: `V11` only touches `ck_orders_status`, `V12` only adds a new nullable
`slot_id` column + FK + index, and neither migration's `ALTER TABLE` statement references
anything the other one creates or drops. **Verified: no genuine ordering dependency exists
between them** — they could technically apply in either order or even be combined into one
migration without a functional difference. They're kept as two separate, sequentially
numbered files (`V11` before `V12`) purely because `V11` closes a pre-existing gap from
Milestone 1/2 (conceptually "owed" already, independent of this milestone) while `V12` is
net-new scope introduced by this doc — keeping that provenance distinction visible in the
migration history is the only reason for the ordering, not a correctness one.

### 1.4 Milestone 4 — no new migration required (verdict, not a recommendation)

Checked directly against the applied migration history and current schema, not assumed:

- `sos_availability` already exists — `V13__create_sos_availability.sql`, applied ahead of
  this milestone specifically to unblock it (`data-model.md` §4, `availability/README.md`).
  Milestone 4's toggle/read endpoints (§2.14/§2.15) read/write this existing table's
  existing `is_available`/`updated_at` columns only — no new column needed.
- `orders.slot_id` and `orders.booked_end` are already nullable (`V12`, §1.2; `V8`
  originally for `booked_end`) — SOS order creation (§2.13) writes `slot_id = NULL`,
  `booked_end = NULL` into columns that already tolerate `NULL`, no `ALTER TABLE` needed.
- `orders.order_status`'s `CHECK` constraint already includes all 7 values including
  `REJECTED` (`V11`, §1.1) — SOS orders use the exact same status set as Standard orders,
  nothing new to add.
- No new table, column, or constraint is introduced anywhere in §2.12–§2.15's design.

**Verdict: `pronto-coding` does not need to write a `V14` (or any new) migration for
Milestone 4.** If this changes later (e.g. a future decision adds an SOS-specific column),
that would be a new, separately-flagged migration — not implied by anything designed here.

### 1.5 Milestone 6 — no new migration required (verdict, checked against the real applied
migration list, not assumed)

Checked directly against `backend/src/main/resources/db/migration/`, which currently
contains exactly `V1`–`V14` (confirmed by listing the directory: `V1`–`V13` sequential, plus
`V14__alter_notifications_message_type_add_rejected.sql` from Milestone 5 — no gaps, no
files beyond `V14`):

- `orders.order_status`'s `CHECK` constraint already allows all 7 values, including
  `ON_THE_WAY` and `COMPLETED` — these were present in the **original**
  `V8__create_orders.sql` (`CHECK (order_status IN ('PENDING', 'CONFIRMED', 'ON_THE_WAY',
  'COMPLETED', 'CANCELLED', 'EXPIRED'))`, before `V11` added the 7th value, `REJECTED`).
  Milestone 6 is the first milestone to *produce* `ON_THE_WAY`/`COMPLETED` via an endpoint,
  but the schema has tolerated both values since `V8`.
- `notifications.message_type`'s `CHECK` constraint (as amended by `V14`) already includes
  `ORDER_ON_THE_WAY` and `ORDER_COMPLETED` — both were present in the original
  `V9__create_notifications.sql` list (`api-contract-notifications.md` §1 quotes it
  verbatim: `'ORDER_CREATED', 'ORDER_CONFIRMED', 'ORDER_ON_THE_WAY', 'ORDER_COMPLETED',
  'ORDER_CANCELLED', 'ORDER_EXPIRED', 'EMAIL_VERIFICATION'`); `V14` only added the unrelated
  `ORDER_REJECTED` gap-fix. `notifications.entity.NotificationMessageType` already declares
  both enum constants (confirmed by reading the file directly) — they have simply had no
  producing call site until this pass.
- `issues.status`'s `CHECK` constraint already includes `COMPLETED` (`data-model.md` §2.7,
  `V6__create_issues.sql`) — this pass is the first to reach it via a real transition, not
  the first to allow it in the schema.
- No new column, table, or constraint is introduced anywhere in §2.16/§2.17's design below.

**Verdict: `pronto-coding` does not need to write a `V15` (or any new) migration for
Milestone 6.** Every value these two new endpoints read or write already exists and is
already tolerated by the schema as of `V14`.

### 1.6 Milestone 7 (slot edit/delete) — no new migration required (verdict, checked
against the real schema, not assumed)

§2.18/§2.19 below add two new **endpoints**, not new **schema**. Both read/write columns
that already exist on `availability_slots` (`start_time`, `end_time`, `is_available`,
`updated_at` — all present since `V5__create_availability_slots.sql`) or delete a row
outright (no new column/constraint needed for a `DELETE`). No new `order_status`,
`message_type`, or `issues.status` value is introduced, and no new table is created.

**Verdict: `pronto-coding` does not need to write a `V15` (or any new) migration for
Milestone 7's slot edit/delete endpoints.** The only artifact this pass requires that
doesn't already exist is the new `SLOT_IN_USE` `ErrorCode` enum value (Java-only, not a
schema change — §2's new "Milestone 7 additions" taxonomy table).

---

## 2. Error response envelope (reused verbatim from `api-contract.md` §1 / `api-contract-issues.md` §1)

```json
{
  "timestamp": "2026-08-13T12:34:56Z",
  "path": "/api/bookings/orders/900/reject",
  "error": {
    "code": "ORDER_NOT_PENDING",
    "message": "Order 900 is not in PENDING status and cannot be accepted or rejected.",
    "details": null
  }
}
```

### Error code taxonomy — Milestone 3 additions

Codes already defined in `api-contract.md` §1 / `api-contract-issues.md` §1 are **reused
as-is** (`VALIDATION_ERROR`, `FORBIDDEN`, `UNAUTHORIZED`, `NOT_FOUND`, `INTERNAL_ERROR`).
New codes:

| `error.code` | HTTP status | Meaning |
|---|---|---|
| `ISSUE_NOT_BOOKABLE` | 409 | `POST /api/bookings/orders` (or the professional-listing/slot endpoints) called against an issue whose `status != 'OPEN'` — either it already has an active order (`BOOKED`), or it's terminal (`COMPLETED`/`CANCELLED`/`EXPIRED`). Also returned if a concurrent request wins the race to book the issue first (§3.2). |
| `CATEGORY_MISMATCH` | 400 | The referenced `professionalId`'s `category_id` doesn't match the issue's `category_id` (defense against URL/body tampering — the listing/slot endpoints only ever *offer* correctly-matched professionals). |
| `SLOT_UNAVAILABLE` | 409 | The referenced `slotId` doesn't exist for that professional, isn't currently `is_available = true`, has `start_time <= now()`, or lost a concurrency race to another request claiming it first (§3.2). **Vestigial as of the professional weekly availability calendar design's M2 (2026-08-18)** — `POST /api/bookings/orders` (§2.4) no longer accepts a `slotId` at all, so no code path can return this anymore; the professional's own `PUT`/`DELETE /api/availability/slots/{slotId}` endpoints (§2.18/§2.19) never returned it in the first place (they return `SLOT_IN_USE`, Milestone 7 table below) — kept in the taxonomy regardless, not deleted, per this doc family's "cheap insurance" convention. |
| `ORDER_NOT_PENDING` | 409 | `accept`/`reject` called on an order whose `order_status != 'PENDING'` (already decided, or lost a race to a concurrent accept/reject). |
| `ORDER_NOT_CANCELLABLE` | 409 | `cancel` called on an order in a terminal state (`COMPLETED`/`CANCELLED`/`REJECTED`/`EXPIRED`), or by an actor/state combination that isn't permitted (a professional calling `cancel` on a still-`PENDING` order — they must use `reject` instead, §2.7). |

### Error code taxonomy — Milestone 4 additions

| `error.code` | HTTP status | Meaning |
|---|---|---|
| `ISSUE_URGENCY_MISMATCH` | 409 | The referenced issue's `urgency_type` doesn't match the booking path the caller is using — a Standard-path endpoint (§2.2/§2.3/§2.4) called against an issue whose `urgency_type = 'SOS'`, or the SOS-path endpoints (§2.12/§2.13) called against an issue whose `urgency_type = 'STANDARD'`. New this milestone — see §6 item 5 for why this fixes a pre-existing Milestone 3 gap rather than only guarding the new SOS endpoints. |
| `SOS_PROFESSIONAL_UNAVAILABLE` | 409 | `POST /api/bookings/sos-orders` (§2.13) called against a `professionalId` whose `sos_availability.is_available` is not `true` at the moment of the call — the professional was available when the customer loaded the list (§2.12) but has since toggled off, or the row was never toggled on. This is the backend-observable side of PRD §3.5.6's "becomes unavailable" branch — see §3.11 for the full reasoning. |

### Error code taxonomy — Milestone 6 additions

Both new codes below follow the exact naming precedent `ORDER_NOT_PENDING` already set
(§2, Milestone 3 table) — a single-expected-source-status guard failing names that expected
status directly, distinct from `ORDER_NOT_CANCELLABLE`'s broader "wrong state or wrong
actor" framing (`cancel`, unlike `on-the-way`/`complete`, has more than one valid source
status depending on the actor, §2.7 step 4 — these two new transitions each have exactly
one).

| `error.code` | HTTP status | Meaning |
|---|---|---|
| `ORDER_NOT_CONFIRMED` | 409 | `POST /api/bookings/orders/{orderId}/on-the-way` (§2.16) called on an order whose `order_status != 'CONFIRMED'` — already progressed further (`ON_THE_WAY`/`COMPLETED`), still `PENDING`, or terminal (`CANCELLED`/`REJECTED`/`EXPIRED`); also covers losing a race to a concurrent transition on the same order. |
| `ORDER_NOT_ON_THE_WAY` | 409 | `POST /api/bookings/orders/{orderId}/complete` (§2.17) called on an order whose `order_status != 'ON_THE_WAY'` — **including** a professional attempting to jump directly from `CONFIRMED` to `COMPLETED`, which this design deliberately disallows (§6 item 9); also covers an already-`COMPLETED` order, a still-`CONFIRMED`/`PENDING` order, a terminal order, or a lost race. |

### Error code taxonomy — Milestone 7 additions (slot edit/delete)

| `error.code` | HTTP status | Meaning |
|---|---|---|
| `SLOT_IN_USE` | 409 | `PUT`/`DELETE /api/availability/slots/{slotId}` (§2.18/§2.19) called on a slot whose `is_available != true` **after** ownership has already been confirmed (§2.18/§2.19 step 3-4 already ruled out "doesn't exist"/"not yours") — i.e. the slot is currently held by an active order (`PENDING`/`CONFIRMED`/`ON_THE_WAY`) or was consumed by a `COMPLETED` order (§3.4's `is_available` reasoning, extended below), or lost a race to a concurrent claim/edit/delete between the ownership check and the atomic guard. **Deliberately a new code, not a reuse of `SLOT_UNAVAILABLE`** — see §2.18's "why a new code" note for the full reasoning; the two codes now cover disjoint call sites (`SLOT_UNAVAILABLE` was the customer-side booking-claim failure at the pre-M2 §2.4 step 9, now vestigial per the table above; `SLOT_IN_USE` is the professional-side edit/delete-protection failure here, unaffected by M2) and were never returned by the same endpoint. |

### Error code taxonomy — professional weekly availability calendar M2 addition (2026-08-18)

| `error.code` | HTTP status | Meaning |
|---|---|---|
| `BOOKING_TIME_UNAVAILABLE` | 409 | `POST /api/bookings/orders` (§2.4, reworked) — the requested `[bookedStart, bookedEnd)` is not fully contained in a single derived `AVAILABLE` segment at pre-check time (outside working hours, overlapping a manual block, or overlapping an existing `PENDING`/`CONFIRMED`/`ON_THE_WAY` booking), **or** the `INSERT` itself was rejected by the `ck_orders_no_overlap` exclusion constraint (a lost concurrency race). Replaces `SLOT_UNAVAILABLE` for this endpoint — see the Milestone 3 table above. Full design: `docs/architecture/professional-weekly-calendar-design.md` §9.2.2. |

---

## 3. Endpoints

### 2.1 `GET /api/issues/{id}` — new, `issues` package

> **Extended (2026-08-20) by `ai-issue-classification-redesign.md`.** The response gains `clarifications` (the customer's own answers, verbatim, for both roles) and `prontoAnalysis` — Pronto's Professional Brief, returned **only** to a `PROFESSIONAL` caller with an order on the issue, and `null` otherwise. Authorization, ownership rules and every existing field are unchanged.

**Why this belongs here, not silently assumed to exist.** `api-contract-issues.md` §4 and
`overview.md` §6 both flagged this exact gap: M3's booking flow needs to resolve an issue
by id (show its category/description on the professional-listing screen; validate it
belongs to the calling customer and is still bookable). **Decided: add it**, rather than
have `GET /api/bookings/professionals` take a bare `categoryId` — a `categoryId`-only
design would let a customer browse professionals for a category with no issue behind it at
all, breaking the "an order is always created against a persisted, confirmed issue"
invariant (`bookings/README.md`) and losing the ownership/bookable-state check the brief
explicitly asks for. Endpoint conceptually belongs to `issues` (mirrors how M2 kept
`/classify` under `/api/issues/*` even though it's implemented via the `ai` package) —
**`pronto-coding` should add the controller method to `issues`**, not `bookings`. Suggested
follow-up (not done by this doc): fold this section into `api-contract-issues.md` proper
during `pronto-documentation`'s next pass, the same way `data-model.md` flagged suggested
`overview.md` edits without making them itself.

**A second reason this endpoint's authorization rule matters**: `api-contract-issues.md`
§2.4's flagged scope note — "Milestone 3/4 (bookings) will need a professional assigned to
an order to view that order's issue... that authorization rule doesn't exist yet" — is
resolved here for the *issue detail* case (a professional can `GET` an issue they have any
order against, to decide accept/reject or review a confirmed job). The *image bytes*
retrieval case (`GET /api/storage/images/{key}`) is **not** resolved by this doc — remains
genuinely open, see §6 item 3 / §7.

Auth required: **yes**. Role: **either** `CUSTOMER` or `PROFESSIONAL`
— ownership resolved in the service layer, not by a route-level role matcher (§0.1).

**Behavior:**
1. Resolve caller from JWT.
2. Load issue by id → `404 NOT_FOUND` if missing.
3. Authorize:
   - `role = CUSTOMER`: allowed iff `issue.customerId == caller.id`, else `403 FORBIDDEN`.
   - `role = PROFESSIONAL`: allowed iff an `orders` row exists with
     `issue_id = :id AND professional_id = <caller's professional id>` (any status, not
     just active — a professional who was rejected, or completed a past job, can still look
     back at the issue), else `403 FORBIDDEN`.
4. Return the issue, its images, and a summary of its most recent order (if any) — see
   below.

**Response `200`:**
```json
{
  "id": 101,
  "customerId": 42,
  "categoryId": 1,
  "categoryCode": "plumbing",
  "description": "יש נזילת מים מתחת לכיור במטבח",
  "urgencyType": "STANDARD",
  "status": "BOOKED",
  "images": [
    { "id": 501, "imageUrl": "https://.../9f1c2e4a....jpg", "uploadedAt": "2026-08-13T12:30:10Z" }
  ],
  "latestOrder": {
    "id": 900,
    "professionalId": 43,
    "professionalName": "דוד כהן",
    "orderStatus": "PENDING",
    "bookedStart": "2026-08-14T09:00:00Z",
    "bookedEnd": "2026-08-14T11:00:00Z",
    "finalPrice": 150.00,
    "createdAt": "2026-08-13T12:40:00Z"
  },
  "createdAt": "2026-08-13T12:34:56Z",
  "updatedAt": "2026-08-13T12:40:00Z"
}
```

`latestOrder` is the most-recently-created `orders` row for this issue, regardless of its
status (`null` if none exists yet) — **not** restricted to "only if still active." This
single field is what lets the frontend implement the reject-return-to-list branch (see
§4) and the tracking screen without a separate list-orders call: after a rejection, the
customer's client re-fetches this endpoint, sees `latestOrder.orderStatus == 'REJECTED'`
and `issue.status == 'OPEN'`, and shows the professional list again.

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND`.

---

### 2.2 `GET /api/bookings/professionals?issueId={id}`

Auth required: **yes**. Role: **CUSTOMER**.

Professional listing for a Standard booking, filtered by the issue's category. Placed
under `/api/bookings/*` rather than `/api/professionals/*` — see §3.9.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != CUSTOMER`.
2. `issueId` query param required, must parse as a positive integer → `400
   VALIDATION_ERROR` otherwise.
3. Load issue → `404 NOT_FOUND` if missing.
4. `issue.customerId != caller.id` → `403 FORBIDDEN`.
5. **`issue.urgencyType != 'STANDARD'` → `409 ISSUE_URGENCY_MISMATCH`.** New this pass —
   see §6 item 5 for why this pre-existing Milestone 3 gap (this endpoint never checked
   `urgencyType` at all until now) is fixed here rather than left open.
6. `issue.status != 'OPEN'` → `409 ISSUE_NOT_BOOKABLE`.
7. Query `professionals` joined to `users` where `category_id = issue.categoryId` and
   `users.deleted_at IS NULL` (**resolves the flagged gap in `api-contract.md` §2.5**: a
   soft-deleted professional's row previously stayed queryable with nothing filtering it
   out of Standard/SOS listings — this join is the fix, landing exactly where that gap
   said it would need to). Ordered by `base_price ASC` (cheapest first — **judgment call**,
   not specified by any source document; trivial to change, e.g. to `reliability_score DESC
   NULLS LAST`, later).
8. Return the list. **Not** filtered by whether the professional currently has any open
   `availability_slots` (see §3.4 for the reasoning) — a professional with zero slots still
   appears; the slot-selection screen (§2.3) shows "no available times" for them.

**Response `200`:**
```json
{
  "issueId": 101,
  "categoryId": 1,
  "professionals": [
    {
      "professionalId": 43,
      "fullName": "דוד כהן",
      "serviceArea": "תל אביב",
      "basePrice": 150.00,
      "reliabilityScore": 4.50
    }
  ]
}
```

`reliabilityScore` may be `null` (no computation mechanism exists yet, per
`data-model.md` §4 — unrelated to this milestone, just carried forward as a known
null-tolerant field).

**Status codes**: `200` success · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` ·
`403 FORBIDDEN` · `404 NOT_FOUND` · `409 ISSUE_URGENCY_MISMATCH` · `409 ISSUE_NOT_BOOKABLE`.

---

### 2.3 `GET /api/bookings/professionals/{professionalId}/available-windows?issueId={id}` (superseded, M2)

> **Superseded, 2026-08-18, by the professional weekly availability calendar design's M2**
> (`docs/architecture/professional-weekly-calendar-design.md` §9.2.2). The route below is
> `GET .../available-windows?issueId=` — **not** the original `GET .../slots?issueId=` this
> section originally specified; that route is retired entirely, not kept for compatibility.
> This section is rewritten in place (not left stale) since a reader landing here needs the
> real, currently-implemented contract, not Milestone 3's original design.

Auth required: **yes**. Role: **CUSTOMER**.

A specific professional's derived `AVAILABLE` windows (from the weekly working-hours/block/
booking calendar, `professional_weekly_calendar` design §5), each already sized to fit the
default job duration, for the customer to pick a start time to book against. Discrete
`availability_slots` rows are no longer read by this endpoint at all.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != CUSTOMER`.
2. `issueId` query param required/valid → `400 VALIDATION_ERROR` otherwise.
3. Load issue → `404 NOT_FOUND`; ownership → `403 FORBIDDEN`; urgency type
   (`urgencyType == 'STANDARD'`) → `409 ISSUE_URGENCY_MISMATCH`; bookable (`status ==
   'OPEN'`) → `409 ISSUE_NOT_BOOKABLE`. **Unchanged from the original design** — identical to
   the pre-M2 checks, byte-for-byte.
4. Load professional by `{professionalId}` path variable → `404 NOT_FOUND` if it doesn't
   exist at all (path-referenced id, §0's convention). **Unchanged.**
5. `professional.categoryId != issue.categoryId` → `400 CATEGORY_MISMATCH`. **Unchanged.**
6. **New in M2, replaces the old raw `availability_slots` query**: call
   `AvailabilityDerivationService#deriveAvailableWindows(professionalId, from, to,
   Duration.ofMinutes(DEFAULT_JOB_DURATION_MINUTES))` — a thin filter over the same
   subtract-blocks/subtract-bookings derivation the calendar read endpoint uses (design §5),
   keeping only `AVAILABLE` segments whose duration is `>= DEFAULT_JOB_DURATION_MINUTES` (60).
   `from = now()`, `to = now() + 14 days` — a fixed internal lookahead, not exposed as a query
   param (a judgment call, design §9.2.2, since deriving availability on demand makes an
   unbounded future window computationally unreasonable).
7. Return the list.

**Response `200`:**
```json
{
  "professionalId": 43,
  "issueId": 101,
  "defaultDurationMinutes": 60,
  "timezone": "Asia/Jerusalem",
  "windows": [
    { "startAt": "2026-08-20T08:00:00+03:00", "endAt": "2026-08-20T12:00:00+03:00" },
    { "startAt": "2026-08-20T13:00:00+03:00", "endAt": "2026-08-20T15:00:00+03:00" }
  ]
}
```

An empty `windows` array is a valid, expected response (the professional currently has no
open windows long enough for the default job duration) — not an error. `defaultDurationMinutes`/
`timezone` are echoed by the server so the frontend never hardcodes either value.

**Status codes**: `200` success · `400 VALIDATION_ERROR` · `400 CATEGORY_MISMATCH` ·
`401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND` · `409 ISSUE_URGENCY_MISMATCH` ·
`409 ISSUE_NOT_BOOKABLE`.

---

### 2.4 `POST /api/bookings/orders` (reworked, M2)

> **Reworked, 2026-08-18, by the professional weekly availability calendar design's M2**
> (§9.2.2). Rewritten in place for the same "don't leave a reader with a stale contract"
> reason as §2.3 above.

Auth required: **yes**. Role: **CUSTOMER**.

Creates the order — the Standard-booking "pick this professional, at this start time" action.

**Request:**
```json
{
  "issueId": 101,
  "professionalId": 43,
  "bookedStart": "2026-08-20T09:00:00+03:00",
  "serviceCity": "תל אביב",
  "serviceStreet": "אלנבי",
  "serviceHouseNumber": "12"
}
```
(`serviceApartment`/`serviceFloor`/`serviceEntrance`/`serviceAddressNotes` unchanged,
optional, omitted above for brevity — see the service-address snapshot fields this DTO
already carried before M2.)

**Field validation:**

| Field | Rule |
|---|---|
| `issueId` | required, positive integer. Must resolve to an issue owned by the caller (§ Behavior step 3) — invalid/nonexistent → `404 NOT_FOUND` (path-style semantics apply here too, even though it's a body field, because the *primary* resource this call acts on is the issue being booked; **exception to the general body-field-id-is-VALIDATION_ERROR rule stated in §0**, called out explicitly since it's the one deliberate inconsistency in this doc). |
| `professionalId` | required, positive integer. Must reference an existing `professionals` row whose `users.deleted_at IS NULL` → `400 VALIDATION_ERROR` otherwise (ordinary body-reference convention, matches M1/M2's `categoryId`). |
| `bookedStart` | **New in M2, replaces `slotId`.** Required `Instant`. Must be strictly in the future (`bookedStart > now()` at request time) → `400 VALIDATION_ERROR` otherwise — same "strictly future" convention the retired slot-claim path used, re-anchored to a client-chosen instant instead of a pre-existing row. `bookedEnd` is **never** accepted from the client — always computed server-side as `bookedStart + DEFAULT_JOB_DURATION_MINUTES` (60, a placeholder business figure flagged prominently in `BookingsService`'s own Javadoc, design §9.2.1). |

**`slotId` is removed from this request entirely** — not kept, even as an optional/ignored
field, for backward compatibility (design §9.2.2: no production data, single frontend
redeployed atomically with the backend, no external API consumer to preserve compatibility
for).

**Behavior** (evaluated in this order):
1. Resolve caller; `403 FORBIDDEN` if `role != CUSTOMER`.
2. Validate field presence/shape → `400 VALIDATION_ERROR`.
3. Load issue by `issueId` → `404 NOT_FOUND` if missing.
4. `issue.customerId != caller.id` → `403 FORBIDDEN`.
5. `issue.urgencyType != 'STANDARD'` → `409 ISSUE_URGENCY_MISMATCH`. **Unchanged.**
6. `issue.status != 'OPEN'` → `409 ISSUE_NOT_BOOKABLE`. **Unchanged.**
7. Load professional by `professionalId` (with `users.deleted_at IS NULL`) → `400
   VALIDATION_ERROR` if missing/deleted. **Unchanged.**
8. `professional.categoryId != issue.categoryId` → `400 CATEGORY_MISMATCH`. **Unchanged.**
9. `bookedStart` not strictly in the future → `400 VALIDATION_ERROR`. **New in M2.**
10. **Compute `bookedEnd = bookedStart + DEFAULT_JOB_DURATION_MINUTES`. New in M2** —
    replaces the old "read the slot's own `startTime`/`endTime`" step.
11. **Fast pre-check, new in M2, replaces the old atomic slot claim (step 9 pre-M2)**: call
    `AvailabilityDerivationService#deriveCalendar(professionalId, bookedStart, bookedEnd)` and
    confirm the result contains a single `AVAILABLE` segment that fully contains
    `[bookedStart, bookedEnd)`. If not → `409 BOOKING_TIME_UNAVAILABLE` (**new error code,
    replaces `SLOT_UNAVAILABLE` for this endpoint** — `SLOT_UNAVAILABLE` itself stays in the
    taxonomy, vestigial, never returned by any code path once no caller can supply a `slotId`),
    roll back, return immediately.
12. **Atomically transition the issue**: `UPDATE issues SET status = 'BOOKED', updated_at =
    now() WHERE id = :issueId AND status = 'OPEN'`. If affected rows `= 0` → the issue was
    booked by a concurrent request between step 6 and here → roll back the whole transaction
    → `409 ISSUE_NOT_BOOKABLE`. **Unchanged in shape**, just renumbered.
13. Insert the `orders` row: `issue_id`, `customer_id = caller.id`, `professional_id`,
    `booked_start = bookedStart`, `booked_end = bookedEnd`, `order_status = 'PENDING'`,
    `cancelled_by = NULL`, `final_price = professional.basePrice` (unchanged pricing rule),
    **`slot_id = NULL` always, for every order created via this path from now on** (M2 —
    replaces the old "always set" rule; the same already-proven-safe no-op pattern SOS orders
    have used since Milestone 4).
14. **The `INSERT` itself is protected by the `ck_orders_no_overlap` exclusion constraint**
    (professional weekly availability calendar design §6, added by that feature's M1) — the
    sole authoritative backstop for the true concurrency race (two simultaneous `createOrder`
    calls for the same professional with overlapping ranges, both passing step 11's pre-check
    before either commits). Catch Postgres's `23P01` (exclusion-violation) SQLState on insert
    → map to the same `409 BOOKING_TIME_UNAVAILABLE` as step 11 (a client cannot distinguish
    "you lost a very fast race" from "that time was already gone by the time you asked," and
    doesn't need to — both mean "pick a different time"). **New in M2** — the old slot-based
    path had no equivalent race window at this step (the atomic slot claim, formerly step 9,
    already closed it).
15. Commit. Return `201`.

**Response `201`:**
```json
{
  "id": 900,
  "issueId": 101,
  "customerId": 42,
  "professionalId": 43,
  "orderStatus": "PENDING",
  "bookedStart": "2026-08-20T06:00:00Z",
  "bookedEnd": "2026-08-20T07:00:00Z",
  "finalPrice": 150.00,
  "cancelledBy": null,
  "createdAt": "2026-08-18T12:40:00Z",
  "updatedAt": "2026-08-18T12:40:00Z"
}
```
(Shape unchanged from before M2 — `bookedEnd` was already a field on this response, simply
always non-null now for a Standard order.)

**Status codes**: `201` success · `400 VALIDATION_ERROR` · `400 CATEGORY_MISMATCH` ·
`401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND` · `409 ISSUE_URGENCY_MISMATCH` ·
`409 ISSUE_NOT_BOOKABLE` · `409 BOOKING_TIME_UNAVAILABLE` (replaces `409 SLOT_UNAVAILABLE`
for this endpoint as of M2 — see step 11/14 above).

**A customer may re-pick the same professional after a rejection.** Nothing in this
endpoint (or anywhere else) prevents creating a second `orders` row against the same issue
naming the same `professionalId` that previously rejected it — `data-model.md` §2.9
explicitly allows an issue to "accumulate more than one `orders` row over time," and no
source document says a professional can't be asked again. Not blocked; also not
specially encouraged (no dedup/"already declined you" annotation on the listing, §2.2) —
plain, unfiltered re-listing.

---

### 2.5 `POST /api/bookings/orders/{orderId}/accept`

Auth required: **yes**. Role: **PROFESSIONAL**.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Load order by `orderId` → `404 NOT_FOUND` if missing.
3. Resolve caller's `professionals.id` via `ProfessionalRepository.findByUserId(caller.id)`
   (already exists — no new lookup mechanism needed). `order.professionalId !=` that id →
   `403 FORBIDDEN`.
4. Atomically transition: `UPDATE orders SET order_status = 'CONFIRMED', updated_at =
   now() WHERE id = :orderId AND order_status = 'PENDING'`. Affected rows `0` → `409
   ORDER_NOT_PENDING` (already decided, or lost a race to a concurrent accept/reject on the
   same order — extremely unlikely in practice for a single professional's own action, but
   the same atomic-guard mechanism as §2.4 is used uniformly, not conditionally).
5. `issues.status` is **not** touched — it's already `'BOOKED'` (set at order creation,
   §2.4 step 9) and stays `'BOOKED'` through `CONFIRMED`/`ON_THE_WAY`, per `data-model.md`
   §3 item 8's lifecycle table.
6. Return `200` with the updated order (same shape as §2.4's response, `orderStatus:
   "CONFIRMED"`).

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND` · `409 ORDER_NOT_PENDING`.

---

### 2.6 `POST /api/bookings/orders/{orderId}/reject`

Auth required: **yes**. Role: **PROFESSIONAL**.

Implements the **exact** semantics from `data-model.md` §3 item 10, first bullet — binding,
not deviated from: a professional declining a still-`PENDING` request →
`PENDING → REJECTED`, `cancelled_by` stays `NULL`.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Load order → `404 NOT_FOUND` if missing.
3. Ownership check, same as §2.5 step 3 → `403 FORBIDDEN`.
4. Atomically transition: `UPDATE orders SET order_status = 'REJECTED', updated_at =
   now() WHERE id = :orderId AND order_status = 'PENDING'`. Affected rows `0` → `409
   ORDER_NOT_PENDING` (e.g. the professional already accepted it, or the order already
   expired via a future M5 sweep — `reject` is only ever valid from `PENDING`).
5. **Release the slot**: see §3.4 for the exact mechanism (via `order.slotId`, `V12`, §1.2).
6. **Revert the issue to `OPEN`**: `UPDATE issues SET status = 'OPEN', updated_at = now()
   WHERE id = :issueId`. Unconditional (no `WHERE status = 'BOOKED'` guard needed) — see
   §3.3 for why the single-active-order-per-issue invariant makes this safe.
7. Return `200` with the updated order (`orderStatus: "REJECTED"`, `cancelledBy: null`).

**This is the reject → return-to-list branch's server-side half** — see §4 for the full
client-observable flow.

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND` · `409 ORDER_NOT_PENDING`.

---

### 2.7 `POST /api/bookings/orders/{orderId}/cancel`

Auth required: **yes**. Role: **CUSTOMER or PROFESSIONAL** (actor determined by which party
the caller is, not by a route-level role matcher — §0.1).

Implements `data-model.md` §3 item 10's second and third bullets: a professional backing
out of an already-`CONFIRMED` (or later) order, or a customer backing out at any stage,
both go through `CANCELLED` (never `REJECTED` — that status is reserved for the
PENDING-decline case only, handled by §2.6).

**Behavior:**
1. Resolve caller (no role gate at the route level — either role may call this).
2. Load order → `404 NOT_FOUND` if missing.
3. Determine actor:
   - `caller.role == CUSTOMER` and `order.customerId == caller.id` → actor `CUSTOMER`.
   - `caller.role == PROFESSIONAL` and `order.professionalId == <caller's professional
     id>` → actor `PROFESSIONAL`.
   - Neither → `403 FORBIDDEN`.
4. Check the actor/state combination is permitted:
   - `CUSTOMER` may cancel from `PENDING`, `CONFIRMED`, or `ON_THE_WAY`.
   - `PROFESSIONAL` may cancel **only** from `CONFIRMED` or `ON_THE_WAY` — a professional
     backing out of a still-`PENDING` order must use `reject` (§2.6) instead, so the two
     endpoints stay unambiguous about which one produces `REJECTED` vs. `CANCELLED`.
   - Any other combination (e.g. professional attempting `cancel` on a `PENDING` order, or
     either actor attempting it on a terminal-state order) → `409
     ORDER_NOT_CANCELLABLE`.
5. Atomically transition: `UPDATE orders SET order_status = 'CANCELLED', cancelled_by =
   :actor, updated_at = now() WHERE id = :orderId AND order_status = :expectedCurrentStatus`
   (the status read in step 4, re-checked here as the concurrency guard). Affected rows `0`
   → `409 ORDER_NOT_CANCELLABLE` (lost a race).
6. Release the slot — same mechanism as §2.6 step 5.
7. Revert the issue to `OPEN` — same as §2.6 step 6, unconditional.
8. Return `200` (`orderStatus: "CANCELLED"`, `cancelledBy: "CUSTOMER" | "PROFESSIONAL"`).

**Not built this milestone**: any endpoint or trigger that produces `cancelled_by =
'SYSTEM'`. The value remains valid in the `ck_orders_cancelled_by` constraint (already true
in the applied `V8`, unaffected by `V11`) for a future automated process — distinct from
the `EXPIRED` sweep (`data-model.md` §3 item 8), which uses its own status and doesn't go
through `CANCELLED` at all.

**Also not built this milestone**: an "abandon this issue entirely" action distinct from
cancelling one order. `data-model.md` §3 item 8 defines issue-level `CANCELLED` as "the
customer gave up on the issue entirely," a different, still-open concept from an
individual order being cancelled (which reverts the issue to `OPEN`, not to
issue-level `CANCELLED`) — no endpoint here produces issue-level `CANCELLED`, consistent
with that item still being an open gap-fill, not resolved by this doc either.

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND` · `409 ORDER_NOT_CANCELLABLE`.

---

### 2.8 `GET /api/bookings/orders/{orderId}`

Auth required: **yes**. Role: **CUSTOMER or PROFESSIONAL** (party-to-the-order check, same
pattern as §2.7 step 3, read-only). This is the **tracking/status endpoint** — status only,
no GPS/map, per the hard exclusion.

**Behavior:**
1. Resolve caller.
2. Load order → `404 NOT_FOUND` if missing.
3. Caller must be the order's customer or its professional → else `403 FORBIDDEN`.
4. Return the order, enriched with display-friendly names (avoids the client needing
   follow-up calls just to show "David Cohen" instead of `professionalId: 43`).

**As of the professional weekly availability calendar design's M2 (2026-08-18)**, the
response also carries `customerPhone` — populated by this **same, unmodified** step 3
authorization check (no new authorization branch, no `order_status` gating), read off the
same `User` row already loaded for `customerName`. Visible to the order's own customer and to
the assigned professional starting the moment the order is created (`PENDING` onward) — the
same access-scoping the service-address snapshot fields already use. See
`docs/architecture/professional-weekly-calendar-design.md` §9.1 for the full reasoning.

**Response `200`:**
```json
{
  "id": 900,
  "issueId": 101,
  "customerId": 42,
  "customerName": "ישראל ישראלי",
  "customerPhone": "0501234567",
  "professionalId": 43,
  "professionalName": "דוד כהן",
  "orderStatus": "CONFIRMED",
  "bookedStart": "2026-08-14T09:00:00Z",
  "bookedEnd": "2026-08-14T11:00:00Z",
  "finalPrice": 150.00,
  "cancelledBy": null,
  "createdAt": "2026-08-13T12:40:00Z",
  "updatedAt": "2026-08-13T12:41:30Z"
}
```

This is the endpoint a short-polling client (per `overview.md` §3.3) hits every 3–5s once
an order exists — **the actual polling loop/scheduler is Milestone 5's job**
(`notifications` package), this endpoint just needs to exist and be cheap (single indexed
PK lookup) for that milestone to build on. `updated_at` is what changes on every status
transition, exactly as `data-model.md` §2.9 designed it to.

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND`.

---

### 2.9 `GET /api/bookings/orders/me`

Auth required: **yes**. Role: **CUSTOMER or PROFESSIONAL** (self-scoped by role).

**Scope note, stated explicitly since it goes slightly beyond the literal M3 bullet list in
`implementation-plan.md`** (which lists "incoming-requests view" under **Milestone 6**):
this is a thin, generic **read API** — `GET /api/bookings/orders/me`, no dashboard UI, no
`features/dashboard` frontend work (out of scope, backend-only milestone anyway). It's
included here because it's a **functional prerequisite**, not a nice-to-have: without some
way for a professional to discover which orders are `PENDING` against them, `accept`/
`reject` (§2.5/§2.6) is only exercisable by a caller who already knows an `orderId` out of
band (e.g. a QA script that captured it from the `POST /api/bookings/orders` response) —
not a real, discoverable flow for an actual professional. One endpoint, both roles: the
**customer's** "my active orders" need and the **professional's** "incoming requests" need
are the same underlying query shape (orders where I'm a party, optionally filtered by
status), so Milestone 6 is expected to build its dashboard UI **on top of this same
endpoint**, not need a new one — this isn't stepping on M6's scope, just building the read
API home it will consume.

**Query params**: `status` (optional, one of the 7 `order_status` values — if provided,
filters to that status only; if omitted, returns all of the caller's orders).

**Behavior:**
1. Resolve caller.
2. `role = CUSTOMER`: query `orders WHERE customer_id = caller.id [AND order_status =
   :status]`.
   `role = PROFESSIONAL`: resolve professional id, query `orders WHERE professional_id =
   :professionalId [AND order_status = :status]`.
3. Order by `created_at DESC` (**judgment call**, easy to change — e.g. to `updated_at
   DESC` if "most recently changed" proves more useful for a professional's incoming-work
   view once M6 builds on it).
4. Return the list. No pagination this milestone (fine at MVP scale — flag for Milestone 7
   hardening if it ever isn't).

**Response `200`:**
```json
{
  "orders": [
    {
      "id": 900,
      "issueId": 101,
      "orderStatus": "PENDING",
      "bookedStart": "2026-08-14T09:00:00Z",
      "bookedEnd": "2026-08-14T11:00:00Z",
      "finalPrice": 150.00,
      "createdAt": "2026-08-13T12:40:00Z"
    }
  ]
}
```

(Field set intentionally lean — this is a list/summary endpoint; use §2.8 for full detail
on one order, matching the same "list is lean, detail is rich" pattern M2 didn't need but
M1 implicitly used for `/api/users/me` vs. nothing-else-existing.)

**Status codes**: `200` success · `400 VALIDATION_ERROR` (invalid `status` value) ·
`401 UNAUTHORIZED`.

---

### 2.10 `POST /api/availability/slots` — new, `availability` package

**Approved, `pronto-lead`, 2026-08-13 (§6 item 2, option (a)).** A professional creates one
bookable Standard advance-booking window. This was the **entire** create-side scope of the
Milestone-3 `availability` slice — no edit, no delete, no toggle-availability action, no
listing-other-professionals'-slots capability. It exists solely so §2.3/§2.4 above have
real `availability_slots` rows to book against without a raw-SQL QA workaround. **Update,
Milestone 7**: edit/delete now exist — §2.18/§2.19 — per the reversal recorded in §8.2;
richer calendar semantics beyond edit/delete (e.g. overlap validation, below) and any
dashboard UI remain out of scope, unaffected by that reversal.

Auth required: **yes**. Role: **PROFESSIONAL**.

**Request:**
```json
{ "startTime": "2026-08-14T09:00:00Z", "endTime": "2026-08-14T11:00:00Z" }
```

**Field validation:**

| Field | Rule |
|---|---|
| `startTime` | required, ISO-8601/RFC 3339 timestamp with offset, parseable → `400 VALIDATION_ERROR` otherwise. Must be strictly in the future (`startTime > now()` at request time) → `400 VALIDATION_ERROR` if not (mirrors the `start_time > now()` condition §2.3 already filters listings by — a slot created in the past could never be listed or booked, so rejecting it at creation time is more useful than silently persisting a dead row). |
| `endTime` | required, same timestamp format → `400 VALIDATION_ERROR` otherwise. Must satisfy `endTime > startTime` → `400 VALIDATION_ERROR` otherwise (matches the DB-level `CHECK (end_time > start_time)` on `availability_slots`, `data-model.md` §2.5 — validated at the API layer too so the error is a clean `400` rather than a DB constraint violation surfacing as a `500`). |

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Validate field presence/shape/ordering per the table above → `400 VALIDATION_ERROR`.
3. Resolve caller's `professionals.id` via `ProfessionalRepository.findByUserId(caller.id)`
   (same mechanism as §3.5 — no new lookup needed).
4. Insert a new `availability_slots` row: `professional_id = <resolved id>`, `start_time`,
   `end_time`, `is_available = true` (default, per `data-model.md` §2.5 — every newly
   created slot starts out bookable).
5. Return `201`.

**No overlap/double-booking validation against the professional's own existing slots** —
not requested by any source document, and out of scope for this narrow Milestone-3 slice;
a professional can create two overlapping slots today. Flagged as a candidate for
Milestone 6's richer calendar semantics, not built here.

**Response `201`:**
```json
{
  "id": 77,
  "professionalId": 43,
  "startTime": "2026-08-14T09:00:00Z",
  "endTime": "2026-08-14T11:00:00Z",
  "isAvailable": true,
  "createdAt": "2026-08-13T12:00:00Z"
}
```

**Status codes**: `201` success · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` ·
`403 FORBIDDEN`.

---

### 2.11 `GET /api/availability/slots/me` — new, `availability` package

**Approved, `pronto-lead`, 2026-08-13 (§6 item 2, option (a)).** Lets the professional who
just created slots via §2.10 verify what they created — the read-side counterpart, and
(together with §2.10) the complete Milestone-3 `availability` scope. **Not** the
professional-facing "manage my calendar" dashboard view (that's Milestone 6) — this is a
bare, unfiltered self-listing.

Auth required: **yes**. Role: **PROFESSIONAL**.

**Query params**: none this milestone. Returns **all** of the caller's slots (past,
future, available, and already-claimed/unavailable) — no `status`/date-range filter, no
pagination, consistent with this doc's "no pagination this milestone" convention elsewhere
(§2.9, §7). A Milestone 6 dashboard view is expected to add filtering/pagination on top of
this same underlying query shape, not replace it — same "read API home, richer UI consumes
it later" relationship as §2.9's note about the future professional dashboard.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Resolve caller's `professionals.id` (same mechanism as §2.10 step 3).
3. Query `availability_slots WHERE professional_id = :professionalId`, ordered by
   `start_time ASC`.
4. Return the list.

**Response `200`:**
```json
{
  "slots": [
    {
      "id": 77,
      "startTime": "2026-08-14T09:00:00Z",
      "endTime": "2026-08-14T11:00:00Z",
      "isAvailable": true,
      "createdAt": "2026-08-13T12:00:00Z"
    },
    {
      "id": 76,
      "startTime": "2026-08-13T09:00:00Z",
      "endTime": "2026-08-13T10:00:00Z",
      "isAvailable": false,
      "createdAt": "2026-08-12T08:00:00Z"
    }
  ]
}
```

An empty `slots` array (a professional who hasn't created any yet) is a valid, expected
response — not an error.

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

---

### 2.12 `GET /api/bookings/sos-professionals?issueId={id}` — new, Milestone 4

> **REMOVED (product decision, 2026-08-21).** This endpoint no longer exists. The
> browse-and-pick SOS flow it belonged to was retired in favour of **Pronto SOS**
> (`/api/sos/**`, see `backend/src/main/java/com/pronto/sos/README.md`), which is now the
> product's only SOS behaviour: the platform matches and dispatches, professionals respond
> that they are available, and the customer chooses among those who did. The specification
> below is retained as a historical record of what was built in Milestone 4.


Auth required: **yes**. Role: **CUSTOMER**.

Professional listing for an SOS booking, filtered by the issue's category **and** currently
`sos_availability.is_available = true`. Placed under `/api/bookings/*`, matching §2.2's
Standard-listing precedent and §3.9's reasoning exactly (issue-scoped matching, not a
general professional directory) — kept as a sibling of §2.2 rather than a query-param
variant of it (e.g. `GET /api/bookings/professionals?issueId=&urgent=true`) so the two
listing shapes can diverge independently later (the SOS listing already needs a different
join/filter today) without one endpoint's contract quietly depending on the other's.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != CUSTOMER`.
2. `issueId` query param required, must parse as a positive integer → `400
   VALIDATION_ERROR` otherwise.
3. Load issue → `404 NOT_FOUND` if missing.
4. `issue.customerId != caller.id` → `403 FORBIDDEN`.
5. `issue.urgencyType != 'SOS'` → `409 ISSUE_URGENCY_MISMATCH` (the SOS-side mirror of the
   fix applied to §2.2/§2.3/§2.4 — see §6 item 5. Unlike those three, this is *not* a
   pre-existing-gap fix — this endpoint is new, so the check is simply built in from the
   start).
6. `issue.status != 'OPEN'` → `409 ISSUE_NOT_BOOKABLE`.
7. Query `professionals` joined to `users` (`users.deleted_at IS NULL`, same soft-delete
   exclusion as §2.2) and joined to `sos_availability` where `category_id =
   issue.categoryId` and `sos_availability.is_available = true`. A professional with no
   `sos_availability` row at all is excluded by the join (in practice this shouldn't occur
   — `data-model.md` §2.6 documents a row being created for every professional at
   registration time, defaulting to `false`, precisely so no NULL-handling is needed here).
   Ordered by `base_price ASC` — same **judgment call** as §2.2, made independently (not
   required to match, per the task brief), landing on the same choice because nothing in
   the source documents suggests a different SOS-specific ordering signal (e.g. "soonest
   available" has no meaning here — SOS availability isn't time-windowed, §2.6).
8. Return the list. An **empty list is a valid, expected response** — this is the backend
   shape behind PRD §3.5.6's "no-available-professional message," which is a **frontend
   rendering concern** (rendering an empty array as that message), not a backend error
   condition. No `404`/`409` is returned for "zero professionals currently available."

**Response `200`:**
```json
{
  "issueId": 102,
  "categoryId": 1,
  "professionals": [
    {
      "professionalId": 47,
      "fullName": "משה לוי",
      "serviceArea": "רמת גן",
      "basePrice": 220.00,
      "reliabilityScore": null
    }
  ]
}
```

Deliberately the **same shape** as §2.2's response (`issueId`, `categoryId`,
`professionals: [{ professionalId, fullName, serviceArea, basePrice, reliabilityScore }]`)
— per `overview.md` §4's `features/professionals` note that a future shared frontend
component should be able to consume both listings with minimal branching (PRD §7.4). No
extra field (e.g. an `sosAvailable` flag) is added to each card — every entry in *this*
response is, by construction, currently SOS-available, so such a flag would be redundant.

**Status codes**: `200` success · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` ·
`403 FORBIDDEN` · `404 NOT_FOUND` · `409 ISSUE_URGENCY_MISMATCH` ·
`409 ISSUE_NOT_BOOKABLE`.

---

### 2.13 `POST /api/bookings/sos-orders` — new, Milestone 4

> **REMOVED (product decision, 2026-08-21).** This endpoint no longer exists. The
> browse-and-pick SOS flow it belonged to was retired in favour of **Pronto SOS**
> (`/api/sos/**`, see `backend/src/main/java/com/pronto/sos/README.md`), which is now the
> product's only SOS behaviour: the platform matches and dispatches, professionals respond
> that they are available, and the customer chooses among those who did. The specification
> below is retained as a historical record of what was built in Milestone 4.


Auth required: **yes**. Role: **CUSTOMER**.

Creates an SOS order — the SOS-booking "pick this currently-available professional" action
(PRD §3.5.3/§3.5.4). No slot selection: SOS has no `availability_slots` involvement at all
(`data-model.md` §2.6/§3 item 5) — the request naturally has one fewer field than §2.4's.

**Request:**
```json
{ "issueId": 102, "professionalId": 47 }
```

**Field validation:**

| Field | Rule |
|---|---|
| `issueId` | required, positive integer. Must resolve to an issue owned by the caller (Behavior step 3/4) — invalid/nonexistent → `404 NOT_FOUND`, same path-style exception to the general body-field-id rule that §2.4's own `issueId` field already uses (§0), for the identical reason (issue is the primary resource this call acts on). |
| `professionalId` | required, positive integer. Must reference an existing `professionals` row whose `users.deleted_at IS NULL` → `400 VALIDATION_ERROR` otherwise — same ordinary body-reference convention as §2.4's `professionalId`. |

**Behavior** (evaluated in this order):
1. Resolve caller; `403 FORBIDDEN` if `role != CUSTOMER`.
2. Validate field presence/shape → `400 VALIDATION_ERROR`.
3. Load issue by `issueId` → `404 NOT_FOUND` if missing.
4. `issue.customerId != caller.id` → `403 FORBIDDEN`.
5. `issue.urgencyType != 'SOS'` → `409 ISSUE_URGENCY_MISMATCH` (built in from the start,
   same reasoning as §2.12 step 5 — this is a new endpoint, not a retrofit).
6. `issue.status != 'OPEN'` → `409 ISSUE_NOT_BOOKABLE`.
7. Load professional by `professionalId` (with `users.deleted_at IS NULL`) → `400
   VALIDATION_ERROR` if missing/deleted.
8. `professional.categoryId != issue.categoryId` → `400 CATEGORY_MISMATCH`.
9. **Read-check the professional's SOS availability** (single transaction from here through
   step 11, same `@Transactional` shape as §2.4, but see the note below on why this step is
   a plain read, not an atomic claim): load the professional's `sos_availability` row. If it
   doesn't exist, or `is_available != true` → `409 SOS_PROFESSIONAL_UNAVAILABLE`, roll back
   the transaction (nothing has been written yet at this point — the issue hasn't been
   touched), return immediately. **This is the order-creation-time check that implements
   PRD §3.5.6's "becomes unavailable" branch — see §3.11 for the full reasoning.**
10. **Atomically transition the issue**: `UPDATE issues SET status = 'BOOKED', updated_at =
    now() WHERE id = :issueId AND status = 'OPEN'` — the **same** `bookIfOpen` mechanism
    §2.4 step 10 uses (`issues.repository.IssueRepository`, no urgency-type branching in
    that query). If affected rows `= 0` → the issue was booked by a concurrent request
    between step 6 and here → roll back → `409 ISSUE_NOT_BOOKABLE`.
11. Insert the `orders` row: `issue_id`, `customer_id = caller.id`, `professional_id`,
    `booked_start = now()` (the moment of the request — SOS has no pre-agreed window, unlike
    Standard's `slot.startTime`), `booked_end = NULL` (always — `data-model.md` §2.9's
    nullability of this column exists specifically for this case), `order_status =
    'PENDING'`, `cancelled_by = NULL`, `final_price = professional.basePrice` (same
    initialization rule as §2.4), `slot_id = NULL` (always — no `availability_slots` row is
    ever involved in an SOS order).
12. Commit. Return `201`.

**Why the SOS-availability check (step 9) is a plain read, not an atomic
claim-with-exclusive-lock, unlike the Standard slot claim (§2.4 step 9 / §3.2) — design
decision made here, evaluated against the source docs, not inherited from anywhere:** a
specific `availability_slots` row represents one professional's one calendar window — two
customers must not both be able to claim it, hence the exclusive `UPDATE ... WHERE
is_available = true` claim. `sos_availability.is_available`, by contrast, is not a
resource that gets "consumed" by a single incoming request — it's a live signal ("I'm
currently open for urgent work"), and nothing in PRD §3.5.2–§3.5.4 or the `sos_availability`
table design (`data-model.md` §2.6/§3 item 5) suggests a professional can only receive one
SOS request at a time. This mirrors the *non-exclusive* model Standard booking already has
at the professional level (nothing stops two different customers from independently
booking the same professional for two different slots) — just applied one level up, at
"is this professional open for SOS work at all" rather than at a specific resource. A plain
read-check inside the `@Transactional` method (ordinary read-committed semantics, no
`SELECT ... FOR UPDATE`, consistent with this whole doc's §3.2 "no explicit locking, no
`@Version` column" convention) is therefore sufficient and is the design adopted here. The
narrow race this leaves open — a professional toggles off in the few milliseconds between
this read and the transaction's commit — is accepted as a low-probability, low-consequence
edge case (the professional would simply reject the resulting `PENDING` order, §2.6,
functionally equivalent to the customer having been bounced back to the list one step
later than ideal), not different in kind from races already accepted elsewhere in this
doc (e.g. concurrent `accept`/`reject`, §3.2).

**Response `201`:** identical shape to §2.4's response, differing only in the values —
```json
{
  "id": 950,
  "issueId": 102,
  "customerId": 42,
  "professionalId": 47,
  "orderStatus": "PENDING",
  "bookedStart": "2026-08-13T13:05:00Z",
  "bookedEnd": null,
  "finalPrice": 220.00,
  "cancelledBy": null,
  "createdAt": "2026-08-13T13:05:00Z",
  "updatedAt": "2026-08-13T13:05:00Z"
}
```

**Status codes**: `201` success · `400 VALIDATION_ERROR` · `400 CATEGORY_MISMATCH` ·
`401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND` · `409 ISSUE_URGENCY_MISMATCH` ·
`409 ISSUE_NOT_BOOKABLE` · `409 SOS_PROFESSIONAL_UNAVAILABLE`.

**Everything downstream of order creation is unchanged, reused endpoints — not redesigned
here.** Once this call returns `201`, the resulting `orders` row is indistinguishable, from
`accept`/`reject`/`cancel`/`GET .../{orderId}`/`GET .../me`'s point of view, from a
Standard order — §2.5–§2.9 apply verbatim, per §3.7's original prediction (now confirmed
against the real code, §0.1 above).

---

### 2.14 `PUT /api/availability/sos-availability` — new, Milestone 4, `availability` package

> **Still live.** The professional's SOS on/off toggle survived the removal of the
> browse-and-pick flow: `sos_availability` is a hard eligibility filter in Pronto SOS
> matching (`SosCandidateRepository.findEligible`), so this endpoint is now more
> load-bearing than it was, not less.


Auth required: **yes**. Role: **PROFESSIONAL**.

The professional's own SOS-availability toggle — "I'm currently available for urgent work"
— per PRD §3.5.2 and `data-model.md` §2.6/§3 item 5. Lives in `availability`, not
`bookings` (same package boundary as §2.10/§2.11 — this package owns both `availability_slots`
and `sos_availability`, `overview.md` §4), under its own base path per §0's convention.
This is the endpoint the `availability/README.md` status line already flagged as
Milestone-4 scope ("the toggle/listing endpoints themselves are Milestone 4") — built here,
not new scope being invented.

**Request:**
```json
{ "isAvailable": true }
```

**Field validation:**

| Field | Rule |
|---|---|
| `isAvailable` | required, boolean → `400 VALIDATION_ERROR` if missing or not a boolean. |

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Validate field presence/shape → `400 VALIDATION_ERROR`.
3. Resolve caller's `professionals.id` via `ProfessionalRepository.findByUserId(caller.id)`
   (same existing mechanism as §2.10 step 3 / §3.5).
4. **Write**: `UPDATE sos_availability SET is_available = :isAvailable, updated_at = now()
   WHERE professional_id = :professionalId`. **No `WHERE <current-state-guard>` beyond the
   row's own key is needed or used, unlike every `orders`/`availability_slots` transition
   in this doc** — there is no "wrong state to toggle from": setting the same value twice
   in a row, or flipping it either direction from either prior value, is always a valid
   request from a still-authenticated professional. This is a plain, unconditional
   `UPDATE`, not the §3.2 guarded-transition pattern — deliberately, because §3.2's pattern
   exists to defend against a *concurrent conflicting state change*, and there is no
   concept of a conflicting concurrent change here (the professional is the sole writer of
   their own toggle; two rapid toggles from the same professional simply produce the
   last-write-wins result, which is correct, not a race to guard against). It is still
   issued as a repository-level `UPDATE`, not a JPA load-mutate-save round trip, because
   `SosAvailability` (like `Order`) exposes no setter for `isAvailable` — consistent with
   this codebase's established entity-design convention (`bookings/README.md`'s note on
   `Order`), not a new convention invented here.
5. **Invariant check, not a normal error path**: the affected-row count from step 4 should
   always be `1` — every professional is expected to have a `sos_availability` row created
   at registration time (`data-model.md` §2.6's row-lifecycle note; already true as of the
   `V13` schema-gap fix). If it is `0` (the row is somehow missing — a data-integrity bug,
   not a user-facing error condition), respond `500 INTERNAL_ERROR` and log at `WARN`,
   **not** a new `4xx` error code — this isn't a condition a well-behaved client can ever
   trigger through normal use, so it doesn't belong in the `error.code` taxonomy the way
   `SOS_PROFESSIONAL_UNAVAILABLE` (a real, reachable client-facing state) does.
6. Return `200` with the updated value.

**Response `200`:**
```json
{
  "professionalId": 47,
  "isAvailable": true,
  "updatedAt": "2026-08-13T13:00:00Z"
}
```

**Status codes**: `200` success · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` ·
`403 FORBIDDEN`.

---

### 2.15 `GET /api/availability/sos-availability` — new, Milestone 4, `availability` package

Auth required: **yes**. Role: **PROFESSIONAL**.

Reads the caller's own current SOS-availability value — the read-side counterpart to
§2.14, for the professional's dashboard toggle UI to render its current state. No `/me`
suffix (unlike §2.11's `/api/availability/slots/me`) — deliberate, not an inconsistency:
`sos_availability` is inherently a single row per professional with no "list" concept at
all (§2.6), so there's no ambiguity a `/me` suffix would need to resolve the way it does
for `/api/availability/slots/me` (which disambiguates "my slots" from a hypothetical
by-id/by-professional variant that doesn't currently exist either, but the naming
precedent there was set by §2.11 already existing). Kept short since there is exactly one
resource this URL could ever mean for an authenticated professional.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Resolve caller's `professionals.id` (same mechanism as §2.14 step 3).
3. Load the `sos_availability` row by `professional_id`. Missing row → same invariant-
   violation handling as §2.14 step 5 (`500 INTERNAL_ERROR`, logged at `WARN` — not
   expected to occur given the registration-time row-creation guarantee).
4. Return it.

**Response `200`:**
```json
{
  "professionalId": 47,
  "isAvailable": false,
  "updatedAt": "2026-08-12T09:00:00Z"
}
```

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

---

### 2.16 `POST /api/bookings/orders/{orderId}/on-the-way` — new, Milestone 6, `bookings` package

Auth required: **yes**. Role: **PROFESSIONAL**.

The first of the two job-status progression endpoints this milestone adds — `CONFIRMED →
ON_THE_WAY`. Every milestone since Milestone 3 has flagged this exact transition as
Milestone 6 scope (§6 item 4 below; `data-model.md` §3 item 8's lifecycle table; the
`orders.order_status` `CHECK` and `NotificationMessageType`/`OrderStatus` enums have carried
`ON_THE_WAY` unused since `V8`/`V9` — §1.5 confirms this precisely). Shape is deliberately
identical to `accept` (§2.5) — same ownership check, same single guarded `UPDATE`, same
"`issues.status` untouched" behavior — because this is the same category of transition:
a professional-only, single-hop, `PENDING`-style guarded advance with no side effect on the
issue or the slot.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Load order by `orderId` → `404 NOT_FOUND` if missing.
3. Resolve caller's `professionals.id` via `ProfessionalRepository.findByUserId(caller.id)`
   (same mechanism as §2.5 step 3 / §3.5). `order.professionalId !=` that id → `403
   FORBIDDEN`.
4. Atomically transition: `UPDATE orders SET order_status = 'ON_THE_WAY', updated_at =
   now() WHERE id = :orderId AND order_status = 'CONFIRMED'`. Affected rows `0` → `409
   ORDER_NOT_CONFIRMED` (the order was never confirmed, already progressed further, is
   terminal, or lost a race to a concurrent transition on the same order — same atomic-guard
   mechanism as every other transition in this doc, §3.2, applied uniformly).
5. **`issues.status` is not touched** — it is already `'BOOKED'` (set at order creation,
   §2.4/§2.13 step 9/10) and stays `'BOOKED'` through `CONFIRMED`/`ON_THE_WAY`, exactly as
   `data-model.md` §3 item 8's lifecycle note and this doc's own §2.5 step 5 already state
   for the `CONFIRMED` case — `ON_THE_WAY` doesn't change that, it's still "the order is in
   progress." See §3.6's updated transition table below.
6. **Notification hook**: `notificationService.recordOrderNotification(orderId,
   order.getCustomerId(), NotificationMessageType.ORDER_ON_THE_WAY)` — recipient is the
   **customer**. Reasoning: symmetric with `accept`'s choice (§4.2 of
   `api-contract-notifications.md`) — the professional took the action and doesn't need
   telling about their own action; the customer needs to know their professional is en
   route. See §6 item 10 below.
7. Return `200` with the updated order (same shape as §2.4/§2.5's `OrderResponse`,
   `orderStatus: "ON_THE_WAY"` — no new DTO needed, `OrderStatus` already has this value).

**Response `200`:**
```json
{
  "id": 900,
  "issueId": 101,
  "customerId": 42,
  "professionalId": 43,
  "orderStatus": "ON_THE_WAY",
  "bookedStart": "2026-08-14T09:00:00Z",
  "bookedEnd": "2026-08-14T11:00:00Z",
  "finalPrice": 150.00,
  "cancelledBy": null,
  "createdAt": "2026-08-13T12:40:00Z",
  "updatedAt": "2026-08-14T09:05:00Z"
}
```

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND` · `409 ORDER_NOT_CONFIRMED`.

---

### 2.17 `POST /api/bookings/orders/{orderId}/complete` — new, Milestone 6, `bookings` package

Auth required: **yes**. Role: **PROFESSIONAL**.

The second job-status progression endpoint — `ON_THE_WAY → COMPLETED`, plus the matching
`issues.status: BOOKED → COMPLETED` transition `data-model.md` §3 item 8 and this doc's own
§3.6 table already named but left unbuilt.

**Decided: `ON_THE_WAY` is a mandatory intermediate step — a professional cannot call this
endpoint directly from `CONFIRMED`, skipping "on the way."** Full reasoning in §6 item 9;
summary here since it's the single most consequential call this section makes: (a) PRD
§3.6.1 lists `Pending, Confirmed, On the Way, Completed, Cancelled, Expired` as a named,
ordered sequence, and nothing in the PRD describes a "skip a step" path; (b) every other
guarded transition already built in this doc (`accept`/`reject` from exactly `PENDING`,
`cancel`'s precise actor/state matrix, §2.7 step 4) is a strict single-hop guard with no
precedent anywhere for a multi-hop "or skip ahead" `WHERE` clause — inventing one here would
be a new, unrequested pattern; (c) allowing `CONFIRMED → COMPLETED` directly would mean
`ORDER_ON_THE_WAY`'s notification (and the "professional en route" signal that is this
platform's core real-time-status value proposition, `overview.md` §3.3) could be silently
skipped for some jobs and not others, with no way for the customer to know which to expect.
Therefore `complete` guards on `ON_THE_WAY` only, exactly as `on-the-way` (§2.16) guards on
`CONFIRMED` only — both single-hop, no fallback branch.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != PROFESSIONAL`.
2. Load order by `orderId` → `404 NOT_FOUND` if missing.
3. Ownership check, same mechanism as §2.16 step 3 → `403 FORBIDDEN`.
4. Atomically transition: `UPDATE orders SET order_status = 'COMPLETED', updated_at =
   now() WHERE id = :orderId AND order_status = 'ON_THE_WAY'`. Affected rows `0` → `409
   ORDER_NOT_ON_THE_WAY` — this is also the code returned for the deliberately-disallowed
   `CONFIRMED → COMPLETED` skip-ahead attempt (a `CONFIRMED` order fails this guard exactly
   like any other non-`ON_THE_WAY` order would; no separate error code distinguishes "you
   skipped a step" from "wrong state for any other reason" — consistent with how
   `ORDER_NOT_PENDING` doesn't separately distinguish *why* an order wasn't `PENDING`
   either).
5. **Transition the issue**: `issueRepository.completeIfBooked(issue.getId(), now)` — a new
   `IssueRepository` method, mirroring `expireIfBooked`'s exact shape (`data-model.md` §3
   item 8 / §4.5 of `api-contract-notifications.md`): `UPDATE issues SET status =
   'COMPLETED', updated_at = now() WHERE id = :issueId AND status = 'BOOKED'` — guarded on
   `BOOKED`, **not** unconditional like `reject`/`cancel`'s `revertToOpen`. **Not branched
   on/checked for a `0`-row result**, exactly matching how the existing
   `BookingsService.expireIfPending` calls `issueRepository.expireIfBooked(...)` today with
   no affected-row check at all: the single-active-order-per-issue invariant (§3.3) that
   already justifies `expireIfBooked`'s unchecked call applies identically here — step 4's
   guarded `UPDATE` only succeeds if this order was still `ON_THE_WAY`, i.e. still the sole
   active order against this issue, which guarantees the issue is still `BOOKED` at this
   exact moment (nothing else could have moved it away without also having moved this order
   out of `ON_THE_WAY` first, which the step-4 guard already ruled out). See §6 item 11 for
   the full reasoning record.
6. **Notification hook**: `notificationService.recordOrderNotification(orderId,
   order.getCustomerId(), NotificationMessageType.ORDER_COMPLETED)` — recipient is the
   **customer**, same reasoning as §2.16 step 6 (the professional acted, the customer needs
   to know; see §6 item 10).
7. Return `200` with the updated order (`orderStatus: "COMPLETED"`).

**Response `200`:**
```json
{
  "id": 900,
  "issueId": 101,
  "customerId": 42,
  "professionalId": 43,
  "orderStatus": "COMPLETED",
  "bookedStart": "2026-08-14T09:00:00Z",
  "bookedEnd": "2026-08-14T11:00:00Z",
  "finalPrice": 150.00,
  "cancelledBy": null,
  "createdAt": "2026-08-13T12:40:00Z",
  "updatedAt": "2026-08-14T11:10:00Z"
}
```

**Status codes**: `200` success · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND` · `409 ORDER_NOT_ON_THE_WAY`.

**`cancel` (§2.7) remains reachable from `ON_THE_WAY`, unchanged — confirmed, not modified
by this pass.** §2.7 step 4 already permits both `CUSTOMER` (from `PENDING`/`CONFIRMED`/
`ON_THE_WAY`) and `PROFESSIONAL` (from `CONFIRMED`/`ON_THE_WAY`) to cancel an
`ON_THE_WAY` order — nothing about §2.16/§2.17 narrows that. A race between a professional
calling `complete` and either party calling `cancel` on the same `ON_THE_WAY` order is
handled by the same guarded-`UPDATE`-on-`order_status` mechanism every other concurrent-
transition race in this doc already relies on (§3.2) — whichever call's `UPDATE` runs first
wins; the other's guard affects `0` rows and returns its own state-conflict error
(`409 ORDER_NOT_ON_THE_WAY` or `409 ORDER_NOT_CANCELLABLE` respectively), no new mechanism
needed. See §6 item 12.

---

### 2.18 `PUT /api/availability/slots/{slotId}` — new, Milestone 7, `availability` package

**Reverses §8.2's Milestone-6 "not building" call — by explicit user decision, not a
re-evaluation of the original reasoning.** The user has decided a professional must be
able to fully manage their own availability calendar: create (§2.10, already built), edit
(this section), and delete (§2.19). See the rewritten §8.2 below for the full record of
what changed and why this is not a silent reversal.

Auth required: **yes**. Role: **PROFESSIONAL**. No `AvailabilityWebConfig` change needed —
its existing registration already covers `/api/availability/**` with a `PROFESSIONAL`
`RoleRequiredInterceptor` on a blanket wildcard pattern (§0.1), which already includes this
new route for free, the same way it already covered §2.14/§2.15 without any config edit.

**Why `PUT`, not `PATCH` — matching this project's own convention, not generic REST
purism.** The brief's default preference is `PATCH`/`PUT` for edit. This project has
exactly one existing precedent for "a professional replaces the current value(s) of a
resource they own": `PUT /api/availability/sos-availability` (§2.14), a full-value
replace of the resource's one mutable field. This endpoint is the same shape one level
up — a full-value replace of *both* of a slot's mutable fields (`startTime`/`endTime`
together, always both required, never one alone) — so `PUT` is the consistent choice, not
`PATCH` (which would imply partial-field semantics this design deliberately doesn't offer;
a professional cannot edit `startTime` without also restating `endTime`, mirroring how
`POST /api/availability/slots`, §2.10, already requires both fields together at creation).

**Why `/api/availability/slots/{slotId}`, not nested any other way.** Mirrors
`/api/bookings/orders/{orderId}` (§2.5-§2.7/§2.16/§2.17)'s path-referenced-resource shape
exactly — the slot being edited is a path variable, not a body field, consistent with §0's
convention that "an id that names *the resource the URL is about*" gets `404 NOT_FOUND`
treatment (see step 2 below) rather than the body-field `400 VALIDATION_ERROR` treatment
`professionalId`/`slotId` get inside `POST /api/bookings/orders`'s request body (§2.4).

**Request:** identical wire shape to §2.10's create request — `pronto-coding` may reuse
`availability.dto.CreateSlotRequest` verbatim for this endpoint's `@RequestBody` (same two
fields, same Bean Validation annotations, same business-rule validation, §3.4/§3.10 of this
codebase's existing DRY conventions favor reuse over a near-duplicate `UpdateSlotRequest`
class) or introduce an identically-shaped sibling type if a future divergence is
anticipated — **either is acceptable**, this doc does not mandate which; the *wire
contract* below is what's binding, not the Java class name.
```json
{ "startTime": "2026-08-20T09:00:00Z", "endTime": "2026-08-20T11:00:00Z" }
```

**Field validation** — identical rules to §2.10's create validation, re-applied to the
*new* values (not the slot's current, pre-edit values):

| Field | Rule |
|---|---|
| `startTime` | required, ISO-8601/RFC 3339 timestamp with offset, parseable → `400 VALIDATION_ERROR` otherwise. Must be strictly in the future (`startTime > now()` at request time) → `400 VALIDATION_ERROR` if not — same rule as §2.10, same reasoning (an edited slot must still be a real, bookable future window). |
| `endTime` | required, same timestamp format → `400 VALIDATION_ERROR` otherwise. Must satisfy `endTime > startTime` → `400 VALIDATION_ERROR` otherwise — same rule as §2.10. |

**Judgment call, stated explicitly: editing does *not* additionally require the slot's
*current* (pre-edit) `startTime` to still be in the future.** A professional may edit a
slot whose original `startTime` has already lapsed, as long as it is still
`is_available = true` (never booked) and the *new* `startTime`/`endTime` satisfy the table
above. Reasoning: an unbooked slot that aged into the past is exactly the "stale slot with
no negative functional consequence" case §8.2's original reasoning already described
(`GET .../slots/me`, §2.11, still lists it, unfiltered) — allowing a professional to
correct/reuse that row by editing it into a valid future window is a strict improvement
over their only prior option (leave it stale forever, or create a brand-new row instead).
Nothing in the booking-protection rule below depends on the slot's *current* `startTime`;
only `is_available` matters, per the reasoning in §3.4 (extended below).

**Behavior** (evaluated in this order — resource existence/ownership is resolved *before*
the new values are business-validated, deliberately mirroring the authorization-first
ordering `accept`/`reject`/`cancel`/`on-the-way`/`complete` already use for their
path-referenced `{orderId}`, §2.5-§2.7/§2.16/§2.17, rather than §2.4's create-order
ordering, which validates body-referenced ids before loading anything — that ordering
doesn't apply here since `{slotId}` is path-referenced, not a body field, per §0's
convention):
1. Resolve caller (role already gated to `PROFESSIONAL` by `AvailabilityWebConfig`, no
   in-method check needed — same as every existing `availability` endpoint, §2.10 step 1 /
   §2.14 step 1).
2. Load the slot by `{slotId}` (`availabilitySlotRepository.findById`) → **`404
   NOT_FOUND`** if it doesn't exist at all.
3. Resolve caller's `professionals.id` via `ProfessionalRepository.findByUserId(caller.id)`
   (same existing mechanism as §2.10 step 3 / §3.5) → **`403 FORBIDDEN`** if the caller has
   no professional profile (same invariant-violation-shouldn't-happen-but-guarded case as
   `AvailabilityService.resolveProfessionalId`'s existing behavior).
4. `slot.professionalId != <resolved professional id>` → **`403 FORBIDDEN`** — **not
   `404`**. See the dedicated "403 vs. 404" note below for why this endpoint follows the
   `issues`/`notifications`/`bookings` "distinct codes" convention rather than
   `storage.service.StorageService.retrieve`'s "collapse both to 403" convention.
5. Validate the new `startTime`/`endTime` per the table above → `400 VALIDATION_ERROR`.
6. **Atomically apply the edit, guarded on the slot still being unprotected**:
   `UPDATE availability_slots SET start_time = :startTime, end_time = :endTime,
   updated_at = now() WHERE id = :slotId AND professional_id = :professionalId AND
   is_available = true`. If the affected-row count is `0` → **`409 SLOT_IN_USE`** (by this
   point, existence and ownership are already proven by steps 2-4, so a `0`-row result here
   can only mean the slot's `is_available` flipped to `false` between step 2's read and this
   write — a concurrent order claimed it, or it was already protected — a genuine, if
   narrow, race; §3.2's usual "no explicit locking" convention applies here too). Roll back,
   return immediately.
7. Return `200` with the updated slot.

**The booking-protection rule, stated precisely (extends §3.4).** `is_available = false`
is, and remains, the exact, reliable signal for "this slot is either currently held by an
active order (`PENDING`/`CONFIRMED`/`ON_THE_WAY`) or was consumed by a `COMPLETED` order" —
confirmed directly against `BookingsService`: `claimSlot` (§2.4 step 9) sets it `false` at
order creation; `releaseSlot` (called from `reject`/`cancel`/`expireIfPending`, §2.6 step
5/§2.7 step 6/§4.5 of the notifications doc) sets it back to `true`; **`accept` and
`complete` never call `releaseSlot` at all** (confirmed by reading both methods directly —
`accept` only transitions `orders.order_status`, `complete` only transitions
`orders.order_status` and `issues.status`, neither touches `availability_slots`). A slot
with `is_available = true` therefore structurally has no order currently depending on it —
any order that once referenced it already released it back to `true` on
`reject`/`cancel`/`expiry`, or never existed. Step 6's guard is exactly this signal,
applied to edit instead of claim — a professional can never silently invalidate a
`CONFIRMED`/`ON_THE_WAY` booking or retroactively alter the record of a `COMPLETED` job by
editing the slot underneath it; any such attempt is rejected outright with `409
SLOT_IN_USE`, never a silent no-op and never a cascade-cancel of the order.

**Why `SLOT_IN_USE` is a new code, not a reuse of `SLOT_UNAVAILABLE` — decided, not a close
call.** `SLOT_UNAVAILABLE` (§2, Milestone 3 table) is documented and used exclusively for
the *customer-side* booking-claim failure at `POST /api/bookings/orders` (§2.4 step 9) —
"someone/something else already has first claim on this slot, from the perspective of a
customer trying to book it." Reusing it here would conflate two different call sites and
two different audiences reading the same `error.code` off the wire (a customer's booking
attempt vs. a professional's own calendar edit) behind one ambiguous name, and would
contradict this doc's own established precedent of minting a new, narrowly-named code when
an existing one's *documented* meaning doesn't fit rather than stretching it (`ORDER_NOT_
CONFIRMED`/`ORDER_NOT_ON_THE_WAY`, Milestone 6, were minted for exactly this reason instead
of stretching `ORDER_NOT_PENDING` — §2's Milestone 6 taxonomy note). `SLOT_IN_USE` names
the professional-side concept precisely: "you can't edit/delete a slot that's in use (held
by an active order) or was used (consumed by a completed order)" — distinct in both
audience and meaning from `SLOT_UNAVAILABLE`'s "someone else's turn already claimed it."
The two codes are never returned by the same endpoint, so there is no ambiguity for a
frontend branching on `error.code` either.

**Response `200`:** same shape as §2.10's create response.
```json
{
  "id": 77,
  "professionalId": 43,
  "startTime": "2026-08-20T09:00:00Z",
  "endTime": "2026-08-20T11:00:00Z",
  "isAvailable": true,
  "createdAt": "2026-08-13T12:00:00Z"
}
```

**Status codes**: `200` success · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` ·
`403 FORBIDDEN` · `404 NOT_FOUND` · `409 SLOT_IN_USE`.

**No overlap/double-booking validation against the professional's own other slots** —
same explicit non-scope as §2.10's create endpoint, unchanged by this pass; editing a slot
to overlap another of the professional's own slots is not blocked.

---

### 2.19 `DELETE /api/availability/slots/{slotId}` — new, Milestone 7, `availability` package

Same reversal-of-§8.2 basis as §2.18 — see that section's opening note.

Auth required: **yes**. Role: **PROFESSIONAL**. Same "no `AvailabilityWebConfig` change
needed" reasoning as §2.18 (existing blanket-wildcard registration already covers it).

**Why `DELETE`, no body.** Unambiguous, standard REST verb for "remove this resource
outright" — no existing project precedent to weigh against (no endpoint in this codebase
has previously needed `DELETE`), so ordinary REST convention applies uncontested.

**Behavior** (same authorization-first ordering as §2.18, no body to validate):
1. Resolve caller (role already gated, no in-method check — same as §2.18 step 1).
2. Load the slot by `{slotId}` → **`404 NOT_FOUND`** if it doesn't exist at all.
3. Resolve caller's `professionals.id` → **`403 FORBIDDEN`** if no professional profile
   (same as §2.18 step 3).
4. `slot.professionalId != <resolved professional id>` → **`403 FORBIDDEN`** (same "403,
   not 404" reasoning as §2.18 step 4 — see the note below).
5. **Atomically delete, guarded on the slot still being unprotected**:
   `DELETE FROM availability_slots WHERE id = :slotId AND professional_id =
   :professionalId AND is_available = true`. If the affected-row count is `0` → **`409
   SLOT_IN_USE`** (existence/ownership already proven by steps 2-4; a `0`-row result here
   means the slot was claimed by a concurrent order between step 2's read and this
   statement — same race as §2.18 step 6, same reasoning). Roll back, return immediately.
6. Return `204 No Content` (no body — nothing left to return; standard REST convention for
   a successful delete, and this project has no prior `DELETE` endpoint whose response
   shape this would need to stay consistent with).

**Booking-protection rule**: identical to §2.18's — `is_available = true` is required for
the delete to succeed, for the exact same reason (§3.4, extended by §2.18's note above). A
slot currently backing a `PENDING`/`CONFIRMED`/`ON_THE_WAY` order, or one that was consumed
by a `COMPLETED` order, can never be deleted — the attempt is rejected outright with `409
SLOT_IN_USE`, never a silent no-op and never a cascade-cancel of the order it backs.

**FK-safety note, confirmed not merely assumed.** `orders.slot_id` is `ON DELETE SET NULL`
(`V12`, §1.2) — if this endpoint ever deleted a slot an order still referenced, that
order's `slot_id` would silently go `NULL`, an unrelated pre-existing FK behavior. Because
step 5's guard only ever succeeds when `is_available = true`, and (per §2.18's `is_available`
reasoning) `is_available = true` structurally means no order currently depends on this row,
this FK's `ON DELETE SET NULL` behavior is **never actually triggered by a live-relevant
order** through this endpoint — it would only fire in the already-established safe case
(a `REJECTED`/`CANCELLED`/`EXPIRED` order that already had its `slot_id` released, i.e. this
same row already went back to `is_available = true` once, meaning that old order's `slot_id`
FK pointing at this now-deleted row was already historically irrelevant — the order's own
status already tells the full story without needing `slot_id` to resolve, exactly as
`OrderDetailResponse`/`OrderSummaryResponse`, §2.4/§2.8/§2.9, already never expose
`slot_id` in any response body).

**Ownership-error status code — 403, not 404, and why (applies to both §2.18 and §2.19).**
This doc's precedent for "resource exists but belongs to someone else" is **not** uniform
across the codebase — two conventions exist:
- `storage.service.StorageService.retrieve` collapses *both* "doesn't exist" and "exists,
  not yours" to `403 FORBIDDEN`, deliberately never `404`, specifically to defend against
  **key enumeration** of opaque, guessable-looking S3 object keys.
- `issues.service.IssuesService` (`GET /api/issues/{id}`, §2.1), `notifications
  .service.NotificationServiceImpl`, and `bookings.service.BookingsService` (`loadOrder` +
  the ownership checks in `accept`/`reject`/`cancel`/`GET .../{orderId}`, §2.5-§2.8) all use
  **distinct** codes instead: `404 NOT_FOUND` if the sequential-integer id doesn't resolve
  at all, `403 FORBIDDEN` if it resolves but the caller isn't a party to it.

**`availability_slots.id` follows the second convention (distinct 404/403), matching the
majority pattern (`issues`/`notifications`/`bookings`), not the `storage` special case.**
Reasoning: `storage`'s anti-enumeration collapse exists because S3 object keys are the kind
of opaque, randomly-generated string an attacker might probe to discover other users'
private image files — the concern is specifically about an attacker *fishing for valid
keys*. `availability_slots.id` is an ordinary sequential-integer JPA-generated primary key,
structurally identical in kind to `orders.id`/`notifications.id`/`issues.id` — all of which
already use the distinct-404/403 convention in this same codebase precisely because
sequential integer PKs aren't a meaningful enumeration attack surface (an attacker can
already trivially guess adjacent ids; the interesting question is only ever "is this one
mine," which `403` already answers without also needing to hide "does it exist"). Following
`storage`'s collapse here would be inconsistent with the majority, sequential-integer-PK
convention this codebase has already established three times over, for a resource that
shares none of `storage`'s enumeration-sensitive properties.

---

## 4. End-to-end flow summary (PRD §3.4, including the reject → return-to-list branch)

For clarity, the full Standard-path sequence this contract implements:

1. Customer has a confirmed `issues` row (Milestone 2, `status = 'OPEN'`).
2. `GET /api/bookings/professionals?issueId=` (§2.2) → professional cards with price
   offers.
3. Customer picks one → `GET /api/bookings/professionals/{id}/slots?issueId=` (§2.3) →
   available time windows.
4. Customer picks a slot → `POST /api/bookings/orders` (§2.4) → `orders` row created
   (`PENDING`), slot claimed, `issues.status → 'BOOKED'`.
5. Professional sees it via `GET /api/bookings/orders/me?status=PENDING` (§2.9) →
   `accept` (§2.5) or `reject` (§2.6).
   - **Accept**: `order_status → 'CONFIRMED'`. Customer's polling `GET
     /api/bookings/orders/{orderId}` (§2.8) observes the change — this is the
     confirmation flow.
   - **Reject**: `order_status → 'REJECTED'`, slot released, `issues.status → 'OPEN'`.
     Customer's client (polling either §2.8 on the known `orderId`, or re-`GET
     /api/issues/{id}` per §2.1's `latestOrder`/`status` fields) observes
     `issue.status == 'OPEN'` again and returns to step 2 — **the customer may browse the
     (now again-`OPEN`) issue's professional list and pick a different professional, or the
     same one again**, creating a second `orders` row against the same issue (§2.4's
     closing note).
6. Once `CONFIRMED`, either party may still `cancel` (§2.7) → `CANCELLED`, slot released,
   `issues.status → 'OPEN'` (same return-to-list branch as a rejection, different terminal
   status) — remains true at `ON_THE_WAY` too, unchanged by step 7 below.
7. **New, Milestone 6.** From `CONFIRMED`, the professional advances the job via `POST
   .../orders/{orderId}/on-the-way` (§2.16) → `order_status → 'ON_THE_WAY'`, `issues.status`
   untouched (stays `BOOKED`). Then, from `ON_THE_WAY` (a mandatory intermediate step — see
   §2.17's "Decided" note / §6 item 9 — `CONFIRMED → COMPLETED` directly is not permitted),
   `POST .../orders/{orderId}/complete` (§2.17) → `order_status → 'COMPLETED'`,
   `issues.status → 'COMPLETED'`. Both steps notify the customer (`ORDER_ON_THE_WAY`/
   `ORDER_COMPLETED`, §2.16/§2.17 step 6). Either party may still `cancel` from `ON_THE_WAY`
   (unchanged, §2.17's closing note).

### SOS-path sequence (PRD §3.5, new this pass — including the reject/becomes-unavailable
→ return-to-list branch, PRD §3.5.6)

1. Customer has a confirmed `issues` row with `urgencyType = 'SOS'` (Milestone 2,
   `status = 'OPEN'`).
2. `GET /api/bookings/sos-professionals?issueId=` (§2.12) → professional cards for
   currently-SOS-available professionals in the issue's category. **May be empty** — the
   frontend renders PRD §3.5.6's "no-available-professional message" directly from an empty
   array; this is not a distinct backend code path or error.
3. Customer picks one → `POST /api/bookings/sos-orders` (§2.13) → **two outcomes**:
   - **Success (`201`)**: `orders` row created (`PENDING`, `bookedStart = now()`,
     `bookedEnd = NULL`, `slotId = NULL`), `issues.status → 'BOOKED'`. Proceeds to step 4.
   - **`409 SOS_PROFESSIONAL_UNAVAILABLE`**: the professional's `sos_availability` flipped
     to unavailable between the customer loading the (possibly slightly stale) list in step
     2 and submitting the request in step 3 — **this is the backend implementation of PRD
     §3.5.6's "becomes unavailable" trigger** (§3.11 below has the full reasoning for this
     mapping). No `orders` row is created; `issues.status` is untouched (still `OPEN`). The
     frontend's response to this error is to re-fetch §2.12 (a fresh list, which will no
     longer include the now-unavailable professional) and return the customer to the SOS
     professional list — the same observable "bounced back to the list" outcome PRD §3.5.6
     describes for a rejection, just triggered one step earlier, before any request ever
     reached the professional.
4. Professional sees the `PENDING` order via `GET /api/bookings/orders/me?status=PENDING`
   (§2.9, unchanged, urgency-agnostic) → `accept` (§2.5) or `reject` (§2.6), **both reused
   verbatim, zero SOS-specific code** (§3.7, confirmed against the real `OrderRepository`
   methods in §0.1 above).
   - **Accept**: `order_status → 'CONFIRMED'`. Customer's polling `GET
     /api/bookings/orders/{orderId}` (§2.8) observes the change — same confirmation flow as
     Standard. ETA/tracking display is explicitly PRD §3.5.5's "(future version)" — not
     built, consistent with the hard GPS/live-tracking exclusion (`overview.md` §2).
   - **Reject** — **this is PRD §3.5.6's "rejects the request" trigger, the other half of
     the reject/becomes-unavailable pair**: `order_status → 'REJECTED'` (`slotId` is
     already `NULL` for an SOS order, so the slot-release step, §3.4, is a no-op exactly as
     §3.7 predicted — verified, not just asserted, against `AvailabilitySlotRepository
     .releaseSlot`'s unconditional-on-`slotId` `UPDATE`), `issues.status → 'OPEN'`. Same
     observable outcome as the `SOS_PROFESSIONAL_UNAVAILABLE` branch in step 3 — the
     customer's client sees `issue.status == 'OPEN'` again (via polling §2.8 or re-`GET
     /api/issues/{id}`, §2.1) and returns to step 2, free to pick a different
     currently-available professional (or, if still listed and still available, the same
     one again — no dedup, matching §2.2/§7's existing "not filtered" call for Standard).
5. Once `CONFIRMED`, either party may still `cancel` (§2.7, unchanged) → `CANCELLED`,
   `issues.status → 'OPEN'` (same return-to-list branch, different terminal status, same as
   Standard step 6).
6. **New, Milestone 6, reused verbatim from Standard, zero SOS-specific code** (same
   pattern §3.7 already established for `accept`/`reject`/`cancel`/tracking/self-listing):
   `POST .../on-the-way` (§2.16) then `POST .../complete` (§2.17) work identically for SOS
   orders — neither method branches on `urgency_type`, and `issueRepository
   .completeIfBooked`'s guard is the same `BOOKED → COMPLETED` transition regardless of how
   the issue got `BOOKED`. The `PENDING`-timeout `EXPIRED` sweep (Milestone 5, already built
   — per `data-model.md` §3 item 8's **5-minute** SOS timeout vs. Standard's 15 minutes) is
   unaffected by this pass; it only ever acts on `PENDING` orders, never on `CONFIRMED`/
   `ON_THE_WAY` ones.

**What's deliberately *not* a third trigger here**: a `PENDING` SOS order whose
professional toggles `sos_availability.is_available` to `false` *after* the order was
already created (i.e., after step 3 succeeded, while the order sits `PENDING` awaiting that
professional's accept/reject) does **not** auto-expire, auto-cancel, or otherwise change
state as a side effect of the toggle. §2.14 (the toggle write) touches only
`sos_availability`, never `orders`. See §3.11 for why this reading — rather than building a
reactive "toggle off cancels my pending SOS requests" mechanism — was chosen.

---

## 5. Cross-cutting mechanisms

### 3.1 Role gating — see §0.1 above (not repeated).

### 3.2 Atomic state transitions — one consistent mechanism, not ad hoc per endpoint

Every state change in this doc (slot claim, issue `OPEN → BOOKED`, every `orders.order_status`
transition) uses the same pattern: a single `UPDATE ... WHERE <current-state-guard>`
statement, checking the affected-row count, inside one `@Transactional` service method.
This is Postgres's plain read-committed-safe "conditional update" idiom — no explicit
`SELECT ... FOR UPDATE` locking is needed, and no optimistic-locking `@Version` column is
introduced (would be a schema change; the `WHERE`-clause guard achieves the same
correctness without one). Applied uniformly so `pronto-coding` doesn't have to invent this
per-endpoint or get it subtly wrong on one of the seven (§2.4/§2.5/§2.6/§2.7's four
transition points, each internally two conditional updates in the create-order case).

### 3.3 Single-active-order-per-issue invariant

`POST /api/bookings/orders` (§2.4 step 5/9) only ever succeeds against an issue whose
`status == 'OPEN'`, and `issues.status` only ever becomes `'BOOKED'` as part of that same
transaction. Consequently: **at most one order in `PENDING`/`CONFIRMED`/`ON_THE_WAY` can
exist for a given issue at any time.** This is what makes the unconditional `issues.status
→ 'OPEN'` in `reject`/`cancel` (§2.6/§2.7, no "are there other active orders?" check) safe —
there structurally cannot be another one. If a future milestone ever relaxes this
invariant (e.g. "request multiple professionals for the same issue at once" — not
requested by any source document, not designed here), that revert-to-`OPEN` logic would
need to change too; flagging the dependency so it isn't silently broken later.

### 3.4 Slot lifecycle: claim on create, release on reject/cancel, kept on accept/complete

> **Historical as of the professional weekly availability calendar design's M2 (2026-08-18)**:
> §2.4 (`POST /api/bookings/orders`) no longer claims an `availability_slots` row at all —
> every Standard order created from M2 onward persists `slot_id = NULL` and is instead
> protected by the `ck_orders_no_overlap` exclusion constraint (see the rewritten §2.4 above).
> This whole section remains accurate for **pre-M2 orders** (their `slot_id` still points at a
> real, now-permanently-claimed row) and for the **release** mechanism below, which is
> unchanged and still exercised by `reject`/`cancel`/the expiry sweep as a safe no-op for
> every order created after M2 (the same already-proven-safe pattern SOS orders established in
> Milestone 4) — not deleted or rewritten, since the release mechanism itself didn't change,
> only what supplies `slot_id` going forward.

- **Claimed** (`is_available: true → false`) at order-creation time — **through Milestone 8,
  before M2 retired this step; see the note directly above.** §2.4 step 8 originally read as
  follows, kept verbatim as the historical record. **Not**
  deferred until the professional accepts. Reasoning: two different customers must not be
  able to both hold a `PENDING` request against the same slot simultaneously — claiming
  early (at request time, not acceptance time) is what a normal "book a specific time
  window" UX implies, and matches how the slot stops appearing in §2.3's listing for anyone
  else immediately after this customer picks it.
- **Released** (`is_available: false → true`) on `reject` (§2.6) and `cancel` (§2.7) —
  the customer's chosen time window becomes bookable by someone else again once this
  attempt didn't pan out.
- **Never released** on `accept`/`COMPLETED` — the professional's calendar window is
  legitimately consumed once the job is confirmed and going ahead.
- **Mechanism for finding *which* slot row to release — decided (`pronto-lead`,
  2026-08-13, §6 item 1): the `orders.slot_id` column (`V12`, §1.2) is the sole release
  mechanism, no fallback branch.** `UPDATE availability_slots SET is_available = true,
  updated_at = now() WHERE id = order.slotId`. For a Standard order, `slot_id` is always
  set at creation time (§2.4 step 10), so this is unconditional for Standard orders; for a
  future SOS order (Milestone 4) `slot_id` is `NULL` and this step is a no-op (§3.7 below).
  The previously-considered `(professional_id, start_time, end_time)` timestamp-heuristic
  fallback is **not built** — it's superseded by this decision, not a remaining option.
  `slot_id` is purely an internal lookup aid, never exposed in any JSON body above.
- **Milestone 5's forward dependency, stated here so it isn't rediscovered**: when the
  `EXPIRED` sweep job (`data-model.md` §3 item 8, owned by Milestone 5) flips a `PENDING`
  order to `EXPIRED`, it must release the slot using this **same** mechanism — ideally by
  calling the same domain-service method `reject`/`cancel` call internally, not
  reimplementing the release logic a third time.
- **New, Milestone 7 (§2.18/§2.19)**: `is_available` is also now the sole guard for
  **edit**/**delete** — a professional may only `PUT`/`DELETE` their own slot while
  `is_available = true`. Because this flag is `false` for the entire span from
  order-creation (`claimSlot`, above) through `COMPLETED` (never released, above), and
  `true` for every slot with no order currently depending on it, this single column is a
  complete, reliable booking-protection signal for edit/delete with no additional query
  needed — see §2.18's "booking-protection rule" note for the full derivation.

### 3.5 Professional identity resolution

Every professional-facing endpoint (`accept`, `reject`, the `PROFESSIONAL` branch of
`cancel`/`GET .../{orderId}`/`GET .../me`) resolves the caller's `professionals.id` from
the JWT's `sub` (user id) via `professionals.repository.ProfessionalRepository
.findByUserId(callerId)` — already exists (`backend/.../professionals/repository/
ProfessionalRepository.java`), no new lookup mechanism needed. If that lookup returns
empty (a `CUSTOMER`-role token somehow reaching a professional-gated code path) — should
not be reachable given §0.1's role interceptor, but if it is, treat as `403 FORBIDDEN`,
not a `500`.

### 3.6 `issues.status` transition table (this milestone's contribution)

| Trigger | `issues.status` | Endpoint |
|---|---|---|
| Issue confirmed/created (Milestone 2) | `OPEN` | `POST /api/issues` |
| Order created (`PENDING`) | `OPEN → BOOKED` | §2.4 |
| Order accepted (`CONFIRMED`) | stays `BOOKED` | §2.5 |
| Order rejected (`REJECTED`) | `BOOKED → OPEN` | §2.6 |
| Order cancelled (`CANCELLED`) | `BOOKED → OPEN` | §2.7 |
| Order set on the way (`ON_THE_WAY`) | stays `BOOKED` | **New, Milestone 6**, §2.16 step 5. |
| Order completed (`COMPLETED`) | `BOOKED → COMPLETED` | **New, Milestone 6**, §2.17 step 5 (`issueRepository.completeIfBooked`, guarded on `BOOKED`, mirrors `expireIfBooked`'s shape). This row was previously "not built"; the mapping itself was already decided (unchanged) — it is now wired to an endpoint. |
| Order expires (`EXPIRED`, sweep) | `BOOKED → EXPIRED` (unless customer already rebooked) | **Milestone 5's job**, already built — per `data-model.md` §3 item 8 and `api-contract-notifications.md` §4.5. Restated here only as a forward-reference; unaffected by this pass (only ever acts on `PENDING` orders). |

### 3.7 Where this design already generalizes to SOS (Milestone 4) vs. where it doesn't

**Written during Milestone 3, as a forward-looking prediction — now confirmed fulfilled by
§2.12–§2.15 above, verified against the real code, not just re-asserted.** Kept in its
original Milestone-3 form below (not rewritten) so the "what we predicted" vs. "what we
built" comparison stays visible; §0.1's Milestone-4 role-gating note and §2.13's own text
independently confirm the same conclusions against the as-built `OrderRepository`/
`AvailabilitySlotRepository`/`Order` code.

Stated explicitly per the task brief's "don't paint yourself into a corner for M4"
instruction:

- **Already generalizes, no rework needed**: `accept`/`reject`/`cancel`/`GET
  .../{orderId}`/`GET .../me` (§2.5–§2.9) are all urgency-agnostic — they operate on an
  `orders` row by id/ownership, with no `urgency_type` branching anywhere in their logic.
  Once Milestone 4 creates SOS orders (via its own creation endpoint), these five endpoints
  work on them identically, unchanged.
- **`orders.slot_id` (§1.2, `V12`) is nullable specifically because of SOS.** SOS orders
  will never consume an `availability_slots` row (SOS uses `sos_availability`, a live
  toggle — `data-model.md` §2.6/§3 item 5) — so `slot_id` tolerates `NULL`, which `V12`
  already allows. `reject`/`cancel`'s slot-release step (§3.4) runs
  `UPDATE availability_slots SET ... WHERE id = order.slotId` unconditionally; when
  `order.slotId IS NULL` (a future SOS order), that `WHERE id = NULL` predicate matches zero
  rows by ordinary SQL `NULL`-comparison semantics, so it's already a safe no-op for SOS
  orders with no extra `IS NOT NULL` branch needed in application code — not a bug waiting
  for Milestone 4 to trip over.
- **Not generalized, M4 needs a net-new creation path**: §2.2–§2.4 (professional
  listing, slot listing, order creation) are Standard-specific by design — SOS's
  "professional listing" is filtered by `sos_availability.is_available = true`, not
  `availability_slots`, and has no slot-selection step at all (an SOS order's
  `booked_start` is presumably "now"/request time, `booked_end` stays `NULL` per
  `data-model.md` §2.9). Milestone 4 will add its own `POST /api/bookings/sos-orders` (or
  similar) rather than reusing §2.4 — not designed here, flagged only so it isn't assumed
  to fall out of this doc for free.

### 3.8 What "confirmation flow" means in this contract

`implementation-plan.md`'s M3 bullet list says "confirmation flow" as a distinct item from
accept/reject. This doc doesn't introduce a separate "confirm" endpoint — **"confirmation"
is the customer-observable *effect* of the professional's `accept` (§2.5) landing**,
visible via polling §2.8/§2.1. No additional server-side step exists between accept and the
order being considered "confirmed" (no double-confirmation, no customer-side ack of the
acceptance) — flagged as an interpretation, not something any source document spells out
as a separate step, but a natural reading of `order_status = 'CONFIRMED'` already being the
confirmation.

### 3.9 Why professional-listing/slots live under `/api/bookings/*`, not `/api/professionals/*`

The `professionals` package (per `overview.md` §4) owns "professional profile, service
area, reliability score" — general profile data, not booking-context-specific matching.
§2.2/§2.3's listing is always issue-scoped (filtered by an issue's category, only
meaningful mid-booking-flow) rather than a general "browse all professionals" directory
feature — placing it under `/api/bookings/*` (implemented in the `bookings` package, reading
from `professionals`/`availability_slots` as needed) keeps the booking journey's endpoints
discoverable together, mirroring the M2 precedent of keeping `/classify` under
`/api/issues/*` even though it's backed by the separate `ai` package. §2.12
(`sos-professionals`) follows the identical placement reasoning — issue-scoped SOS matching
is a `bookings`-package concern for the same reason Standard matching is.

### 3.10 The pre-existing `urgencyType` validation gap — fixed in this pass, not left open
(§6 item 5 has the full decision record; this is the technical summary)

Milestone 3's §2.2/§2.3/§2.4 originally validated issue ownership and bookable status
(`status == 'OPEN'`) but never checked `urgencyType` at all — a customer could call the
Standard-path endpoints against an `urgencyType = 'SOS'` issue and nothing stopped it (and,
symmetrically, nothing in this doc stopped the reverse before §2.12/§2.13 existed, since
those endpoints didn't exist yet to be misused). Now that Milestone 4 introduces a second,
parallel creation path that legitimately *does* need to distinguish the two, leaving the
Standard side's check missing would be an asymmetric, confusing gap — one path validates
`urgencyType`, the other doesn't, for no principled reason. **Fixed directly in §2.2/§2.3/
§2.4 above** (one new behavior step each, one new shared error code,
`ISSUE_URGENCY_MISMATCH`) rather than tracked as an open item — see §6 item 5 for why this
was judged in-scope for this pass rather than deferred.

### 3.11 PRD §3.5.6's two triggers ("rejects" vs. "becomes unavailable") — resolution

PRD §3.5.6: *"If the selected professional rejects the request or becomes unavailable, the
system shall return the customer to the SOS professional list or display a
no-available-professional message."* Two triggers, same observable outcome. This doc maps
them to two different, non-overlapping backend mechanisms — reasoning for each, and for why
a third possible reading (a background sweep reacting to a professional going unavailable
*while* an order they already received sits `PENDING`) was **not** adopted:

- **"Rejects the request"** maps to the existing, unchanged `POST
  /api/bookings/orders/{orderId}/reject` (§2.6) — this can only be true once an `orders` row
  already exists in `PENDING`, i.e. after §2.13 already succeeded and the professional has
  since acted on it. No design work was needed here beyond confirming §2.6 truly has zero
  SOS-specific branching (confirmed, §0.1).
- **"Becomes unavailable"** maps to §2.13 step 9's order-creation-time read-check,
  surfaced as `409 SOS_PROFESSIONAL_UNAVAILABLE`. **Reasoning for choosing this over the
  alternative (a reactive mechanism that watches for a professional going unavailable while
  they already have a `PENDING` SOS order against them and does something to that order as
  a result):**
  - PRD §3.5.3–§3.5.4 describes the flow as: customer selects from the list → the *selected*
    professional receives the request. The moment "does this professional still match what
    the customer saw" is naturally checkable is exactly the instant the request is about to
    be sent — i.e. order-creation time — not some later point. Reading "becomes unavailable"
    as "was already unavailable (or became so) by the time the request would be sent" is a
    direct, literal fit for that moment, with no gap requiring a background process.
  - No source document (PRD, `overview.md`, `data-model.md`) describes or implies a
    mechanism by which toggling `sos_availability` should reach into and mutate any
    already-created `orders` row. Building one now would mean inventing a new
    cross-package trigger (`availability` → `bookings`) and a new sweep/event mechanism
    that doesn't exist anywhere else in this codebase's design — exactly the kind of
    speculative, not-requested infrastructure the task brief says not to build ("no
    speculative microservices, no premature abstractions, no infrastructure the current
    scope doesn't need").
  - The two triggers already map cleanly onto the two moments PRD §3.5 actually describes
    (before the request is sent, §3.5.2–§3.5.4; after it's sent and pending a decision,
    §3.5.4 second half) without needing a third mechanism — a `PENDING` SOS order whose
    professional has since gone unavailable is left exactly where a `PENDING` Standard order
    past its `booked_start` is already left today: unresolved until the professional acts
    (`accept`/`reject`) or the future Milestone 5 `EXPIRED` sweep reaches it. This is a
    **consistent**, not a special-cased, gap — the same "no proactive cleanup, only the
    timeout sweep eventually" posture `data-model.md` §3 item 8 already established for
    every other `PENDING`-order staleness case.
  - **Accepted consequence, stated plainly**: between an SOS order being created and the
    professional acting on it, if that professional flips their toggle off, the customer's
    UI has no faster signal than normal `PENDING`-order polling (§2.8) — the order simply
    stays `PENDING` until the professional explicitly `reject`s it (or a future expiry sweep
    reaches it). This is judged an acceptable MVP gap, not a silently swallowed one — stated
    here explicitly, consistent with the task brief's "flag it instead of silently deciding"
    instruction, even though the overall design choice itself is being made with
    confidence, not left as an open question.

---

## 6. Decisions — resolution record (`pronto-lead`, 2026-08-13; items 5–8 added same day,
Milestone 4 pass)

The original Milestone 3 draft raised four items for explicit sign-off before
`pronto-coding` could start. All four have now been resolved. Kept as a numbered record for
traceability (why the doc reads the way it does above), **not** a pending-sign-off list any
more — items 1, 2, and 4 are fully closed; item 3 is confirmed to remain a genuinely open
question, but one that's explicitly **out of Milestone 3's scope**, not a blocker for
`pronto-coding` to start building everything else in this doc. Items 5–8 below are new,
added as part of this same pass while designing Milestone 4 — each is a call this doc makes
explicitly rather than leaving ambiguous, per the task brief's instruction not to silently
pick an interpretation.

1. **`orders.slot_id` — APPROVED.** Built as `V12__add_slot_id_to_orders.sql`, exactly as
   originally drafted (nullable, FK → `availability_slots(id)` `ON DELETE SET NULL`,
   indexed) — see §1.2 for the final migration text and §5.4 for how `reject`/`cancel` use
   it as the sole, decided slot-release mechanism (the timestamp-heuristic alternative
   described in the original draft is superseded, not built).

2. **Minimal `availability` slice pulled into Milestone 3 — APPROVED, option (a).** A
   minimal `AvailabilitySlot` JPA entity + repository (read-focused, needed by `bookings`
   regardless) plus exactly two endpoints: `POST /api/availability/slots` (a professional
   creates one slot) and `GET /api/availability/slots/me` (list their own slots). The full
   contract-level spec for these two endpoints (role gating, field validation, behavior
   steps, response shape, status codes — matching the rigor of every other endpoint in this
   doc) is now written out in full in §2.10/§2.11 below, resolving the gap the original
   draft flagged (it previously only described this slice at a high level). Explicitly
   **not** the Milestone 6 dashboard — no edit/delete/toggle actions, no UI; full CRUD and
   the dashboard UI remain Milestone 6's job, per `availability/README.md`'s updated status
   line.

3. **Professional viewing issue *images* before deciding accept/reject — STAYS OPEN,
   confirmed out of Milestone 3's scope.** Not resolved by this pass, and deliberately not
   designed or built in Milestone 3 — `pronto-lead`'s explicit instruction. §2.1
   (`GET /api/issues/{id}`) still resolves the *issue detail* authorization gap (a
   professional can see an issue's category/description once they have any order against
   it), but the **image bytes** endpoint (`GET /api/storage/images/{key}`, M2) remains
   hard-restricted to `role = CUSTOMER` — a professional still has no way to view an
   issue's photos at all, in any milestone, as of this doc. Not blocking Milestone 3's core
   acceptance criteria (the PRD's Standard flow doesn't require photo access before
   accept/reject, only description/category, which §2.1 already exposes). **Kept in active
   open-question tracking, not dropped** — see also §7's open-items list, which now
   restates it so it isn't only findable inside this resolution record.

4. **`ON_THE_WAY`/`COMPLETED` progression endpoints deferred to Milestone 6 — CONFIRMED as
   the correct reading.** `pronto-lead` has confirmed this doc's interpretation: Milestone 3
   covers the request/accept/reject/cancel/track surface through `CONFIRMED`, plus the
   terminal `CANCELLED`/`REJECTED` statuses; forward progression of an already-confirmed job
   to `ON_THE_WAY`/`COMPLETED` stays Milestone 6's job, matching
   `implementation-plan.md`'s existing milestone bullet split. No design change to this
   doc was needed — the interpretation was already correct, just unconfirmed until now.

5. **The pre-existing `urgencyType` validation gap in §2.2/§2.3/§2.4 (Standard-path
   listing/slot-listing/order-creation) — DECIDED: fixed in this same pass, not tracked as
   an open item.** Those three Milestone 3 endpoints never validated `issue.urgencyType`
   at all, meaning a customer could technically call the Standard-path endpoints against an
   `urgencyType = 'SOS'` issue and nothing stopped it. Both resolutions were defensible
   (fix now vs. leave open, per the task brief) — **fix now was chosen**, for the same
   reason `V11`/`V13` fixed pre-existing gaps ahead of/during their own milestones rather
   than deferring them indefinitely: the fix is small (one additional behavior step + one
   shared error code per endpoint, no migration, no new table/column), and Milestone 4
   introduces a second creation path where the *absence* of the equivalent check would be a
   glaring, easily-noticed inconsistency (SOS validates urgency type from day one, §2.12/
   §2.13; Standard not validating it at all, right next to it in the same file, would read
   as an oversight rather than a decision). See §3.10 for the technical summary and the
   actual edits in §2.2/§2.3/§2.4 above (new `409 ISSUE_URGENCY_MISMATCH` error code, §2's
   taxonomy table). **Not** treated as a breaking change to already-QA-signed-off
   Milestone 3 behavior in any way that matters — no legitimate Milestone 3 caller was ever
   relying on being able to Standard-book an SOS-flagged issue (nothing in the PRD's
   Standard flow, §3.4, ever describes that as intended behavior); this closes an
   unintentional gap, not a designed capability.

6. **PRD §3.5.6's "becomes unavailable" trigger — DECIDED: maps to the order-creation-time
   read-check in §2.13 step 9 (`409 SOS_PROFESSIONAL_UNAVAILABLE`), not a reactive
   sweep/cancellation mechanism watching already-`PENDING` SOS orders.** Full reasoning in
   §3.11 — evaluated against PRD §3.5.2–§3.5.6's actual described sequence, the existing
   `sos_availability` design (`data-model.md` §2.6/§3 item 5), and the task brief's
   don't-over-engineer instruction, not adopted as a rubber stamp of the brief's own working
   hypothesis. Accepted consequence stated explicitly in §3.11's last bullet: an SOS order
   that's already `PENDING` when its professional goes unavailable has no faster
   customer-facing signal than ordinary polling until the professional acts or a future
   expiry sweep reaches it — consistent with, not a special case of, how every other
   `PENDING`-order staleness scenario is already handled in this doc.

7. **Migration — DECIDED: no new Flyway migration for Milestone 4.** See §1.4 for the full
   verification against the applied migration history (`sos_availability` already exists
   via `V13`; `orders.slot_id`/`booked_end` already nullable via `V12`/`V8`;
   `order_status`'s `CHECK` already includes `REJECTED` via `V11`). §2.12–§2.15 read/write
   only columns that already exist and already tolerate the values Milestone 4 needs.

8. **Role-gating config — DECIDED, verified against the real config classes, not
   assumed.** `bookings.config.BookingsWebConfig`'s existing `CUSTOMER`-scoped
   `RoleRequiredInterceptor` registration needs two new literal path patterns added
   (`/api/bookings/sos-professionals`, `/api/bookings/sos-orders`) — its literal-list
   design (chosen in Milestone 3 specifically because this package mixes roles per-route,
   §0.1) doesn't pick up new routes automatically the way a wildcard would.
   `availability.config.AvailabilityWebConfig`, by contrast, needs **no change** — its
   existing single `PROFESSIONAL`-scoped registration already uses the blanket wildcard
   `/api/availability/**`, which already covers §2.14/§2.15's new routes for free. Full
   detail, including the exact diff, in §0.1 above.

**Milestone 6 pass (this doc, 2026-08-13, `pronto-planning`) — items 9–13, each a call made
explicitly rather than left ambiguous:**

9. **`ON_THE_WAY` is a mandatory intermediate step between `CONFIRMED` and `COMPLETED` —
   DECIDED, not left as an open question.** A professional cannot call `POST
   .../orders/{orderId}/complete` (§2.17) directly from `CONFIRMED`; it only succeeds from
   `ON_THE_WAY`, exactly mirroring `POST .../on-the-way`'s (§2.16) own single-hop guard from
   `CONFIRMED` only. Reasoning (full version in §2.17's own "Decided" note, repeated here for
   the resolution record):
   - PRD §3.6.1 names `Pending, Confirmed, On the Way, Completed, Cancelled, Expired` as an
     explicit, ordered sequence — no source document describes or implies a "skip a named
     status" path for any transition anywhere in the system.
   - Every guarded transition already built in this doc is a strict single-hop guard against
     exactly one expected prior status (`accept`/`reject` from `PENDING` only; `cancel`'s
     actor/state matrix, §2.7 step 4, is multiple *actor*-scoped single-hop guards, not a
     multi-hop "skip a state" allowance for any one actor). A `CONFIRMED`-**or**-`ON_THE_WAY`
     guard on `complete` would be the first "skip-ahead" transition in the whole contract —
     a new pattern, not requested by any source document, and exactly the kind of
     unrequested flexibility the task brief's "don't silently pick an interpretation"/"don't
     over-engineer" instructions caution against inventing.
   - `ORDER_ON_THE_WAY`'s notification (§2.16 step 6) is this platform's concrete
     implementation of the "real-time status updates" value proposition
     (`overview.md` §3.3) — specifically the "professional is en route" signal. Allowing
     `CONFIRMED → COMPLETED` to skip it would mean that signal fires for some jobs and
     silently never fires for others depending on which endpoint the professional happened
     to call, with no way for the customer (or `pronto-lead` reading a future bug report) to
     tell which behavior to expect from the API contract alone. A mandatory intermediate
     step keeps the observable behavior deterministic and matches the PRD's named sequence
     literally.
   - **Accepted consequence, stated plainly**: a professional whose job genuinely required
     no travel time (e.g. a customer physically present with them already, a purely
     theoretical v1.0 edge case no source document describes) must still call `on-the-way`
     immediately before `complete` — two API calls in quick succession rather than one. This
     is judged a negligible UX cost (well within the PRD's ~1-2s screen-load targets,
     `overview.md` §3.3, and not a *screen* at all if the frontend later chooses to fire both
     calls from one button) against the benefit of a deterministic, PRD-literal status
     sequence — not a silently accepted gap, an explicit tradeoff.

10. **Notification recipient for both new transitions — DECIDED: the customer, for both
    `ORDER_ON_THE_WAY` and `ORDER_COMPLETED`.** Symmetric with `accept`'s already-established
    reasoning (`api-contract-notifications.md` §4.2): in both cases the professional is the
    actor, and the party who acts never needs telling about their own action — the customer
    is the party who needs the information (their professional is now en route; their job is
    now done, `final_price` may be worth re-checking). No alternative reading was seriously
    considered — `data-model.md`/`overview.md` describe only two parties to an order
    (customer, professional), and the professional-facing side of "job status" is already
    fully visible to them via `GET /api/bookings/orders/me` (§2.9) without needing a push
    notification about their own actions, consistent with how `accept`/`reject`/`cancel`
    already treat the acting party.

11. **`issues.status → 'COMPLETED'` — DECIDED: a new `issueRepository.completeIfBooked`
    method, guarded on `BOOKED`, called without checking its affected-row count — mirrors
    `expireIfBooked`'s exact shape and the exact way `BookingsService.expireIfPending`
    already calls it.** This was the task brief's explicit instruction ("mirroring
    `expireIfBooked`'s shape") and is additionally the only internally-consistent choice
    once the single-active-order-per-issue invariant (§3.3) is taken seriously: by the time
    §2.17 step 4's `order_status = 'ON_THE_WAY'`-guarded `UPDATE` has already succeeded, this
    order is proven to still be the sole active order for its issue, which proves the issue
    is still `BOOKED` at that exact instant — the same reasoning `api-contract-
    notifications.md` §4.5 already uses verbatim to justify `expireIfPending` not checking
    `expireIfBooked`'s result ("§3.3's single-active-order invariant guarantees this always
    affects 1 row when reached"). Extending, not re-deriving, an already-accepted argument —
    not a new risk being introduced. **Alternative considered and rejected**: guarding on
    `BOOKED` *and* branching on a `0`-row result with a `500 INTERNAL_ERROR` (matching
    `SosAvailabilityRepository`'s "row unexpectedly missing" precedent, §2.14 step 5) — not
    chosen because that precedent applies to a *missing row* (a data-integrity bug with no
    theoretical justification for why it can't happen), whereas here the invariant gives an
    actual proof the guard always succeeds when reached, making an unreachable branch pure
    dead-code risk, not a genuine defensive measure.

12. **`cancel` (§2.7) needs no change and remains reachable from `ON_THE_WAY` — CONFIRMED,
    verified against the existing spec, not assumed.** §2.7 step 4's actor/state permission
    matrix already names `ON_THE_WAY` as a valid source status for both `CUSTOMER` and
    `PROFESSIONAL` cancellation — written during Milestone 3, before `ON_THE_WAY` had any
    producing endpoint, and already correct for this milestone with zero edits needed. The
    race between a concurrent `complete`/`cancel` pair targeting the same `ON_THE_WAY` order
    is resolved by the same guarded-`UPDATE`-on-`order_status` mechanism (§3.2) every other
    concurrent-transition race in this doc already relies on — no new locking/coordination
    mechanism introduced.

13. **No new Flyway migration for Milestone 6 — DECIDED, verified against the applied
    migration list (`V1`–`V14`), not assumed.** Full verification in §1.5: `orders
    .order_status`'s `CHECK` has allowed `ON_THE_WAY`/`COMPLETED` since the original `V8`;
    `notifications.message_type`'s `CHECK` (as amended by `V14`) has allowed
    `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` since the original `V9`; `issues.status`'s `CHECK`
    has allowed `COMPLETED` since `V6`. §2.16/§2.17 are the first endpoints to *reach* these
    already-tolerated values, not the first to need the schema to allow them.

**Milestone 7 pass (this doc, 2026-08-15, `pronto-planning`) — item 14, a direct reversal
of a prior decision, not a new judgment call:**

14. **Slot edit/delete — REVERSED from Milestone 6's "not building" call (§8.2), by
    explicit user decision, not a re-evaluation on the merits.** §8.2's original Milestone
    6 reasoning, and the Milestone 7 hardening pass's independent re-review of it
    (`hardening-plan.md` §4.4, both recommending "leave as-is"), are both superseded by the
    user directly overruling them: a professional must be able to fully manage their own
    availability calendar (create, edit, delete). Built as `PUT`/`DELETE
    /api/availability/slots/{slotId}` (§2.18/§2.19) — owner-only, guarded atomically on
    `is_available = true` so an edit/delete can never silently invalidate a confirmed
    booking (`409 SLOT_IN_USE`, new error code, if the slot is currently protected). No new
    Flyway migration required (§1.6) — both endpoints read/write columns that already exist.
    See §8.2's rewritten text for the full record of what changed and why the original
    reasoning's safety properties (never silently break a booking) are preserved, not
    weakened, by adding the capability.

---

## 7. Open items / risks (lower-stakes, don't block `pronto-coding`, but flagging)

- **STILL OPEN, genuinely unresolved (restated from §6 item 3 so it isn't only findable
  there): professional viewing issue images before deciding accept/reject.** No endpoint
  anywhere lets a professional view an issue's photos — `GET /api/storage/images/{key}`
  remains `role = CUSTOMER`-only. Confirmed explicitly out of Milestone 3's scope (not
  designed or built here); not blocking, since the PRD's Standard flow doesn't require
  photo access before accept/reject. Needs a decision (and, per §6 item 3's original
  writeup, a choice of lookup mechanism) before it's built, in a later milestone.
- **Professional-listing ordering (`base_price ASC`, §2.2) and orders-list ordering
  (`created_at DESC`, §2.9) are judgment calls**, not specified by any source document —
  trivial to change later, no migration implied either way.
- **No pagination on any list endpoint** (§2.2, §2.3, §2.9) — acceptable at MVP/QA scale;
  flag as a Milestone 7 hardening candidate if professional/slot/order counts ever grow
  large enough to matter.
- **No dedup/exclusion of a professional who already rejected this issue from the listing**
  (§2.2) — a customer can see and re-request the same professional who just declined them.
  Not requested to be filtered by any source document; judgment call to leave unfiltered.
- **`final_price` is fixed at `professional.basePrice` at order-creation time, with no
  endpoint to adjust it afterward** (e.g. after an on-site inspection) — `data-model.md`
  §2.9 explicitly calls this a possible future workflow ("application logic, not enforced
  here"); not built this milestone, not blocking.
- **`GET /api/bookings/orders/me`'s `status` filter accepts any of the 7 values including
  `EXPIRED`/`COMPLETED`** even though no endpoint in this doc ever produces those — harmless
  (an always-empty filter result until Milestone 5/6 exist), not worth special-casing.

**Milestone 4 additions:**

- **SOS-listing ordering (`base_price ASC`, §2.12) is a judgment call**, made independently
  of §2.2's identical choice (not required to match, per the task brief) — landed on the
  same value because no source document suggests a different SOS-specific signal. Trivial
  to change later, same as §2.2's/§2.9's equivalent notes above.
- **`sos_availability` still has no auto-expiry/timeout** (`data-model.md` §4, restated
  here now that §2.14 actually builds the toggle-write path this would apply to): a
  professional who forgets to flip `is_available` back to `false` after finishing urgent
  work stays listed as SOS-available indefinitely, and nothing in §2.12/§2.13 changes that.
  Not designed here — no source document specifies a timeout behavior, and building one
  would mean another scheduled-sweep mechanism in the category already deferred to
  Milestone 5 (§3 item 8's `EXPIRED` sweep) — flagged, not built.
- **No notification is sent to the professional when an SOS order lands as `PENDING`
  against them, nor to the customer on accept/reject/`SOS_PROFESSIONAL_UNAVAILABLE`** — the
  `notifications` package doesn't exist yet (Milestone 5). Same "discoverable only via
  polling `GET /api/bookings/orders/me`" limitation §2.9 already states for Standard,
  extended to SOS for free since §2.9 is reused unchanged; not a new gap, just restated so
  it isn't assumed SOS gets push-style notice where Standard doesn't.
- **No rate limiting on `PUT /api/availability/sos-availability`** — a professional could
  toggle the flag arbitrarily rapidly with no cost/throttle. Harmless in practice (a plain
  `UPDATE` on an indexed single-row lookup, no external API cost unlike
  `POST /api/issues/classify`'s already-flagged OpenAI-cost concern, `overview.md` §6) —
  not flagged as a real risk, noted only for completeness.

**Milestone 6 additions:**

- **`GET /api/bookings/orders/me`'s `status` filter can now actually return `ON_THE_WAY`/
  `COMPLETED` results** — the "always-empty filter result until Milestone 5/6 exist" caveat
  on the bullet above is now half-resolved (`COMPLETED`/`ON_THE_WAY` are reachable;
  `EXPIRED` already became reachable in Milestone 5). No endpoint change was needed for
  this — §2.9 was already written generically enough to handle every `OrderStatus` value
  without modification, confirming its own original "no `pronto-coding` guessing" design
  intent.
- **No dedicated "job history" or "completed jobs" view beyond the existing `GET
  /api/bookings/orders/me?status=COMPLETED` filter** — not requested by any source document;
  the existing generic self-listing endpoint already covers it, per §8 below's broader
  "existing surface is sufficient" conclusion.
- **The `EXPIRED`-issue-reopen gap is unaffected by this pass, confirmed not silently
  broken or accidentally fixed** — see §9 below for the explicit confirmation.

**Milestone 7 additions:**

- **§2.10's "no overlap/double-booking validation against the professional's own existing
  slots" carries over unchanged to §2.18's edit** — a professional can edit a slot's
  `startTime`/`endTime` to overlap another of their own slots; still not requested by any
  source document, still out of scope, unaffected by the §8.2 reversal (that reversal is
  specifically about edit/delete existing at all, not about adding overlap-conflict
  detection on top of them).
- **No rate limiting specific to `PUT`/`DELETE /api/availability/slots/{slotId}`** — same
  "harmless, indexed single-row operation, no external API cost" reasoning already stated
  for `PUT /api/availability/sos-availability` above; not flagged as a real risk.

---

## 8. Milestone 6 scope verification — availability management & incoming-requests view
(no new endpoint added; a decision, not an oversight)

Per the task brief's instruction to make an explicit call rather than silently duplicate
work already built in Milestone 3/4, this section reviews exactly what exists today against
what `implementation-plan.md`'s Milestone 6 acceptance criteria ("a professional can manage
availability, see incoming requests, and progress a job through its statuses") actually
require, and concludes **no new backend endpoint is needed** for the first two — only the
job-status piece (§2.16/§2.17 above) required new endpoints.

### 8.1 What already exists (verified against the real code and this doc's own earlier
sections, not assumed)

| Need | Existing endpoint(s) | Built in |
|---|---|---|
| Create a bookable Standard advance-booking window | `POST /api/availability/slots` (§2.10) | Milestone 3 |
| View my own slots (past/future/available/claimed) | `GET /api/availability/slots/me` (§2.11) | Milestone 3 |
| Toggle "available for urgent work now" | `PUT /api/availability/sos-availability` (§2.14) | Milestone 4 |
| Read my current SOS-availability state | `GET /api/availability/sos-availability` (§2.15) | Milestone 4 |
| See incoming (pending) requests | `GET /api/bookings/orders/me?status=PENDING` (§2.9) | Milestone 3 |
| See all my orders / any status, e.g. active jobs in progress | `GET /api/bookings/orders/me` (§2.9, optional `status` filter — now covers all 7 values reachably, see §7's Milestone 6 addition above) | Milestone 3 |
| See one job's full detail | `GET /api/bookings/orders/{orderId}` (§2.8) | Milestone 3 |
| Progress a job through its statuses | `accept`/`reject` (§2.5/§2.6, Milestone 3), `cancel` (§2.7, Milestone 3), `on-the-way`/`complete` (§2.16/§2.17, Milestone 6) | Milestone 3 + 6 |
| Edit / delete a not-yet-in-use slot | `PUT`/`DELETE /api/availability/slots/{slotId}` (§2.18/§2.19, **Milestone 7 — see §8.2 below, reversing this table's original Milestone 6 "no new endpoint" conclusion for this one row**) | Milestone 7 |

§2.9's own text (written during Milestone 3) already anticipated this exact conclusion:
*"Milestone 6 is expected to build its dashboard UI on top of this same endpoint, not need
a new one — this isn't stepping on M6's scope, just building the read API home it will
consume."* This section confirms that prediction held for the incoming-requests/job-status
rows. It did **not** hold for slot edit/delete — see §8.2.

### 8.2 REVERSED (Milestone 7, 2026-08-15): slot edit/delete is now built — §8.2's original
Milestone-6 "not added" call has been overruled by explicit user decision

**Status: reversed, not re-litigated.** The Milestone 6 pass below concluded slot
edit/delete was not needed and stated so explicitly, calling out its own reasoning as "a
judgment call, not a certainty." **The user has since directly overruled that call**: a
professional must be able to fully manage their own availability calendar — create
(§2.10, unchanged), edit (§2.18, new), and delete (§2.19, new). This is stated by the user
as a decided product requirement, not a re-opened design question — this section records
*that the reversal happened and why the original reasoning no longer controls*, not a
fresh cost/benefit re-evaluation of whether to build it (that evaluation already happened
below, twice, and was overruled both times: once by the Milestone 6 pass itself
reaffirming the "leave as-is" position — `docs/architecture/hardening-plan.md` §4.4 — and
now finally by the user directly).

**What actually changed, precisely:**
- Two new endpoints exist: §2.18 (`PUT`, edit) and §2.19 (`DELETE`, delete).
- Both are owner-only (professional may only touch their own slots, §2.18/§2.19 step 3-4)
  and both refuse to touch a slot that is currently protecting an active or completed
  order (`is_available = true` required, guarded atomically — `409 SLOT_IN_USE` otherwise),
  so the original reasoning's core safety property — "a professional edit/delete must never
  silently invalidate a confirmed booking" — is preserved exactly, not weakened, by
  granting the capability.
- No PRD text newly mandates this (that fact hasn't changed) — the requirement's source is
  the user's direct product decision, stated in this milestone's task brief, not a
  rediscovered PRD passage. Recorded here plainly so a future reader doesn't go looking for
  a PRD citation that doesn't exist.

**The original Milestone 6 reasoning is preserved verbatim below, unedited, for the
historical record** — it explains why the call was defensible at the time (no load-bearing
functional gap, "manage" satisfied by create+list+toggle, frontend deferred project-wide)
and was always flagged as the one place in that section "reasonable people could land
differently." That reasoning is superseded, not wrong in hindsight — a legitimate MVP
scope call that a later, explicit product decision has now overridden. Historical text
follows unchanged:

> Several earlier docs speculatively flagged full slot CRUD as a Milestone 6 *candidate* —
> `availability/README.md` ("Full CRUD, richer calendar semantics, and any dashboard UI
> remain Milestone 6 scope"), this doc's own §2.10 ("Flagged as a candidate for Milestone 6's
> richer calendar semantics, not built here"). **Decision: not building slot edit/delete this
> milestone.** Reasoning:
>
> - **No PRD text mandates it.** PRD §6's `AvailabilitySlots` schema lists exactly
>   `id, professional_id, start_time, end_time, is_available` with no described edit/cancel
>   workflow; no wireframe section (§7.x) describes a slot-editing screen either. The
>   "candidate" language in the docs above was exactly that — a speculative placeholder
>   flagged for later reconsideration, not a confirmed requirement, and the task brief
>   explicitly asks this doc to make the call rather than let that speculation silently become
>   scope.
> - **No load-bearing gap exists without it.** A professional who creates a slot with the
>   wrong start/end time has no way today to correct or remove it before it's booked — but
>   this has no negative *functional* consequence: an unwanted, still-`is_available = true`
>   slot simply might get booked by a customer (at which point the normal `reject`/`cancel`
>   flow, §2.6/§2.7, releases it back to `is_available = true` and reopens the issue — the
>   professional isn't stuck fulfilling a mistaken slot), and a slot nobody ever books simply
>   ages into the past with no cleanup needed (`GET .../slots/me`, §2.11, already returns past
>   slots unfiltered, so it remains visible/auditable, not silently lost). There is no
>   scenario in which lacking slot edit/delete leaves a professional unable to run their
>   business or a customer unable to book — the two properties an MVP dashboard actually needs
>   to guarantee.
> - **"Manage availability" (the acceptance-criterion wording) is satisfied by create + list +
>   the SOS toggle.** A professional can already: publish new availability (§2.10), see what
>   they've published (§2.11), and flip a live "available now" signal on/off (§2.14/§2.15).
>   Read this literally against the acceptance criterion's own wording — "manage" is satisfied
>   by the ability to add and view availability; it does not, on its own, imply mutate/delete
>   of a specific already-published entry.
> - **Frontend is out of scope project-wide this milestone anyway** (deferred pending the
>   design-system decision, per this pass's task brief) — even if a future dashboard UI wanted
>   an "edit slot" affordance, that's a frontend-scope question to raise when UI work actually
>   starts, not a reason to speculatively build a backend endpoint with no current caller.
>
> **This is a judgment call, not a certainty** — flagged explicitly, per the task brief's
> "don't silently pick an interpretation" instruction, as the one place in this section where
> reasonable people could land differently (e.g. if a future UX review decides a professional
> genuinely needs to delete a stale slot for peace of mind, not correctness). If that need is
> confirmed later, the addition would be a small, independent slice (`DELETE
> /api/availability/slots/{slotId}`, professional-owner-only, guarded on `is_available = true`
> so a claimed/booked slot can't be silently deleted out from under an active order) — not
> designed here, since it is not currently requested by any source document.

**That last paragraph's predicted shape (`DELETE`, owner-only, guarded on
`is_available = true`) is exactly what §2.19 builds** — the original judgment call's own
fallback plan turned out to be the correct design once the capability was actually
requested, requiring no rework beyond also adding the edit (`PUT`) counterpart the original
note didn't separately anticipate.

### 8.3 SOS availability and incoming-requests need no further work

`sos_availability` (§2.14/§2.15) is inherently a single row per professional with a live
toggle — there is no "CRUD" concept that applies to it beyond read/write, both of which
already exist. **Confirmed explicitly for this pass, not silently skipped**: unlike
`availability_slots`, "edit" and "delete" don't map onto `sos_availability` at all.
`PUT /api/availability/sos-availability` (§2.14) already **is** the edit operation — a
full-value replace of the row's one mutable field (`is_available`), the exact same verb
and shape this doc would otherwise reach for. "Delete" has no coherent meaning here: every
professional has exactly one `sos_availability` row, created at registration
(`data-model.md` §2.6) and relied upon as an invariant by both `AvailabilityService`
(§2.14 step 5/§2.15 step 3 — a missing row is treated as a data-integrity bug, `500
INTERNAL_ERROR`) and `BookingsService` (§2.13 step 9's read-check). Deleting it would
create exactly the missing-row condition those two call sites already treat as a bug, not
a valid state — so no `DELETE` endpoint is added for `sos_availability`, a deliberate
judgment call considered and rejected, not an oversight.

`GET /api/bookings/orders/me?status=PENDING` (§2.9) is exactly the "incoming-requests view"
backend need — already built, already generic, already exercises correctly for both
Standard and SOS orders (§3.7). Needs nothing new.

**Conclusion for deliverable 2 (updated, Milestone 7):** the existing `availability` and
`bookings` endpoint surface (Milestones 3/4/6, listed in §8.1) covers every
professional-dashboard backend need this doc has identified, **plus** §2.18/§2.19's new
slot edit/delete, added this pass by explicit user decision reversing the original
Milestone 6 "not needed" call. `sos_availability` genuinely needs nothing further (§8.3
above, unchanged) — its existing read/write pair already covers the full scope "edit"
could mean for a singleton toggle row.

---

## 9. Confirmation — the `EXPIRED`-issue-reopen gap is unaffected by this pass, and is now
DECIDED (Milestone 7) as intentional, permanent behavior

`data-model.md` §4 and `api-contract-notifications.md` §7 document that an issue that
reaches `issues.status = 'EXPIRED'` has no endpoint anywhere that transitions it back to
`'OPEN'`, so `POST /api/bookings/orders` (§2.4 step 6) and `POST /api/bookings/sos-orders`
(§2.13 step 6) both reject a rebooking attempt against it with `409 ISSUE_NOT_BOOKABLE`.
**As of Milestone 7, this is confirmed intentional, permanent design (user ruling,
2026-08-15) — not an open gap.** See `data-model.md` §4 and
`api-contract-notifications.md` §7 for the full resolution record. This section's
verification below (that Milestone 6's `on-the-way`/`complete` endpoints don't touch this
behavior) remains accurate and is kept for the historical record.

**Confirmed still accurately described as open, and confirmed unaffected by §2.16/§2.17
above** — checked explicitly, not assumed, per the task brief:

- §2.16 (`on-the-way`) only ever reads/writes an order whose `order_status = 'CONFIRMED'`
  and an issue that is (by the single-active-order invariant, §3.3) necessarily `BOOKED` —
  it never touches `OPEN` or `EXPIRED` issues, and has no code path that could reach one
  (an `EXPIRED` issue's most recent order is, by construction, `EXPIRED` too — never
  `CONFIRMED` — so §2.16's ownership/guard checks would simply `404`/`409` long before
  reaching any issue-status logic, the same as they would for any other order that isn't
  this professional's `CONFIRMED` order).
- §2.17 (`complete`) similarly only ever transitions an issue `BOOKED → COMPLETED`, guarded
  on `BOOKED` — it has no `WHERE status = 'EXPIRED'` (or `'OPEN'`) branch anywhere, and
  cannot be reached by an `EXPIRED` issue's orders for the identical reason above.
- Neither endpoint adds, removes, or narrows any existing pathway to/from `EXPIRED` or
  `OPEN` — the gap's shape (no endpoint transitions `EXPIRED → OPEN`) is exactly as
  `data-model.md` §4 and `api-contract-notifications.md` §7 already describe it, unchanged
  by this pass.

**Resolved, Milestone 7 (2026-08-15) — not attempted as a code change here, decided as a
permanent behavior in the docs.** The user has ruled `EXPIRED` stays a final,
permanent `issues.status` state: no reopen endpoint, no relaxed booking guard, ever. The
intended path for a customer who wants service again is to create a new `issues` row for
the same problem. Tracked in `data-model.md` §4, `api-contract-notifications.md` §7, and
`hardening-plan.md` §4.1; restated here only for cross-reference completeness so a reader of
this doc's Milestone 6 pass doesn't have to wonder whether it was silently resolved or
silently broken by the new endpoints above. It was neither — it was decided explicitly,
elsewhere, with no code change to this doc's endpoints required.

---

## MS4 (2026-08-24) — listing card and category matching

**Step 7 of §2.2 changed.** "Query `professionals` joined to `users` where `category_id =
issue.categoryId`" is now a membership test over `professional_categories`: a professional is
eligible if the issue's category is anywhere in their set, not only if it is their first. The
predicate is `professionals.ProfessionalCategoryMatch.SERVES_CATEGORY_JPQL`, and the SOS hard
filter (`sos.repository.SosCandidateRepository`) is built from the same constant, so the two
surfaces cannot disagree about who serves what.

Rule 5 of `POST /api/bookings/orders` (`professional.categoryId != issue.categoryId` →
`400 CATEGORY_MISMATCH`) keeps its behaviour and its error code; the check is now
"does this professional serve `issue.categoryId`".

**`ProfessionalCard` fields**: `serviceArea` → `serviceRegion` (the canonical region's Hebrew
label, nullable), `city` is now the resolved base-city name rather than a free-text column, and
`categoryIds` is new. All other fields, the `base_price ASC` default order and the
`CHEAPEST`/`FASTEST`/`RECOMMENDED` sorts are unchanged.

`categoryIds` is attached during the existing in-Java enrichment pass from **one batched
`professional_categories` read for the whole page** — JPQL cannot project a collection into a
`SELECT NEW` constructor expression, and N+1 queries per listing is not a trade worth making to
pretend otherwise.

