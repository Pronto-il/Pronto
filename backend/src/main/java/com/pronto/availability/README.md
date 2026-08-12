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

Stub only, no logic yet — implemented in **Milestone 6 (Professional dashboard)** per
`docs/architecture/implementation-plan.md`.
