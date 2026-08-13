package com.pronto.users.dto;

import java.math.BigDecimal;

/**
 * The nested {@code professional} object in {@code GET /api/users/me}'s response for a
 * {@code PROFESSIONAL}-role caller. See {@code docs/architecture/api-contract.md} §2.4.
 */
public record ProfessionalInfo(Long categoryId, String serviceArea, BigDecimal basePrice) {
}
