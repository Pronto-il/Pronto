package com.pronto.favorites.controller;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.favorites.dto.AddFavoriteRequest;
import com.pronto.favorites.dto.FavoritesListResponse;
import com.pronto.favorites.service.FavoritesService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/favorites*}. Every route requires {@code CUSTOMER}, enforced by
 * {@code favorites.config.FavoritesWebConfig}'s {@code RoleRequiredInterceptor} registration,
 * not in these method bodies.
 */
@RestController
@RequestMapping("/api/favorites")
public class FavoritesController {

    private final FavoritesService favoritesService;

    public FavoritesController(FavoritesService favoritesService) {
        this.favoritesService = favoritesService;
    }

    @PostMapping
    public ResponseEntity<Void> add(@AuthenticationPrincipal AuthenticatedUser principal,
                                     @Valid @RequestBody AddFavoriteRequest request) {
        favoritesService.addFavorite(principal, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{professionalId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable("professionalId") String professionalIdRaw) {
        Long professionalId = parsePathId(professionalIdRaw);
        favoritesService.removeFavorite(principal, professionalId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<FavoritesListResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(favoritesService.listFavorites(principal));
    }

    /** Same path-referenced-id convention as {@code bookings.controller.BookingsController}. */
    private Long parsePathId(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Not found.");
        }
    }
}
