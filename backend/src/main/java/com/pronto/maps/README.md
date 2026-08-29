# `maps`

## Purpose

Real geography for Pronto: geocoding addresses to coordinates, computing real driving distance
and duration between two points, judging whether a professional's device position may be
trusted, and deciding whether somebody is physically at a customer's door.

Introduced by **Production MS2**. Before it, the platform answered "how far away is this
professional?" by comparing two city strings — 8.0 km if they matched, 35.0 km otherwise — and
every ETA in the product was one of exactly four numbers (34, 40, 54 or 70 minutes, depending on
same-city and a hardcoded peak-hour window). None of that survives.

## Responsibilities

- **Coordinates as a validated value.** `GeoCoordinates` range-checks on construction and
  normalises to the `NUMERIC(9,6)` scale every position column in the schema uses, so a value
  validated in Java and the same value read back from the database are identical.
- **Addresses as a stable, comparable thing.** `PostalAddress` normalises the three
  building-locating fields (city, street, house number — apartment/floor/entrance are excluded;
  no geocoder resolves "דירה 4") and produces a content digest. That digest is what makes
  "don't re-geocode an unchanged address" enforceable and "an address edit invalidates its
  coordinates" automatic.
- **Two provider seams.** `GeocodingProvider` and `RoutingProvider`, each with a Google
  implementation and an offline deterministic one. No provider SDK, no provider-specific type,
  and no HTTP shape escapes this package.
- **Results that cannot lie.** `RouteResult` and `GeocodeResult` are either usable *with* figures
  or unusable *without* them, enforced in the constructor. This is the type-level half of MS2's
  central rule.
- **Local proximity.** `GeoDistance` (Haversine) answers "is this person near that address" for
  the arrival geofence. It never produces a customer-facing distance.
- **Policy, as configuration.** `LocationProperties` (`pronto.location.*`) holds what Pronto
  considers a trustworthy fix and how close counts as arrived; `MapsProperties`
  (`pronto.maps.*`) holds the vendor, credential, timeouts, batch sizes and cache TTLs.
- **Orchestration.** `ServiceAddressGeocoder` decides when an address is resolved and when a
  stored result is reused; `ArrivalVerifier` is the single geofence rule both the Standard and
  the SOS flow run.

## Provider decision — Google Maps Platform

Evaluated against AWS Location Service, and chosen on the strength of the two things this
platform actually needs.

| Criterion | Google Maps Platform | AWS Location Service | Weight |
|---|---|---|---|
| Israeli address geocoding, **in Hebrew** | Strong; Hebrew street-level coverage is its best-in-class case | Depends on the chosen data provider (Esri/HERE); Hebrew street-level coverage is materially weaker | **Decisive** |
| Road routing quality in Israel | Strong | Adequate | High |
| Traffic-aware duration | Yes — Routes API `routingPreference: TRAFFIC_AWARE` | Limited/varies by provider | High |
| Batch matrix routing | Yes — `computeRouteMatrix`, the whole reason N+1 is avoidable | Yes — route matrix calculator | High |
| Java/Spring integration | Plain JSON over HTTPS; no SDK needed | AWS SDK v2 (already a dependency) | Low |
| Operational fit | One API key, one env var | IAM roles, already understood here | Low |
| Retention terms | **Restrictive — see below** | More permissive | Medium |

**Israeli Hebrew address geocoding is the criterion that decides it.** Everything downstream —
the routing origin, the SOS radius, the arrival geofence — is built on a coordinate that came
from a Hebrew street address a customer typed. A geocoder that resolves those less reliably
does not produce a slightly worse product; it produces a product that cannot verify arrivals
and cannot rank by distance, because the coordinates are missing or wrong. No amount of routing
quality compensates for that.

**Explicitly not chosen for consistency with the rest of the stack.** Pronto's storage, email and
SMS all run on AWS, and the roadmap is direct that this must not be the reason. It is not: the
table above is what decided it, and the AWS option loses on the criterion that matters most.

### The one place Google costs us something

