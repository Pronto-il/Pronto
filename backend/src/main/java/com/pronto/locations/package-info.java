/**
 * The closed Israeli service-region and service-city catalogue (MS4 Part A).
 *
 * <p>Owns the two read-only reference tables {@code service_regions} and {@code service_cities}
 * seeded by {@code V43__create_service_regions_and_cities.sql}, exposes them on the public
 * {@code GET /api/service-areas}, and owns
 * {@link com.pronto.locations.service.ServiceCoverageValidator} — the one rule deciding whether
 * a coverage selection (region + cities + base city) is legal, shared by professional
 * registration and the later profile edit so the two surfaces cannot enforce different things.
 *
 * <p>A leaf package: it imports no other domain package. {@code professionals} references it
 * ({@code professionals.service_region_id}, {@code professionals.base_city_id}, and the
 * {@code professional_service_cities} relation), never the reverse.
 *
 * <p>Placed here rather than inside {@code professionals} deliberately. That package's own
 * {@code Category} entity carries a standing note regretting exactly this shortcut — a shared
 * reference table buried inside the first package that happened to need it — and a location
 * catalogue is even less of a "professionals" concern than a service-category one. See
 * {@code locations/README.md}.
 */
package com.pronto.locations;
