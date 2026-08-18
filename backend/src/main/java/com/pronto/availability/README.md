# `availability`

## Purpose

`AvailabilitySlots` management for professionals — a deliberately narrow, read-focused
slice as of Milestone 3, extended with full edit/delete of a not-yet-in-use slot as of
Milestone 7 — plus, as of Milestone 4, the professional's own SOS-availability toggle/read
endpoints, built on the `sos_availability` entity/repository that landed ahead of this
milestone via the pre-Milestone-4 schema-gap fix. **As of 2026-08-18 (M1 of the professional
weekly availability calendar feature)**, also owns the new weekly working-hours
get/replace endpoints, manual availability-block create/edit/delete endpoints, and the
consolidated derived-calendar read endpoint — the new "actual available time" model this
whole feature is built around. The pre-existing `availability_slots`/`sos_availability`
surface (previous paragraph) is **kept, completely unmodified** alongside this addition —
see `docs/architecture/professional-weekly-calendar-design.md` §2.4 for why, and the M1
entry in Status below for the full record.

Implements `docs/architecture/api-contract-bookings.md` §2.10-2.11 (Standard slots,
Milestone 3), §2.14-2.15 (SOS-availability toggle, Milestone 4), §2.18-2.19 (slot
edit/delete, Milestone 7), and, as of 2026-08-18,
`docs/architecture/professional-weekly-calendar-design.md` §4.1-§4.6 (working hours, manual
blocks, derived calendar — M1, backend-only; frontend consumption is M3-M6, not built yet).

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

### Weekly availability calendar (M1, 2026-08-18)

- `GET /api/availability/working-hours` — returns the caller's configured week (0-7 entries;
  fewer than 7 only before first-time setup, an empty array on a brand-new professional, not
  an error).
- `PUT /api/availability/working-hours` — replaces the caller's entire week in one call
  (idempotent upsert of all 7 weekdays, transactional; a plain JPA save-per-weekday loop,
  loading existing rows and updating in place or inserting missing weekdays — no bulk SQL).
  Request must contain exactly 7 entries, one per weekday `0`-`6`, no duplicates/gaps (`400
  VALIDATION_ERROR` otherwise); `startTime`/`endTime` required with `endTime > startTime`
  when `enabled = true`, ignored when `enabled = false`. Never touches
  `professional_availability_blocks` or `orders` — a structural guarantee, not just an
  unenforced convention, that editing working hours can never mutate/delete a confirmed
  booking or corrupt manual-block history.