Google Maps Platform's terms **restrict how long its geocoding content may be retained**. That is
why `pronto.maps.geocode-cache-max-age-days` exists and defaults to 30 rather than being
unbounded — coordinates are re-resolved after that horizon even when the address has not changed.

> **Operational prerequisite, not yet discharged.** The exact terms in force must be confirmed
> against live Google Maps Platform documentation before production launch, and the property
> adjusted to whatever the contract actually permits. This has been implemented as a
> configurable bound precisely so that answer is a config change rather than a code change.

## Key classes

| Class | Role |
|---|---|
| `GeoCoordinates` | The only coordinate type. Validated on construction, so no downstream code re-checks a range. Deliberately does **not** render itself in `toString()` — a professional's position must not leak into interpolated log lines by accident. |
| `PostalAddress` | The geocodable subset of an address, plus `contentHash()` — the change detector behind geocode reuse and invalidation. |
| `GeoDistance` | Haversine, metres. Used for the arrival geofence and nothing customer-facing. Genuinely pure, unlike the pre-MS2 `DistanceEtaStrategy` Javadoc's claim about itself. |
| `GeocodingProvider` / `GeocodeResult` / `GeocodeStatus` | Address → coordinates. Four distinguishable outcomes, because `FAILED` (never retry this text) and `UNAVAILABLE` (retry later) call for opposite behaviour. |
| `RoutingProvider` / `RouteResult` / `RouteUnavailableReason` | Coordinates → real distance and duration. Batch-first: `routeMatrix` is the primary method and `route` is the single-pair convenience, not a shortcut around it. |
| `google.GoogleGeocodingProvider` | Geocoding API. **Rejects anything coarser than `ROOFTOP`/`RANGE_INTERPOLATED`** — accepting a locality centroid would reintroduce exactly the fake precision MS2 removes. |
| `google.GoogleRoutingProvider` | Routes API `computeRouteMatrix`, traffic-aware. Matches response elements back to caller keys by `originIndex`, never by array position. |
| `fake.FakeGeocodingProvider` / `fake.FakeRoutingProvider` | Offline, deterministic, non-production only. Real city anchors plus a hash-derived intra-city offset; straight-line distance × a road-winding factor ÷ an average speed. **Genuinely a function of real geometry**, which is what makes the ordering/radius tests elsewhere meaningful — and it reports `trafficAware=false`, so nothing can mistake it for the real thing. |
| `cache.RouteCache` | Bounded in-process LRU with two TTLs — long for road distance, short for traffic-aware duration. Never caches an unavailable result. |
| `config.MapsProperties` | The vendor: mode, key, region, timeouts, batch size, routing budget, cache TTLs. |
| `config.LocationProperties` | The product rules: freshness, accuracy floors, arrival radius, clock-skew tolerance. |
| `service.ServiceAddressGeocoder` | When an address is geocoded and when a stored answer is reused. Also the advisory `service_cities` reconciliation. |
| `service.ArrivalVerifier` | The single geofence rule, shared by `bookings` and `sos`. |

## Interactions with other packages

- **`matching`** implements `DistanceEtaStrategy` (`RoutedDistanceEtaStrategy`) on top of
  `RoutingProvider` + `RouteCache` + `ProfessionalLocationService`. Every distance/ETA figure in
  the product comes through that one seam.
- **`professionals`** owns `professional_locations` (the table, entity and repository) and
  `ProfessionalLocationService`, which applies this package's `LocationProperties` rules. The
  position belongs to the professional; the rules for judging it live here.
- **`bookings`** geocodes and snapshots an order's destination at creation, and calls
  `ArrivalVerifier` on the `ON_THE_WAY -> ARRIVED` transition.
- **`sos`** geocodes the SOS destination at creation, routes candidates in one batch, filters on
  real distance, and calls the same `ArrivalVerifier`.
- **`locations`** supplies the `service_cities` catalogue that `ServiceAddressGeocoder`
  reconciles free-text city names against — advisory only, never a gate.
- **`auth.config.ProviderModeStartupGuard`** refuses to start a Production-like environment on
  the fake provider, or a real provider with no API key.

## Data model

**This package owns no table.** The columns MS2 added live with their owners:

