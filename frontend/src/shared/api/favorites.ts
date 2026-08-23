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
  /**
   * Production Roadmap MS1 (design §D-G). Whether this saved professional is currently
   * marketplace-eligible — `approval_status = APPROVED` **and** completed onboarding, computed
   * per request by `professionals.ProfessionalEligibility`. Mirrors
   * `favorites.dto.FavoriteProfessionalSummary#bookable`.
   *
   * Neutral by design: it says "you cannot book this person right now", never *why*, so a
   * favorites list cannot become a channel for learning that a named professional was rejected.
   * Ineligible favorites are still listed, never deleted (`FavoritesService#listFavorites`).
   *
   * **Not yet consumed by any component** — `FavoriteProfessionalCard.tsx` does not read it.
   * Wiring it into a customer-facing affordance is deliberately outside MS1's frontend scope;
   * see `docs/production-roadmap/reports/MS1-report.md` Known Limitation 9.
   */
  bookable: boolean;
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