- `POST /api/availability/blocks` — creates a manual block (personal appointment, lunch,
  vacation, etc.). Two-layer overlap protection: (1) a fast pre-check `SELECT` against both
  the caller's own bookings (`409 BLOCK_OVERLAPS_BOOKING`) and own other blocks (`409
  BLOCK_OVERLAPS_EXISTING_BLOCK`); (2) the DB-level `ck_blocks_no_overlap` exclusion
  constraint as the authoritative backstop for the true concurrency race, caught via its
  `23P01` (exclusion-violation) Postgres SQLState and mapped to the same `409
  BLOCK_OVERLAPS_EXISTING_BLOCK` — the first new-in-this-codebase instance of "catch a
  constraint-violation exception and map it to a domain error" (every other guard in this
  app uses an affected-row-count check instead, since no prior feature needed a
  range-overlap guard). `startAt` must not be strictly in the past (relaxed to allow
  "blocking the rest of today"); `endAt` must be after `startAt`.
- `PATCH /api/availability/blocks/{blockId}` — full replace of `startAt`/`endAt`/`reason`
  (despite the HTTP verb, mirrors `PUT /api/availability/slots/{slotId}`'s existing
  "resend the whole editable shape" convention). Same authorization-first ordering as slot
  edit (`404 NOT_FOUND` → `403 FORBIDDEN` → validate → two-layer overlap check, excluding the
  block's own row from both layers).
- `DELETE /api/availability/blocks/{blockId}` — same ownership check as edit, then an
  unconditional (within ownership) `DELETE`. No "in use" guard needed — a block is never
  referenced by any FK, so deleting it can never orphan/corrupt anything else.
- `GET /api/availability/calendar?from=&to=` — the one new consolidated read endpoint,
  returning the caller's derived `AVAILABLE`/`BLOCKED`/`BOOKED` segment timeline for
  `[from, to)` (ISO-8601 date or date-time, `to > from`, capped at a 6-week span, `400
  VALIDATION_ERROR` otherwise) plus the raw `workingHours` array (date-independent, returned
  once). Delegates the actual derivation to `service.AvailabilityDerivationService`. A
  `PENDING`/`CONFIRMED`/`ON_THE_WAY` order (non-null `bookedEnd`) renders as `BOOKED`
  (sub-labeled by `orderStatus`); a `COMPLETED` order is additionally emitted as `BOOKED` too
  (never subtracted from a live `AVAILABLE` interval, since its window is definitionally in
  the past); an SOS order (always `bookedEnd = NULL`) is structurally invisible to this
  endpoint. Time outside any `enabled` weekday's configured window has **no** segment at all
  — not `AVAILABLE`, not editable as a block. All segment boundaries are exact,
  non-grid-rounded timestamps.
- All six new routes require `role = PROFESSIONAL`, same blanket `RoleRequiredInterceptor`
  registration as every other route in this package — no `AvailabilityWebConfig` change
  needed.

## Key classes

| Class | Role |
|---|---|
| `entity.AvailabilitySlot` | JPA entity for `availability_slots`. `professionalId` is a plain FK column, not an association. Always starts `isAvailable = true`. |
| `entity.SosAvailability` | JPA entity for `sos_availability` (§2.6). `professionalId` is the PK itself (1 row per professional, no surrogate `id`). Always starts `isAvailable = false`. |
| `entity.ProfessionalWorkingHours` | **New, M1.** JPA entity for `professional_working_hours`. `weekday` is stored as `short` (not `int`) to match the `SMALLINT` column type under Hibernate's `ddl-auto: validate` (same convention `reviews.entity.Review#rating` already uses for its own `SMALLINT` column) — constructor/getters still expose plain `int`/`short` interchangeably via Java's automatic widening. No per-field setter; `update(enabled, startTime, endTime)` is the sole full-replace mutation path (no concurrency guard needed — a professional's own working-hours write has no "wrong state to update from" concept, same reasoning `SosAvailability`'s unconditional toggle already relies on). |
| `entity.ProfessionalAvailabilityBlock` | **New, M1.** JPA entity for `professional_availability_blocks`. Same no-setter-except-`update(...)` convention as `ProfessionalWorkingHours`, for the same reason (the DB exclusion constraint, not an atomic-affected-row-count guard, is what actually protects correctness here). |
| `repository.AvailabilitySlotRepository` | `JpaRepository`, plus the two finder methods behind §2.3/§2.11's listings and — the reason this repository matters most to `bookings` — `claimSlot`/`releaseSlot`, the atomic `UPDATE ... WHERE <state guard>` transitions that are this milestone's **sole** slot-claim/release mechanism (`docs/architecture/api-contract-bookings.md` §3.4). `releaseSlot` is unconditional on `slotId`, which is safe (a no-op) when called with `null` — relied on by `bookings` for the future SOS case where `orders.slot_id` is always `NULL`. As of Milestone 7, also `updateSlotTimes`/`deleteSlotIfAvailable` — the atomic guarded `UPDATE`/`DELETE` behind §2.18/§2.19's edit/delete endpoints, same `WHERE ... AND is_available = true` guard pattern. |
| `repository.SosAvailabilityRepository` | `JpaRepository`, plus `updateAvailability` (new, Milestone 4) — the plain, unconditional `UPDATE` behind §2.14's toggle write (see the Responsibilities section above for why it's unconditional, not the guarded-transition pattern). `findById` (inherited, PK is `professionalId`) is used for both §2.15's read and `bookings.service.BookingsService`'s §2.13-step-9 read-check. `auth.service.AuthService#register` remains the only inserter of the initial row. |
| `repository.ProfessionalWorkingHoursRepository` | **New, M1.** `JpaRepository`, plus `findByProfessionalId` — the only access pattern needed (at most 7 rows). |
| `repository.ProfessionalAvailabilityBlockRepository` | **New, M1.** `JpaRepository`, plus `findByProfessionalIdAndStartAtLessThanAndEndAtGreaterThan` (one range-overlap finder, reused for both the calendar derivation's block-fetch and the block-create/edit overlap pre-check — no dedicated overlap/exists query, per design §3) and `deleteByIdAndProfessionalId` (explicit JPQL bulk `DELETE`, same convention as `AvailabilitySlotRepository.deleteSlotIfAvailable`). |
| `service.AvailabilityDerivationService` | **New, M1.** The core read-side engine — `deriveCalendar(professionalId, from, to)` implements the exact subtract-blocks-then-bookings algorithm (design §5). Also defines `BUSINESS_TIMEZONE` (`ZoneId.of("Asia/Jerusalem")`), the single named business-timezone constant this whole feature reuses. **Cross-package dependency, flagged**: depends directly on `bookings.repository.OrderRepository` (existing, unmodified) rather than a new bookings-side query method, since this M1 task brief required zero changes to the `bookings` package — reuses the existing `findByProfessionalIdOrderByCreatedAtDesc` finder and filters/clips in memory (consistent with this codebase's "no pagination at MVP scale" convention). See the class's own Javadoc for the full reasoning and the `BUSINESS_TIMEZONE` Javadoc for a pre-existing-duplicate-constant note flagged to `pronto-lead`. **As of M2 (2026-08-18)**: gained a second public method, `deriveAvailableWindows(professionalId, from, to, minDuration)` — a thin filter over `deriveCalendar`'s own `AVAILABLE` segments (`segment.duration() >= minDuration`, via a new `CalendarSegment#duration()` helper), no duplicated derivation logic. Two callers now: `bookings.service.BookingsService#createOrder`'s pre-check (calls `deriveCalendar` directly, checking full containment) and the new `GET /api/bookings/professionals/{id}/available-windows?issueId=` listing (calls `deriveAvailableWindows`). Both live in `bookings`, not here — this class itself gained no new endpoint, no new dependency, just the one new method. |
| `dto.CreateSlotRequest` | `POST /api/availability/slots` wire shape. |
| `dto.SlotResponse` | `POST /api/availability/slots`'s response shape (includes `professionalId`). |
| `dto.SlotListItem` / `dto.SlotListResponse` | `GET /api/availability/slots/me`'s response shape — deliberately omits `professionalId` (every entry is implicitly the caller's own), matching the contract doc's §2.11 example exactly. |
| `dto.SosAvailabilityRequest` | `PUT /api/availability/sos-availability` wire shape (new, Milestone 4). Single `Boolean isAvailable` field (boxed, not primitive, so a missing field is distinguishable from `false` at `@NotNull` validation time); pinned to `StrictBooleanDeserializer` via `@JsonDeserialize`. |
| `dto.SosAvailabilityResponse` | Shared response shape for both `PUT` and `GET /api/availability/sos-availability` (new, Milestone 4) — `professionalId`, `isAvailable`, `updatedAt`. |
| `dto.StrictBooleanDeserializer` | Custom Jackson `JsonDeserializer<Boolean>` (new, Milestone 4, package-private) — rejects any non-boolean-literal JSON token instead of Jackson's default lenient numeric-to-boolean coercion. Scoped to `SosAvailabilityRequest.isAvailable` only via `@JsonDeserialize`; see its Javadoc and the QA bug writeup below for why it isn't a global `ObjectMapper` config change. |
| `dto.WorkingHoursItem` | **New, M1.** One weekday's row — `weekday`, `enabled`, `startTime`/`endTime` (`LocalTime`, `null` when disabled). Shared by `GET`/`PUT /working-hours`'s response and `GET /calendar`'s `workingHours` array. `@JsonFormat(pattern = "HH:mm")` on both time fields — **verified live during manual QA that this is needed**: Jackson's default `LocalTime` serializer emits seconds (`"08:00:00"`), which doesn't match the design doc's documented `"08:00"` wire-format examples; pinned explicitly rather than left silently mismatched. |
| `dto.WorkingHoursItemRequest` / `dto.WorkingHoursUpdateRequest` | **New, M1.** `PUT /working-hours`'s request shape — `WorkingHoursUpdateRequest` wraps exactly 7 `WorkingHoursItemRequest`s (`@Size(min=7,max=7)`); per-field Bean Validation only (weekday range, non-null `enabled`) — the cross-field "no duplicate/missing weekday" and "times required when enabled" rules live in `AvailabilityService`, same split `CreateSlotRequest`'s own field-ordering rules already use. |
| `dto.CreateBlockRequest` | **New, M1.** Wire shape for both `POST /blocks` and `PATCH /blocks/{blockId}` — reused verbatim (full replace, not a partial patch), mirroring `CreateSlotRequest`'s existing reuse across `POST`/`PUT /slots*`. |
| `dto.BlockResponse` | **New, M1.** `POST`/`PATCH /blocks*`'s response shape — mirrors `SlotResponse`'s shape (`id`, `professionalId`, both timestamps). |
| `dto.SegmentType` | **New, M1.** `AVAILABLE`/`BLOCKED`/`BOOKED` enum — the three visual states `GET /calendar` renders (design's §9, "at least three states"). |
| `dto.CalendarSegment` | **New, M1.** One entry in `GET /calendar`'s `segments` array — `type`, `startAt`, `endAt`, plus `blockId`/`reason` (only when `BLOCKED`) or `orderId`/`orderStatus` (only when `BOOKED`, reusing `bookings.entity.OrderStatus` directly — same cross-package DTO-field-reuse convention `bookings.dto.OrderResponse` already uses). Three static factories (`available`/`blocked`/`booked`) are the only construction path, so an `AVAILABLE` segment can never carry a stray `blockId`/`orderId`. |
| `dto.CalendarResponse` | **New, M1.** `GET /calendar`'s full response shape — `professionalId`, `from`, `to`, `timezone` (always `"Asia/Jerusalem"`, echoed so the frontend never hardcodes it), `workingHours`, `segments`. |
| `service.AvailabilityService` | Field-ordering validation (future/`end > start`) and create/list logic for slots (§2.10/§2.11, Milestone 3); as of Milestone 4, also `updateSosAvailability`/`getSosAvailability` (§2.14/§2.15) — both resolve the caller's `professionals.id` via the same `ProfessionalRepository.findByUserId` mechanism the slot methods already used. As of Milestone 7, also `edit`/`delete` (§2.18/§2.19) — same authorization-first ordering (load slot → resolve caller's professional id → ownership check → business-validate → atomic guarded write), reusing the same field-ordering validation `create` already has via a shared private `validateSlotTimes` helper. **As of M1 (2026-08-18)**, also `getWorkingHours`/`updateWorkingHours`/`createBlock`/`updateBlock`/`deleteBlock`/`getCalendar` — see the Responsibilities section above for the exact behavior of each; the block-creation/edit methods introduce this codebase's first "catch a `DataIntegrityViolationException`, inspect its Postgres SQLState (`23P01`), map to a domain error" pattern, via a small `extractSqlState` helper that walks the exception's cause chain for any `java.sql.SQLException`. |
| `controller.AvailabilityController` | `/api/availability/slots` (`POST`), `/api/availability/slots/me` (`GET`), `/api/availability/slots/{slotId}` (`PUT`, `DELETE`, new Milestone 7), and, as of Milestone 4, `/api/availability/sos-availability` (`PUT`, `GET`). **As of M1**, also `/api/availability/working-hours` (`GET`, `PUT`), `/api/availability/blocks` (`POST`), `/api/availability/blocks/{blockId}` (`PATCH`, `DELETE`), and `/api/availability/calendar` (`GET`, `from`/`to` bound as raw `String` query params rather than `Instant`, so a parse failure is guaranteed to surface as `400 VALIDATION_ERROR` via this class's own explicit handling rather than depending on Spring's default query-param type-conversion failure path, which this codebase's `GlobalExceptionHandler` has no dedicated handler for). |
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
- **New, M1**: `service.AvailabilityDerivationService` and
  `service.AvailabilityService#createBlock`/`updateBlock` depend directly on
  `bookings.repository.OrderRepository` (existing, unmodified interface) to read a
  professional's order history for calendar derivation and the block-vs-booking overlap
  pre-check. This is a new **availability → bookings** dependency at the repository level
  only (not on `bookings.service.BookingsService`), which does not create a circular bean
  graph even once a later milestone adds the opposite-direction `bookings.service
  .BookingsService → availability.service.AvailabilityDerivationService` dependency (`
  OrderRepository` itself has no dependency back on either service). Flagged because it's a
  new *direction* of coupling for this package — every prior dependency in this section flows
  the other way (`bookings` depending on `availability`).
- **The opposite-direction dependency anticipated above landed as of M2 (2026-08-18,
  confirmed, not merely anticipated)**: `bookings.service.BookingsService` now depends on
  `service.AvailabilityDerivationService` (constructor-injected), calling `deriveCalendar`
  (order-creation pre-check) and the new `deriveAvailableWindows` (the available-windows
  listing endpoint) — see `bookings/README.md`'s dedicated M2 section for the full writeup
  from that package's side. No circular bean graph, exactly as predicted: `AvailabilityDerivationService`
  has no dependency back on `BookingsService`.

## Data model

Owns the `availability_slots` table (see `docs/architecture/data-model.md` §2.5) and, as of
the `V13` migration, the `sos_availability` table (§2.6) — closing the schema gap flagged
in `data-model.md` §4 / `api-contract.md` §4 ahead of Milestone 4. `V11`/`V12` (Milestone 3)
only touched `orders`, unrelated to this package's tables. **As of M1 (2026-08-18)**, also
owns two new tables: `professional_working_hours` (`V25`, `data-model.md` §2.13) and
`professional_availability_blocks` (`V26`, §2.14, enables the `btree_gist` Postgres
extension for its `ck_blocks_no_overlap` exclusion constraint). `V27` (same M1 pass) adds a
new `ck_orders_no_overlap` exclusion constraint to the existing `orders` table (owned by
`bookings`, not this package) — read-only from this package's perspective via the
`OrderRepository` dependency noted above.

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
- **M1 (2026-08-18), deviations/clarifications found and resolved while implementing, all
  flagged to `pronto-lead` rather than silently guessed past:**
  - The design doc's own §4.3 prose reads "`startAt >= now()` → `400 VALIDATION_ERROR`,"
    which taken literally would reject every valid future block and accept only
    already-past ones — the exact opposite of that same sentence's own explanatory
    parenthetical ("relaxed to `>=` since blocking 'the rest of today' is a legitimate use
    case"). Implemented per the doc's own stated intent (`startAt < now()` → `400`, i.e. `>=
    now()` is valid), not its literal (self-contradicting, almost certainly a typo)
    wording.
  - The design doc's §9.5 claims "no [business-timezone] constant existed anywhere in the
    codebase before this feature" — not quite accurate: `matching
    .ApproximateDistanceEtaStrategy` already has its own *private*, separately-defined
    `ZoneId.of("Asia/Jerusalem")` constant (unrelated peak-hour-ETA concern, pre-dating this
    feature). `AvailabilityDerivationService.BUSINESS_TIMEZONE` is a **second**, independent
    definition of the same value — this milestone did not touch the out-of-scope `matching`
    package to consolidate them. Flagged as a follow-up candidate, not fixed here.
  - `weekday`'s DB column is `SMALLINT` (design §2.1); the design's own entity-shape
    prose says plain `int` (§3). Hibernate's `ddl-auto: validate` rejects an `int`
    (→ `INTEGER`)-mapped field against an actual `SMALLINT` column at startup — caught live
    during this milestone's own manual verification pass (app failed to boot). Resolved by
    storing `weekday` as `short` internally (matching this codebase's own established
    `SMALLINT` ↔ `Short`/`short` convention, `reviews.entity.Review#rating`), with `int`
    still used at every external call site (constructor param, DTOs) — no design deviation
    in shape/behavior, purely an internal storage-type correction.
  - `AvailabilityDerivationService`'s dependency on `bookings.repository.OrderRepository`
    (see "Interactions with other packages" above) is not spelled out anywhere in the design
    doc, which only describes the derivation *algorithm*, not which repository supplies the
    `orders` data it reads. Necessitated by this milestone's explicit "zero changes to
    `bookings`" constraint (a new query method on `OrderRepository` would have violated
    that) — resolved by reusing the existing, unmodified `findByProfessionalIdOrderByCreatedAtDesc`
    finder and filtering in memory, not by adding a new repository anywhere.
  - Jackson's default `LocalTime` JSON serialization includes seconds (`"08:00:00"`),
    verified live via a running instance during manual QA — doesn't match the design doc's
    own `"08:00"`-style worked examples. Fixed with `@JsonFormat(pattern = "HH:mm")` on
    `dto.WorkingHoursItem`'s two `LocalTime` fields (response-side only; the request-side
    deserializer already accepted `"08:00"` input without any annotation).

## Status

**Implemented and QA-validated through Milestone 4** (no code changes to this package in
Milestone 5 or Milestone 6 — see the note further below), plus the Milestone 7 slot
edit/delete addition (implemented this pass, unit-tested by `pronto-coding`, and **now
QA-validated** — full regression plus new targeted tests, zero gaps, see the Milestone 7
entry at the bottom of this section for the full QA summary), on branch `MS7`, per
`docs/architecture/api-contract-bookings.md` ("Milestones 3, 4, 6 & 7") and
`docs/architecture/implementation-plan.md`. **Plus, as of 2026-08-18, M1 of the professional
weekly availability calendar feature** (working hours, manual blocks, derived calendar —
see the M1 entry at the very bottom of this section for the full implementation/verification
summary). **The full M1-M6 feature (backend + frontend) is now QA-signed-off, zero known
open bugs, including a post-QA bug-fix round (also re-verified and signed off) — see the
"QA sign-off" entry at the very end of the Status section below.**

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

**M1 of the professional weekly availability calendar feature (2026-08-18, `pronto-coding`),
backend-only — implemented and manually verified by `pronto-coding`, subsequently
independently QA-signed-off (see the "QA sign-off" entry at the end of this section).**

Scope: `V25`/`V26`/`V27` migrations; `entity.ProfessionalWorkingHours`/
`entity.ProfessionalAvailabilityBlock` and their repositories; new
`service.AvailabilityDerivationService` (`deriveCalendar`); the 6 new
`service.AvailabilityService`/`controller.AvailabilityController` methods/routes
(working-hours get/put, block create/edit/delete, calendar read); two new `ErrorCode` values
(`BLOCK_OVERLAPS_EXISTING_BLOCK`, `BLOCK_OVERLAPS_BOOKING`, both `409`). Full detail in
`docs/architecture/professional-weekly-calendar-design.md` §3/§4/§5/§10 (M1). Zero changes to
`bookings`, `users.phone` (M2 scope), `availability_slots`/`sos_availability`'s existing
entities/repositories/service-methods/routes, or any other package.

**Build/test**: `mvnd clean package` — `BUILD SUCCESS`, 189/189 tests pass (26 new: 4 in the
new `AvailabilityDerivationServiceTest`, 22 added to the existing `AvailabilityServiceTest`),
zero regressions to any pre-existing test in this or any other package.

**Manual live verification against a real Postgres instance** (fresh database, all 27
migrations applied cleanly from `V1`): registered a real professional + customer via the
actual `/api/auth/*` flow, then exercised all 6 new endpoints end-to-end via `curl`:
- `GET`/`PUT /working-hours`: empty-array first-time state confirmed; full-week save/re-fetch
  round-trip confirmed; validation errors confirmed for wrong entry count, duplicate weekday,
  `enabled=true` with missing times, and `endTime <= startTime`.
- `POST`/`PATCH`/`DELETE /blocks`: happy-path create/edit/delete confirmed, including the
  calendar correctly reflecting each mutation immediately after; both overlap rejections
  (`409 BLOCK_OVERLAPS_EXISTING_BLOCK` vs. an existing block, `409 BLOCK_OVERLAPS_BOOKING`
  vs. a real `orders` row inserted directly for the test) confirmed; `endAt <= startAt` and
  past-`startAt` validation confirmed; ownership (`403 FORBIDDEN` for a different
  professional's block, on both edit and delete) and not-found (`404 NOT_FOUND`) confirmed.
  A genuine concurrent-request pair (two simultaneous overlapping `POST /blocks` calls) was
  fired to exercise the true race path — one succeeded (`201`), the other got a clean `409`
  (never a raw `500`), confirming the `23P01`-catch backstop works end-to-end alongside the
  pre-check.
- `GET /calendar`: **reproduced the design doc's exact §5/§36 worked example** (Monday
  08:00-18:00 working hours, a 12:00-13:00 block, a 15:00-16:30 `CONFIRMED` booking) and
  confirmed the exact 5-segment result byte-for-byte (`AVAILABLE 08:00-12:00`, `BLOCKED
  12:00-13:00`, `AVAILABLE 13:00-15:00`, `BOOKED 15:00-16:30` w/ `orderStatus: CONFIRMED`,
  `AVAILABLE 16:30-18:00`, all in `Asia/Jerusalem` local time, converted correctly to/from
  UTC across the DST-active `+03:00` offset); validation errors confirmed for `to <= from`
  and a >6-week span; role gate confirmed (`403 FORBIDDEN` for a `CUSTOMER`-role caller).
- **DB-level constraint verification** (direct SQL, bypassing the application): a
  manually-inserted `orders` row overlapping an existing active booking for the same
  professional was rejected by `ck_orders_no_overlap` with the exact expected Postgres error
  (`conflicting key value violates exclusion constraint`); same confirmed for
  `ck_blocks_no_overlap` against `professional_availability_blocks`.

No deviation from the design's substantive behavior was needed — the five items listed in
"Assumptions / judgment calls" above are all either typo-resolutions-per-the-doc's-own-stated-
intent, pre-existing-fact corrections, or narrow internal-implementation-detail fixes (entity
storage type, JSON serialization format), none of which change any endpoint's documented
request/response contract or business behavior.

**M2 of the professional weekly availability calendar feature (2026-08-18, `pronto-coding`),
backend-only — implemented and manually verified by `pronto-coding`, subsequently
independently QA-signed-off (see the "QA sign-off" entry at the end of this section).**

Scope, from this package's own side (the bulk of M2's work lives in `bookings`/`users`/`auth`
— see those packages' READMEs): one new public method,
`service.AvailabilityDerivationService#deriveAvailableWindows(professionalId, from, to,
minDuration)`, and one new record method, `dto.CalendarSegment#duration()`. **No entity/
repository/controller/config changes in this package** — no new migration owned here either
(`V28` adds `users.phone`, owned by `users`). Every pre-existing `availability` endpoint
(4 slot CRUD + 2 SOS-toggle + the 6 M1 calendar endpoints) is confirmed unchanged and
unaffected — manually re-verified `GET /api/availability/slots/me` and `GET
/api/availability/calendar` both still work correctly against a real running instance during
this milestone's verification pass (see below).

**Build/test**: `mvnd clean package` — `BUILD SUCCESS`, 201/201 tests pass (12 new, all in
`bookings`/`auth`, none in this package's own test files — `AvailabilityDerivationServiceTest`/
`AvailabilityServiceTest` are unchanged, 4 and 31 tests respectively, both still passing),
zero regressions.

**Manual live verification against a real Postgres instance** (fresh database, all 28
migrations applied cleanly from `V1`, including this milestone's `V28`): confirmed
`deriveAvailableWindows` end-to-end via its sole real caller, `GET /api/bookings/professionals
/{id}/available-windows?issueId=` — returned a 14-day-bounded set of windows, each
`>= 60` minutes, `defaultDurationMinutes: 60`/`timezone: "Asia/Jerusalem"` echoed correctly;
confirmed `GET /api/availability/slots/me` and `GET /api/availability/calendar` (both
untouched M1 endpoints) still function identically post-M2. Full verification detail (order
creation, the three `409 BOOKING_TIME_UNAVAILABLE` triggers, the concurrent-race test, and
`customerPhone` visibility) lives in `bookings/README.md`'s M2 section, since that's where
the bulk of M2's behavior actually lives.

**Post-QA bug-fix pass (2026-08-18) — malformed `{slotId}`/`{blockId}` returned `500`
instead of `404`:** `pronto-qa` flagged (low priority, only reachable via a malformed URL,
never possible from the real UI) that `DELETE`/`PATCH /api/availability/blocks/{id}` with a
non-numeric id returned a raw `500 INTERNAL_ERROR` instead of a `4xx`. Root cause: `blockId`
(and, latently, the pre-existing `slotId` on `PUT`/`DELETE /api/availability/slots/{slotId}`
— same bug, same file, not separately reported but fixed alongside for consistency) were
typed `@PathVariable Long`, so a malformed value threw Spring's `MethodArgumentTypeMismatchException`,
which `common.exception.GlobalExceptionHandler` has no dedicated handler for and so fell
through to its generic catch-all. Fixed by switching both to manually-parsed
`@PathVariable("...") String` + a new private `parsePathId` helper (unparsable/non-positive →
`404 NOT_FOUND` via this app's own error envelope) — the exact same pattern already
established in `issues.controller.IssuesController`/`notifications.controller
.NotificationController`/`bookings.controller.BookingsController` (see each of those classes'
own `parsePathId`), not a new pattern introduced for this fix. Live-verified against a running
backend with a real professional token: `DELETE /api/availability/blocks/abc` → `404
NOT_FOUND` (was `500`), `PATCH /api/availability/blocks/abc` → `404`, `DELETE
.../blocks/-5` → `404`, `DELETE .../slots/abc` → `404` (was latently `500` too, now fixed).
`mvnd test` — 201/201 pass, zero regressions.

**QA sign-off (2026-08-18) — full feature, M1-M6, zero known open bugs.** `pronto-qa`
independently validated this package's full M1 slice (working hours, manual blocks, the
derived calendar, both overlap-rejection codes, the exclusion-constraint race backstop, role/
ownership enforcement, and the §36 worked-example reproduction) plus the M2 consequences
recorded in `bookings/README.md`'s own M2 section, and re-verified the malformed-path-id
fix above after it landed — **full sign-off, zero known open bugs**, consistent with the
"zero known open bugs" bar every prior milestone in this project has been held to. See
`docs/architecture/implementation-plan.md`'s "Professional Weekly Availability Calendar"
entry for the consolidated M1-M6 QA record across both `backend` and `frontend`.