| Table | Columns | Migration |
|---|---|---|
| `professional_locations` | the whole table — one current row per professional | `V49` |
| `users` | `default_latitude/longitude`, `default_geocode_status`, `default_geocoded_at`, `default_address_hash`, `default_service_city_id` | `V50` |
| `orders` | `service_latitude/longitude` (destination snapshot); `arrived_at`, `arrival_latitude/longitude`, `arrival_accuracy_meters`, `arrival_distance_meters` (evidence) | `V50`, `V51` |
| `sos_requests` | `geocode_status` (`latitude`/`longitude` already existed, `V34`) | `V50` |

Every one of them is nullable, and that is load-bearing: legacy rows have no coordinates and no
way to acquire them without calling a paid API for every historical row.

## Call budget — how N+1 is avoided

Measured per user interaction, with the shipped defaults.

| Interaction | Geocoding calls | Routing calls |
|---|---|---|
| Customer books to their **saved default address** (the common case) | **0** — coordinates are already on the `users` row and still match the address digest | 1 batched matrix call per listing (2 if more than 25 candidates survive the business filters) |
| Customer types a **one-off address** | 1, at listing time; 1 more at order creation if they proceed | same as above |
| Customer **edits** their default address | 1, on the next read after the edit (the digest stops matching) | — |
| SOS activation | 0 if the client supplied a device fix, else 1 | 1 batched call for the whole candidate pool (≤ 8, or 15 for an emergency) |
| SOS expansion wave | 0 | 1 batched call |
| Professional marks `ON_THE_WAY` | 0 | 1, or 0 on a cache hit |
| Professional marks `ARRIVED` | 0 | **0** — Haversine, locally |
| Re-sorting a listing already loaded | 0 | 0 — served from `RouteCache` |

The mechanisms, in the order they apply:

1. **Business filters run first.** Category, marketplace eligibility, SOS availability,
   already-offered and busy exclusions all narrow the set in SQL before anything is routed.
2. **Geocode on write, not on read.** An address is resolved when it is accepted, not once per
   professional card.
3. **Batch.** `calculateBatch` is what listing and SOS matching call; `RoutedDistanceEtaStrategy`
   makes exactly one call into the provider abstraction regardless of candidate count, and the
   provider chunks by `pronto.maps.matrix-batch-size`.
4. **Cache.** Origin/destination pairs quantised to ~11 m, so a stationary device's GPS jitter
   still hits.
5. **A hard cap.** `pronto.maps.max-routed-candidates` bounds one evaluation, and the overflow is
   logged at WARN rather than silently dropped — a silent cap reads downstream as "these people
   have no GPS", which is a completely different operational problem.

## Address validation — selected places, not typed text

Added after MS2, for the beta. Until it, every service address in the platform was free text: a
customer could type any street, house number and city, in any combination, including one that does
not exist. That text drives matching, distance, ETA, coverage, SOS dispatch and the door a
professional knocks on, so an address that resolves to nowhere is not a data-quality nit — it is a
job nobody can arrive at.

**The distinction this adds** is not "where is this text?" — MS2's geocoder already answered that,
for any string you gave it. It is **"did a human pick this from a list of places that exist?"** A
place id only exists because Google returned that place as a suggestion and somebody chose it.

| Piece | Role |
|---|---|
| `SelectedPlace` | The value type: place id + formatted address + validated coordinates. Never built from request fields directly. |
| `service.SelectedPlaceValidator` | The only constructor. Structural checks, **no provider call**: all-or-nothing, coordinates in range, inside the Israel service-area box. |
| `service.ServiceAddressGeocoder#applyCustomerDefaultFromSelectedPlace` | Adopts a selection as a customer's stored resolution, writing the same V50 digest a geocode would. |
| `V55` | `default_place_id`/`default_formatted_address` on `users`, `service_place_id`/`service_formatted_address` on `orders` and `sos_requests`. All nullable, append-only. |

### Where a selection is required, and where it is not

