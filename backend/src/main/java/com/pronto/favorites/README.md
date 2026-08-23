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
| `service.FavoritesService` | Business logic for all three endpoints, including the idempotency handling for add/remove and the per-entry enrichment (profile image URL resolution via `StorageService#getPresignedUrl` — `StorageClient` was injected directly and called via `resolveUrl` before backend MS9, see "Interactions" below — rating aggregate via `professionals.repository.ReviewAggregateRepository`) for the listing response. |
| `controller.FavoritesController` | `/api/favorites` (`POST`/`GET`), `/api/favorites/{professionalId}` (`DELETE`). Manual path-id parsing, same convention as every other controller in this codebase. |
| `config.FavoritesWebConfig` | A **single blanket-pattern** `RoleRequiredInterceptor(CUSTOMER)` registration covering `/api/favorites` and `/api/favorites/**` — safe here (unlike `reviews`/`professionals`, which mix roles per-route) because every route in this package requires the same single role, mirroring `storage.config.StorageWebConfig`'s blanket-pattern precedent for the same reason. |
| `dto.AddFavoriteRequest` | `{ professionalId }` — the sole write-request shape. |
| `dto.FavoriteProfessionalSummary` / `dto.FavoritesListResponse` | The listing response shapes. `FavoriteProfessionalSummary` is a **deliberately separate, leaner DTO**, not a reuse of `bookings.dto.ProfessionalCard` — this endpoint has no service-location context (unlike a booking listing), so `ProfessionalCard`'s `distanceKm`/`baseTravelTimeMinutes`/`trafficAdjustmentMinutes`/`etaMinutes`/`sameCity` fields simply don't apply here; reusing it would mean either always-null fields on this response or a confusing partially-populated card — the simpler-DTO option was chosen deliberately. |

## Interactions with other packages

- Depends on `professionals` (`ProfessionalRepository` for the existence check on add, and
  `Professional`/`ReviewAggregateRepository`/`ProfessionalRatingAggregate` for the listing
  enrichment), `users` (`UserRepository`, for the favorited professional's display name), and
  `storage` for the profile-image URL. **As of backend MS9 (2026-08-18)**: this dependency
  changed from directly injecting `storage.client.StorageClient` (`resolveUrl(key)`, a
  permanent, non-expiring proxy URL — now removed from that interface entirely) to
  injecting `storage.service.StorageService` (`getPresignedUrl(callerId, key)`, a
  time-limited presigned URL, 300s default TTL) — `toSummary` now threads `caller.id()`
  through, since `getPresignedUrl` reuses the same ownership/visibility check every other
  presign call site goes through. See `storage/README.md` and
  `docs/architecture/backend-ms9-presigned-image-urls-design.md` §9.2.
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

**Backend MS9 — presigned image URLs (2026-08-18)**: `toSummary`'s `storage` dependency
swapped from `StorageClient#resolveUrl` to `StorageService#getPresignedUrl` — see
"Interactions" above. No entity/migration/`ErrorCode` changes in this package; the
substantive fix (permanent proxy URLs replaced by time-limited presigned URLs) lives in
`storage` — see that package's README and
`docs/architecture/backend-ms9-presigned-image-urls-design.md` for the full record.
Backend: 163/163 tests pass.

**Production Roadmap MS1 — eligibility on add, annotation on list (2026-08-22).**
`addFavorite`'s existence check became an eligibility check:
`professionalRepository.existsById` → `existsEligibleById` (the single-row form of
`professionals.ProfessionalEligibility`), still a `400 VALIDATION_ERROR` on `professionalId`,
message now "must reference an existing, bookable professional". Favoriting is a *creation*
path — it is how a customer builds the shortlist they will book from later — so an unapproved
or half-onboarded professional must not be addable to it, and an ineligible professional's id
must not become a way to confirm they exist. `listFavorites` is deliberately asymmetric with
that: it **never deletes anything**, and an already-saved professional who has become
ineligible stays in the list carrying the new `bookable = false` on
`dto.FavoriteProfessionalSummary`. Silently dropping them would be a worse answer to the same
question — the customer chose to save that person, the row is theirs, and a favorites list that
quietly shrinks reads as data loss rather than "this one is not available right now"; an
ineligible professional who finishes onboarding simply becomes bookable again with the
shortlist intact. `bookable` is neutral by design: it says the customer cannot book this person
right now and never *why*, so a favorites list cannot become a channel for learning that a
particular professional was rejected. Adding it made this package's dependency on
`professionals.repository.ProfessionalRepository` slightly wider (`toSummary` now calls
`existsEligibleById` per entry) but introduced no new package dependency, no migration and no
new `ErrorCode`. Extended `favorites.service.FavoritesServiceTest`; live-validated (MS1
report, Validation 10: favoriting an ineligible professional returns `400`). See
`professionals/README.md`'s "Approval lifecycle and marketplace eligibility" section and
`docs/production-roadmap/reports/MS1-report.md`.
