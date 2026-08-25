# `matching`

## Purpose

The seam every distance and arrival-time figure in Pronto comes through — professional listing
cards, the `FASTEST` sort, SOS ranking and its geographic filter, and
`orders.expected_arrival_at`.

**Production MS2 replaced what is behind that seam entirely.** The interface survived; the
implementation, the inputs, the output shape and this document's previous claims about all three
did not.

## What this package used to say, and why it is gone

This README previously described a package that was *"pure computation only — no persistence, no
controller, no endpoint of its own"*, whose `DistanceEtaStrategy` was *"pure/stateless by
contract, no I/O"*, and whose sole implementation produced:

- 15 (later 34) minutes and 8.0 km when the professional's registered city string equalled the
  customer's;
- 40 minutes and 35.0 km otherwise;
- plus 20 or 30 minutes inside two hardcoded windows, `[08:00, 11:00)` and `[15:00, 18:00)`.

Four possible ETAs and two possible distances, for every professional and every address in the
country. **All of it is deleted.** `ApproximateDistanceEtaStrategy` and its unit test no longer
exist — not disabled, not behind a flag, not retained as a fallback. There is no configuration,
environment or failure path that can reach those numbers, because the code that produced them is
not in the repository.

The peak-hour heuristic is gone with it, and was not replaced with anything of its own: when the
routing provider supplies a traffic-aware duration the platform uses it, and when it does not the
platform reports an honest non-traffic duration rather than adding an invented adjustment on top.

## Responsibilities

- Defines **`DistanceEtaStrategy`**, still a seam and still the single thing consumers call, now
  with two methods:
  - `calculate(professionalId, destination, requestTime)` — one pair.
  - `calculateBatch(professionalIds, destination, requestTime)` — **the primary method**, and the
    reason a fifty-professional listing costs one provider request rather than fifty.
- Provides **`RoutedDistanceEtaStrategy`**, the sole implementation: real road distance and real
  driving duration, from the professional's fresh device position to geocoded destination
  coordinates, via `maps.RoutingProvider`.
- Defines **`EtaResult`**, now nullable-by-construction.
- Defines **`ServiceLocation`**, the customer's address text — no longer a routing input at all,
  only the thing that gets geocoded once on write.

## The three contract changes, and why each was necessary

**1. The origin is a live position, not a city name.**
A professional is usually coming from another job, not from home, so their registered base city
cannot produce a true arrival estimate. The strategy therefore resolves the origin *itself*, from
`professionals.service.ProfessionalLocationService`, and only when that position is fresh and
precise enough. Callers **cannot pass an origin in** — which is exactly what makes it impossible
for any call site to route from a stale fix or from a base-city centroid. `base_city_id` remains
business coverage data (which region they serve, what their card says); it is never a routing
origin.

**2. The destination is coordinates, not an address record.**
Geocoding happens once, at write time, where the address is accepted — never once per
professional card. `ServiceLocation.toPostalAddress()` is the bridge, and
`maps.service.ServiceAddressGeocoder` owns the policy.

**3. There is I/O, and the documentation says so.**
An external routing provider, an in-process cache and a database read. The implementation is a
Spring bean with dependencies, not a pure function, and its tests exercise it with a fake
provider rather than by calling a static method. The old "pure/stateless by contract" wording is
removed rather than left to mislead the next reader — it stopped being true the moment real
routing landed, and a comment that is confidently wrong is worse than no comment.

## `EtaResult` — the shape change that carries the milestone

| Before | After |
|---|---|
| `sameCity: boolean` | *(gone)* — a string comparison masquerading as geography |
| `distanceKm: BigDecimal` | `distanceKm: BigDecimal` — **nullable** |
| `baseTravelTimeMinutes: int` | *(gone)* — half of a hardcoded surcharge |
| `trafficAdjustmentMinutes: int` | *(gone)* — the other half |
| `etaMinutes: int` | `etaMinutes: Integer` — **nullable** |
| — | `trafficAware: boolean` — carried from the provider, never assumed |
| — | `unavailableReason: RouteUnavailableReason` |

