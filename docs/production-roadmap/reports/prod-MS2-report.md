# Production MS2 — Real Maps, Geocoding, Distance, ETA & Professional Live Location

**Status: DONE — all 26 Definition-of-Done lines PASS, verified against the live provider.**
**Base:** `44b91cff30e3b57c6955d10528dab5f997867d9b` (`main`, MS1 merged)
**Branch:** `main` (uncommitted working tree — nothing committed, pushed or merged)
**Date:** 2026-08-25

---

## 1. Scope

MS2 removes the placeholder distance/ETA model from the product and replaces it with real
geocoding, real driving distance, real driving duration and a backend-verified arrival check.

Delivered:

- Provider abstractions (`GeocodingProvider`, `RoutingProvider`) with a Google Maps Platform
  implementation and an offline deterministic one, plus a production startup guard.
- A professional current-location model (`professional_locations`), its API, and one authority on
  whether a position may be trusted.
- Geocoded, snapshotted service-location coordinates for customers, orders and SOS requests.
- `DistanceEtaStrategy` reimplemented on real routing; the placeholder deleted outright.
- Real distance/ETA on professional cards, real duration behind `FASTEST`, truthful degradation
  when there is no figure.
- SOS matching on fresh professional GPS and a real dispatch radius.
- `ARRIVED` as a geofence-verified order status, on both the Standard and the SOS flow.
- Privacy hardening and tests proving no raw position reaches a customer.

Not touched: authentication, AI classification, AWS deployment, pricing, the SOS lifecycle
itself, unrelated UI.

---

## 2. The behaviour that was replaced

`matching.ApproximateDistanceEtaStrategy` answered a geographic question with a string
comparison between the professional's registered city and the customer's:

| Input | Distance | Base ETA | Peak surcharge | Resulting ETA |
|---|---|---|---|---|
| Same city, off-peak | 8.0 km | 34 min | — | **34** |
| Same city, peak | 8.0 km | 34 min | +20 | **54** |
| Different city, off-peak | 35.0 km | 40 min | — | **40** |
| Different city, peak | 35.0 km | 40 min | +30 | **70** |

Peak = `[08:00, 11:00)` or `[15:00, 18:00)`, Asia/Jerusalem. Two possible distances and four
possible ETAs for every professional and every address in the country.

Consumers: professional listing cards, `FASTEST` sorting, `orders.expected_arrival_at`, SOS
ranking, the SOS geographic hard-filter, SOS offers, and the customer's tracking countdown.

**The class and its unit test are deleted** — not disabled, not flagged, not retained as a
fallback. No configuration, environment or failure path can reach those numbers, because the code
that produced them is not in the repository.

---

## 3. Provider decision — Google Maps Platform

| Criterion | Google | AWS Location Service | Weight |
|---|---|---|---|
| Israeli address geocoding, in Hebrew | Strong | Materially weaker (Esri/HERE data) | **Decisive** |
| Road routing quality in Israel | Strong | Adequate | High |
| Traffic-aware duration | Routes API `TRAFFIC_AWARE` | Limited/provider-dependent | High |
| Batch matrix routing | `computeRouteMatrix` | Route matrix calculator | High |
| Java/Spring integration | Plain JSON/HTTPS, no SDK | AWS SDK v2 (already present) | Low |
| Operational fit | One API key | IAM, already understood here | Low |
| Retention terms | **Restrictive** | More permissive | Medium |

**Rationale.** Everything downstream in this milestone — the routing origin, the SOS radius, the
arrival geofence — rests on a coordinate obtained from a Hebrew street address a customer typed.
A geocoder that resolves those less reliably does not produce a slightly worse product; it
produces one that cannot verify arrivals and cannot rank by distance. Routing quality does not
compensate for that.

**Not chosen for stack consistency.** The roadmap is explicit that "the rest of Pronto uses AWS"
must not be the reason, and it is not — the table above decided it, and AWS loses on the
criterion that matters most.

**APIs used:** Geocoding API (`maps.googleapis.com/maps/api/geocode/json`) and Routes API
(`routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix`, `travelMode: DRIVE`,
`routingPreference: TRAFFIC_AWARE`, explicit field mask). No SDK dependency added — Spring's
`RestClient` over plain JSON.

**Cost of the choice, recorded honestly.** Google Maps Platform's terms restrict retention of its
geocoding content. `pronto.maps.geocode-cache-max-age-days` (default 30) bounds how long a
persisted result is reused. **The exact terms in force must be confirmed against live provider
documentation before production launch** — implemented as a property precisely so that answer is
a config change. See §17.

---

## 4. Schema changes

Latest pre-MS2 migration was `V48`. Three new migrations, all backward-compatible, none
destructive, no already-applied migration edited.

### `V49__create_professional_locations.sql`

New table `professional_locations` — one current row per professional, `professional_id` as the
primary key, so replace/update semantics are structural rather than conventional.

| Column | Type | Notes |
|---|---|---|
| `professional_id` | `BIGINT PK` | FK → `professionals`, `ON DELETE CASCADE` |
| `latitude` / `longitude` | `NUMERIC(9,6) NOT NULL` | same precision and range CHECKs as `sos_requests` (`V34`) |
| `accuracy_meters` | `NUMERIC(8,2) NOT NULL` | `> 0 AND <= 100000` — a fix with no accuracy figure cannot be quality-checked |
| `captured_at` | `TIMESTAMPTZ NOT NULL` | device clock |
| `updated_at` | `TIMESTAMPTZ NOT NULL` | server clock, not client-controllable |

Plus `idx_professional_locations_updated_at`. Deliberately a separate table from `professionals`:
different lifecycle (a ping every few minutes would otherwise bump `professionals.updated_at`,
which the approval audit trail reads), different privacy class, different retention.

### `V50__add_service_location_coordinates.sql`

Coordinates added **beside** the human-readable address text, never instead of it.

