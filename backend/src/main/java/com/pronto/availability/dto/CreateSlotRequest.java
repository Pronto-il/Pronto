package com.pronto.availability.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Wire shape for {@code POST /api/availability/slots} and, as of Milestone 7,
 * {@code PUT /api/availability/slots/{slotId}} too — reused verbatim rather than duplicated
 * into a sibling {@code UpdateSlotRequest}, since both endpoints share the identical wire
 * shape and validation rules (§2.18 of the contract doc explicitly allows either choice; this
 * codebase's established DRY convention favors reuse). See
 * {@code docs/architecture/api-contract-bookings.md} §2.10/§2.18. Presence/parseability of
 * {@code startTime}/{@code endTime} is covered by Bean Validation ({@code @NotNull}) plus
 * Jackson's ISO-8601 parsing (an unparseable value fails at deserialization, surfaced as
 * {@code 400 VALIDATION_ERROR} by {@code common.exception.GlobalExceptionHandler}'s
 * {@code HttpMessageNotReadableException} handler). The "strictly future" / "endTime >
 * startTime" ordering rules are validated in {@code AvailabilityService} — not expressible
 * as a single-field Bean Validation annotation.
 */
public record CreateSlotRequest(
        @NotNull Instant startTime,
        @NotNull Instant endTime
) {
}