An `EtaResult` is either available *with* both figures, or unavailable *with* a reason —
**enforced in the compact constructor**. That is the type-level half of MS2's central rule: a
caller that wants a number has to acknowledge the possibility that there is not one. The old
primitive `int etaMinutes` had no value meaning "unknown", which is precisely why the old
implementation had to invent 34.

## The four gates a figure passes

`RoutedDistanceEtaStrategy` produces a number only if all four hold; otherwise it produces a
reason.

1. **Destination known** — no geocoded coordinates ⇒ `DESTINATION_UNKNOWN`. Never a centroid.
2. **Origin usable** — `ProfessionalLocationService` applies the freshness and accuracy rules.
   Missing, stale or coarse ⇒ the specific reason. Never approximated.
3. **Budget** — at most `pronto.maps.max-routed-candidates` per evaluation; overflow is reported
   unavailable **and logged at WARN**, because a silent cap reads downstream as "these people
   have no GPS".
4. **Provider answered** — timeout, error or no-route ⇒ the provider's reason. Never substituted.

## Key classes

| Class | Role |
|---|---|
| `DistanceEtaStrategy` | The seam. Batch-first by design, so the N+1 failure mode is structurally hard rather than merely discouraged. |
| `RoutedDistanceEtaStrategy` | The sole implementation. Cache first, then one batched matrix call for the misses. |
| `EtaResult` | Figures **or** a reason, never both, never neither. |
| `ServiceLocation` | The customer's address text. Carried through for the snapshot and geocoded once; no longer read by any computation. |

## Interactions with other packages

- **`bookings`** — `listProfessionals` geocodes the destination once and calls `calculateBatch`
  for the whole page; `onTheWay` calls `calculate` once for the committed
  `expected_arrival_at` snapshot.
- **`sos`** — `SosMatchingService` calls `calculateBatch` for the candidate pool after the SQL
  eligibility filter, then applies the radius filter to the **real** kilometres.
- **`maps`** — supplies `RoutingProvider`, `RouteCache` and the coordinate types.
- **`professionals`** — supplies the current-position lookup and the freshness/accuracy verdict.

This package still **owns no table, no migration, no controller and no repository**. One caller
persists one derived value (`orders.expected_arrival_at`, from a single `calculate` result at the
`ON_THE_WAY` transition), exactly as it did before MS2.

## `expectedArrivalAt` is still a snapshot

Computed once, at the `ON_THE_WAY` transition, and never recomputed as the professional's GPS
moves. Live location exists to make the estimate **better before it is promised**, not to make a
promise that slides around for the rest of the journey — a countdown that jumps every thirty
seconds is worse than a slightly wrong fixed one.

It may now be `null`: if the professional has no usable position, or the address never geocoded,
or the provider is unreachable, the transition still succeeds and the column stays empty. The
alternative — refusing to let a professional start driving because a maps API is down — would let
an external dependency halt the platform's core flow.

## `FASTEST` sorting

Real driving duration ascending, **professionals with no ETA last**, professional id ascending as
a deterministic final tie-break. Base city plays no part, hidden or otherwise.

The null-handling rule is not a detail. Before MS2 every professional had a (fabricated) ETA, so
"fastest" could never be wrong about who was missing one. Now that unavailable is a real outcome,
sorting it anywhere but last would let somebody the platform cannot route win the one tab whose
entire promise is arrival speed.

`RECOMMENDED` and `CHEAPEST` are **unchanged**, and deliberately so: `RECOMMENDED` ranks on
`averageRating` then `reviewCount` and has never had a distance or ETA component, so there was
nothing here for real routing to replace; `CHEAPEST` leaves the query's `base_price ASC` ordering
alone.

## Status

Implemented in Production MS2, replacing the v1.0 approximation entirely. Tested by
`matching.RoutedDistanceEtaStrategyTest` (18 cases — the four gates, no-fake-fallback, batching,
budget truncation, cache behaviour and the contract's "every requested id is accounted for"
promise), with sorting covered in `bookings.service.BookingsServiceTest` and the geometry itself
in `maps.GeoDistanceTest`. See `docs/production-roadmap/reports/prod-MS2-report.md`.
