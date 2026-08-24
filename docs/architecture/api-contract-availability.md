# Pronto — REST API Contract: Professional Weekly Availability Calendar (`availability` package, M1)

Status: **FINALIZED — implemented, live-verified by `pronto-coding`, and QA-signed-off**
(including a post-QA bug-fix round, re-verified). See `backend/src/main/java/com/pronto/
availability/README.md`'s "Weekly availability calendar (M1, 2026-08-18)" section and its
Status section's M1/M2 entries for the full implementation/verification record.

Written by `pronto-documentation` as part of this feature's closing documentation pass, to
give the 6 new pure-`availability` endpoints below (working hours, manual blocks, the
derived calendar) the same durable, package-scoped API-contract-doc treatment every other
package's endpoint family already has (`api-contract-bookings.md` for `bookings`+the
*legacy* `availability` slot/SOS endpoints, `api-contract-issues.md`, `api-contract-
notifications.md`, `api-contract-professionals-reviews.md` for `professionals`/`reviews`/
`favorites`/`matching`). Before this pass, these 6 endpoints were specified in full only
inside the feature's own design doc — accurate, but not this project's established
convention for where an "as-built, kept-current-forever" contract reference lives. This
doc is that reference, going forward; the design doc remains the authoritative record of
*why* each decision was made (§9's full decision log) and is not duplicated here.

**Full design/decision record**:
`docs/architecture/professional-weekly-calendar-design.md`, particularly §2 (schema), §3
(entity/repository/service/controller shapes), §4 (this doc's own source material), §5
(derivation algorithm), §6 (double-booking protection), §9 (decisions:
`Asia/Jerusalem` business timezone, the 60-minute default job duration, the
`customerPhone` visibility rule, etc.).

**Relationship to `api-contract-bookings.md`**: that doc remains the home for the *legacy*
`availability` endpoints (`POST`/`GET .../slots`+`/me`, `PUT`/`DELETE .../slots/{slotId}`,
`PUT`/`GET .../sos-availability`, §2.10/§2.11/§2.14/§2.15/§2.18/§2.19) — kept there because
they were designed/built alongside the `bookings` Milestones 3/4/7 they directly serve, and
moving them now would break that doc's own internal cross-references for no benefit. It
also owns the `bookings`-side consequences of this feature (the reworked `POST
/api/bookings/orders`, the new `GET .../available-windows` endpoint, `customerPhone` on
`OrderDetailResponse`) — see its own "Professional weekly availability calendar, M2" header
note. **This doc owns only the 6 endpoints below**, which are pure `availability`-package
CRUD/read surface with no `bookings` involvement at all.

---

## 0. Conventions (reused verbatim from `api-contract.md` §0 / `api-contract-bookings.md` §0)

| Convention | Choice |
|---|---|
| Base path | `/api/availability/*` (`availability` package). |
| Request/response bodies | JSON, `camelCase`. |
| Auth header | `Authorization: Bearer <jwt>` — every endpoint below requires auth. |
| Role | `PROFESSIONAL` only, every endpoint — enforced by `availability.config.AvailabilityWebConfig`'s existing blanket `/api/availability/**` `RoleRequiredInterceptor` registration (needed no change for this feature — every new route already falls under that wildcard). |
| Timestamps in JSON | ISO-8601 / RFC 3339 with offset for `Instant` fields (`startAt`/`endAt`/`from`/`to`/`createdAt`/`updatedAt`); `"HH:mm"` (no seconds) for `LocalTime` fields (`startTime`/`endTime`) — see §1's flagged deviation below. |
| Path-referenced ids (`{blockId}`) | Missing/unparsable/non-positive → **`404 NOT_FOUND`** (manually parsed, `AvailabilityController#parsePathId` — same convention `issues`/`notifications`/`bookings` controllers already use, and, as of the post-QA bug-fix pass below, applied consistently to `{slotId}` on the legacy slot endpoints too). |
| Professional identity | No route ever takes a `professionalId` path/query parameter — the caller's own `professionals.id` is always resolved server-side from the JWT (`ProfessionalRepository.findByUserId`), the same mechanism every existing `availability` endpoint already uses. Structurally satisfies "a professional may only view/edit their own availability data" with no per-endpoint authorization code. |
| Business timezone | **`Asia/Jerusalem`**, a single named constant (`AvailabilityDerivationService.BUSINESS_TIMEZONE`) — settled, not a per-professional/per-region setting (design §9.5). Every wall-clock `LocalTime` in `professional_working_hours` and every day-boundary computation on the calendar endpoint is interpreted in this zone; `GET /calendar`'s response echoes it explicitly (`timezone` field) so the frontend never hardcodes or guesses it. |

### 0.1 Deviation from the design doc's own literal text, confirmed correct behavior

The design doc's §4.3 prose reads "`startAt >= now()` → `400 VALIDATION_ERROR`," which read
literally would reject every valid future block. The implemented (and correct) behavior is
the opposite: `startAt < now()` → `400 VALIDATION_ERROR`, i.e. `startAt >= now()` is what's
**accepted** — matching that same sentence's own explanatory parenthetical ("relaxed to `>=`
since blocking 'the rest of today' is a legitimate use case a strict future-only rule would
awkwardly forbid"). Implemented per the doc's own stated intent, not its literal (almost
certainly typo'd) wording — see `availability/README.md`'s Assumptions section for the full
record of this and two other minor implementation-time clarifications (none of which changed
any endpoint's documented request/response contract or business behavior).

---

## 1. `GET /api/availability/working-hours`

Returns the caller's configured week.

**Response `200`:**
```json
{
  "workingHours": [
    { "weekday": 0, "enabled": true, "startTime": "08:00", "endTime": "18:00" },
    { "weekday": 1, "enabled": true, "startTime": "08:00", "endTime": "18:00" },
    { "weekday": 5, "enabled": true, "startTime": "08:00", "endTime": "13:00" },
    { "weekday": 6, "enabled": false, "startTime": null, "endTime": null }
  ]
}
```

`weekday`: `0` = Sunday … `6` = Saturday, matching `professional_working_hours.weekday`
(`data-model.md` §2.13). `workingHours` has **0-7 entries** — fewer than 7 only before a
professional has ever saved a full week (a brand-new professional gets an **empty array**,
not an error; the frontend renders the first-time working-hours setup form in that case,
`WorkingHoursForm.tsx`, not a blocking hard gate). `startTime`/`endTime` are `null` when
`enabled = false`.

**Deviation, implementation detail (not a contract change)**: Jackson's default `LocalTime`
serializer emits seconds (`"08:00:00"`), caught live during manual QA — pinned to
`@JsonFormat(pattern = "HH:mm")` on `WorkingHoursItem`'s two `LocalTime` fields so the wire
shape matches the examples above exactly.

**Status codes**: `200` · `401 UNAUTHORIZED` · `403 FORBIDDEN` (non-`PROFESSIONAL` caller).

---

## 2. `PUT /api/availability/working-hours`

Replaces the caller's entire week in one call — a full transactional upsert of all 7
weekdays, not a partial patch. Chosen over 7 separate per-weekday `PUT`s because the product
spec frames this as one coherent settings form, and a partial-week write would leave an
ill-defined state for any weekday the client forgot to send. **Never touches
`professional_availability_blocks` or `orders`** — a structural guarantee (this service
method only writes `professional_working_hours` rows) that editing working hours can never
mutate/delete a confirmed booking or corrupt manual-block history.

**Request**: array of **exactly 7 entries**, one per weekday `0`-`6`, no duplicates/gaps.
```json
{
  "workingHours": [
    { "weekday": 0, "enabled": true, "startTime": "08:00", "endTime": "18:00" },
    { "weekday": 1, "enabled": true, "startTime": "08:00", "endTime": "18:00" },
    { "weekday": 2, "enabled": true, "startTime": "08:00", "endTime": "18:00" },
    { "weekday": 3, "enabled": true, "startTime": "08:00", "endTime": "18:00" },
    { "weekday": 4, "enabled": true, "startTime": "08:00", "endTime": "18:00" },
    { "weekday": 5, "enabled": true, "startTime": "08:00", "endTime": "13:00" },
    { "weekday": 6, "enabled": false, "startTime": null, "endTime": null }
  ]
}
```

**Validation** (`400 VALIDATION_ERROR` on any failure):
- Exactly 7 entries (`@Size(min = 7, max = 7)`), `weekday` in `0`-`6`, no duplicate/missing
  weekday.
- When `enabled = true`: `startTime`/`endTime` both required, `endTime > startTime`.
- When `enabled = false`: `startTime`/`endTime` may be omitted or `null` — ignored either way.

**Response `200`**: same shape as §1, reflecting the just-saved week.

**Status codes**: `200` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

---

## 3. `POST /api/availability/blocks`

Creates a manual, temporary availability exception (personal appointment, lunch, vacation,
etc.) — never auto-generated, never represents a booking.

**Request**:
```json
{
  "startAt": "2026-08-20T12:00:00+03:00",
  "endAt": "2026-08-20T13:00:00+03:00",
  "reason": "פגישה אישית"
}
```
`reason` is optional (`VARCHAR(255)`, free text).

**Validation** (`400 VALIDATION_ERROR`): parseable ISO-8601 timestamps, `endAt > startAt`,
`startAt` not strictly in the past (see §0.1 — "the rest of today" is a valid block).

**Two-layer overlap protection** (both against the caller's **own** other blocks, and against
the caller's own active bookings — never another professional's data):
1. A fast pre-check `SELECT`: overlaps an existing `PENDING`/`CONFIRMED`/`ON_THE_WAY` order
   with a non-null `bookedEnd` → `409 BLOCK_OVERLAPS_BOOKING`; overlaps another of the
   caller's own blocks → `409 BLOCK_OVERLAPS_EXISTING_BLOCK`.
2. The DB-level `ck_blocks_no_overlap` exclusion constraint (`data-model.md` §2.14,
   `V26`, requires `btree_gist`) is the authoritative backstop for the true concurrency race
   (two simultaneous `POST /blocks` calls for overlapping ranges) — its Postgres `23P01`
   (exclusion-violation) SQLState is caught and mapped to the same `409
   BLOCK_OVERLAPS_EXISTING_BLOCK` rather than surfacing as a raw `500`. This was
   live-verified with a genuine concurrent-request pair during M1's own verification pass:
   one request succeeded (`201`), the other received a clean `409`, never a `500`.

**Response `201`:**
```json
{
  "id": 12,
  "professionalId": 43,
  "startAt": "2026-08-20T12:00:00+03:00",
  "endAt": "2026-08-20T13:00:00+03:00",
  "reason": "פגישה אישית",
  "createdAt": "2026-08-18T10:00:00Z",
  "updatedAt": "2026-08-18T10:00:00Z"
}
```

**Status codes**: `201` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`409 BLOCK_OVERLAPS_EXISTING_BLOCK` · `409 BLOCK_OVERLAPS_BOOKING`.

---

## 4. `PATCH /api/availability/blocks/{blockId}`

Full replace of `startAt`/`endAt`/`reason` — despite the `PATCH` verb, this mirrors `PUT
/api/availability/slots/{slotId}`'s existing "resend the whole editable shape" convention
in this same package, not a partial patch. Same request/validation/overlap-check shape as
§3 (excluding the block's own row from both the pre-check and, structurally, the exclusion
constraint).

**Request/Response**: identical shapes to §3's request/`201` response, except this returns
`200`.

**Authorization sequence**: load block by id → `404 NOT_FOUND` if missing → ownership check
(`professionalId` mismatch) → `403 FORBIDDEN` → validate → two-layer overlap check → write.

**Status codes**: `200` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND` · `409 BLOCK_OVERLAPS_EXISTING_BLOCK` · `409 BLOCK_OVERLAPS_BOOKING`.

---

### 4a. `GET /api/availability/blocks/{blockId}`

Reads one of the caller's own blocks. Same authorization sequence as §4 (`404 NOT_FOUND` →
`403 FORBIDDEN`), same `BlockResponse` body as §3's `201`.

**Why this exists, given §6's "no separate list-blocks endpoint" ruling**: §6's `segments` are
*derived* — the calendar clips every block to each day's working-hours window, so a block
spanning several days appears as several day-sized `BLOCKED` segments sharing one `blockId`.
That is right for rendering and wrong for editing: an editor seeded from a clicked segment
would `PATCH` a multi-day block down to the single day it was opened from. This endpoint
returns the block **row**, unclipped, and is called by the block editor before an edit. It is a
single-id read, not the range listing §6 rejects.

**Status codes**: `200` · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND`.

---

## 5. `DELETE /api/availability/blocks/{blockId}`

Same ownership check as §4 (`404 NOT_FOUND` → `403 FORBIDDEN`), then an unconditional
(within ownership) delete — no "in use" guard needed, since a block is never referenced by
any FK and can never orphan/corrupt anything else by being deleted.

**Response**: `204 No Content`, no body.

**Status codes**: `204` · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND`.

---

## 6. `GET /api/availability/calendar?from={iso}&to={iso}`

The one new consolidated "big" read endpoint this feature adds — a derived,
backend-computed view over three tables (`professional_working_hours`,
`professional_availability_blocks`, `orders`), not raw CRUD data. Justified over three
separate calls (working hours + a blocks listing + an orders listing, the latter two of
which don't otherwise exist) plus a client-side subtract-and-merge reimplementation, which
would duplicate business logic and risk drifting from the backend's own double-booking
rules over time (design §4.6). No separate "list blocks for date range" endpoint exists —
this response's `segments` array already carries every block inline.

**Request**: `from`/`to` query params, bound as raw strings (not typed `Instant`) so a
parse failure surfaces as this app's own `400 VALIDATION_ERROR` rather than Spring's
default query-param type-conversion failure path (which this codebase's
`GlobalExceptionHandler` has no dedicated handler for). Accepts either a bare
`"yyyy-MM-dd"` date (interpreted as midnight in the fixed `Asia/Jerusalem` business
timezone) or a full ISO-8601 instant. `to > from`, span capped at **6 weeks**
(`400 VALIDATION_ERROR` beyond that).

**Response `200`:**
```json
{
  "professionalId": 43,
  "from": "2026-08-16T00:00:00+03:00",
  "to": "2026-08-23T00:00:00+03:00",
  "timezone": "Asia/Jerusalem",
  "workingHours": [
    { "weekday": 0, "enabled": true, "startTime": "08:00", "endTime": "18:00" }
  ],
  "segments": [
    { "type": "AVAILABLE", "startAt": "2026-08-16T08:00:00+03:00", "endAt": "2026-08-16T12:00:00+03:00" },
    { "type": "BLOCKED", "startAt": "2026-08-16T12:00:00+03:00", "endAt": "2026-08-16T13:00:00+03:00", "blockId": 12, "reason": "פגישה אישית" },
    { "type": "AVAILABLE", "startAt": "2026-08-16T13:00:00+03:00", "endAt": "2026-08-16T15:00:00+03:00" },
    { "type": "BOOKED", "startAt": "2026-08-16T15:00:00+03:00", "endAt": "2026-08-16T16:30:00+03:00", "orderId": 900, "orderStatus": "CONFIRMED" },
    { "type": "AVAILABLE", "startAt": "2026-08-16T16:30:00+03:00", "endAt": "2026-08-16T18:00:00+03:00" }
  ]
}
```

- `workingHours` is date-independent (returned once, not per-day) — lets the frontend shade
  "outside working hours" across the whole visible week without per-day lookups.
- Time outside any `enabled` weekday's `[startTime, endTime)` window has **no segment at
  all** for that gap — not `AVAILABLE`, not editable as a block. The frontend treats an
  absence of any segment as "outside working hours."
- A `PENDING`/`CONFIRMED`/`ON_THE_WAY` order with a non-null `bookedEnd` renders as
  `type: "BOOKED"`, sub-labeled by `orderStatus`. A `PENDING` order renders as `BOOKED` too
  (not a distinct 4th visual state) — the existing rule (this same design's §1/§3.4) is
  that a `PENDING` Standard order already claims the time exclusively, so showing it as
  falsely `AVAILABLE` would be misleading (design §9.4, a documented judgment call).
  `COMPLETED` orders within `[from, to)` are also included as `BOOKED`
  (`orderStatus: "COMPLETED"`) — never subtracted from a live `AVAILABLE` interval, since a
  completed job's window is definitionally in the past.
- **SOS orders are structurally invisible to this endpoint** — always `booked_end = NULL`,
  which is exactly the condition that excludes them from both the derivation and the
  `ck_orders_no_overlap` constraint (§7 below). Accepted, documented gap (design §9.6): a
  professional mid-`ON_THE_WAY` on an SOS job shows as fully `AVAILABLE` on this calendar
  for that same window — not fixable without inventing an assumed SOS-job duration, which no
  source document provides.
- All segment boundaries are **exact, non-grid-rounded timestamps** — a `10:15`–`11:15`
  booking renders as exactly that, never snapped to a grid boundary. The "30-minute grid" is
  purely a **frontend rendering/interaction convention** (gridlines + new-block-picker
  snapping), not a property of the data (design §5).

**Status codes**: `200` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

**Verified, M1's own live QA pass**: this endpoint's exact 5-segment output was reproduced
byte-for-byte against the design doc's own §5/§36 worked example (Monday 08:00-18:00
working hours, a 12:00-13:00 block, a 15:00-16:30 `CONFIRMED` booking), including correct
`Asia/Jerusalem` ↔ UTC conversion across the `+03:00` offset.

---

## 7. Double-booking protection this feature adds (cross-reference, not owned by this doc's endpoints)

`ck_orders_no_overlap` (a Postgres partial exclusion constraint on `orders`, `V27`,
`data-model.md` §2.9) is the authoritative double-booking guard this feature introduces —
but it lives on the `orders` table (owned by `bookings`) and is exercised at order-creation
time (`POST /api/bookings/orders`, `api-contract-bookings.md` §2.4), not by any endpoint in
this doc. Listed here only for completeness/cross-reference: `AvailabilityDerivationService`
(the read-side engine behind §6 above) and this constraint together implement the same
"actual available time" model, and `AvailabilityService#createBlock`/`updateBlock`'s own
block-vs-booking pre-check (§3/§4 above) reads `orders` rows via the same
`bookings.repository.OrderRepository` dependency the derivation engine uses — see
`availability/README.md`'s Interactions section for the full cross-package dependency
record.

---

## 8. Error code taxonomy — this feature's additions

| `error.code` | HTTP | Meaning | Endpoint(s) |
|---|---|---|---|
| `BLOCK_OVERLAPS_EXISTING_BLOCK` | 409 | The requested block range overlaps another of the caller's own manual blocks (pre-check or the `ck_blocks_no_overlap` exclusion-constraint race backstop). | §3, §4 |
| `BLOCK_OVERLAPS_BOOKING` | 409 | The requested block range overlaps an existing `PENDING`/`CONFIRMED`/`ON_THE_WAY` order with a non-null `bookedEnd`. | §3, §4 |

`BOOKING_TIME_UNAVAILABLE` (409) — the order-creation-side error code this feature also
introduces — belongs to `bookings`' taxonomy (`api-contract-bookings.md` §2's error-code
table), not this doc's; listed there, not repeated here, since no endpoint in this doc
returns it.

Every other status code any endpoint above returns (`400 VALIDATION_ERROR`,
`401 UNAUTHORIZED`, `403 FORBIDDEN`, `404 NOT_FOUND`) reuses this app's existing, unchanged
taxonomy — no new code needed for any of them.

---

## 9. Migrations backing this doc's endpoints

| File | Adds |
|---|---|
| `V25__create_professional_working_hours.sql` | `professional_working_hours` (§1/§2's backing table, `data-model.md` §2.13). |
| `V26__create_professional_availability_blocks.sql` | `professional_availability_blocks` (§3/§4/§5's backing table, `data-model.md` §2.14) — also enables the `btree_gist` Postgres extension and adds `ck_blocks_no_overlap`. |

`V27` (`ck_orders_no_overlap`, on `orders`) and `V28` (`users.phone`) are **not** owned by
this package — see §7 above and `api-contract-bookings.md`/`api-contract.md` respectively.

---

## 10. Endpoints reused unmodified by this feature (cross-reference only)

- `POST`/`GET .../slots`+`/me`, `PUT`/`DELETE .../slots/{slotId}` (`api-contract-
  bookings.md` §2.10/§2.11/§2.18/§2.19) — kept, unmodified, no longer reachable from the
  professional-facing UI as of frontend M4 (design §2.4/§7.1), fully vestigial (no code path
  creates new rows) as of frontend M6, but never deleted — cheap insurance.
- `PUT`/`GET .../sos-availability` (`api-contract-bookings.md` §2.14/§2.15) — completely
  untouched by this feature, per the task brief's explicit exclusion.

---

## 11. Status

**Implemented (M1, `pronto-coding`, 2026-08-18) and QA-signed-off**, including a post-QA
bug-fix pass. Full implementation/verification record —live-verified against a real
running backend + Postgres instance, all 6 endpoints, both overlap-rejection codes, the
`ck_orders_no_overlap`/`ck_blocks_no_overlap` constraint-violation race path, role/ownership
enforcement, and the exact §36 worked-example reproduction — lives in
`backend/src/main/java/com/pronto/availability/README.md`'s Status section (the "M1 of the
professional weekly availability calendar feature" and "Post-QA bug-fix pass" entries), not
restated here. **Post-QA bug-fix pass (2026-08-18)**: malformed `{blockId}`/`{slotId}` path
values previously returned a raw `500 INTERNAL_ERROR` instead of `404 NOT_FOUND` — fixed via
the `parsePathId` convention now recorded in §0's table above, live-verified, zero
regressions (201/201 backend tests pass at the time of that fix).
