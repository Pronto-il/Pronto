# `matching`

## Purpose

Distance/ETA approximation between a professional's registered city and a customer's
service-address input, plus the "fastest" sort mode this powers on professional-listing
endpoints. Pure computation only — no persistence, no controller, no endpoint of its own.

Implements the distance/ETA design in
`docs/architecture/api-contract-professionals-reviews.md` §6, consumed by
`bookings.service.BookingsService` per §7 of that same doc.

**Scope note, load-bearing**: this package exists because a prior "ETA display is out of
v1.0 scope, permanent" ruling (`docs/architecture/data-model.md` §4) was **explicitly
overridden by direct user instruction, 2026-08-15** — see
`docs/architecture/api-contract-professionals-reviews.md` §5 for the full override record.
GPS/live-location tracking remains a separate, still-valid, permanent exclusion — nothing in
this package does real routing, live position tracking, or map integration; every figure it
produces is a coarse, documented approximation.

## Responsibilities

- Defines `DistanceEtaStrategy`, a one-method interface (`calculate(professionalCity,
  customerLocation, requestTime) -> EtaResult`) — pure/stateless by contract, no I/O, no
  persistence, so any implementation is trivially unit-testable without a Spring context or
  database.
- Provides `ApproximateDistanceEtaStrategy`, the sole v1.0 implementation, a
  `@Component` Spring bean injected into `bookings.service.BookingsService`. Deterministic:
  no randomness, no external routing/GPS/traffic-provider API call.
- The approximation model, exactly as implemented:
  - **Same city** (case-insensitive, trimmed string equality): base travel time 15 min,
    placeholder distance 8.0 km.
  - **Different city** (or either city is null/blank): base travel time 40 min, placeholder
    distance 35.0 km.
  - **Peak hours**, evaluated against `Asia/Jerusalem` local time specifically (hardcoded,
    not the JVM's default timezone — deliberate, since this is an Israel-only app and the
    server's own timezone can't be assumed to match): two half-open windows,
    `[08:00, 11:00)` and `[15:00, 18:00)`. Inside either: +20 min (same city) or +30 min
    (different city) added to the base. Outside both: +0.
  - `etaMinutes = baseTravelTimeMinutes + trafficAdjustmentMinutes`.
- A `null` professional city is treated as **different city**, never as "matches
  everywhere" — a deliberate conservative default (see "Assumptions" below).

## Key classes

| Class | Role |
|---|---|
| `DistanceEtaStrategy` | The strategy interface — allows a future, more precise implementation (e.g. a real geocoding/routing API) to be swapped in later without changing `bookings`' call site, mirroring the `StorageClient`/`AiClassificationClient` swappable-abstraction pattern already used elsewhere in this codebase. |
| `ApproximateDistanceEtaStrategy` | The sole implementation. Every numeric constant (15/40 min base, 8.0/35.0 km, +20/+30 min peak surcharge) is a `static final` field with a Javadoc explicitly labeling it an approximation/placeholder, not sourced routing data — see "Assumptions" below for which figures came from the user's own instruction vs. which were `pronto-coding`-chosen. |
| `EtaResult` | The output record — `(sameCity, distanceKm, baseTravelTimeMinutes, trafficAdjustmentMinutes, etaMinutes)`. Never persisted anywhere (no table, no migration owned by this package) — recomputed fresh on every call. |
| `ServiceLocation` | The customer-side input record — `(city, street, houseNumber, apartment)`. Only `city` is actually read by `ApproximateDistanceEtaStrategy`'s computation; the other three fields are carried through for shape-compatibility with `orders.service_*` (owned by `bookings`/`Order`, a separate, persisted concept — see this doc's package-info for the precise relationship) and for forward-compatibility with a future, more address-precise strategy implementation. |

## Interactions with other packages

- Consumed by `bookings.service.BookingsService` (constructor-injected `DistanceEtaStrategy`)
  to enrich every `bookings.dto.ProfessionalCard` on `GET /api/bookings/professionals`/
  `sos-professionals` with `sameCity`/`distanceKm`/`baseTravelTimeMinutes`/
  `trafficAdjustmentMinutes`/`etaMinutes`, and to power the `sort=FASTEST` in-memory re-sort
  (`etaMinutes` is never a database column, so no SQL `ORDER BY` could sort by it).
- Reads `professionals.city` (via the `Professional` entity/`ProfessionalCard` projection,
  not by depending on `professionals` directly — `bookings` already owns that dependency) and
  the customer-supplied `ServiceLocation` parsed from `BookingsController`'s new query
  params.
- **Owns no table, no migration, no controller, no `@Repository`** — the only package in this
  feature set (and one of very few in the whole codebase) with zero persistence surface at
  all. Not consumed by `reviews`/`favorites`/`professionals` — `favorites`' listing endpoint
  deliberately carries no distance/ETA fields at all (see `favorites/README.md`'s DTO note).

## Data model

None. This package owns no table. `EtaResult` is computed fresh on every request and never
written to any column — the persisted service-address snapshot on `orders` (`service_city`/
`service_street`/`service_house_number`/`service_apartment`, `V18`) is a structurally similar
but functionally distinct concept owned entirely by `bookings`/`Order`, not by this package
(see `docs/architecture/api-contract-professionals-reviews.md` §8 for the precise
relationship between the two).

## Assumptions / judgment calls made during implementation

- **Peak-hour windows and their surcharge minutes (`[08:00,11:00)`/`[15:00,18:00)`,
  +20/+30 min) were given directly by the user's own explicit instruction**, not invented —
  distinguished here from the base-travel-time/distance figures below, which were not.
- **Base travel times (15/40 min) and placeholder distances (8.0/35.0 km) are
  `pronto-coding`-chosen approximations**, since no source document specified exact figures
  for those — reasonable, defensible placeholders good enough to produce a stable ordering
  signal, not claimed to be accurate real-world travel estimates.
- **`Asia/Jerusalem` is hardcoded, not configurable** — no multi-region deployment exists or
  is planned for v1.0; using `ZoneId.systemDefault()` instead would have been wrong on any
  server not itself running in this timezone, so this was a deliberate, documented choice,
  not an oversight.
- **A `null`/blank professional or customer city is treated as "different city"** — the
  conservative direction to be wrong in (never silently understating distance/ETA for a
  professional whose location is genuinely unknown). See `implementation-plan.md`'s
  Milestone 8 entry and this package's parent design doc §9 for the concrete, accepted
  consequence this produces for newly-registered professionals (`professionals.city` stays
  `NULL` until they self-edit their profile — `auth.service.AuthService#register` was not
  changed by this feature set).
- **City matching is case-insensitive and trimmed**, string equality only — no fuzzy
  matching, no normalization against a fixed city list (no such reference table exists for
  cities, unlike `categories`).

## Status

Implemented and QA-signed-off (zero bugs found on functionality/security) as part of the
professional-profile/reviews/favorites/matching feature set, 2026-08-15 (branch `MS7`, not
yet committed at the time this doc was written). See
`docs/architecture/implementation-plan.md`'s Milestone 8 entry for the full QA summary and
`docs/architecture/api-contract-professionals-reviews.md` §5-§6 for the complete design
record, including the explicit ETA-scope-override this package exists to implement. Unit-
tested (`matching.ApproximateDistanceEtaStrategyTest`) — 12 cases covering every
same/different-city × peak/off-peak combination, case-insensitive/trimmed city matching,
`null`-professional-city handling, and all 6 named half-open-interval boundary times
(`08:00:00`/`10:59:59`/`11:00:00`/`15:00:00`/`17:59:59`/`18:00:00`).
