/**
 * {@code AvailabilitySlots} management for professionals.
 *
 * <p>Owns the {@code availability_slots} table (see
 * {@code docs/architecture/data-model.md} §2.5), used both for Standard scheduling
 * (future bookable windows) and SOS "currently available" matching. Consumed by the
 * {@code bookings} package when matching professionals to Standard/SOS requests. Stub
 * only as of Milestone 0.
 *
 * <p><b>Milestone 3 slice approved, 2026-08-13</b> ({@code pronto-lead}): a narrow,
 * read-focused slice — the {@code AvailabilitySlot} entity/repository plus {@code POST
 * /api/availability/slots} and {@code GET /api/availability/slots/me} only, no edit/
 * delete/toggle, no UI — is pulled forward into Milestone 3 so the {@code bookings}
 * package's Standard-path endpoints have real slot rows to book against. Full contract:
 * {@code docs/architecture/api-contract-bookings.md} §2.10/§2.11. The rest of this
 * package (full CRUD, calendar-management surface, dashboard UI) remains Milestone 6
 * (Professional dashboard) scope, per {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.availability;