- `users`: `default_latitude`, `default_longitude`, `default_geocode_status`,
  `default_geocoded_at`, `default_address_hash`, `default_service_city_id` (FK →
  `service_cities`, `ON DELETE SET NULL`). Constraints: range CHECKs, a status CHECK over the
  four `GeocodeStatus` values, and `ck_users_default_geocode_consistency` — `RESOLVED` and
  "coordinates present" must be the same fact.
- `orders`: `service_latitude`, `service_longitude` — the destination snapshot, with range CHECKs.
- `sos_requests`: `geocode_status` (the coordinates already existed since `V34`). Backfills
  `RESOLVED` for pre-existing rows that already carried client-supplied coordinates.
- Updated `sos_requests.latitude/longitude` column comments, which said "captured but unused".

Every column nullable — legacy rows have no coordinates and no way to acquire them without
calling a paid API for every historical row.

### `V51__alter_orders_add_arrived.sql`

- `ck_orders_status` rewritten to add `ARRIVED` (all seven pre-existing values preserved).
- `orders`: `arrived_at`, `arrival_latitude`, `arrival_longitude`, `arrival_accuracy_meters`,
  `arrival_distance_meters` — the verification evidence, with range/positivity CHECKs.
- `ck_notifications_message_type` rewritten to add `ORDER_ARRIVED` and
  `SOS_TEMPORARILY_UNAVAILABLE` (all 20 pre-existing values preserved).

---

## 5. Current-location architecture

```
professional device (browser geolocation)
        ↓  latitude / longitude / accuracyMeters / capturedAt
PUT /api/professionals/me/location        ← PROFESSIONAL-only, subject is always the caller
        ↓  validate shape → reject, never clamp
professional_locations                     ← one row, upserted; server stamps updated_at
        ↓
ProfessionalLocationService                ← THE authority on usability
        ↓  usable origin, or a RouteUnavailableReason
RoutingProvider → real distance + duration
```

**Capture strategy — foreground snapshots, not `watchPosition`.** Browsers throttle or suspend
geolocation in background tabs and stop entirely when the tab closes, so a continuous watch buys
unreliable coverage at a real battery cost and loses it exactly when the phone goes in a pocket.
The client (`useProfessionalLocationSync`) captures at: dashboard mount, tab becoming visible,
connection returning, a periodic interval while visible (sized at 40% of the server's own
freshness window, read from the response rather than hardcoded), and immediately before an
arrival claim (a separate, stricter reading).

**Freshness takes the stricter of both clocks.** `captured_at` alone is client-controlled — a
device with a fast clock could keep a half-hour-old fix looking new forever. `updated_at` alone
would treat a twenty-minute-old reading uploaded just now as current. `ProfessionalLocation#age`
returns the larger of the two ages.

**Accuracy has two bars, because there are two questions.**

| Purpose | Property | Default | Why |
|---|---|---|---|
| Routing origin | `pronto.location.max-accuracy-meters` | 500 m | Being 500 m off changes a 20-minute ETA by a minute or two. Rejects coarse wifi/IP geolocation, not honest GPS. |
| Routing freshness | `pronto.location.professional-freshness` | 10 min | A working professional moves; an hour-old fix describes a place they have left. |
| Arrival precision | `pronto.location.arrival-max-accuracy-meters` | 100 m | A fix whose error circle exceeds the geofence cannot establish presence inside it. |
| Arrival freshness | `pronto.location.arrival-max-age` | 2 min | This fix is not estimating anything — it is the whole evidence for a claim about now. |
| Arrival radius | `pronto.location.arrival-radius-meters` | 150 m | See §10. |
| Clock skew | `pronto.location.max-clock-skew` | 2 min | Modest future skew clamped; wild skew refused. |

Startup fails if `arrival-max-accuracy-meters > arrival-radius-meters`, which would make the
geofence decorative.

**Rejection, not correction.** A malformed coordinate or an implausible accuracy is a `400`,
never a clamped-and-stored value — the stored row must describe what the device actually
reported, which is the property arrival verification later depends on. A *poor but plausible* fix
(5 km accuracy) is stored and then judged unusable; a *nonsensical* one (200 km) is refused.

---

## 6. Geocoding flow

**Policy: geocode on write, persist beside the address, never re-resolve an unchanged address.**

```
address accepted (registration / profile edit / order creation / SOS activation)
        ↓  PostalAddress: city + street + houseNumber, normalised
        ↓  contentHash()  ← the change detector
GeocodingProvider.geocode()
        ↓
persisted:  coordinates + status + geocoded_at + address_hash
```

Reuse requires all three: status `RESOLVED`, the stored digest still matches the current address
text, and the result is younger than `pronto.maps.geocode-cache-max-age-days`. An address edit
changes the digest, which makes invalidation automatic rather than something every edit path must
remember — and `UsersService#updateMe` additionally clears the coordinates synchronously, so a
read landing between the edit and the re-resolve cannot route to where the customer used to live.

**A defect found and fixed during this milestone's own E2E.** The first implementation resolved
and persisted the customer's default address from inside `listProfessionals`, which runs
`@Transactional(readOnly = true)` — the mutation was silently discarded at flush, so the geocode
was paid for on *every listing request* and its result thrown away every time, with nothing
failing loudly enough to notice. Geocoding now happens only on write paths
(`AuthAccountWriter#applyCustomerRegistrationData`, `UsersService#updateMe`,
`BookingsService#createOrder`, `SosService#activate`); the read path calls
`ServiceAddressGeocoder#storedCustomerDefault`, which never calls the provider, and falls back to
a transient non-persisting resolve only when nothing usable is stored.

**Four outcomes, not two.** `FAILED` (the provider says it is not a place — terminal for that
text, retrying burns quota forever) is distinguished from `UNAVAILABLE` (the provider could not
answer — says nothing about the address, retry later). Conflating them either wastes quota
indefinitely or strands a valid customer permanently.

**Precision filtering plus correspondence checking.** `GoogleGeocodingProvider` accepts only
`ROOFTOP`/`RANGE_INTERPOLATED` geometry **and** requires the returned structured
`address_components` to correspond to the address the customer typed. The second half was added
after live validation proved geometry alone insufficient — see §15.4.

