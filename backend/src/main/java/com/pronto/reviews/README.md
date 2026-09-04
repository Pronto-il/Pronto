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
  when the professional has no reviews) and `reviewCount`. **Public — no authentication of any
  kind, as of 2026-08-29** (see "Public read" below). `404 NOT_FOUND` if the professional itself
  doesn't exist. Returns `dto.PublicReviewResponse`, deliberately narrower than
  `dto.ReviewResponse`.
- `PUT /api/reviews/{reviewId}` — owner-only edit of `rating`/`comment`. `orderId`/
  `professionalId`/`customerId` are immutable — the update DTO carries no fields for them at
  all. Existence + ownership resolved by a prior read, then an atomic guarded
  `UPDATE ... WHERE id = ? AND customer_id = ?`; `0` affected rows at that point means the
  row was concurrently deleted between the read and the write, surfaced as `404 NOT_FOUND`
  (not `409` — there is nothing left to conflict with).
- `DELETE /api/reviews/{reviewId}` — owner-only delete, identical ownership-guard pattern.
  `204 No Content` on success.

## Public read (2026-08-29) — the guest fix, and its blast radius

**The bug.** A guest browsing professionals got `401 UNAUTHORIZED "Missing, invalid, or expired
authentication token."` from `GET /api/reviews`. Nothing in this package rejected them — this route
never had a role gate. It was `auth.config.SecurityConfig`'s blanket
`.anyRequest().authenticated()` catch-all: deferred authentication's permit list named
`/api/professionals/*` but not the reviews that the profile behind it invites you to read. So a
visitor could open a professional's profile and see everything except the one thing people
actually choose on.

**The fix is one line**, in `SecurityConfig`, scoped to `GET` and to the exact literal path:
`.requestMatchers(HttpMethod.GET, "/api/reviews").permitAll()`. **This package's own config was not
touched at all** — `ReviewsWebConfig`'s `POST`-scoped registration already declines to run on a
`GET`, and its `/api/reviews/*` registration covers a path no `GET` uses (there is no get-by-id
endpoint). A blanket `permitAll` on the controller was rejected outright: it would have taken the
`POST` gate with it.

**Every write is unchanged.** `POST` still requires an authenticated `CUSTOMER` who owns a
`COMPLETED` order that has no review yet (the `ux_reviews_order` unique constraint is still the
race backstop); `PUT`/`DELETE` still require the review's own author. None of those rules were
read, moved or relaxed.

**`dto.PublicReviewResponse` is new, and it is the reason the response shape changed.**
`dto.ReviewResponse` — still the body of `POST`/`PUT` — carries `customerId` (the reviewer's
internal `users` row id) and `orderId` (the booking the review came from). Those were harmless
while the list required a JWT and became a real leak the moment it did not: an anonymous caller
walking `professionalId` 1..n could otherwise assemble a map of which customer account hired which
professional on which order, from a public endpoint, without ever creating an account. Neither
field was ever rendered — `frontend/src/features/professionals/ReviewList.tsx` shows
`customerName`, `rating`, `comment` and `createdAt` — so removing them cost nothing. `updatedAt` is
kept: "this review was edited" is a fact about a public review, not private data.

**Not rate limited**, deliberately and consistently: it is a cheap indexed read, and the two public
routes it sits beside in the journey (`GET /api/bookings/professionals`,
`GET /api/professionals/{id}`) carry no limiter either. `POST /api/issues/classify` and the guest
upload routes are limited because each request has a real per-call cost (an OpenAI call, an S3
write); this one does not.

Tests: `reviews.PublicReviewReadTest` (route-level permit, no-caller signature, the field
allow-list on `PublicReviewResponse`, the concrete "no customerId/orderId in the payload" check,
and the three write gates), plus `auth.config.GuestRouteBoundaryTest`.

## Key classes

