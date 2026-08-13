/**
 * {@code AvailabilitySlots} management for professionals.
 *
 * <p>Owns the {@code availability_slots} table (see
 * {@code docs/architecture/data-model.md} §2.5), used for Standard scheduling (future
 * bookable windows). Consumed by the {@code bookings} package when matching professionals
 * to Standard requests and for the atomic slot-claim/release mechanism (see
 * {@code repository.AvailabilitySlotRepository}).
 *
 * <p><b>Milestone 3 slice — implemented, 2026-08-13</b>: the {@code AvailabilitySlot} JPA
 * entity + repository, plus {@code POST /api/availability/slots} and {@code GET
 * /api/availability/slots/me} (full contract:
 * {@code docs/architecture/api-contract-bookings.md} §2.10/§2.11), so the {@code bookings}
 * package's Standard-path endpoints have real slot rows to book against. No edit/delete/
 * toggle, no UI. Full CRUD, richer calendar semantics, and any dashboard UI remain
 * Milestone 6 (Professional dashboard) scope.
 */
package com.pronto.availability;
