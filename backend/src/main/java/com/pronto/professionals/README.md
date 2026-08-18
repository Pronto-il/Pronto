# `professionals`

## Purpose

Professional profile: category, service area/city, standing price offer, bio, profile
image, and the derived average-rating/review-count aggregate. No approval workflow — v1.0
auto-approves professional accounts (`approval_status` defaults to and stays `'APPROVED'`).

**This README was previously stale** (described only the Milestone 1 entity+repository-only
state). Rewritten 2026-08-15 to reflect this package's first-ever service/controller/DTO/
config layer, added alongside the reviews/favorites/matching feature set. Implements
`docs/architecture/api-contract-professionals-reviews.md` §4.1-4.4.

## Responsibilities

- **Entity/repository layer (Milestone 1, unchanged in shape, extended in columns)**: owns
  the `Professional` JPA entity mapped to the `professionals` table, matching
  `V4__create_professionals.sql` plus this feature set's `V15__alter_professionals_add_profile_fields.sql`
  (adds `bio`, `profile_image_key`, `city`) exactly (`ddl-auto: validate`). Provides
  `ProfessionalRepository` (`findByUserId`, used by `auth` at registration time, by `users`
  for `GET /api/users/me`'s nested `professional` object, and by this package's own new
  service layer to resolve "the caller's own professional row"). Provides a **read-only**
  `Category` entity + `CategoryRepository`, unchanged since Milestone 1 (see "Assumptions"
  below for why `Category` lives here).
- **Self-service profile layer (new this pass)**:
  - `GET /api/professionals/me` — the caller's own profile, enriched with the average-
    rating/review-count aggregate and a resolved profile-image URL. `favorited` is always
    `null` on this self-view.
  - `PUT /api/professionals/me` — allowlist edit of `fullName` (on the underlying `users`
    row), `serviceArea`, `city`, `bio`, `basePrice`. A plain load-mutate-save write (not the
    guarded-atomic-`UPDATE` pattern reserved for concurrency-contended state machines like
    `orders`/`availability_slots`) — a single owner editing their own profile has no
    meaningful concurrent-writer race to guard against.
  - `POST /api/professionals/me/profile-image` — multipart upload, stores under
    `professionals/{professionalId}/profile/{uuid}.{ext}` via `storage.service
    .StorageService#uploadWithKey` (a different key template/code path than `issues`' own
    `customers/{callerId}/issues/temp/...` upload), replaces (not appends to) any prior
    `profile_image_key`.
  - `GET /api/professionals/{professionalId}` — public detail view, either role, no
    route-level gate (authorization for what it needs — the `favorited` flag — happens in
    the service layer). Same response shape as `/me`, but `favorited` is populated only for
    a `CUSTOMER` caller.

## Key classes