**City reconciliation.** Free-text `default_city` is matched against the `service_cities`
catalogue (hyphen/maqaf/whitespace-insensitive) and the id recorded when unambiguous. **Advisory
and never a gate** — a customer in a town outside the catalogue keeps working exactly as before.

---

## 7. Routing flow

```
candidate professional ids + destination coordinates
        ↓
[gate 1] destination known?            no → DESTINATION_UNKNOWN
[gate 2] origin usable?                no → PROFESSIONAL_LOCATION_{MISSING,STALE,INACCURATE}
[gate 3] within routing budget?        no → PROVIDER_UNAVAILABLE  (+ WARN log)
         └─ RouteCache hit?            yes → served, no provider call
[gate 4] provider answered?            no → PROVIDER_UNAVAILABLE / NO_ROUTE
        ↓
EtaResult.available(distanceKm, etaMinutes, trafficAware)
```

**Batching.** `calculateBatch` makes exactly one call into the provider abstraction regardless of
candidate count; `GoogleRoutingProvider` chunks by `pronto.maps.matrix-batch-size` (25) and
matches response elements back to caller keys by `originIndex`, never by array position.

**Caching.** In-process bounded LRU, keys quantised to ~11 m so a stationary device's GPS jitter
still hits. Two TTLs: road distance 24 h, traffic-aware duration 3 min — caching a traffic-aware
figure for hours would put back a confident number that stopped being true. Unavailable results
are never cached. **No Redis** (roadmap §16): this collapses duplicate work inside one user
interaction, which one instance's heap serves entirely.

**Timeouts.** Connect ≤ 2 s, read `pronto.maps.timeout-ms` (4 s). Both set: a read timeout alone
still lets a connect to an unreachable host hold a request thread.

**Traffic honesty.** `trafficAware` is carried from the provider, never assumed. If a provider
stops supplying traffic-aware durations the platform reports the plain one rather than adding an
invented adjustment — the old peak-hour surcharge is gone and was not replaced.

---

## 8. Normal marketplace listing

`GET /api/bookings/professionals`:

- **Fresh location** → real `distanceKm` and `etaMinutes` from a routed journey.
- **Missing / stale / imprecise location, or provider failure** → the professional **still
  appears** (being unroutable right now is no reason to hide someone bookable for next Tuesday),
  with `distanceKm: null`, `etaMinutes: null` and `etaUnavailableReason` as a stable code.

**DTO change.** `sameCity`, `baseTravelTimeMinutes` and `trafficAdjustmentMinutes` are removed —
all three were artefacts of the placeholder, and a field that is always zero is a field a client
will eventually render. Added: `etaTrafficAware`, `etaUnavailableReason`. `distanceKm`/
`etaMinutes` became nullable.

**Frontend.** `ProfessionalCard` renders `זמן הגעה לא זמין כרגע` instead of an ETA, and drops the
distance clause entirely (the service region reads fine on its own) — never `0.0 ק״מ` or
`0 דקות`. Muted styling, not error styling: nothing is broken from the customer's point of view.

**`FASTEST`** sorts by real duration ascending, unavailable ETAs **last**, professional id as a
deterministic final tie-break. No hidden base-city component. That null rule is new and load-
bearing: before MS2 every professional had a fabricated ETA, so "fastest" could never be wrong
about who was missing one.

**`RECOMMENDED` and `CHEAPEST`: audited, unchanged.** `RECOMMENDED` ranks on `averageRating` then
`reviewCount` and has never had a distance or ETA component — there was nothing for real routing
to replace. `CHEAPEST` leaves the query's `base_price ASC` ordering alone.

---

## 9. SOS

```
SOS destination (client fix, else geocoded at creation)
        ↓
SQL eligibility filter (category, approved, SOS-available, not-offered, not-busy)
        ↓
ONE batched routing call for the whole candidate pool
        ↓
professionals with no usable fresh position → EXCLUDED ENTIRELY
        ↓
real distance vs. SOS_MAX_DISPATCH_RADIUS_KM (× SOS_EXPANSION_RADIUS_MULTIPLIER^level)
        ↓
ranked, pool-capped, dispatched
```

**The stricter rule.** A professional without a sufficiently fresh usable position does not
participate in geographic SOS matching — not approximated from `base_city`, not given a neutral
ETA score, not dispatched. Deliberately harsher than the normal listing: a standard listing is
"book this person for Tuesday", where being unroutable now is irrelevant; SOS is "this person
will reach you soon", which is a claim the platform cannot make about somebody whose position it
does not know.

**The radius machinery finally means something.** `SOS_MAX_DISPATCH_RADIUS_KM=40` was compared
against a model that could only return 8 or 35 km, so it excluded nobody and
`SOS_EXPANSION_RADIUS_MULTIPLIER` was documented as inert. Both are now live against real road
distance — verified in the E2E, where a Haifa professional (115.4 km by road) was correctly
excluded from a Tel Aviv SOS. The lifecycle was **not** redesigned; only the number being compared
is now true.

**Professional-committed ETA preserved.** `sos_offers.promised_eta_minutes` is still write-once at
acceptance, `SosOfferService#updateEta` still refuses afterwards with `SOS_ETA_LOCKED`, and
nothing in MS2 recomputes it. Routing informs candidate discovery, ranking and the *initial*
estimate; the professional's own commitment remains the promise. `SosEtaImmutabilityTest` and the
29 `SosOfferServiceTest` cases still pass unchanged.

---

## 10. Arrival verification