| Flow | Rule | Why |
|---|---|---|
| Customer registration | **Not collected** (required *if* sent) | Address-flow redesign: registration no longer asks for one at all, because an address is a property of a job rather than of an account. `customer.defaultAddress` remains an accepted optional field for the seed/demo paths, and a supplied one is held to the full rule. |
| `PUT /api/users/me` (profile address edit) | **Required when supplied**, optional to supply | Editing the saved address *is* the moment a legacy row is expected to become validated. Omitting it leaves the stored address untouched — necessary now that a customer may have none. |
| `PUT /api/users/me/default-address` ("הפוך את זה לכתובת הבית") | **Required** | Same act as the profile edit, reached from the booking flow. |
| Booking / SOS — "another address" | **Required** | New text nobody has confirmed. This is the case the feature exists for. |
| Booking / SOS — the caller's **own saved default address** | **Grandfathered** | It may predate the feature. Stopping an existing customer mid-booking — or mid-SOS — to re-enter an address that has been working is a self-inflicted outage. |

The grandfathering test is `PostalAddress#contentHash()` equality against the caller's own
`users.default_*`. It cannot be used to smuggle unvalidated text through, because the only text it
admits is text already saved on the caller's own row.

### Provider call budget — this feature *removes* calls

| Interaction | Before | After |
|---|---|---|
| Customer registration | 1 geocode | **0** — no address is collected at all |
| Profile address edit | 1 geocode | **0** |
| Booking to a newly selected address | 1 geocode | **0** |
| Booking to a grandfathered legacy default | 1 geocode (cached by digest) | unchanged |

The frontend pays for autocomplete instead, on its own browser-restricted key, billed per session
rather than per keystroke. As of the address-flow redesign it pays for a little more of it: the
address form asks Google three questions rather than one — cities, then streets within the chosen
city, then a confirmation of the complete city + street + house number — all inside a single
autocomplete session token, and all on Places API (New). The confirmation step deliberately does
**not** reach for the Geocoding API: that is on the *backend's* key, and calling it from a browser
would either fail the key restriction or force that restriction to be widened.

### House numbers are digits only

`HouseNumbers` holds the rule and the places that enforce it. City and street are chosen from
Google's lists, so a customer cannot invent one; the house number is typed by hand, which makes it
the only part of an address constrained by a rule rather than by an act of selection — and the part
that decides which door somebody knocks on.

`12א` and `12/3` are real Israeli spellings and are refused anyway: the numeric part locates the
building, and everything after it describes which dwelling *inside* it, which is what `apartment`,
`floor`, `entrance` and the access notes are for. A house number that can hold arbitrary text
becomes a second address line, and that text flows into the geocoding query.

Enforced on `auth.dto.DefaultAddressRequest`, `users.dto.CustomerAddressRequest`,
`bookings.dto.CreateOrderRequest` and — by hand, since query params are not `@Valid`-bound —
`BookingsController`'s professional-listing route. **Not** on `sos.dto.CreateSosRequestRequest`,
deliberately: refusing to summon help over the spelling of a house number is a worse outcome than
accepting the spelling. No existing row is rewritten and no read is gated; a stored `12א` is still
displayed, geocoded and delivered to, it simply cannot be re-submitted unchanged.

### Apartment, floor and entrance are shaped too

`AddressAccessFields` is the same idea applied to the three optional "how do I get in" fields.
They are typed rather than selected for the same reason the house number is — no geocoder resolves
"דירה 4, קומה 2" — and left as 20 characters of free text they become a second address line: where
the `12א` a house number just refused reappears, and where a sentence lands in a field the
professional's app renders as a two-character chip.

- **apartment** — digits only
- **floor** — digits only
- **entrance** — at most two characters, each a letter of any script (`\p{L}`, so `א`/`ב`/`ג`, not
  just `A`/`B`/`C`) or an ASCII digit; no spaces, no punctuation

All three stay **optional** — every pattern admits the empty string, so omitting them is unaffected.

**A negative floor is deliberately not accepted.** `-1` for a basement is a real spelling, and it is
refused for the reason `12א` is: nothing here ever intentionally supported it (the column was
`varchar(20)` with no rule, which took `-1` exactly as it took `"ליד המעלית"`), so there is no
behaviour to preserve, and digits-only was the rule decided on. A basement is described in
`addressNotes`, the free-text field that exists for what a structured field cannot hold. Reversing
that decision is one pattern here plus one sanitizer in `frontend/.../addressTypes.ts`.