| Class | Role |
|---|---|
| `entity.Professional` | JPA entity for `professionals`. `userId`/`categoryId` are plain FK columns (not `@ManyToOne`/`@OneToOne` associations) — unchanged reasoning since Milestone 1. As of this pass, also carries `bio`/`profileImageKey`/`city` (all nullable, plain `String` columns). |
| `entity.Category` | Read-only reference entity for `categories` (8 fixed rows) — unchanged since Milestone 1. |
| `repository.ProfessionalRepository` | `findByUserId` — unchanged since Milestone 1, now also the primary lookup this package's own new service layer uses to resolve "the caller's own professional row." |
| `repository.CategoryRepository` | Plain `JpaRepository`, `existsById`-only usage — unchanged since Milestone 1. |
| `repository.ReviewAggregateRepository` | **New this pass.** A narrow, read-only `Repository<Review, Long>` (not `JpaRepository` — exists purely to expose one aggregate query, not full CRUD) reading `reviews.entity.Review` from outside its owning package, projecting into `ProfessionalRatingAggregate`. Deliberately lives here, not in `reviews` — mirrors the intentional narrow-cross-package-repository pattern `bookings.repository.ProfessionalListingRepository` already established (a package reading another package's entity for its own projection need, rather than that dependency running the other direction). |
| `repository.ProfessionalRatingAggregate` | Projection record, `(averageRating, reviewCount)`. `averageRating` is `null` and `reviewCount` is `0` when the professional has no reviews (JPQL `AVG`/`COUNT` over zero rows) — always exactly one row is returned (aggregate functions never produce zero result rows), so callers never need an `Optional`. |
| `service.ProfessionalsService` | **New this pass.** All business logic for the four endpoints above — resolving "the caller's own professional" (with a defense-in-depth `403 FORBIDDEN` if a `PROFESSIONAL`-role caller somehow has no `professionals` row, not expected to be reachable in practice), the profile-image upload flow, and the shared response-building helper that resolves the profile-image URL and the rating aggregate (via `ReviewAggregateRepository`) for both the self-view and the public-detail-view endpoints. **As of backend MS9 (2026-08-18)**: the profile-image URL is resolved via `storage.service.StorageService#getPresignedUrl(callerId, key)` (a time-limited presigned URL) — this class previously also injected `storage.client.StorageClient` directly and called `resolveUrl` (a permanent, non-expiring proxy URL); that field/constructor param was dropped since this class already separately injected `StorageService` (for `uploadWithKey`). `toResponse` now takes a `callerId` param, threaded in from all three of its call sites. |
| `controller.ProfessionalsController` | **New this pass.** `/api/professionals/me` (`GET`/`PUT`), `/api/professionals/me/profile-image` (`POST`, `multipart/form-data`), `/api/professionals/{professionalId}` (`GET`). Manual path-id parsing, same convention as every other controller in this codebase. |
| `config.ProfessionalsWebConfig` | **New this pass.** A single `RoleRequiredInterceptor(PROFESSIONAL)` registered on the literal paths `/api/professionals/me` and `/api/professionals/me/profile-image` (not a blanket `/api/professionals/**` — this package mixes a `PROFESSIONAL`-only surface with the either-role `{professionalId}` detail route, the same reason `bookings`/`issues` use literal-pattern lists instead of a wildcard). `GET /api/professionals/{professionalId}` is left ungated at the route level. |
| `dto.ProfessionalProfileResponse` | Shared response shape for `GET`/`PUT /api/professionals/me` and `GET /api/professionals/{professionalId}` — `favorited` is `Boolean` (not `boolean`), since it's meaningfully three-valued (`null` on self-views/non-`CUSTOMER` callers, `true`/`false` for a `CUSTOMER` viewing another professional's card). |
| `dto.UpdateProfessionalProfileRequest` | Allowlist DTO for `PUT /api/professionals/me` — deliberately excludes (by omission) `id`, `userId`, `categoryId`, `approvalStatus`, `reliabilityScore`, any rating/review-count field, `profileImageKey` (its own endpoint), and the timestamps. |
| `dto.ProfileImageUploadResponse` | Response shape for the profile-image upload — mirrors `storage.dto.ImageUploadResponse`'s shape, scoped to this endpoint's own key/URL. |

## Interactions with other packages

- Depended on by `auth` (`AuthService`) to create a `Professional` row at professional
  registration and to validate `categoryId` via `CategoryRepository` — unchanged since
  Milestone 1. **Known gap, not fixed by this pass**: `AuthService#register` still only sets
  `serviceArea` from `RegisterRequest`, never `city` — a newly-registered professional has
  `city = NULL` until they call `PUT /api/professionals/me` themselves. See "Assumptions"
  below and `docs/architecture/implementation-plan.md`'s Milestone 8 entry for the full
  consequence this has on `matching`'s distance/ETA computation.
- Depended on by `users` (`UsersService`) to populate `GET /api/users/me`'s nested
  `professional` object — unchanged since Milestone 1.
- Depended on by `issues`/`ai` (both reuse `CategoryRepository` read-only) — unchanged since
  Milestone 2.
- Depended on by `bookings` (`ProfessionalRepository` for lookup/ownership checks,
  `Professional` entity for `categoryId`/`basePrice`/`city`/`profileImageKey`) — unchanged
  dependency edge since Milestone 3, now also reading the three new columns. **Note**:
  `bookings.repository.ProfessionalListingRepository` is a *separate* Spring Data repository
  over the same `Professional` entity, deliberately kept in `bookings` rather than merged
  here — see `bookings/README.md`'s Interactions section and `overview.md` §6's
  2026-08-15 finding for the full "two repositories, one entity, two packages" reasoning
  (not new to this pass, restated here for discoverability).
