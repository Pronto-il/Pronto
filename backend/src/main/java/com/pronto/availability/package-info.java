/**
 * {@code AvailabilitySlots}/{@code sos_availability} management for professionals.
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
 *
 * <p><b>Pre-Milestone-4 schema-gap fix — 2026-08-13</b>: also owns the {@code
 * sos_availability} table (§2.6), added by {@code V13__create_sos_availability.sql}. This
 * closes a previously-flagged divergence: the originally-applied
 * {@code V5__create_availability_slots.sql} implemented SOS matching as a query variant of
 * {@code availability_slots} — the single-table design §2.6/§3 item 5 explicitly rejected in
 * favor of a dedicated table — done now, ahead of Milestone 3/4, specifically so
 * professional registration ({@code auth.service.AuthService#register}) can insert the
 * default {@code isAvailable = false} row per {@code data-model.md} §2.6's row-lifecycle
 * requirement. The {@code SosAvailability} entity + repository exist; the toggle/listing
 * endpoints themselves remain Milestone 4 (SOS booking flow) scope.
 */
package com.pronto.availability;
