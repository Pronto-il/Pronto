package com.pronto.favorites.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Wire shape for {@code POST /api/favorites}.
 */
public record AddFavoriteRequest(@NotNull Long professionalId) {
}