```
professional presses הגעתי
        ↓  client takes a FRESH high-accuracy fix (maximumAge: 0)
POST /api/bookings/orders/{id}/arrived   |   POST /api/sos/requests/{id}/arrived
        ↓  { latitude, longitude, accuracyMeters, capturedAt }
maps.service.ArrivalVerifier             ← ONE rule, shared by both flows
   1. shape valid?          no → 400 VALIDATION_ERROR
   2. fix ≤ 2 min old?      no → 422 LOCATION_QUALITY_INSUFFICIENT
   3. accuracy ≤ 100 m?     no → 422 LOCATION_QUALITY_INSUFFICIENT
   4. destination known?    no → 409 ORDER_DESTINATION_UNKNOWN
   5. Haversine ≤ 150 m?    no → 422 ARRIVAL_OUT_OF_RANGE
        ↓  yes
atomic ON_THE_WAY → ARRIVED, then evidence written
```

**The backend is authoritative and the customer's coordinates never leave the server.** A design
that sent them to the client to compare locally would leak the address to anyone holding an offer
and would let any modified client claim to be anywhere — the check would be decoration.

**Haversine, not a routing call.** The question is "are they near the door", not "how far must
they drive". A routing call would be slower, cost money per arrival, fail during an outage, and
be *less* correct (road distance from a point to itself is not zero when the nearest road segment
is 40 m away).

**Why 150 m.** Professionals park where they can — routinely a street away in a dense Israeli
city; the destination coordinate is a geocoder's idea of a building, itself good to tens of
metres; a phone in a built-up street has tens of metres of error of its own. A 25 m radius would
reject honest arrivals constantly, and a professional who cannot complete a job because the
platform disbelieves them stops using the platform. 150 m accepts every genuine arrival and
still fails from home or from the previous job.

**Arrival is optional, not a toll gate.** `ON_THE_WAY → COMPLETED` remains legal, so a
professional whose device cannot get a usable fix — and every order in flight when MS2 shipped —
can still finish the job.

**No manual override.** Adding one silently would hand every professional a bypass of the check
that justifies the feature. If operations later needs one it belongs on the operator surface as
an audited exception, not as a flag on this endpoint.

**The refusal discloses nothing.** `ARRIVAL_OUT_OF_RANGE` does not report the measured distance —
a refusal that says how far off you are can be used to triangulate the address by pressing the
button from three places. The distance *is* logged and *is* persisted as evidence.

**Both flows.** The SOS `arrived` endpoint previously moved the request to `ARRIVED` on a button
press alone, because the platform had no coordinates for either party. It now runs the identical
verifier. Verifying the calm flow and trusting the urgent one would have left the guarantee where
it matters least.

---

## 11. Failure / degradation behaviour

| Failure | Behaviour |
|---|---|
| Geocoder timeout / 5xx / rate limit | `UNAVAILABLE`; retried later. Never blocks registration, profile edit or order creation. |
| Address unresolvable, or only to a centroid | `FAILED`; terminal for that text. Order still created, arrival simply not geofence-verifiable. |
| Rejected/missing API key | `MapsProviderException`, logged at ERROR. Listing degrades to "no ETA" — **never a 500 to a customer**. Also caught at startup. |
| Routing timeout / error, one candidate | Normal listing: shown without an ETA. SOS: excluded from that evaluation. |
| Routing failure, **all** SOS candidates | Request fails with `SOS_TEMPORARILY_UNAVAILABLE`, not `SOS_NO_PROFESSIONALS`. Telling a customer with a burst pipe that nobody is available, when the truth is Pronto could not measure distances, would be false and harmful. |
| SOS destination never geocoded | Same degraded path, `DESTINATION_UNKNOWN` detail. |
| Professional GPS missing / stale / imprecise | No ETA + specific reason. Listing: still shown. SOS: excluded. **Never approximated from base city.** |
| GPS permission denied | Full product minus SOS matching and verified arrival. Dashboard notice names those two consequences. |
| Provider budget exceeded | Overflow reported unavailable **and logged at WARN** — a silent cap reads downstream as "these people have no GPS". |
| `ON_THE_WAY` with no routable ETA | Transition succeeds, `expected_arrival_at` stays `null`. Refusing would let a maps outage halt the core flow. |

**No path anywhere surfaces a fabricated precise distance or ETA.** Enforced by type:
`EtaResult`/`RouteResult` cannot carry figures when unavailable.

---

## 12. Privacy

**Never exposed to a customer:** latitude, longitude, GPS accuracy, location timestamps,
professional live location, professional home address, arrival evidence coordinates.

**Exposed:** `distanceKm`, `etaMinutes` — derived answers to "how far" and "how long", not
reconstructible into "where".

- `CustomerLocationPrivacyTest` walks the actual record components of seven customer-facing DTOs
  and fails on any field whose *name* suggests raw position data. Name-based, not allow-list-based:
  a developer adding `professionalLat` will not remember to update an allow-list.
- `orders.arrival_*` columns are read by no response DTO.
- The professional's own location endpoint returns usability + reason, **no coordinates**.
- `GeoCoordinates` does not render itself in `toString()`, so a position cannot leak into an
  interpolated log line by accident. Logs carry ids and reason codes only.
- The geocoding URI (which carries the API key as a query parameter) is never logged.
- Order destination coordinates were **not** added to any DTO just because MS2 stores them.

---

## 13. Caching / batching strategy

| Interaction | Geocode calls | Routing calls |
|---|---|---|
| Book to saved default address | **0** (persisted at registration) | 1 batched (2 if > 25 candidates) |
| Book to a one-off address | 1 at order creation | 1 batched |
| Edit default address | 1, on the edit | — |
| SOS activation | 0 with a client fix, else 1 | 1 batched (pool ≤ 8, or 15 emergency) |
| SOS expansion wave | 0 | 1 batched |
| `ON_THE_WAY` | 0 | 1, or 0 on cache hit |
| `ARRIVED` | 0 | **0** (Haversine, local) |
| Re-sort a loaded listing | 0 | 0 (cache) |

Mechanisms in order: business filters in SQL first → geocode on write → batch → cache → hard cap
(`max-routed-candidates`, logged when hit).

---

## 14. Tests

### Backend

```
$ cd backend && mvn -o clean test
Tests run: 1235, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
```

7 skipped = the opt-in live-provider tests (`GoogleMapsLiveApiTest`, `@Tag("live")`) and the
pre-existing OpenAI evaluation runner.

