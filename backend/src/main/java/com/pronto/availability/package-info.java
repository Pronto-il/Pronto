/**
 * {@code AvailabilitySlots} management for professionals.
 *
 * <p>Owns the {@code availability_slots} table (see
 * {@code docs/architecture/data-model.md} §2.5), used both for Standard scheduling
 * (future bookable windows) and SOS "currently available" matching. Consumed by the
 * {@code bookings} package when matching professionals to Standard/SOS requests. Stub
 * only as of Milestone 0 — implemented in Milestone 6 (Professional dashboard) per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.availability;
