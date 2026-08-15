# `favorites`

## Purpose

A customer's favorited (bookmarked) professionals — a simple many-to-many join with no
independent meaning beyond "this customer wants to find this professional again easily."

Implements `docs/architecture/api-contract-professionals-reviews.md` §4.9-4.11.

## Responsibilities

- `POST /api/favorites` — add a professional to the caller's favorites. `professionalId`
  must reference an existing professional (`400 VALIDATION_ERROR` otherwise, the same
  body-field-reference convention `bookings.dto.CreateOrderRequest`'s `professionalId`
  already uses). **Idempotent, always `204 No Content` on success** — an already-favorited
  pair is a silent no-op (not an error), and a `DataIntegrityViolationException` from a
  concurrent request winning the composite-PK-uniqueness race is swallowed the same way.
  There is deliberately no "already favorited" error code (see `common.exception.ErrorCode`
  — no new code was added for this package at all).
- `DELETE /api/favorites/{professionalId}` — remove a professional from the caller's
  favorites. Also idempotent — `204 No Content` regardless of whether the pair existed (no
  `404` for "wasn't favorited"). A genuinely malformed/non-positive path id (not "doesn't
  exist," which is a silent no-op) still `404 NOT_FOUND`s via the standard path-id-parsing
  convention.
- `GET /api/favorites` — the caller's own favorited professionals, newest-favorited first,
  each entry enriched with a lean `dto.FavoriteProfessionalSummary` (name, service area,
  city, base price, resolved profile-image URL, average rating, review count, and the
  `favoritedAt` timestamp). A favorited professional row that's unexpectedly missing at read
  time (would only happen if `ON DELETE CASCADE` somehow raced with this read — not expected
  in practice) is silently filtered out of the response rather than causing a `500`.

## Key classes

| Class | Role |
|---|---|
| `entity.Favorite` | JPA entity for `favorites` — a genuinely composite-key row, `(customer_id, professional_id)`, **no surrogate `id`**. Mapped via `@IdClass(FavoriteId.class)`, not `@EmbeddedId` — see `entity.FavoriteId`'s Javadoc for why: `@IdClass` lets the same two plain FK fields double as the identifier without introducing a separate embeddable wrapper type this entity has no other use for. |
| `entity.FavoriteId` | The `@IdClass` companion — field names/types must mirror `Favorite`'s `@Id` fields exactly (a `@IdClass` contract requirement), implements `equals`/`hashCode` over both fields (required for JPA's composite-key identity resolution). |
| `repository.FavoriteRepository` | `existsByCustomerIdAndProfessionalId` (the idempotency check for `POST`/enrichment source elsewhere), `findByCustomerIdOrderByCreatedAtDesc` (the listing query), `deleteByCustomerIdAndProfessionalId` (the idempotent delete — a plain derived-query delete, no atomic guard needed since there's no state machine to race against, just presence/absence of a row). |
| `service.FavoritesService` | Business logic for all three endpoints, including the idempotency handling for add/remove and the per-entry enrichment (profile image URL resolution via `StorageClient`, rating aggregate via `professionals.repository.ReviewAggregateRepository`) for the listing response. |
| `controller.FavoritesController` | `/api/favorites` (`POST`/`GET`), `/api/favorites/{professionalId}` (`DELETE`). Manual path-id parsing, same convention as every other controller in this codebase. |
| `config.FavoritesWebConfig` | A **single blanket-pattern** `RoleRequiredInterceptor(CUSTOMER)` registration covering `/api/favorites` and `/api/favorites/**` — safe here (unlike `reviews`/`professionals`, which mix roles per-route) because every route in this package requires the same single role, mirroring `storage.config.StorageWebConfig`'s blanket-pattern precedent for the same reason. |
| `dto.AddFavoriteRequest` | `{ professionalId }` — the sole write-request shape. |
| `dto.FavoriteProfessionalSummary` / `dto.FavoritesListResponse` | The listing response shapes. `FavoriteProfessionalSummary` is a **deliberately separate, leaner DTO**, not a reuse of `bookings.dto.ProfessionalCard` — this endpoint has no service-location context (unlike a booking listing), so `ProfessionalCard`'s `distanceKm`/`baseTravelTimeMinutes`/`trafficAdjustmentMinutes`/`etaMinutes`/`sameCity` fields simply don't apply here; reusing it would mean either always-null fields on this response or a confusing partially-populated card — the simpler-DTO option was chosen deliberately. |

## Interactions with other packages

- Depends on `professionals` (`ProfessionalRepository` for the existence check on add, and
  `Professional`/`ReviewAggregateRepository`/`ProfessionalRatingAggregate` for the listing
  enrichment), `users` (`UserRepository`, for the favorited professional's display name), and
  `storage` (`StorageClient#resolveUrl`, for the profile-image URL).
- **Read cross-package, in the other direction**: `bookings.repository
  .ProfessionalListingRepository` and `professionals.service.ProfessionalsService` both run a
  narrow, read-only query directly against `favorites.entity.Favorite`/
  `favorites.repository.FavoriteRepository` (a correlated `COUNT`/`existsBy...` check scoped
  to one calling customer) to populate their own responses' `favorited` flag, rather than
  calling into this package's service layer — the same intentional
  narrow-cross-package-repository pattern `bookings.repository.ProfessionalListingRepository`
  already established for `professionals`/`sos_availability`/`reviews`.
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode` — no new code was
  needed for this package, see "Responsibilities" above) and
  `RoleRequiredInterceptor`/`AuthenticatedUser`.

## Data model

Owns the `favorites` table (`V17__create_favorites.sql`; full spec in
`docs/architecture/data-model.md` §2.12 and
`docs/architecture/api-contract-professionals-reviews.md` §2.2). Composite PK
(`customer_id`, `professional_id`), no surrogate id. Both FKs are `ON DELETE CASCADE`
(deliberate deviation from this schema's general "`RESTRICT` for core entities" convention —
a favorite row has no independent meaning apart from either party, the same reasoning
already applied to `issue_images`/`availability_slots`/`sos_availability`).

## Assumptions / judgment calls made during implementation

- **Both write endpoints are idempotent by design** — no "already favorited"/"not
  favorited" error case exists anywhere in this package, a deliberate simplification over
  making the client track favorited-state itself before calling.
- **`@IdClass` over `@EmbeddedId`** for the composite key — a judgment call favoring the
  simpler of JPA's two standard mechanisms here, since `Favorite` needs no separate
  embeddable value object exposed through its own API surface.
- **No pagination** on `GET /api/favorites` — consistent with every other list endpoint in
  this codebase to date.

## Status

Implemented and QA-signed-off (zero bugs found on functionality/security) as part of the
professional-profile/reviews/favorites/matching feature set, 2026-08-15 (branch `MS7`, not
yet committed at the time this doc was written). See
`docs/architecture/implementation-plan.md`'s Milestone 8 entry for the full QA summary and
`docs/architecture/api-contract-professionals-reviews.md` for the complete design/contract
this package implements. Unit-tested (`favorites.service.FavoritesServiceTest`).