Enforced on `auth.dto.DefaultAddressRequest`, `users.dto.CustomerAddressRequest` and
`bookings.dto.CreateOrderRequest`. **Not** on `sos.dto.CreateSosRequestRequest`, matching
`HouseNumbers`' omission and for the same reason. No existing row is rewritten and no read is
gated.

### Two keys, deliberately

`MAPS_API_KEY` (backend, Secrets Manager, Geocoding + Routes) and
`VITE_GOOGLE_MAPS_BROWSER_KEY` (frontend, inlined into the bundle, Places only, restricted by HTTP
referrer to Pronto's own origins). **The backend key must never reach a bundle.** The frontend key
is public by construction; the referrer + API restriction is what makes that acceptable, not
secrecy. `vite.config.ts` refuses to build a production bundle without it, because a bundle without
it renders an autocomplete that never returns a suggestion — silently blocking every registration.

### What this does NOT claim

`SelectedPlaceValidator` does not call Google to confirm a place id, and deliberately so: it would
cost a request per write to re-derive what the client already resolved, and a caller willing to
fabricate a payload can fabricate a *real* place id just as easily. **The residual is that a
determined client can submit the identity of a real place other than the one they mean to be at.**
That is accepted: the address is the customer's own destination, so the only people misled are the
customer and the professional they summoned, and no privilege boundary is crossed. What is closed
completely is the ordinary case — an address that does not exist, entered by mistake or by a client
that never ran the selection flow.

### Backward compatibility

Nothing is backfilled and no existing row is rewritten. `NULL` place id means "saved before address
validation", which is true, and is handled everywhere rather than treated as corrupt. Existing
customers keep booking; their address gains a place id the next time they edit it. Orders are
snapshots and stay exactly as written.

## Privacy

**A professional's live position is never exposed to a customer.** Customers receive derived
values only — `distanceKm` and `etaMinutes` — which answer "how far" and "how long" without
being reconstructible into "where".

- No customer-facing DTO carries a latitude, longitude, accuracy figure or location timestamp.
  Enforced by `maps.CustomerLocationPrivacyTest`, which walks the actual record components of
  every customer-facing DTO and fails on any field whose *name* suggests raw position data —
  name-based rather than allow-list-based, because a future developer adding `professionalLat`
  will not remember to update an allow-list.
- The arrival evidence columns on `orders` (the professional's own position at the moment of the
  claim) are read by no response DTO at all.
- An out-of-range arrival refusal deliberately does **not** report the measured distance. A
  refusal that says how far off you are is a refusal that can be used to triangulate the
  customer's address by pressing the button from three places.
- The professional's own `GET/PUT /api/professionals/me/location` returns their usability status
  and the reason — but no coordinates. The client already knows where it is; returning them would
  add nothing while creating a response shape a later change could widen.
- Logs carry ids and reason codes, never positions. `GeoCoordinates` does not render itself in
  `toString()` for exactly this reason, and the Google geocoding URI (which carries the API key
  as a query parameter) is never logged.

## Failure policy

| Situation | Behaviour |
|---|---|
| Geocoder timeout / 5xx / rate limit | `UNAVAILABLE`. Says nothing about the address; retried later. Never blocks order creation. |
| Address not resolvable, or only to a centroid | `FAILED`. Terminal for that exact text — retrying spends quota to receive the same answer. Only an edit (which changes the digest) resets it. |
| Rejected API key / malformed request | `MapsProviderException` — a deployment fault, logged at ERROR. **Still never surfaces as a 500 to a customer**: the listing degrades to "no ETA" and stays usable. |
| Routing timeout / error, one candidate | That candidate has no ETA. In a normal listing they still appear; in SOS they are excluded from this evaluation. |
| Routing failure, **every** candidate in an SOS wave | The request fails with `SOS_TEMPORARILY_UNAVAILABLE`, **not** `SOS_NO_PROFESSIONALS`. Telling a customer with a burst pipe that no plumber is available, when the truth is that Pronto could not measure how far away the available plumbers are, would be false and actively harmful. |
| Professional GPS missing / stale / imprecise | No ETA, with the specific reason. Normal listing: still shown. SOS: excluded. **Never approximated from their base city.** |
| GPS permission denied | The professional keeps full use of the product minus SOS matching and verified arrival. The dashboard notice names those two consequences rather than saying "location is disabled". |

## Web GPS limitations

This is a web application, and the design is built around that rather than pretending otherwise.

- **Foreground snapshots, not `watchPosition`.** Browsers throttle or suspend timers and
  geolocation in background tabs and stop entirely when the tab closes, so a continuous watch
  buys unreliable coverage at a real battery cost — and loses it exactly when the professional
  pockets their phone. Positions are captured at meaningful moments instead: dashboard mount, tab
  becoming visible, connection returning, a modest visible-tab interval, and immediately before
  an arrival claim.
- **The freshness window is sized for that.** Ten minutes is comfortably longer than the client's
  own refresh cadence, so an ordinary active session never goes stale.
- **This architecture is reusable by a native app.** A mobile milestone can add background or
  higher-frequency updates by writing to the same endpoint; no backend redesign is implied. No
  native-specific infrastructure is built now.

## Anti-spoofing: what "verified arrival" does and does not mean

**It means:** the server validated that a position the professional's device reported — within
the last two minutes, with a device-reported accuracy of 100 m or better — is within 150 m of the
order's immutable destination snapshot, and recorded the measured distance as evidence.

**It does not mean the device was telling the truth.** Browser geolocation originates on the
client and can be spoofed by a determined user with developer tools or a modified browser. MS2
makes no claim to be fraud-proof and deliberately builds **no device attestation, no
integrity-check API integration and no GPS-spoofing detection** — that is a future hardening
milestone if operational evidence ever justifies it.

What MS2 does buy is real: a professional cannot mark arrival from home, from the previous job,
or from a train, without deliberately falsifying their device's position. That closes the
accidental and the casual case, which is the overwhelming majority of it.

## Assumptions / judgment calls

- **The arrival radius is 150 m, not 25 m.** Chosen from what actually happens rather than what
  would be tidy: professionals park where they can (routinely a street away in a dense Israeli
  city), the destination coordinate is a geocoder's idea of a building, and a phone in a built-up
  street has tens of metres of error of its own. A tight radius would reject honest arrivals
  constantly, and a professional who cannot complete a job because the platform disbelieves them
  stops using the platform.
- **Arrival is optional, not a toll gate.** `ON_THE_WAY -> COMPLETED` remains legal. A
  professional whose device cannot get a usable fix must still be able to finish the job, and
  every order in flight when MS2 shipped predates the whole mechanism.
- **Routing tolerates a 500 m fix; arrival tolerates 100 m.** Different questions: "roughly where
  are you" versus "are you at this door". Startup fails if the arrival tolerance is ever
  configured looser than the geofence radius, which would make the geofence decorative.
- **Freshness takes the stricter of device and server timestamps.** Neither alone is enough: the
  device clock is client-controlled, and the server timestamp would treat a twenty-minute-old
  reading uploaded just now as current.
- **The `service_cities` reconciliation is advisory and never gates anything.** A customer in a
  town outside the catalogue keeps working exactly as before. Making it a requirement would break
  booking for real customers in order to tidy a reference table.
- **No Redis.** The route cache collapses duplicate work inside and around a single user
  interaction, which one instance's heap serves entirely. A cross-instance cache would buy a
  marginally better hit rate for a new piece of production infrastructure to run and secure.
- **The old `ApproximateDistanceEtaStrategy` was deleted, not kept as a fallback.** There is no
  configuration, environment or failure path that reaches 8/35 km or 34/40/54/70 minutes, because
  the code that produced them no longer exists — a stronger guarantee than a flag defaulting the
  right way.

## Status

Implemented in Production MS2. See
`docs/production-roadmap/reports/prod-MS2-report.md` for the full record, including test results,
live-validation evidence and the remaining operational prerequisites.
