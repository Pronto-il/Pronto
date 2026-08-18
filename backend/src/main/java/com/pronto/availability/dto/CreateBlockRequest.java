package com.pronto.availability.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Wire shape for {@code POST /api/availability/blocks} and, as of this same milestone,
 * {@code PATCH /api/availability/blocks/{blockId}} too -- reused verbatim rather than
 * duplicated into a sibling {@code UpdateBlockRequest} (full replace of {@code startAt}/
 * {@code endAt}/{@code reason}, not a partial patch, despite the HTTP verb), mirroring
 * {@code CreateSlotRequest}'s existing reuse across {@code POST}/{@code PUT
 * /api/availability/slots*}. See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §4.3/§4.4.
 *
 * <p>Presence/parseability of {@code startAt}/{@code endAt} is covered by Bean Validation
 * ({@code @NotNull}) plus Jackson's ISO-8601 parsing. The "{@code endAt > startAt}"/
 * "{@code startAt} not in the past" ordering rules are validated in
 * {@code AvailabilityService} -- not expressible as a single-field Bean Validation
 * annotation.
 */
public record CreateBlockRequest(
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @Size(max = 255) String reason
) {
}
