import { httpClient } from './httpClient';

/**
 * `favorites` domain types/functions (`backend/src/main/java/com/pronto/favorites/`).
 * Shapes verified directly against `favorites.dto.FavoritesListResponse`/
 * `favorites.dto.FavoriteProfessionalSummary`, not copied from prose. All three endpoints
 * are CUSTOMER-only (route-level gate).
 */

export interface FavoriteProfessionalSummary {
  professionalId: number;
  fullName: string;
  serviceArea: string;
  city: string;
  basePrice: number;
  profileImageUrl: string | null;
  averageRating: number | null;
  reviewCount: number;
  favoritedAt: string;
}

export interface FavoritesListResponse {
  favorites: FavoriteProfessionalSummary[];
}

/** POST /api/favorites — CUSTOMER only, idempotent (204 even if already favorited). */
export function addFavorite(professionalId: number): Promise<void> {
  return httpClient.post<void>('/api/favorites', { professionalId });
}

/** DELETE /api/favorites/{id} — CUSTOMER only, idempotent (204 even if not favorited). */
export function removeFavorite(professionalId: number): Promise<void> {
  return httpClient.delete<void>(`/api/favorites/${professionalId}`);
}

/** GET /api/favorites — CUSTOMER only, created_at DESC, no pagination. */
export function getFavorites(): Promise<FavoritesListResponse> {
  return httpClient.get<FavoritesListResponse>('/api/favorites');
}
