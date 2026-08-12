/**
 * Professional profile, service area, and reliability score. (No approval workflow — v1.0
 * auto-approves professional accounts.)
 *
 * <p>Owns the {@code professionals} table (see {@code docs/architecture/data-model.md}
 * §2.4), which extends a {@code users} row with role {@code PROFESSIONAL}. Consumed by
 * the {@code bookings} package for Standard/SOS professional listings, and by the
 * {@code availability} package for slot management. Stub only as of Milestone 0 —
 * implemented in Milestone 1 (Auth & user management) per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.professionals;
