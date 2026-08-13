# `availability`

## Purpose

`AvailabilitySlots` management for professionals.

## Responsibilities

- Owns the `AvailabilitySlot` JPA entity mapped to `availability_slots`.
- CRUD for a professional's bookable time windows (Standard scheduling).
- Serves the "currently available" query used by SOS matching (a slot whose
  `[start_time, end_time)` window contains `NOW()` and `is_available = true`) — see
  `docs/architecture/data-model.md` §2.5 for the single-table design rationale.

## Key classes

None yet — stub package (`package-info.java` only).

## Interactions with other packages

- Depends on `professionals` (`professional_id` FK).
- Consumed by `bookings` for both Standard slot selection and SOS live-availability
  matching.

## Data model

Owns the `availability_slots` table (see `docs/architecture/data-model.md` §2.5).

## Status

Stub only, no logic yet. **Milestone 3 slice approved and design-finalized, 2026-08-13**
(`pronto-lead`) — a deliberately narrow, read-focused piece is pulled forward into
**Milestone 3**: the `AvailabilitySlot` JPA entity + repository, plus exactly two
endpoints, `POST /api/availability/slots` and `GET /api/availability/slots/me` (full
contract in `docs/architecture/api-contract-bookings.md` §2.10/§2.11), so `bookings`'
Standard-path endpoints (§2.3/§2.4 of that doc) have real slot rows to book against. This
slice explicitly does **not** include edit/delete/toggle actions or any UI. Full CRUD, the
rest of the calendar-management surface, and the dashboard UI remain **Milestone 6
(Professional dashboard)**'s job, per `docs/architecture/implementation-plan.md` — this
status line only narrows what's pulled forward into M3, it doesn't change M6's scope.

(The SOS-availability content elsewhere in this file — the single-table design described
in "Responsibilities"/"Data model" above — is a separate, pre-existing gap between this
stub and the decided `sos_availability`-split design in `data-model.md` §2.6/§3 item 5 and
`overview.md` §4; out of scope for this Milestone 3 update and intentionally left
untouched here — see `overview.md` §6 for that tracked gap, owned by Milestone 4/6.)
