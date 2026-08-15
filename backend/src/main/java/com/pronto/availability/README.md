# `availability`

## Purpose

`AvailabilitySlots` management for professionals — a deliberately narrow, read-focused
slice as of Milestone 3, extended with full edit/delete of a not-yet-in-use slot as of
Milestone 7 — plus, as of Milestone 4, the professional's own SOS-availability toggle/read
endpoints, built on the `sos_availability` entity/repository that landed ahead of this
milestone via the pre-Milestone-4 schema-gap fix.

Implements `docs/architecture/api-contract-bookings.md` §2.10-2.11 (Standard slots,
Milestone 3), §2.14-2.15 (SOS-availability toggle, Milestone 4), and §2.18-2.19 (slot
edit/delete, Milestone 7).

## Responsibilities

- `POST /api/availability/slots` — a professional creates one bookable Standard
  advance-booking window. Validates `startTime`/`endTime` are parseable (Bean Validation +
  Jackson), `startTime` is strictly in the future, and `endTime > startTime` (mirrors the
  DB-level `CHECK` on `availability_slots`) — all `400 VALIDATION_ERROR` on failure. Inserts
  with `is_available = true` (default).
- `GET /api/availability/slots/me` — lists **all** of the caller's own slots (past, future,
  available, and already-claimed), ordered by `start_time ASC`. No filter/pagination this
  milestone.
