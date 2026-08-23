package com.pronto.professionals.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/admin/professionals/{professionalId}/reject}. MS1 (D-F).
 *
 * <p>{@code reason} is required, not optional. A rejection with no recorded reason is a decision
 * nobody — not the next operator, not the professional, not an audit — can account for later, and
 * "why" is the one field a rejection has that an approval does not. {@code 500} matches
 * {@code professionals.approval_rejection_reason}'s column width.
 */
public record RejectProfessionalRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
