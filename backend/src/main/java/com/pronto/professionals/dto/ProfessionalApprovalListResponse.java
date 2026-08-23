package com.pronto.professionals.dto;

import java.util.List;

/**
 * {@code GET /api/admin/professionals[?approvalStatus=]} — the operator queue. MS1 (D-F).
 * Envelope-with-a-list, matching every other list response in this codebase
 * ({@code OrdersListResponse}, {@code FavoritesListResponse}, {@code SlotListResponse}) rather
 * than a bare JSON array.
 */
public record ProfessionalApprovalListResponse(
        List<ProfessionalApprovalSummary> professionals
) {
}
