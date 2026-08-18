import { httpClient } from './httpClient';

/**
 * `reviews` domain types/functions — first frontend consumer of `POST /api/reviews`
 * (`backend/src/main/java/com/pronto/reviews/`, reused as-is, no backend changes). Shapes
 * verified directly against `reviews.dto.CreateReviewRequest`/`reviews.dto.ReviewResponse`,
 * not copied from prose docs.
 */

/**
 * Wire shape for `POST /api/reviews`. No `professionalId`/`customerId` — both are derived
 * server-side from the loaded `orderId` (`ReviewsService.createReview`), never sent by the
 * client. `comment` is optional (`@Size(max = 2000)` server-side, no `@NotNull`).
 */
export interface CreateReviewRequest {
  orderId: number;
  rating: number;
  comment?: string;
}

export interface ReviewResponse {
  id: number;
  professionalId: number;
  customerId: number;
  customerName: string | null;
  orderId: number;
  rating: number;
  comment: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * `POST /api/reviews` — CUSTOMER only (route-level gate, `ReviewsWebConfig`). Errors:
 * `404 NOT_FOUND` (order doesn't exist), `403 FORBIDDEN` (caller isn't the order's
 * customer), `409 REVIEW_ORDER_NOT_COMPLETED` (order isn't `COMPLETED` yet),
 * `409 REVIEW_ALREADY_EXISTS` (order already has a review).
 */
export function createReview(payload: CreateReviewRequest): Promise<ReviewResponse> {
  return httpClient.post<ReviewResponse>('/api/reviews', payload);
}