| Class | Role |
|---|---|
| `entity.Review` | JPA entity for `reviews`. `professionalId`/`customerId`/`orderId` are plain FK columns (not `@ManyToOne` associations), matching this codebase's universal convention. Exposes `setRating`/`setComment` for `updateIfOwnedByCustomer`'s pre-fetch-then-guarded-write shape, but no setter for any FK field — those are set once at construction and never change. |
| `repository.ReviewRepository` | `existsByOrderId` (the create-time pre-check), `findByProfessionalIdOrderByCreatedAtDesc` (the listing query), and the two atomic guarded methods `updateIfOwnedByCustomer`/`deleteIfOwnedByCustomer`. **Deliberately does not** expose the average-rating/review-count aggregate query — that's owned by `professionals.repository.ReviewAggregateRepository`, a narrow read into this package's table from `professionals` (see "Interactions" below). |
| `service.ReviewsService` | All business logic for the four endpoints above — ownership/state checks, the race-backstop catch, the average-rating computation for the listing response. |
| `controller.ReviewsController` | `/api/reviews` (`POST`/`GET`), `/api/reviews/{reviewId}` (`PUT`/`DELETE`). Path/query ids parsed manually (same convention as `bookings.controller.BookingsController`) so a malformed value produces this app's standard error envelope rather than Spring's default type-mismatch handling. |
| `config.ReviewsWebConfig` | Registers two `RoleRequiredInterceptor`s: one scoped to `POST` only (via the HTTP-method-scoped constructor, `RoleRequiredInterceptor(role, "POST")`) on the literal path `/api/reviews` — needed because `POST`/`GET /api/reviews` share an identical literal path but require different gating (`CUSTOMER`-only vs., now, fully public); and one covering `PUT`/`DELETE` on `/api/reviews/*`. **That method scoping is precisely what let `GET` become public on 2026-08-29 without editing this class at all.** Do not widen either pattern. |
| `dto.CreateReviewRequest` / `dto.UpdateReviewRequest` | Allowlist DTOs — neither carries `professionalId`/`customerId`/`orderId` where they'd be client-settable (`CreateReviewRequest` needs `orderId` to identify *which* order is being reviewed; `UpdateReviewRequest` needs none of the three, since the review already exists). |
| `dto.ReviewResponse` | The **author's own view of their own review** — the body of `POST`/`PUT` only. Carries `customerId`/`orderId` because the author already knows both. Unchanged by the 2026-08-29 public-read work. |
| `dto.PublicReviewResponse` | **New, 2026-08-29.** One entry in the public list: `id`, `professionalId`, `customerName`, `rating`, `comment`, `createdAt`, `updatedAt`. Deliberately omits `customerId` and `orderId` — see "Public read" above for why that stopped being safe once the endpoint dropped its JWT requirement. |
| `dto.ReviewListResponse` | The professional-scoped list, carrying the `averageRating`/`reviewCount` aggregate plus `List<PublicReviewResponse>` (was `List<ReviewResponse>`). |

## Interactions with other packages

- **Frontend consumer, as of the Active Booking Floating Indicator feature (2026-08-17)**:
  `POST /api/reviews` gained its first real frontend caller —
  `frontend/src/shared/api/reviews.ts` (`createReview`), used by
  `frontend/src/features/booking/CompletionReviewPage.tsx` (the new
  `/orders/:orderId/review` route). This package's backend implementation is reused
  completely as-is — no backend changes were made for this feature; see
  `docs/architecture/active-booking-floating-indicator.md` §7. Before this feature, `POST
  /api/reviews`/`GET /api/reviews` were implemented and QA-signed-off with no frontend
  consumer yet.
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

**Active Booking Floating Indicator feature (2026-08-17)**: no backend changes to this
package. `POST /api/reviews` gained its first real frontend consumer — see "Interactions
with other packages" above.


## Comment length (2026-09-04)

`ReviewText.COMMENT_MAX_LENGTH` is **500**, shared by `CreateReviewRequest` and
`UpdateReviewRequest` so writing a review and editing one can never disagree about what fits.
Narrowed from 2000, which nothing on the client ever told the customer about and which no review
had a use for. The comment stays optional. Mirrored on the client by
`shared/api/fieldLimits.ts`'s `REVIEW_COMMENT_MAX_LENGTH`, which caps the field and shows a
counter; covered on this side by `common.validation.FreeTextLengthLimitsTest`.