- `PUT /api/availability/slots/{slotId}` — **new, Milestone 7.** A professional replaces
  the `startTime`/`endTime` of one of their own slots. Same request shape/validation as
  `POST /api/availability/slots` (`CreateSlotRequest` reused verbatim — `startTime` strictly
  future, `endTime > startTime`, re-applied to the new values, not the slot's pre-edit
  values). Resolves/loads the slot first (`404 NOT_FOUND` if it doesn't exist at all),
  checks ownership (`403 FORBIDDEN` if the caller isn't the slot's professional), validates
  the new values (`400 VALIDATION_ERROR`), then applies the edit via a single atomic guarded
  `UPDATE ... WHERE id = :slotId AND professional_id = :professionalId AND is_available =
  true` (`AvailabilitySlotRepository.updateSlotTimes`) — `0` affected rows at that point
  (existence/ownership already proven) means the slot is currently held by an active order
  or was consumed by a completed one, mapped to `409 SLOT_IN_USE`. Returns `200` with the
  updated slot on success. See §2.18 of the contract doc.
- `DELETE /api/availability/slots/{slotId}` — **new, Milestone 7.** Same
  resolve/load/ownership sequence as the edit endpoint above, then an atomic guarded
  `DELETE ... WHERE id = :slotId AND professional_id = :professionalId AND is_available =
  true` (`AvailabilitySlotRepository.deleteSlotIfAvailable`) — `0` affected rows maps to
  `409 SLOT_IN_USE`, same reasoning as edit. Returns `204 No Content` on success, no body.
  See §2.19 of the contract doc.
- **Booking-protection invariant, enforced identically by both new endpoints**:
  `is_available = false` means the slot is either currently held by an active order
  (`PENDING`/`CONFIRMED`/`ON_THE_WAY`) or was consumed by a `COMPLETED` order — `accept` and
  `complete` never call `releaseSlot` (only `reject`/`cancel`/`expireIfPending` do), so
  `is_available = true` structurally means no order currently depends on the row. The atomic
  `WHERE ... AND is_available = true` guard is the sole enforcement mechanism (no
  load-then-check-then-mutate race), mirroring `claimSlot`/`releaseSlot`'s existing pattern
  exactly. Neither endpoint ever silently no-ops or cascade-cancels the order that holds a
  protected slot — a rejected attempt always surfaces as `409 SLOT_IN_USE`.
- All four `slots` endpoints (create, list-mine, edit, delete) plus the two
  `sos-availability` endpoints require `role = PROFESSIONAL`, enforced via
  `common.security.RoleRequiredInterceptor` (registered for `/api/availability/**` by
  `config.AvailabilityWebConfig`) — a single blanket-pattern registration is correct here
  since every endpoint in this package shares the same role (unlike `bookings`); the new
  Milestone 7 routes needed no config change, already covered by the existing wildcard.
- No overlap/double-booking validation against a professional's own other slots — out of
  scope, unchanged by Milestone 7 (editing a slot to overlap another of the professional's
  own slots is not blocked). **Milestone 6 had explicitly reviewed and declined to add slot
  edit/delete; Milestone 7 reverses that specific call by direct user product decision** —
  see Status below for the full record of the reversal.
- `PUT /api/availability/sos-availability` — **new, Milestone 4.** The professional's own
  live "I'm currently available for urgent work" toggle (PRD §3.5.2). Request: `{
  "isAvailable": <boolean> }`. Writes via a **plain, unconditional** `UPDATE
  sos_availability SET is_available = :isAvailable, updated_at = now() WHERE professional_id
  = :professionalId` — deliberately **not** the guarded-transition (`WHERE
  <current-state-guard>`) pattern every other state change in this doc family uses, because
  there is no "wrong state to toggle from": setting the same value twice, or flipping either
  direction, is always valid from an authenticated professional (no concurrent-conflict
  concept to guard against, unlike a slot claim or an order-status transition). An
  affected-row count of `0` (the row is missing entirely) is treated as a data-integrity bug
  — `500 INTERNAL_ERROR` logged at `WARN`, not a new `4xx` code, since it isn't a condition a
  well-behaved client can trigger through normal use (every professional gets a row at
  registration time).
- `GET /api/availability/sos-availability` — **new, Milestone 4.** Read-side counterpart to
  the toggle above, for a dashboard UI to render current state. No `/me` suffix (unlike
  `/api/availability/slots/me`) — deliberate, not an inconsistency: `sos_availability` is
  inherently one row per professional with no "list" concept, so there's no ambiguity a
  `/me` suffix would need to resolve.
- **A bug was found and fixed here mid-Milestone-4** (QA): a numeric JSON value (e.g. `1`)
  sent as `isAvailable` was silently coerced to a boolean by Jackson's default lenient
  scalar coercion, instead of being rejected as `400 VALIDATION_ERROR` per the contract
  doc's field-validation rule. Fixed via `dto.StrictBooleanDeserializer`, a narrowly-scoped
  custom Jackson deserializer applied only to `SosAvailabilityRequest.isAvailable` via
  `@JsonDeserialize` — deliberately not a global `ObjectMapper` coercion-config change, to
  avoid any risk to Milestone 1-3's already-shipped `Boolean`-typed fields elsewhere in the
  app. Re-verified fixed with no side effects.
- `sos_availability`: entity/repository **implemented as of the pre-Milestone-4 schema-gap
  fix**, endpoints **implemented as of Milestone 4** (the two bullets above). A live on/off
  toggle a professional flips to signal "currently available for urgent work," structurally
  separate from `availability_slots` (not a query variant of it — see §2.6/§3 item 5's
  explicit rationale for rejecting that approach). One row per professional, created at
  registration time (`auth.service.AuthService#register`) defaulting to `isAvailable =
  false`, so the SOS-matching query in `bookings` (`ProfessionalListingRepository
  .listSosAvailableByCategory`, §2.12) needs no NULL-handling for professionals who've never
  toggled it.

## Key classes

| Class | Role |
|---|---|
| `entity.AvailabilitySlot` | JPA entity for `availability_slots`. `professionalId` is a plain FK column, not an association. Always starts `isAvailable = true`. |
| `entity.SosAvailability` | JPA entity for `sos_availability` (§2.6). `professionalId` is the PK itself (1 row per professional, no surrogate `id`). Always starts `isAvailable = false`. |
| `repository.AvailabilitySlotRepository` | `JpaRepository`, plus the two finder methods behind §2.3/§2.11's listings and — the reason this repository matters most to `bookings` — `claimSlot`/`releaseSlot`, the atomic `UPDATE ... WHERE <state guard>` transitions that are this milestone's **sole** slot-claim/release mechanism (`docs/architecture/api-contract-bookings.md` §3.4). `releaseSlot` is unconditional on `slotId`, which is safe (a no-op) when called with `null` — relied on by `bookings` for the future SOS case where `orders.slot_id` is always `NULL`. As of Milestone 7, also `updateSlotTimes`/`deleteSlotIfAvailable` — the atomic guarded `UPDATE`/`DELETE` behind §2.18/§2.19's edit/delete endpoints, same `WHERE ... AND is_available = true` guard pattern. |
| `repository.SosAvailabilityRepository` | `JpaRepository`, plus `updateAvailability` (new, Milestone 4) — the plain, unconditional `UPDATE` behind §2.14's toggle write (see the Responsibilities section above for why it's unconditional, not the guarded-transition pattern). `findById` (inherited, PK is `professionalId`) is used for both §2.15's read and `bookings.service.BookingsService`'s §2.13-step-9 read-check. `auth.service.AuthService#register` remains the only inserter of the initial row. |
| `dto.CreateSlotRequest` | `POST /api/availability/slots` wire shape. |
| `dto.SlotResponse` | `POST /api/availability/slots`'s response shape (includes `professionalId`). |
| `dto.SlotListItem` / `dto.SlotListResponse` | `GET /api/availability/slots/me`'s response shape — deliberately omits `professionalId` (every entry is implicitly the caller's own), matching the contract doc's §2.11 example exactly. |
| `dto.SosAvailabilityRequest` | `PUT /api/availability/sos-availability` wire shape (new, Milestone 4). Single `Boolean isAvailable` field (boxed, not primitive, so a missing field is distinguishable from `false` at `@NotNull` validation time); pinned to `StrictBooleanDeserializer` via `@JsonDeserialize`. |
| `dto.SosAvailabilityResponse` | Shared response shape for both `PUT` and `GET /api/availability/sos-availability` (new, Milestone 4) — `professionalId`, `isAvailable`, `updatedAt`. |
| `dto.StrictBooleanDeserializer` | Custom Jackson `JsonDeserializer<Boolean>` (new, Milestone 4, package-private) — rejects any non-boolean-literal JSON token instead of Jackson's default lenient numeric-to-boolean coercion. Scoped to `SosAvailabilityRequest.isAvailable` only via `@JsonDeserialize`; see its Javadoc and the QA bug writeup below for why it isn't a global `ObjectMapper` config change. |
| `service.AvailabilityService` | Field-ordering validation (future/`end > start`) and create/list logic for slots (§2.10/§2.11, Milestone 3); as of Milestone 4, also `updateSosAvailability`/`getSosAvailability` (§2.14/§2.15) — both resolve the caller's `professionals.id` via the same `ProfessionalRepository.findByUserId` mechanism the slot methods already used. As of Milestone 7, also `edit`/`delete` (§2.18/§2.19) — same authorization-first ordering (load slot → resolve caller's professional id → ownership check → business-validate → atomic guarded write), reusing the same field-ordering validation `create` already has via a shared private `validateSlotTimes` helper. |
| `controller.AvailabilityController` | `/api/availability/slots` (`POST`), `/api/availability/slots/me` (`GET`), `/api/availability/slots/{slotId}` (`PUT`, `DELETE`, new Milestone 7), and, as of Milestone 4, `/api/availability/sos-availability` (`PUT`, `GET`). |
| `config.AvailabilityWebConfig` | Registers `common.security.RoleRequiredInterceptor(role = "PROFESSIONAL")` for the blanket `/api/availability/**` pattern. **Needed no change for Milestone 4** — the existing wildcard already covers `/api/availability/sos-availability` for free, unlike `bookings.config.BookingsWebConfig`'s literal-list design, which did need two new entries. |

