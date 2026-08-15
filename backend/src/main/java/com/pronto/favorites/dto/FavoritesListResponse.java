package com.pronto.favorites.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/favorites}.
 */
public record FavoritesListResponse(List<FavoriteProfessionalSummary> favorites) {
}