- **New this pass**: depends on `reviews` (`ReviewAggregateRepository`'s narrow read into
  `reviews.entity.Review`), `favorites` (`FavoriteRepository`, for the `favorited` flag on
  `GET /api/professionals/{professionalId}`), and `storage` (`StorageService`, for
  profile-image upload via `uploadWithKey` and, as of backend MS9, URL resolution via
  `getPresignedUrl` too — a new dependency edge this package did not have in Milestone 1,
  when it had no service layer at all). **As of backend MS9 (2026-08-18)**: this class
  dropped its separate direct `StorageClient` injection (previously used for `resolveUrl`,
  now removed from that interface entirely) — `StorageService`, already injected for
  `uploadWithKey`, now also covers URL resolution, so this package's `storage` dependency is
  a single `StorageService` field, not two. See `storage/README.md` and
  `docs/architecture/backend-ms9-presigned-image-urls-design.md` §9.3.
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`) and
  `RoleRequiredInterceptor`/`AuthenticatedUser` — new dependency edge, this package's
  controller/service layer didn't exist before this pass to need it.

## Data model

Owns the `professionals` table (`docs/architecture/data-model.md` §2.4, amended by
`V15__alter_professionals_add_profile_fields.sql` — adds `bio`/`profile_image_key`/`city`,
the last backfilled from `service_area` for existing rows at migration time only). Also maps
`categories` (§2.1) as a read-only reference entity, unchanged since Milestone 1.

## Assumptions / judgment calls made during implementation

- **`Category` entity placement** — unchanged since Milestone 2's resolution (see prior
  revisions of this doc / `data-model.md` §4 if needed); not revisited by this pass.
- `Professional.approvalStatus` remains a plain `String`, functionally inert in v1.0 —
  unchanged since Milestone 1.
- `reliabilityScore` remains mapped but never set/read by any code path in this package —
  **this feature set's `averageRating`/`reviewCount` (derived live from `reviews`) are a
  separate, newly-populated concept and do not retrofit `reliability_score`**; the original
  Milestone 1 open question ("where does this number come from") is unaffected and still
  open, per `data-model.md` §4.
- **`PUT /api/professionals/me` is a load-mutate-save write, not a guarded atomic `UPDATE`**
  — a deliberate choice, not an inconsistency with `bookings`/`availability`'s guarded-
  transition pattern: those packages guard against *concurrent, adversarial* state changes
  (two customers racing for the same slot); a professional editing their own profile has no
  equivalent contention to guard against, matching `users.service.UsersService#deleteMe`'s
  existing load-mutate-save precedent for the same category of single-owner write.
- **Newly-registered professionals get `city = NULL`** (known gap, not fixed by this pass —
  `AuthService.register()` was out of scope to change here). Combined with
  `matching.ApproximateDistanceEtaStrategy`'s conservative "`null` city = different city"
  default, a new professional shows worse ETA/`sameCity: false` by default in Standard/SOS
  listings until they self-edit via `PUT /api/professionals/me`. Documented as an accepted
  consequence of the approved design, not silently omitted — see
  `docs/architecture/implementation-plan.md`'s Milestone 8 entry and
  `docs/architecture/api-contract-professionals-reviews.md` §9 for the full record.
- **Profile-image retrieval has no ownership check** (any authenticated caller of either
  role can fetch a `professionals/`-prefixed key) — owned and documented by `storage`, not
  this package; see `storage/README.md`'s "Role enforcement" section for the full mechanism.
  This package only generates the key and stores it.

## Status

**Entity/repository layer**: implemented in Milestone 1 (Auth & user management), QA-
validated 2026-08-13 — unchanged summary, see prior implementation-plan entries.

**Self-service profile/controller/service/config layer**: implemented and QA-signed-off
(zero bugs found on functionality/security) as part of the professional-profile/reviews/
favorites/matching feature set, 2026-08-15 (branch `MS7`, not yet committed at the time this
doc was written). See `docs/architecture/implementation-plan.md`'s Milestone 8 entry for the
full QA summary (including the `city = NULL` known gap) and
`docs/architecture/api-contract-professionals-reviews.md` §4.1-4.4 for the complete
design/contract this layer implements. Unit-tested
(`professionals.service.ProfessionalsServiceTest`).

**Backend MS9 — presigned image URLs (2026-08-18)**: `toResponse`'s profile-image-URL
resolution swapped from a direct `StorageClient#resolveUrl` call to
`StorageService#getPresignedUrl(callerId, key)`, and the now-redundant direct
`StorageClient` field/constructor param was dropped — see "Interactions" above. No
entity/migration/`ErrorCode` changes in this package; the substantive fix (permanent proxy
URLs replaced by time-limited presigned URLs) lives in `storage` — see that package's
README and `docs/architecture/backend-ms9-presigned-image-urls-design.md` for the full
record. Backend: 163/163 tests pass.