## Interactions with other packages

- Depends on `professionals.repository.ProfessionalRepository.findByUserId` (already
  existed, no new lookup mechanism) to resolve the caller's `professionals.id` — used by all
  four endpoints in this package, Standard and SOS alike.
- Consumed by `bookings` for Standard slot selection (§2.3's listing query, called directly
  against this package's repository), the atomic claim/release mechanism
  (`bookings.service.BookingsService` calls `AvailabilitySlotRepository.claimSlot`/
  `releaseSlot` directly — not duplicated logic), and, as of Milestone 4, the SOS path:
  `BookingsService.createSosOrder`'s plain read-check calls `SosAvailabilityRepository
  .findById` directly, and `ProfessionalListingRepository.listSosAvailableByCategory`
  (`bookings`) joins to this package's `SosAvailability` entity.
- Depends on `common` for the error envelope and `RoleRequiredInterceptor`/
  `AuthenticatedUser`.
- `SosAvailabilityRepository` is depended on by `auth.service.AuthService#register`, which
  inserts the initial row for every newly registered professional.

## Data model

Owns the `availability_slots` table (see `docs/architecture/data-model.md` §2.5) and, as of
the `V13` migration, the `sos_availability` table (§2.6) — closing the schema gap flagged
in `data-model.md` §4 / `api-contract.md` §4 ahead of Milestone 4. `V11`/`V12` (Milestone 3)
only touched `orders`, unrelated to this package's tables.

## Assumptions / judgment calls made during implementation

- **No overlap/double-booking validation against a professional's own existing slots** — a
  professional can create two overlapping slots today. Per the contract doc's explicit
  "not requested, out of scope for this narrow slice" call (§2.10). Was speculatively flagged
  in earlier docs as a Milestone 6 candidate; Milestone 6 explicitly reviewed this package's
  scope (§8.2 of the contract doc) and did not add it — remains undesigned/unbuilt, not
  currently requested by any source document.
- **`GET /api/availability/slots/me` has no query params this milestone** — returns
  everything unfiltered, per the doc's explicit "no `status`/date-range filter, no
  pagination" call (§2.11).
- **The SOS-availability toggle's write is a plain, unconditional `UPDATE`, not the §3.2
  guarded-transition pattern** used everywhere else in the `bookings`/`availability`
  contract doc — per the doc's own explicit reasoning (§2.14 step 4): there is no
  concurrent-conflicting-state concept for a professional's own single-writer toggle, so a
  state guard would be meaningless, not merely omitted for convenience.
- **The Jackson-coercion fix (`StrictBooleanDeserializer`) is scoped to one field, not a
  global `ObjectMapper` config change** — a deliberate choice to carry zero risk to any
  other `Boolean`-typed field on any Milestone 1-3 endpoint, per the QA bug writeup below,
  not merely the simplest fix available.

## Status

**Implemented and QA-validated through Milestone 4** (no code changes to this package in
Milestone 5 or Milestone 6 — see the note further below), plus the Milestone 7 slot
edit/delete addition (implemented this pass, unit-tested by `pronto-coding`, and **now
QA-validated** — full regression plus new targeted tests, zero gaps, see the Milestone 7
entry at the bottom of this section for the full QA summary), on branch `MS7`, per
`docs/architecture/api-contract-bookings.md` ("Milestones 3, 4, 6 & 7") and
`docs/architecture/implementation-plan.md`.

**Milestone 3 slice** (`POST`/`GET /api/availability/slots`, `/slots/me`): QA live-validated
both endpoints against a real Postgres instance as part of the full Milestone 3 pass (slot
creation feeding the Standard booking flow's slot-selection/claim/release mechanism
end-to-end) — zero bugs found.

**`sos_availability` entity/repository**: implemented and live-verified (2026-08-13,
pre-Milestone-4 schema-gap fix, merged from `main`): booted against real Postgres, confirmed
`V13` applies cleanly, registered a test professional via the real API, and confirmed the
`sos_availability` row landed correctly (`is_available = f`).

**Milestone 4 additions** (`PUT`/`GET /api/availability/sos-availability`): QA
live-validated both endpoints against a real Postgres instance as part of the full
Milestone 4 SOS pass — toggle-on/toggle-off/read-back round trips, the toggle's idempotent/
no-state-guard semantics verified from both prior states, ownership/role enforcement, and
the downstream effect on `bookings`'s SOS-professional listing (§2.12) all confirmed
correct. **One bug found and fixed**: a numeric JSON value silently coerced to a boolean
instead of being rejected — see the Responsibilities section above and
`docs/architecture/implementation-plan.md`'s Milestone 4 entry for the full writeup. Fixed
and re-verified; final QA verdict for this package this milestone: zero known open bugs.

**Milestone 6 had concluded no new `availability` endpoint was needed and that full slot
CRUD was not coming later — that specific conclusion (slot edit/delete only) was reversed by
Milestone 7, see below.** `implementation-plan.md`'s Milestone 6 acceptance criteria ("a
professional can manage availability, see incoming requests, and progress a job through its
statuses") were reviewed against what already existed (`api-contract-bookings.md` §8) and
found already satisfied for the "manage availability" piece by this package's
then-existing surface: create a slot (§2.10), list your own slots (§2.11), and toggle live
SOS availability (§2.14/§2.15). **Slot edit/delete was explicitly considered and explicitly
declined at the time** (§8.2 of the contract doc has the full historical reasoning,
condensed here for the record — superseded by Milestone 7, not wrong in hindsight, a
legitimate MVP scope call a later explicit product decision overrode):
- No PRD text mandated it — PRD §6's `AvailabilitySlots` schema and PRD §7's wireframes both
  describe no edit/cancel workflow for a slot.
- No load-bearing *functional* gap existed without it — an unwanted `is_available = true`
  slot could still get booked and released via the ordinary `reject`/`cancel` flow, and a
  never-booked slot just aged into the past, still visible/auditable via `GET .../slots/me`.
- Frontend was out of scope project-wide at the time, so there was no current UI caller
  motivating the endpoint even speculatively.

`sos_availability` has no auto-expiry/timeout (a professional who forgets to toggle off
after finishing urgent work stays listed indefinitely) — not designed anywhere, flagged as a
candidate alongside Milestone 5's `EXPIRED` sweep, not built; unaffected by Milestone 6/7 and
not part of either milestone's scope (§8.3 of the contract doc: `sos_availability`'s
read/write surface needed no further work).