New MS2 test classes:

| Class | Cases | Covers |
|---|---|---|
| `maps.GeoDistanceTest` | 10 | Haversine against known Israeli separations, geofence-scale accuracy, longitude convergence, antipodal stability, null endpoints |
| `maps.GeoCoordinatesTest` | 15 | Range validation, scale normalisation, `ofNullable`, no-position-in-`toString` |
| `maps.PostalAddressTest` | 12 | Normalisation, hash stability/sensitivity, field-boundary ambiguity, geocodability, query construction |
| `maps.config.MapsConfigurationTest` | 20 | Startup validation for both property objects, the geofence-vs-accuracy interlock, shipped-default sanity |
| `maps.cache.RouteCacheTest` | 12 | Key precision both ways, two TTLs, no caching of failures, LRU bound |
| `maps.fake.FakeMapsProviderTest` | 13 | The offline provider is a real function of geometry, is deterministic, and never claims traffic-awareness |
| `maps.google.GoogleAddressMatchTest` | 33 | **Address correspondence** (§15.4) — fixtures transcribed from real observed responses: the live defect, wrong street/city/number, missing route, missing street number, `partial_match`, coarse geometry, and the normalisation cases that must NOT be rejected |
| `maps.google.GoogleMapsProviderContractTest` | 32 | **Provider integration** — real client classes over HTTP against a local stub: geocode success/zero-results/centroid-rejection/quota/denied-key/timeout/5xx; route same-city/intercity/traffic flags/departure-time handling/`originIndex` matching/omitted elements/per-element failures/no-route/rate-limit/403/chunking/duration parsing |
| `maps.CustomerLocationPrivacyTest` | 11 | No raw position in any customer DTO; unavailable serialises as nulls + reason, never zeros |
| `professionals.service.ProfessionalLocationServiceTest` | 20 | Freshness boundary (at/one-second-past), both-clock rule in both directions, accuracy boundary, precedence, absence, full accounting, write validation, clock skew, replace semantics |
| `matching.RoutedDistanceEtaStrategyTest` | 18 | The four gates, **no-fake-fallback**, batching, budget truncation, cache TTLs, per-id accounting, rounding |
| `bookings.service.BookingsArrivalTest` | 17 | Inside/outside geofence, stale, imprecise, malformed, no destination, wrong status, lost race, wrong professional, non-disclosure, completion still reachable, fix recorded on rejection |
| `migration.Ms2MigrationIntegrationTest` | 22 | **DB integration** — V49–V51 against real PostgreSQL: one-row-per-professional, cascade delete, coordinate/accuracy CHECKs, geocode-consistency CHECK, legacy rows load, snapshot independence, SOS backfill, every pre-existing status/notification value preserved |

Amended: `SosMatchingServiceTest` (+7 MS2 cases — SOS exclusion, degradation vs. emptiness,
one-batched-call, real radius expansion), `BookingsServiceTest` (+3 — null-last `FASTEST`, batched
routing, null `expectedArrivalAt`), `ProviderModeStartupGuardTest` (+4 — fake-maps refusal,
missing key, combined reporting), `SosSchemaConstraintTest` (retargeted at `V51`).

### Frontend

```
$ cd frontend && npx vitest run
Test Files  4 passed (4)
     Tests  34 passed (34)

$ npx tsc -b --noEmit      # clean
$ npx oxlint src/          # 0 errors, 3 pre-existing warnings
$ npm run build            # ✓ built in 609ms
```

Vitest + Testing Library were added by this milestone — the frontend had no test runner, and MS2
introduced a permission/timeout state machine and null-rendering logic that TypeScript cannot
check and a reviewer cannot reliably eyeball.

| File | Cases | Covers |
|---|---|---|
| `shared/lib/geolocation.test.ts` | 12 | Success, denied, timeout, unavailable, unsupported, too-coarse, accuracy boundary, **settles when the browser never answers** (the infinite spinner), never rejects, arrival-vs-routing bars, `maximumAge: 0` |
| `features/booking/arrivalAction.test.ts` | 9 | Success, device failures never reach the server, each refusal gets distinct actionable Hebrew, unknown-code fallback |
| `features/professionals/ProfessionalCard.test.tsx` | 6 | Real figures, unavailable copy, **never zero**, professional still rendered, region kept, independent fields |
| `features/dashboard/LocationStatusNotice.test.tsx` | 7 | Silent when healthy, names the consequence when denied, no useless retry button, coarse-fix advice, stale explanation, unsupported browser, silent before first answer |

---

## 15. Live validation

### 15.1 Production startup guard — **verified**

```
$ PRONTO_ENVIRONMENT=production EMAIL_MODE=ses SMS_MODE=aws ... mvn spring-boot:run
Refusing to start: pronto.environment='production' with an unsafe messaging configuration.
  - pronto.maps.mode=fake (MAPS_MODE). Distances, ETAs, geocoding and the arrival geofence
    would all be computed from invented geography rather than from a real mapping provider,
    and nothing in the product would look broken. Set MAPS_MODE=google and supply MAPS_API_KEY.
```

### 15.2 Full-stack E2E through the real HTTP API — **passed on both providers**

Scripts retained under `docs/production-roadmap/reports/ms2-evidence/`. Run against a live
backend, live PostgreSQL, real registration, OTP verification, listing, booking, order transitions
and SOS activation — **twice: once on the fake provider and once on live Google** (§15.5).

The fake-provider run is reported first because it is reproducible by anyone without a credential;
the Google run below is the one that discharges roadmap §37.

**Standard flow — `ms2_standard_flow.py`, 34 checks, 0 failures.** Selected evidence:

