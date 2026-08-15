# `reviews`

## Purpose

Customer reviews of a professional, one per completed order — star rating (1-5) plus an
optional free-text comment.

Implements `docs/architecture/api-contract-professionals-reviews.md` §4.5-4.8.

## Responsibilities

- `POST /api/reviews` — creates a review. `orderId` is the only entity reference in the
  request body; `professionalId`/`customerId` are derived server-side from the loaded order,
  never trusted from the client. Requires the caller to own the order as `CUSTOMER`
  (`403 FORBIDDEN` otherwise) and the order to have reached `order_status = 'COMPLETED'`
  (`409 REVIEW_ORDER_NOT_COMPLETED` otherwise). At most one review per order
  (`ux_reviews_order` DB constraint) — a pre-check (`ReviewRepository.existsByOrderId`)
  returns `409 REVIEW_ALREADY_EXISTS` fast, and a `DataIntegrityViolationException` on the
  insert (the race backstop for two concurrent requests both passing the pre-check) is
  mapped to the identical error code.
- `GET /api/reviews?professionalId=` — every review for one professional, newest first,
  plus the computed `averageRating` (rounded half-up to 2 decimals, `null` — never `0.00` —
  when the professional has no reviews) and `reviewCount`. Either role, no ownership check
  (public data). `404 NOT_FOUND` if the professional itself doesn't exist.
- `PUT /api/reviews/{reviewId}` — owner-only edit of `rating`/`comment`. `orderId`/
  `professionalId`/`customerId` are immutable — the update DTO carries no fields for them at
  all. Existence + ownership resolved by a prior read, then an atomic guarded
  `UPDATE ... WHERE id = ? AND customer_id = ?`; `0` affected rows at that point means the
  row was concurrently deleted between the read and the write, surfaced as `404 NOT_FOUND`
  (not `409` — there is nothing left to conflict with).
- `DELETE /api/reviews/{reviewId}` — owner-only delete, identical ownership-guard pattern.
  `204 No Content` on success.

## Key classes

| Class | Role |
|---|---|
| `entity.Review` | JPA entity for `reviews`. `professionalId`/`customerId`/`orderId` are plain FK columns (not `@ManyToOne` associations), matching this codebase's universal convention. Exposes `setRating`/`setComment` for `updateIfOwnedByCustomer`'s pre-fetch-then-guarded-write shape, but no setter for any FK field — those are set once at construction and never change. |
| `repository.ReviewRepository` | `existsByOrderId` (the create-time pre-check), `findByProfessionalIdOrderByCreatedAtDesc` (the listing query), and the two atomic guarded methods `updateIfOwnedByCustomer`/`deleteIfOwnedByCustomer`. **Deliberately does not** expose the average-rating/review-count aggregate query — that's owned by `professionals.repository.ReviewAggregateRepository`, a narrow read into this package's table from `professionals` (see "Interactions" below). |
| `service.ReviewsService` | All business logic for the four endpoints above — ownership/state checks, the race-backstop catch, the average-rating computation for the listing response. |
| `controller.ReviewsController` | `/api/reviews` (`POST`/`GET`), `/api/reviews/{reviewId}` (`PUT`/`DELETE`). Path/query ids parsed manually (same convention as `bookings.controller.BookingsController`) so a malformed value produces this app's standard error envelope rather than Spring's default type-mismatch handling. |
| `config.ReviewsWebConfig` | Registers two `RoleRequiredInterceptor`s: one scoped to `POST` only (via the new HTTP-method-scoped constructor, `RoleRequiredInterceptor(role, "POST")`) on the literal path `/api/reviews` — needed because `POST`/`GET /api/reviews` share an identical literal path but require different gating (`CUSTOMER`-only vs. either-role); and one covering `PUT`/`DELETE` on `/api/reviews/*`. |
| `dto.CreateReviewRequest` / `dto.UpdateReviewRequest` | Allowlist DTOs — neither carries `professionalId`/`customerId`/`orderId` where they'd be client-settable (`CreateReviewRequest` needs `orderId` to identify *which* order is being reviewed; `UpdateReviewRequest` needs none of the three, since the review already exists). |
| `dto.ReviewResponse` / `dto.ReviewListResponse` | Wire shapes for a single review and the professional-scoped list (the latter also carrying the `averageRating`/`reviewCount` aggregate). |

## Interactions with other packages

- Depends on `bookings` (`OrderRepository`/`Order`/`OrderStatus`) to load and validate the
  order a review is being created against — the only place this package reaches outside its
  own table for a *write* path.
- Depends on `professionals` (`ProfessionalRepository`, for the `GET` listing's professional-
  existence check) and `users` (`UserRepository`, to resolve `customerName` for display).
- **Read cross-package, in the other direction**: `professionals.repository
  .ReviewAggregateRepository` and `bookings.repository.ProfessionalListingRepository` both
  run their own narrow, read-only queries directly against `reviews.entity.Review` (a
  correlated `AVG`/`COUNT` subquery each) to enrich their own responses with rating data,
  rather than calling into this package's service layer. This mirrors the intentional
  narrow-cross-package-repository pattern `bookings.repository.ProfessionalListingRepository`
  already established for reading `professionals`/`sos_availability` — a package reading
  another package's entity for its own projection need, kept deliberately as a database-level
  read (not a service call), documented explicitly rather than treated as an oversight.
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`, including this
  package's two new codes, `REVIEW_ORDER_NOT_COMPLETED`/`REVIEW_ALREADY_EXISTS`, both `409`)
  and `RoleRequiredInterceptor`/`AuthenticatedUser`.

## Data model

Owns the `reviews` table (`V16__create_reviews.sql`; full spec in
`docs/architecture/data-model.md` §2.11 and
`docs/architecture/api-contract-professionals-reviews.md` §2.1). `professional_id`/
`customer_id`/`order_id` are all `RESTRICT` FKs (a review has independent meaning as a
historical record even if, hypothetically, one of its referenced rows were ever removed —
consistent with this schema's general `RESTRICT`-for-core-entities convention).
`order_id` is `UNIQUE` (`ux_reviews_order`) — the DB-level backstop behind the
`existsByOrderId` pre-check.

## Assumptions / judgment calls made during implementation

- **No review-edit time window.** `PUT /api/reviews/{id}` has no "only within N days"
  restriction — a review can be edited indefinitely by its owner. Not specified by any
  source document either way; simplest correct behavior given no stated requirement.
- **A review requires `order_status = 'COMPLETED'` specifically** — not "any terminal
  status." A `CANCELLED`/`REJECTED`/`EXPIRED` order is never reviewable; only a job that
  actually finished can be rated.
- **`averageRating` is computed live on every `GET` call**, not cached/denormalized onto
  `professionals` — acceptable at MVP query volume/scale, consistent with this codebase's
  general "no premature caching" posture elsewhere.
- **No pagination** on `GET /api/reviews?professionalId=` — consistent with every other list
  endpoint in this codebase to date.

## Status

Implemented and QA-signed-off (zero bugs found on functionality/security) as part of the
professional-profile/reviews/favorites/matching feature set, 2026-08-15 (branch `MS7`, not
yet committed at the time this doc was written). See
`docs/architecture/implementation-plan.md`'s Milestone 8 entry for the full QA summary and
`docs/architecture/api-contract-professionals-reviews.md` for the complete design/contract
this package implements. Unit-tested (`reviews.service.ReviewsServiceTest`).
