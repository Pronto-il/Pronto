/**
 * Professional profile, service area, and reliability score. (No approval workflow — v1.0
 * auto-approves professional accounts.)
 *
 * <p>Owns the {@code professionals} table (see {@code docs/architecture/data-model.md}
 * §2.4), which extends a {@code users} row with role {@code PROFESSIONAL}. Also maps the
 * read-only {@code categories} reference table (§2.1) — see {@code professionals/README.md}
 * for why that lives here rather than a dedicated {@code categories} package. Depended on
 * by {@code auth} (professional registration), {@code users} (profile lookups), and
 * {@code bookings} (professional lookup/ownership checks); as of Milestone 8 also depends on
 * {@code reviews}, {@code favorites}, and {@code storage} for its own service layer.
 *
 * <p>Entity/repository layer implemented in Milestone 1 per
 * {@code docs/architecture/implementation-plan.md} — profile creation only. A full
 * service/controller/dto/config layer was added in Milestone 8, adding self-service profile
 * editing ({@code GET}/{@code PUT /api/professionals/me}), profile-image upload
 * ({@code POST /api/professionals/me/profile-image}, reusing the {@code storage} package),
 * a public detail view ({@code GET /api/professionals/{professionalId}}), and read access to
 * derived rating/review-count aggregates. See {@code docs/architecture/implementation-plan
 * .md}'s Milestone 8 entry and {@code docs/architecture/api-contract-professionals-reviews
 * .md} for the full design/contract.
 */
package com.pronto.professionals;