| Check | Observed |
|---|---|
| Real distances, not 8.0/35.0 | Tel Aviv pro **8.1 km**, Haifa pro **115.4 km** |
| Real ETAs, not 34/40/54/70 | **16 min** and **186 min** |
| `FASTEST` by real duration | `[(63, 16), (64, 186)]` |
| Stale location degrades | `distanceKm: null, etaMinutes: null, etaUnavailableReason: "PROFESSIONAL_LOCATION_STALE"`, sorted last |
| Address geocoded at registration | `32.041031 / 34.806077 / RESOLVED`, city reconciled to catalogue id 43 |
| Order destination snapshotted | `32.041031\|34.806077` |
| Snapshot immutable across an address edit | unchanged after the customer "moved to Haifa" |
| `expectedArrivalAt` committed | `2026-08-25T14:27:53Z` (16 min after `ON_THE_WAY`) |
| `הגעתי` ~5 km away | **422 `ARRIVAL_OUT_OF_RANGE`**, order stayed `ON_THE_WAY` |
| `הגעתי` with 500 m accuracy at the door | **422 `LOCATION_QUALITY_INSUFFICIENT`** |
| `הגעתי` with a 10-minute-old fix at the door | **422 `LOCATION_QUALITY_INSUFFICIENT`** |
| `הגעתי` ~50 m away, 10 m accuracy, fresh | **200 `ARRIVED`**, evidence `arrival_distance_meters = 50.04` |
| Completion from `ARRIVED` | 200 `COMPLETED` |
| Privacy | no `latitude`/`longitude`/`accuracyMeters` in any customer response |

**SOS flow — `ms2_sos_flow.py`, 12 checks, 0 failures.** Selected evidence:

| Check | Observed |
|---|---|
| SOS destination geocoded at creation | `32.041031\|34.806077\|RESOLVED` |
| Offer carries real distance/ETA | `8.10 km / 16 min` — not 8.0/35.0, not 34/40/54/70 |
| 115 km professional excluded by the 40 km radius | offered ids `[63]` only |
| Stale-location professional receives **no** offer | request went `FAILED` rather than dispatching |
| No 8/35 km fallback anywhere | `count(*) where distance_km in (8.0, 35.0)` = 0 |
| Fresh position restores eligibility | same professional dispatched again at 8.10 km |

### 15.3 Live Google Maps Platform validation — **PERFORMED**

```
MAPS_LIVE_TEST=true MAPS_API_KEY=<key> mvn -o test -Dtest=GoogleMapsLiveApiTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

Confirmed against the real service:

| Assertion | Result |
|---|---|
| Geocoding API reachable, key and billing valid | OK |
| Routes API reachable | OK |
| Real Hebrew Tel Aviv address resolves inside Tel Aviv | OK |
| Real Hebrew Haifa address resolves inside Haifa | OK |
| A nonexistent address does **not** resolve | OK *(after the fix in §15.4)* |
| Same-city route returns plausible traffic-aware figures | OK |
| Intercity route substantially longer than intra-city | OK |
| Four origins answered in **one** matrix request | OK |

Observed latencies: geocode 90–580 ms, route matrix 145–312 ms for four origins.

### 15.4 Defect found by live validation, and fixed

**The first live run failed one assertion**, and it was the most important one in the file:

```
GoogleMapsLiveApiTest.anAddressThatDoesNotExistDoesNotQuietlyResolveToSomewherePlausible
  [the precision filter must reject the locality centroid Google falls back to]
  Expecting value to be false but was true