**This package had zero code changes in Milestone 6** — the two new endpoints Milestone 6
added (`on-the-way`/`complete`) live entirely in `bookings`, guarded on `orders.order_status`,
and never touch `availability_slots` or `sos_availability`. See
`backend/src/main/java/com/pronto/bookings/README.md`'s Status section for that milestone's
full QA summary.

**Milestone 7 (2026-08-15): slot edit/delete implemented, reversing the Milestone 6 "not
building" call above by explicit user product decision — not a re-evaluation of the
Milestone 6 reasoning's merits.** Two new endpoints, `PUT`/`DELETE
/api/availability/slots/{slotId}` (§2.18/§2.19 of the contract doc), implemented in
`entity.AvailabilitySlot`/`repository.AvailabilitySlotRepository`/`service
.AvailabilityService`/`controller.AvailabilityController` — see the Responsibilities and Key
classes sections above for the exact behavior. One new `ErrorCode`,
`SLOT_IN_USE` (409), added to `common.exception.ErrorCode`. No `AvailabilityWebConfig`
change (existing blanket `PROFESSIONAL`-only `/api/availability/**` wildcard already covers
the new routes), no new Flyway migration (both endpoints operate on `availability_slots`
columns that have existed since `V5`), and `sos_availability` code is completely untouched by
this pass. The Milestone 6 reasoning's core safety property — "a professional edit/delete
must never silently invalidate a confirmed booking" — is preserved exactly by the new
endpoints' `is_available = true` atomic guard (`409 SLOT_IN_USE` otherwise, never a silent
no-op, never a cascade-cancel of the order), not weakened by granting the capability.

First unit tests added for this package (`src/test/java/com/pronto/availability/service
/AvailabilityServiceTest.java`, 9 cases): happy-path edit, happy-path delete, booking
protection on edit (`is_available = false` → `409 SLOT_IN_USE`, no other repository
interaction attempted), booking protection on delete (same), ownership (`403 FORBIDDEN` for
a non-owning professional, on both edit and delete), and not-found (`404 NOT_FOUND` for a
nonexistent slot id, on both edit and delete). Concrete-class mocking of the JPA entities
(`AvailabilitySlot`/`Professional`) was found to misbehave in this environment (byte-buddy
subclassing of the entity's real getters corrupted Mockito's stubbing state) — worked around
by constructing real entity instances via their public constructors and setting the
generated `id` field via reflection, rather than mocking the entities directly; only the
repository interfaces are mocked. All 9 new tests pass; full backend suite (39 tests total)
passes; `mvnd clean package` builds successfully.

**QA summary (Milestone 7, 2026-08-15): full regression plus new targeted tests for §2.18/
§2.19, zero gaps.** Live-validated against a real Postgres instance: happy-path edit and
delete against an owned, unprotected (`is_available = true`) slot; ownership enforcement
(`403 FORBIDDEN` for a non-owning professional, both endpoints); not-found (`404 NOT_FOUND`
for a nonexistent slot id, both endpoints); field validation re-applied to the new values on
edit (`400 VALIDATION_ERROR` for a non-future `startTime` or `endTime <= startTime`); and
the booking-protection guard (`409 SLOT_IN_USE`, never a silent no-op, never a
cascade-cancel of the order the slot backs) **live-verified across all four order lifecycle
states a slot can be protecting** — `PENDING`, `CONFIRMED`, `ON_THE_WAY`, and `COMPLETED` —
confirming `is_available = false` reliably blocks both edit and delete regardless of which
stage the protecting order is in, not just the `PENDING`/`CONFIRMED` cases exercised by unit
tests. Full cross-milestone regression re-run alongside this (Standard/SOS booking flows,
notifications, job-status progression) with zero regressions found. **Final verdict: full
sign-off, zero known open bugs for this package.**
