# `com.pronto.locations`

**Purpose.** The canonical, closed list of Israeli service regions and the cities inside them,
plus the validator that decides whether a given coverage selection is legal. Introduced by MS4
Part A, which replaced professionals' free-text service area with controlled values.

## Responsibilities

- Own the two reference tables `service_regions` and `service_cities` (seeded by
  `V43__create_service_regions_and_cities.sql`; the application never writes to either).
- Expose them, in one payload, on `GET /api/service-areas` — public, because professional
  registration needs the list before an account exists.
- Enforce the coverage rules the schema cannot: a city must sit inside the declared region, and
  a professional's base city must be one of the cities they actually serve.

## Key classes

| Class | What it is |
| --- | --- |
| `entity/ServiceRegion` | Read-only mapping of `service_regions`. Migrations are the only writer, which is what makes its `id` safe to store on `professionals.service_region_id`. |
| `entity/ServiceCity` | Read-only mapping of `service_cities`. `regionId` **is** the region→city filter. |
| `repository/ServiceRegionRepository` / `ServiceCityRepository` | Catalogue reads, in `display_order`. `findByRegionIdOrderByDisplayOrderAsc` is index-anchored on `idx_service_cities_region`. |
| `controller/ServiceAreasController` | `GET /api/service-areas` — regions with nested cities, two queries joined in memory (7 regions × ~96 cities is trivial volume). |
| `service/ServiceCoverageValidator` | The single enforcement point for a coverage selection. Shared verbatim by registration (`auth.service.AuthService`) and the profile edit (`professionals.service.ProfessionalsService`). |
| `dto/ServiceRegionResponse`, `dto/ServiceCityResponse` | Wire shapes for the endpoint. |

## Interactions

- **`professionals`** — `Professional.serviceRegionId`/`baseCityId` are FKs into these tables, and
  `professional_service_cities` joins a professional to many `service_cities`.
  `professionals.service.ProfessionalCoverageService` is the only reader/writer of that relation
  and delegates validation here.
- **`auth`** — registration validates its nested `professional` payload through
  `ServiceCoverageValidator` before writing any row.
- **`bookings` / `sos`** — their listing queries `LEFT JOIN` these tables to resolve the Hebrew
  labels a card shows. Left joins, deliberately: `V44` leaves both ids null on any pre-MS4 row
  whose free text named no recognisable place, and dropping those professionals out of the
  listing would be a de-listing the migration explicitly refused to perform.
- **`matching`** — `ApproximateDistanceEtaStrategy` compares the professional's base-city *name*
  against the customer's typed service city. Customer addresses are still free text (they are
  arbitrary street addresses, not a closed set), so this stays a string comparison.

## Assumptions

- **Israel only, one language.** Regions and cities carry a Hebrew display name and an English
  gloss; there is no localisation mechanism and none is planned for v1.0.
- **The catalogue is small and static.** No pagination, no caching, no search endpoint — a client
  fetches the whole thing once and filters locally (`shared/api/serviceAreas.ts`).
- **Ids are stable forever.** Because only migrations write these tables, a stored region or city
  id cannot be reassigned by anything a user does. Any future migration that retires a city must
  add a row rather than renumber one, exactly as `V31` did for the retired Carpentry category.
- **This package is a leaf.** It imports no other domain package. `professionals` depends on it,
  never the reverse.

## What is deliberately not here

- **Customer addresses.** `users.default_city` and `orders.service_city` remain free text. A
  customer types where they live; a professional declares which of a fixed set of places they
  will travel to. Forcing the first into this catalogue would reject perfectly real addresses in
  towns the service-area list does not name.
- **Geocoding, coordinates, distance.** `matching` owns distance/ETA and will keep owning it.
