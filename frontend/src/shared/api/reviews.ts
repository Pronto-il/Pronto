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

/**
 * The author's own view of their own review — the body of `POST`/`PUT /api/reviews`. Carries
 * `customerId`/`orderId` because the author already knows both. Not what the public list returns;
 * see `PublicReviewResponse`.
 */
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
 * One entry in `GET /api/reviews?professionalId=` — the discovery shape, readable by a guest.
 *
 * Deliberately narrower than `ReviewResponse`: no `customerId` (the reviewer's internal user id)
 * and no `orderId` (the booking it came from). Those were harmless while the list required a JWT
 * and became a leak the moment it stopped — an anonymous caller walking `professionalId` could
 * otherwise assemble a map of which customer account hired which professional on which order.
 * Nothing in this app ever rendered either field; `ReviewList.tsx` shows `customerName`, `rating`,
 * `comment` and `createdAt`. Mirrors `reviews.dto.PublicReviewResponse`.
 */
export interface PublicReviewResponse {
  id: number;
  professionalId: number;
  customerName: string | null;
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

export interface ReviewListResponse {
  professionalId: number;
  averageRating: number | null;
  reviewCount: number;
  reviews: PublicReviewResponse[];
}

/**
 * `GET /api/reviews?professionalId=` — **public as of 2026-08-29**, no pagination.
 *
 * It used to be "either role, no route gate", which in practice meant "any authenticated caller":
 * `SecurityConfig`'s blanket `.anyRequest().authenticated()` answered `401` for a guest, so every
 * professional profile a visitor opened during the guest journey showed a review error. The
 * backend now permits `GET` on this exact path; the three write routes are unchanged.
 *
 * `auth: false` because there is nothing to authenticate and nothing that varies by caller —
 * omitting the header keeps a guest's request identical to a signed-in customer's, so a stale or
 * expired token can never turn a public read into a `401` that fires the dead-session handler.
 */
export function getReviews(professionalId: number): Promise<ReviewListResponse> {
  return httpClient.get<ReviewListResponse>(`/api/reviews?professionalId=${professionalId}`, {
    auth: false,
  });
}
