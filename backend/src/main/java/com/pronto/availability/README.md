# `availability`

## Purpose

`AvailabilitySlots` management for professionals — a deliberately narrow, read-focused
slice as of Milestone 3 — plus, as of the pre-Milestone-4 schema-gap fix, the
`sos_availability` live-toggle table's entity/repository (table exists and gets a row per
professional now; the toggle/listing endpoints themselves are Milestone 4 scope).

Implements `docs/architecture/api-contract-bookings.md` §2.10-2.11.

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
- No overlap/double-booking validation, no edit/delete/toggle actions — out of scope for
  this narrow Milestone 3 slice (Milestone 6's job).
- `sos_availability`: **implemented as of the schema-gap fix.** A live on/off toggle a
  professional flips to signal "currently available for urgent work," structurally separate
  from `availability_slots` (not a query variant of it — see §2.6/§3 item 5's explicit
  rationale for rejecting that approach). One row per professional, created at registration
  time (`auth.service.AuthService#register`) defaulting to `isAvailable = false`, so a
  future SOS-matching query needs no NULL-handling for professionals who've never toggled
  it. The toggle/listing endpoints themselves are Milestone 4 (SOS booking flow) scope.

## Key classes

| Class | Role |
|---|---|
| `entity.AvailabilitySlot` | JPA entity for `availability_slots`. `professionalId` is a plain FK column, not an association. Always starts `isAvailable = true`. |
| `entity.SosAvailability` | JPA entity for `sos_availability` (§2.6). `professionalId` is the PK itself (1 row per professional, no surrogate `id`). Always starts `isAvailable = false`. |
| `repository.AvailabilitySlotRepository` | `JpaRepository`, plus the two finder methods behind §2.3/§2.11's listings and — the reason this repository matters most to `bookings` — `claimSlot`/`releaseSlot`, the atomic `UPDATE ... WHERE <state guard>` transitions that are this milestone's **sole** slot-claim/release mechanism (`docs/architecture/api-contract-bookings.md` §3.4). `releaseSlot` is unconditional on `slotId`, which is safe (a no-op) when called with `null` — relied on by `bookings` for the future SOS case where `orders.slot_id` is always `NULL`. |
| `repository.SosAvailabilityRepository` | Plain `JpaRepository`, no custom methods yet — `auth.service.AuthService#register` is the only current caller (inserts the initial row). Milestone 4 adds the toggle/listing queries. |
| `dto.CreateSlotRequest` | `POST /api/availability/slots` wire shape. |
| `dto.SlotResponse` | `POST /api/availability/slots`'s response shape (includes `professionalId`). |
| `dto.SlotListItem` / `dto.SlotListResponse` | `GET /api/availability/slots/me`'s response shape — deliberately omits `professionalId` (every entry is implicitly the caller's own), matching the contract doc's §2.11 example exactly. |
| `service.AvailabilityService` | Field-ordering validation (future/`end > start`), professional-id resolution (`ProfessionalRepository.findByUserId`), create/list logic. |
| `controller.AvailabilityController` | `/api/availability/slots` (`POST`), `/api/availability/slots/me` (`GET`). |
| `config.AvailabilityWebConfig` | Registers `common.security.RoleRequiredInterceptor(role = "PROFESSIONAL")` for `/api/availability/**`. |

## Interactions with other packages

- Depends on `professionals.repository.ProfessionalRepository.findByUserId` (already
  existed, no new lookup mechanism) to resolve the caller's `professionals.id`.
- Consumed by `bookings` for both Standard slot selection (§2.3's listing query, called
  directly against this package's repository) and the atomic claim/release mechanism
  (`bookings.service.BookingsService` calls `AvailabilitySlotRepository.claimSlot`/
  `releaseSlot` directly — not duplicated logic).
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

## Status

**`AvailabilitySlot` implemented and QA-validated, Milestone 3 slice**, on branch `MS3`
(not yet merged to `main` — pending the user's own git operations), per
`docs/architecture/api-contract-bookings.md` §2.10-2.11 and
`docs/architecture/implementation-plan.md`. QA live-validated both endpoints against a real
Postgres instance as part of the full Milestone 3 pass (slot creation feeding the Standard
booking flow's slot-selection/claim/release mechanism end-to-end) — zero bugs found. See
`docs/architecture/implementation-plan.md`'s Milestone 3 entry for the full QA summary.
Full CRUD, richer calendar semantics, and the professional dashboard UI remain Milestone 6
scope — this slice is intentionally not extended beyond §2.10/§2.11 (no edit/delete/toggle
actions exist).

`sos_availability` **implemented and live-verified** (2026-08-13, pre-Milestone-4
schema-gap fix, merged from `main`): booted against real Postgres, confirmed `V13` applies
cleanly, registered a test professional via the real API, and confirmed the
`sos_availability` row landed correctly (`is_available = f`).
