# `availability`

## Purpose

`AvailabilitySlots`/`sos_availability` management for professionals. Still a stub for
`AvailabilitySlots` as of this commit (Milestone 3's job) — this commit only adds the
`sos_availability` live-toggle table's entity/repository, as a schema-gap fix done ahead of
Milestone 3/4.

## Responsibilities

- `availability_slots`: not yet implemented in this package (no entity/repository/
  controller). The table exists (`V5__create_availability_slots.sql`), scoped to Standard
  advance-booking scheduling per the decided design (`docs/architecture/data-model.md`
  §2.5) — Milestone 3's job to build the actual CRUD/endpoints.
- `sos_availability`: **implemented as of this commit.** A live on/off toggle a professional
  flips to signal "currently available for urgent work," structurally separate from
  `availability_slots` (not a query variant of it — see §2.6/§3 item 5's explicit rationale
  for rejecting that approach). One row per professional, created at registration time
  (`auth.service.AuthService#register`) defaulting to `isAvailable = false`, so a future
  SOS-matching query needs no NULL-handling for professionals who've never toggled it. The
  toggle/listing endpoints themselves are Milestone 4 (SOS booking flow) scope — this commit
  only lays down the table + entity + the registration-time row insert.

## Key classes

| Class | Role |
|---|---|
| `entity.SosAvailability` | JPA entity for `sos_availability` (§2.6). `professionalId` is the PK itself (1 row per professional, no surrogate `id`). Always starts `isAvailable = false`. |
| `repository.SosAvailabilityRepository` | Plain `JpaRepository`, no custom methods yet — `auth.service.AuthService#register` is the only current caller (inserts the initial row). Milestone 4 adds the toggle/listing queries. |

## Interactions with other packages

- Depends on `professionals` (`professional_id` FK).
- `SosAvailabilityRepository` is depended on by `auth.service.AuthService#register`, which
  inserts the initial row for every newly registered professional.
- Will be consumed by `bookings`/Milestone 4 for SOS live-availability matching once the
  toggle/listing endpoints are built.

## Data model

Owns the `availability_slots` table (stub, see `docs/architecture/data-model.md` §2.5, not
yet implemented in this package) and, as of `V13__create_sos_availability.sql`, the
`sos_availability` table (§2.6).

## Status

`sos_availability` **implemented and live-verified** (2026-08-13, pre-Milestone-4
schema-gap fix): booted against real Postgres, confirmed `V13` applies cleanly, registered a
test professional via the real API, and confirmed the `sos_availability` row landed
correctly (`is_available = f`). `availability_slots` remains a stub — Milestone 3's job to
build the actual entity/repository/endpoints.
