# `availability`

## Purpose

`AvailabilitySlots` management for professionals — a deliberately narrow, read-focused
slice as of Milestone 3 — plus, as of Milestone 4, the professional's own SOS-availability
toggle/read endpoints, built on the `sos_availability` entity/repository that landed ahead
of this milestone via the pre-Milestone-4 schema-gap fix.

Implements `docs/architecture/api-contract-bookings.md` §2.10-2.11 (Standard slots,
Milestone 3) and §2.14-2.15 (SOS-availability toggle, Milestone 4).

## Responsibilities

- `POST /api/availability/slots` — a professional creates one bookable Standard
  advance-booking window. Validates `startTime`/`endTime` are parseable (Bean Validation +
  Jackson), `startTime` is strictly in the future, and `endTime > startTime` (mirrors the
  DB-level `CHECK` on `availability_slots`) — all `400 VALIDATION_ERROR` on failure. Inserts
  with `is_available = true` (default).
- `GET /api/availability/slots/me` — lists **all** of the caller's own slots (past, future,
  available, and already-claimed), ordered by `start_time ASC`. No filter/pagination this
  milestone.
- Both endpoints require `role = PROFESSIONAL`, enforced via
  `common.security.RoleRequiredInterceptor` (registered for `/api/availability/**` by
  `config.AvailabilityWebConfig`) — a single blanket-pattern registration is correct here
  since both endpoints share the same role (unlike `bookings`).
- No overlap/double-booking validation, no edit/delete/toggle actions on `availability_slots`
  — out of scope for this narrow Milestone 3 slice (Milestone 6's job).
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
| `repository.AvailabilitySlotRepository` | `JpaRepository`, plus the two finder methods behind §2.3/§2.11's listings and — the reason this repository matters most to `bookings` — `claimSlot`/`releaseSlot`, the atomic `UPDATE ... WHERE <state guard>` transitions that are this milestone's **sole** slot-claim/release mechanism (`docs/architecture/api-contract-bookings.md` §3.4). `releaseSlot` is unconditional on `slotId`, which is safe (a no-op) when called with `null` — relied on by `bookings` for the future SOS case where `orders.slot_id` is always `NULL`. |
| `repository.SosAvailabilityRepository` | `JpaRepository`, plus `updateAvailability` (new, Milestone 4) — the plain, unconditional `UPDATE` behind §2.14's toggle write (see the Responsibilities section above for why it's unconditional, not the guarded-transition pattern). `findById` (inherited, PK is `professionalId`) is used for both §2.15's read and `bookings.service.BookingsService`'s §2.13-step-9 read-check. `auth.service.AuthService#register` remains the only inserter of the initial row. |
| `dto.CreateSlotRequest` | `POST /api/availability/slots` wire shape. |
| `dto.SlotResponse` | `POST /api/availability/slots`'s response shape (includes `professionalId`). |
| `dto.SlotListItem` / `dto.SlotListResponse` | `GET /api/availability/slots/me`'s response shape — deliberately omits `professionalId` (every entry is implicitly the caller's own), matching the contract doc's §2.11 example exactly. |
| `dto.SosAvailabilityRequest` | `PUT /api/availability/sos-availability` wire shape (new, Milestone 4). Single `Boolean isAvailable` field (boxed, not primitive, so a missing field is distinguishable from `false` at `@NotNull` validation time); pinned to `StrictBooleanDeserializer` via `@JsonDeserialize`. |
| `dto.SosAvailabilityResponse` | Shared response shape for both `PUT` and `GET /api/availability/sos-availability` (new, Milestone 4) — `professionalId`, `isAvailable`, `updatedAt`. |
| `dto.StrictBooleanDeserializer` | Custom Jackson `JsonDeserializer<Boolean>` (new, Milestone 4, package-private) — rejects any non-boolean-literal JSON token instead of Jackson's default lenient numeric-to-boolean coercion. Scoped to `SosAvailabilityRequest.isAvailable` only via `@JsonDeserialize`; see its Javadoc and the QA bug writeup below for why it isn't a global `ObjectMapper` config change. |
| `service.AvailabilityService` | Field-ordering validation (future/`end > start`) and create/list logic for slots (§2.10/§2.11, Milestone 3); as of Milestone 4, also `updateSosAvailability`/`getSosAvailability` (§2.14/§2.15) — both resolve the caller's `professionals.id` via the same `ProfessionalRepository.findByUserId` mechanism the slot methods already used. |
| `controller.AvailabilityController` | `/api/availability/slots` (`POST`), `/api/availability/slots/me` (`GET`), and, as of Milestone 4, `/api/availability/sos-availability` (`PUT`, `GET`). |
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
  "not requested, out of scope for this narrow slice" call (§2.10), flagged there as a
  Milestone 6 candidate, not built here.
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

**Implemented and QA-validated through Milestone 4**, on branch `MS4` (not yet merged to
`main`, nor is `MS3` — pending the user's own git operations), per
`docs/architecture/api-contract-bookings.md` ("Milestones 3 & 4") and
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

Full CRUD, richer calendar semantics, and the professional dashboard UI (for
`availability_slots`) remain Milestone 6 scope — the Standard-slot slice is intentionally
not extended beyond §2.10/§2.11 (no edit/delete/toggle actions on `availability_slots`
exist). `sos_availability` has no auto-expiry/timeout (a professional who forgets to toggle
off after finishing urgent work stays listed indefinitely) — not designed anywhere, flagged
as a candidate alongside Milestone 5's `EXPIRED` sweep, not built.
