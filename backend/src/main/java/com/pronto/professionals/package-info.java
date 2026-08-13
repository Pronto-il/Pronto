/**
 * Professional profile, service area, and reliability score. (No approval workflow — v1.0
 * auto-approves professional accounts.)
 *
 * <p>Owns the {@code professionals} table (see {@code docs/architecture/data-model.md}
 * §2.4), which extends a {@code users} row with role {@code PROFESSIONAL}. Also maps the
 * read-only {@code categories} reference table (§2.1) — see {@code professionals/README.md}
 * for why that lives here rather than a dedicated {@code categories} package. Depended on
 * by {@code auth} (professional registration) and {@code users} (profile lookups); will be
 * consumed by {@code bookings}/{@code availability} in later milestones.
 *
 * <p>Entity/repository layer implemented in Milestone 1 per
 * {@code docs/architecture/implementation-plan.md} — profile creation only; dashboard/edit
 * flows are Milestone 6.
 */
package com.pronto.professionals;