```

#### What Google actually returned

Probing the live API with the exact query
(`רחוב שלא קיים בשום מקום כלל 12345, תל אביב, ישראל`) produced `status: OK` and **three**
candidates:

| # | `formatted_address` | `location_type` | `partial_match` | `route` | `street_number` |
|---|---|---|---|---|---|
| 0 | תל אביב-יפו, ישראל | `APPROXIMATE` | `true` | — | — |
| 1 | תל אביב-יפו, ישראל | `GEOMETRIC_CENTER` | `true` | — | — |
| 2 | **ראול ולנברג 36, תל אביב-יפו** | **`ROOFTOP`** | `true` | ראול ולנברג | 36 |

#### Root cause

`firstAcceptableResult` scanned the array and returned **the first candidate whose
`geometry.location_type` was acceptable**. Candidates 0 and 1 were correctly rejected as coarse;
candidate 2 was accepted. It is a real building at rooftop precision — on a completely different
street, at a completely different number.

The implementation read only `geometry.location_type`, `geometry.location` and
`formatted_address`. It **discarded** the three fields that carry correspondence information:

- `partial_match` — Google's own statement that it reinterpreted the query. `true` on all three.
- `address_components` — the structured `route`, `street_number`, `locality`.
- `types` — `establishment`/`point_of_interest` rather than `street_address`.

The underlying misconception was treating precision as correctness. **Google's geocoder is an
interpreter, not a validator**: it silently drops tokens it cannot use and returns its best guess
for whatever remains. How precisely it located *something* says nothing about whether that thing
is what was asked for.

Impact had this reached production: a customer whose address does not exist (a typo, a made-up
street) would have had an order created with a confident destination snapshot pointing at a
stranger's building, a professional dispatched there, and an arrival geofence verified against it.

#### Calibration

Before choosing rules, the live API was probed with eight queries — legitimate addresses,
`רחוב`-prefixed variants, a real street with an impossible number, a real street in the wrong
city, and the nonsense query. Every legitimate address returned `partial_match` absent/false with
matching `route`/`street_number`/`locality`; every wrong answer failed at least one of those. Two
findings shaped the tolerances:

- Requested `אבן גבירול 69` → Google's `route` is **`שלמה אבן גבירול`** (its fuller formal name).
  Exact street equality would have rejected a legitimate address the live test itself uses.
- Requested city `תל אביב` → Google's `locality` is **`תל אביב-יפו`**.

#### Fix

New `maps.google.GoogleAddressMatch` judges every candidate against the requested
`PostalAddress`. Rules, all four in addition to the unchanged geometry filter:

| Rule | Behaviour |
|---|---|
| `partial_match == true` | **Reject.** Google's own admission it did not match the query. |
| `route` | Must be present and correspond to the requested street. |
| `locality` (or `postal_town`/`sublocality`/`admin_area_2`) | Must correspond to the requested city. |
| `street_number` | If a house number was requested: must be present (else `MISSING_STREET_NUMBER` — street-level is not a service address) and identify the same building. |

Correspondence is **token-subset in either direction** after normalisation (lower-case; Hebrew
maqaf/hyphen/dash/comma/slash as separators; gershayim/geresh/quotes/periods dropped;
street-type words such as `רחוב`/`שדרות` stripped from both sides). Subset — not fuzzy matching —
is precisely what keeps it safe: it cannot bridge two names that disagree on any token, so
`דיזנגוף` cannot match `ראול ולנברג` and `רמת גן` cannot match `רמת השרון`, while
`אבן גבירול` ⊆ `שלמה אבן גבירול` and `תל אביב` ⊆ `תל אביב-יפו` both pass.

House numbers compare on the **leading numeric run**, so `12א`, `12/4` and `12` are all building
12 — a distinction Pronto already carries in its own `apartment`/`entrance` fields — while
`12345` and `36` are not.

**Failure semantics unchanged.** A refused candidate set yields `FAILED` (the provider answered;
the address is not one — terminal for that text), never `UNAVAILABLE` (the provider could not
answer — retry later) and never a 500. The rejection reason is logged as a code; the address is
not. Live confirmation:

```
maps.geocode.rejected provider=google outcome=FAILED reason=IMPRECISE_GEOMETRY candidates=3
maps.geocode.ok       provider=google outcome=FAILED latencyMs=110
```

#### Regression tests

- **`maps.google.GoogleAddressMatchTest` (33 cases, new)** — every fixture transcribed from a real
  observed response, including the exact `ראול ולנברג 36` candidate. Covers: exact match; different
  street; different city; different house number; missing route; missing street number when one was
  requested; missing street number when none was requested (accepted); `partial_match`; absent
  `partial_match` field; `APPROXIMATE`/`GEOMETRIC_CENTER`; the impossible-house-number street-centroid
  fallback that only the geometry filter catches; malformed coordinates; and the normalisation cases
  — fuller formal street name, hyphenated municipal city name, `רחוב`/`שדרות` prefixes, whitespace/case,
  maqaf vs hyphen, gershayim — plus the negative cases the tolerance must not cross.
- **`GoogleMapsProviderContractTest` (+4 cases, and 3 fixtures corrected)** — the live defect replayed
  end to end through the real HTTP path; a real street in the wrong city; a real street with an
  impossible number; and an explicit proof that an invalid address and an unreachable provider remain
  different outcomes. The three pre-existing fixtures that asserted resolution carried **no
  `address_components` at all** — they were not faithful Google responses, which is exactly the
  omission that hid this defect; they now carry the real component sets.

None of these call Google.

### 15.5 Live-provider E2E — `MAPS_MODE=google` — **45/45 passed**

The same two scripts, re-run against a backend booted with the real Google provider and a fresh
customer (a customer's address is geocoded once, at registration, so a new one was needed to
exercise the live geocode path).

```
MAPS_MODE=google MAPS_API_KEY=<key> mvn -o spring-boot:run
MS2_CUSTOMER=ms2-cust-google@example.test python ms2_standard_flow.py   →  34 passed, 0 failed
MS2_CUSTOMER=ms2-cust-google@example.test python ms2_sos_flow.py        →  11 passed, 0 failed
```

Real figures produced by Google in that run:

| Fact | Value |
|---|---|
| `דיזנגוף 10, תל אביב` geocoded at registration | `32.073993, 34.780446` — the real Dizengoff 10 |
| Geocode status / catalogue reconciliation | `RESOLVED`, `service_cities` id 43 |
| Geocode latency | 428 ms |
| SOS offer distance / ETA (professional ~1.8 km north) | **2.70 km / 18 min**, traffic-aware |
| Route matrix, 2 origins | one request, 291 ms |
| Order destination snapshot | `32.073993 / 34.780446`, unchanged after the customer "moved" |
| `expected_arrival_at` | committed from the routed ETA |
| Verified arrival | accepted at **50.04 m**; refused at ~5 km, at 500 m accuracy, and at 10 minutes stale |
| Haifa professional (~90 km by road) | correctly outside the 40 km SOS radius, no offer |

Every assertion listed in §15.2 held identically on the real provider.

---

## 16. Known limitations

1. **Verified arrival is not fraud-proof.** It means: the server validated that a position the
   professional's device reported, within 2 minutes and with ≤ 100 m claimed accuracy, is within
   150 m of the order's immutable destination. It does **not** prove the device was telling the
   truth — browser geolocation originates on the client and can be spoofed. MS2 deliberately
   builds no device attestation, no integrity API and no spoofing detection; that is a future
   hardening milestone. What it does close is the accidental and casual case, which is the
   overwhelming majority.
2. **Web GPS is foreground-only.** No background tracking; positions are snapshots at meaningful
   moments. The architecture is reusable by a native app without backend changes.
3. **Google's retention terms bound geocode reuse** (§3, §17).
4. **The route cache is per-instance.** Multiple instances will each hold their own; the hit rate
   degrades, correctness does not.
5. **`ARRIVED` orders are not cancellable.** Cancel rules were left unchanged, so `ON_THE_WAY` is
   cancellable and `ARRIVED` is not. Defensible (the professional is at the door) but it is a
   behaviour change worth an explicit product decision.
6. **City reconciliation is exact-match after normalisation**, not fuzzy — it will leave
   `default_service_city_id` null for spellings outside the catalogue. Deliberate: fuzzy matching
   would introduce wrong matches to fix inconsistent ones.
7. **No geocoding retry job.** An `UNAVAILABLE` address is re-resolved the next time a write path
   touches it, or transiently on read. A background retry sweep was judged over-engineering for
   the current flows.
8. **Address validation is strict, deliberately.** A customer whose real address is misspelled
   enough for Google to flag `partial_match` is refused rather than silently corrected (§15.4).
   The consequence is a customer with no ETA and no geofenced arrival — visible and recoverable —
   rather than a professional dispatched to a stranger's building. If field evidence shows this
   rejecting too many real addresses, the lever is to relax the `partial_match` rule while keeping
   the component comparisons, not to relax the component comparisons.

---

## 17. Remaining operational prerequisites

**None are gating.** The three that were — obtaining a key, running the live test, and running
the live-provider E2E — are discharged (§15.3, §15.5). What remains is deployment and product
housekeeping:

1. **Confirm Google's current caching/retention terms** and set
   `MAPS_GEOCODE_CACHE_MAX_AGE_DAYS` to whatever they permit. The default of 30 is a conservative
   placeholder, not a verified figure.
2. **Set quotas and budget alerts** on the Google Cloud project, and **restrict the API key** to
   the deployment's egress. The key used for validation is a development key held in local IDE
   configuration (git-ignored); production needs its own.
3. **Set `MAPS_MODE=google` and `MAPS_API_KEY`** in the production environment — the startup guard
   refuses to boot without both.
4. **Decide the `ARRIVED`-cancellability question** (§16.5).
5. **Watch `maps.geocode.rejected` reason codes** after launch. A rise in `PARTIAL_MATCH` would be
   the signal that the strictness in §15.4 is refusing real addresses; a rise in
   `IMPRECISE_GEOMETRY` is customers typing addresses that do not exist, which is a UX prompt
   rather than a bug.

---

## 18. MS2 Gate status

| # | Definition of Done | Status | Evidence |
|---|---|---|---|
| 1 | Production no longer uses 8/35 km placeholders | **PASS** | Class deleted; E2E observed 8.1 / 115.4 km; `RoutedDistanceEtaStrategyTest.noFailurePathEverProducesTheOldPlaceholderFigures` |
| 2 | Production no longer uses 34/40/54/70 min logic | **PASS** | Peak-hour code deleted; E2E observed 16 / 186 min |
| 3 | Real Israeli service addresses are geocoded | **PASS** | Live Google resolved `דיזנגוף 10, תל אביב` to `32.073993, 34.780446` (§15.5); invalid addresses correctly refused after the §15.4 fix; `GoogleMapsLiveApiTest` 6/6 |
| 4 | Booking/order destination coordinates snapshotted | **PASS** | `V50`; E2E: snapshot written and unchanged after an address edit; `Ms2MigrationIntegrationTest` |
| 5 | Professional fresh current location supported | **PASS** | `V49`, `PUT /api/professionals/me/location`, E2E |
| 6 | Freshness and accuracy backend-validated | **PASS** | `ProfessionalLocationServiceTest` (20 boundary cases); E2E |
| 7 | Real driving distance used | **PASS** | §7; E2E |
| 8 | Real driving ETA used | **PASS** | §7; E2E |
| 9 | `Fastest` uses real duration | **PASS** | E2E `[(63,16),(64,186)]`; `BookingsServiceTest` |
| 10 | Cards handle missing routing truthfully | **PASS** | E2E null+reason; `ProfessionalCard.test.tsx` "never renders a zero" |
| 11 | SOS matching uses fresh professional GPS | **PASS** | `ms2_sos_flow.py`; `SosMatchingServiceTest` |
| 12 | SOS radius eligibility uses real distance | **PASS** | 115 km professional excluded by the 40 km radius |
| 13 | SOS does not use base-city as a fake position | **PASS** | Base city is not readable by the strategy; stale professional excluded entirely |
| 14 | Accepted SOS ETA locking intact | **PASS** | `SosEtaImmutabilityTest`, 29 `SosOfferServiceTest` cases unchanged and passing |
| 15 | `expectedArrivalAt` from a committed real estimate | **PASS** | E2E: committed 16 min; null when unroutable |
| 16 | `הגעתי` backend geofence-validated | **PASS** | `ArrivalVerifier`, both flows; E2E 422/422/422/200 |
| 17 | Stale/poor/missing GPS cannot verify arrival | **PASS** | E2E; `BookingsArrivalTest` |
| 18 | Professional coordinates not exposed to customers | **PASS** | `CustomerLocationPrivacyTest`; E2E |
| 19 | Provider failure never yields fake precise ETA | **PASS** | Type-enforced; `RoutedDistanceEtaStrategyTest`; `GoogleMapsProviderContractTest` |
| 20 | N+1 routing explosion avoided | **PASS** | §13; `everyCandidateIsRoutedInOneBatchedCall`, `manyCandidatesCostOneProviderCall` |
| 21 | Automated backend tests pass | **PASS** | 1235 / 0 failures / 7 skipped (opt-in live tests) |
| 22 | Frontend typecheck/lint/build pass | **PASS** | clean / 0 errors / built |
| 23 | Provider integration tests pass | **PASS** | 32/32 `GoogleMapsProviderContractTest` + 33/33 `GoogleAddressMatchTest` (local stub, no internet) |
| 24 | Live real-provider E2E documented | **PASS** | `GoogleMapsLiveApiTest` 6/6 (§15.3); full app E2E on `MAPS_MODE=google` 34/34 + 11/11 (§15.5) |
| 25 | MS2 report complete | **PASS** | this document |
| 26 | No unrelated regressions | **PASS** | Full suite green; only MS2-touched files changed |

**Overall: 26 PASS, 0 PARTIAL, 0 BLOCKED. MS2 is DONE.**

Live validation did not merely tick the last two boxes — it found a real correctness defect that
every layer of automated testing had missed, because every fixture in the suite had been written
from the documented response shape rather than from an observed one (§15.4). The lesson is
recorded in `GoogleAddressMatch`'s Javadoc and in the corrected contract fixtures: a provider
integration is not verified until it has been pointed at the provider.

---

## 19. Git status

- **Branch:** `main`
- **Base SHA:** `44b91cff30e3b57c6955d10528dab5f997867d9b`
- **Commits:** none — nothing committed
- **Pushed:** no
- **Merged:** no

The working tree carries all MS2 changes uncommitted, awaiting review.
