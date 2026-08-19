# Pronto — REST API Contract: Professional Self-Service Profile, Reviews, Favorites &
# Distance/ETA Matching

Status: **IMPLEMENTED, QA-signed-off (zero bugs found on functionality/security), backend
only.** Written by `pronto-documentation` retroactively against the real, already-coded
implementation on branch `MS7` (uncommitted at the time of writing) — this is a
documentation-agent-authored **as-built contract spec**, not a plan written ahead of
coding, because coding and QA both finished before a dedicated design doc existed for this
feature set (the gap this doc closes). Every endpoint/behavior described below was verified
directly against the real source (`backend/src/main/java/com/pronto/{professionals,reviews,
favorites,matching,bookings}/**`, migrations `V15`–`V19`), not inferred from a prior plan.

Referred to informally in code comments as "Milestone 8-ish" (e.g.
`common.security.RoleRequiredInterceptor`'s Javadoc); this doc set and
`implementation-plan.md` formally label it **Milestone 8 — Professional Profiles, Reviews,
Favorites & Matching (Distance/ETA)**, the first milestone-equivalent unit of work added
after Milestone 7's hardening/QA pass closed. See `implementation-plan.md`'s Milestone 8
entry for the QA summary and known-gap record; this doc is the design/contract reference
that entry points back to.

**Load-bearing note on scope, read this before anything else below**: this feature set
makes **distance/ETA between a professional and a customer's service address** a
dynamically-computed, in-scope v1.0 field for the first time. This directly **overrides** a
prior, explicit "out of v1.0 scope, permanent" ruling recorded in
`docs/architecture/data-model.md` §4 ("ETA display — resolved, not open"). The override is
by direct, detailed user instruction (exact ETA formulas, peak-hour windows, and required
test coverage were specified directly) — not a `pronto-planning`/`pronto-lead`
reinterpretation of the same source documents. See §5 below for the full resolution record,
and `data-model.md` §4 / `overview.md` §2 for where the override is also recorded (kept
consistent in all three places, per this project's convention of stating overrides
explicitly rather than silently rewriting prior text). **GPS/live-location tracking remains
a separate, still-valid, permanent hard exclusion** (`overview.md` §2) — nothing in this
feature set adds real routing, a live map, or GPS. Distance/ETA here are coarse, same-city/
different-city + peak-hour approximations computed from two city strings, not real
geolocation.

Builds on:
- `docs/architecture/data-model.md` §2.4 (`professionals`), §2.9 (`orders`) — this doc's
  §1/§4 extend both with the new columns from `V15`/`V18`/`V19`; §2 (new) adds full table
  specs for `reviews`/`favorites` (`V16`/`V17`).
- `docs/architecture/api-contract-bookings.md` (Milestones 3/4/6/7) — this doc's §4 amends
  that contract's `GET /api/bookings/professionals`/`sos-professionals` and
  `POST /api/bookings/orders`/`sos-orders` endpoints in place (new query params/body fields/
  response fields); does not restate their full existing behavior, only the delta.
- `docs/architecture/api-contract.md` §0 (JWT/error-envelope conventions, reused verbatim).

Scope: the `professionals` package's **first-ever service/controller/DTO/config layer**
(previously entity+repository only, per Milestone 1); the new `reviews` package (full CRUD);
the new `favorites` package (add/remove/list); the new `matching` package (pure
distance/ETA computation, no endpoint of its own); and the `bookings`/`orders` additions
(service-address snapshot, SOS surcharge, sort modes, enriched `ProfessionalCard`). Frontend
remains deferred project-wide, unchanged by this feature set.

---

## 0. Conventions (reused verbatim from `api-contract.md` §0 / `api-contract-bookings.md` §0)

| Convention | Choice |
|---|---|
| Base paths | `/api/professionals/*` (`professionals`), `/api/reviews*` (`reviews`), `/api/favorites*` (`favorites`). `matching` has no endpoint of its own — it's consumed in-process by `bookings`. |
| Request/response bodies | JSON, `camelCase`. |
| Auth header | `Authorization: Bearer <jwt>` — every endpoint in this doc requires auth. |
| Timestamps | ISO-8601/RFC 3339 with offset. |
| Money fields | JSON number, ≤2 decimal places, `NUMERIC(10,2)`. |
| Path-referenced vs. body-referenced ids | Same rule as `api-contract-bookings.md` §0: a path id that names the resource the URL is about → `404 NOT_FOUND` if malformed/missing; a body-field id referencing another entity → `400 VALIDATION_ERROR`. `reviews`/`favorites`/`professionals` follow this without exception (no `api-contract-bookings.md` §2.4-style deliberate carve-out anywhere in this doc). |
| Role gating mechanism | `common.security.RoleRequiredInterceptor`, registered per-package via a `*WebMvcConfigurer`, same pattern as every prior package. **New here**: `RoleRequiredInterceptor` gained an optional HTTP-method-scoped constructor (`RoleRequiredInterceptor(String requiredRole, String... httpMethods)`), needed because `reviews.config.ReviewsWebConfig` has to distinguish `POST /api/reviews` (CUSTOMER-only) from `GET /api/reviews` (either-role) on the **identical literal path** — the original single-arg constructor (which gates every HTTP method on a path) can't express that. Every pre-existing registration in the codebase is unaffected — the single-arg constructor's "applies to every method" behavior is unchanged. |

---

## 1. Migrations — `V15`–`V19` (all applied, no new migration needed for anything in this doc)

All five are additive `ALTER TABLE`/`CREATE TABLE` migrations, no data loss, no destructive
change to any existing column.

### 1.1 `V15__alter_professionals_add_profile_fields.sql`

Adds `professionals.bio` (`TEXT`, nullable), `professionals.profile_image_key`
(`VARCHAR(500)`, nullable), `professionals.city` (`VARCHAR(100)`, nullable). `city` is
backfilled from the pre-existing `service_area` column for every existing row
(`UPDATE professionals SET city = service_area WHERE city IS NULL`) — a one-time migration
backfill, not an ongoing sync between the two columns (they can diverge after this point;
`service_area` remains a free-text field, `city` is the new field `matching` actually reads).
No `CHECK`/`NOT NULL` on any of the three — deliberate, since existing rows have no
backfillable source of truth for `bio`/`profile_image_key`.

### 1.2 `V16__create_reviews.sql`

New table, `reviews` — see §2.1 below for the full spec.

### 1.3 `V17__create_favorites.sql`

New table, `favorites` — see §2.2 below for the full spec.

### 1.4 `V18__alter_orders_add_service_address.sql`

Adds `orders.service_city`/`service_street`/`service_house_number`/`service_apartment`
(all `VARCHAR`, all nullable at the DB level). **Nullable at the DB level, required at the
API level going forward** — existing orders (created before this migration) have no
backfillable service address, so the DB can't enforce `NOT NULL`; new orders are required to
supply all three non-apartment fields via `CreateOrderRequest`/`CreateSosOrderRequest`'s
`@NotBlank` Bean Validation (§4.2/§4.3 below) instead. Same nullable-at-DB/
required-at-API-boundary pattern already used for `orders.slot_id`/`booked_end` in
Milestone 3/4.

### 1.5 `V19__alter_orders_add_sos_pricing.sql`

Adds `orders.base_price_snapshot` (`NUMERIC(10,2)`, nullable, backfilled from the pre-existing
`final_price` for every existing row) and `orders.sos_surcharge` (`NUMERIC(10,2)`,
`NOT NULL DEFAULT 0`, `CHECK (sos_surcharge >= 0)`). `sos_surcharge` is the one column here
that *is* `NOT NULL` — every order, past and future, has a well-defined surcharge amount (0
for every existing/Standard order). `base_price_snapshot` stays nullable, mirroring
`final_price`'s own existing nullability, for the same "no backfillable source of truth
beyond what we can copy from `final_price`" reasoning.

---

## 2. New table specs (at the same rigor as `data-model.md` §2.x — also mirrored there, §2.11-§2.12)

### 2.1 `reviews`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `BIGINT` (identity) | NO | — | PK |
| `professional_id` | `BIGINT` | NO | — | FK → `professionals(id)` `ON DELETE RESTRICT`. Denormalized from the reviewed order for query convenience (avoids a join for "all reviews of professional X"). |
| `customer_id` | `BIGINT` | NO | — | FK → `users(id)` `ON DELETE RESTRICT`. The reviewer — always the order's own customer, never trusted from the request body (derived server-side from the loaded order, §3.2). |
| `order_id` | `BIGINT` | NO | — | FK → `orders(id)` `ON DELETE RESTRICT`. `UNIQUE` (`ux_reviews_order`) — at most one review per order, the DB-level backstop behind the service-layer `existsByOrderId` pre-check (§3.2). |
| `rating` | `SMALLINT` | NO | — | `CHECK (rating BETWEEN 1 AND 5)`. |
| `comment` | `TEXT` | YES | `NULL` | Optional free text, capped at 2000 chars by Bean Validation (`@Size(max = 2000)` on the DTO, not a DB constraint). |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | |
| `updated_at` | `TIMESTAMPTZ` | NO | `now()` | Bumped on `PUT /api/reviews/{id}`. |

**Constraints**: PK(`id`); FK(`professional_id`) → `professionals(id)` `RESTRICT`;
FK(`customer_id`) → `users(id)` `RESTRICT`; FK(`order_id`) → `orders(id)` `RESTRICT`;
`UNIQUE(order_id)`; `CHECK (rating BETWEEN 1 AND 5)`.
**Indexes**: `idx_reviews_professional_created ON (professional_id, created_at DESC)` — the
hot-path query for "this professional's reviews, newest first" (§4.4 below).

**A review may only be created against an order the caller owns as `CUSTOMER` that has
reached `order_status = 'COMPLETED'`** (`409 REVIEW_ORDER_NOT_COMPLETED` otherwise) — no
review-editing-a-still-open-job case exists. **Immutable fields**: `orderId`/
`professionalId`/`customerId` can never change after creation — `PUT /api/reviews/{id}`'s
DTO carries only `rating`/`comment` (§4.4).

### 2.2 `favorites`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `customer_id` | `BIGINT` | NO | — | **PK, part 1** (composite, no surrogate `id`). FK → `users(id)` `ON DELETE CASCADE`. |
| `professional_id` | `BIGINT` | NO | — | **PK, part 2**. FK → `professionals(id)` `ON DELETE CASCADE`. |
| `created_at` | `TIMESTAMPTZ` | NO | `now()` | When favorited — the sort key for the customer's favorites list (`created_at DESC`). |

**Constraints**: PK(`customer_id`, `professional_id`); FK(`customer_id`) → `users(id)`
`CASCADE`; FK(`professional_id`) → `professionals(id)` `CASCADE`. **Deliberate deviation
from `data-model.md` §0's general "`RESTRICT` for core entities" FK-policy convention** — a
favorite has no independent meaning apart from its customer or its professional (identical
reasoning to `issue_images`/`availability_slots`/`sos_availability`'s existing `CASCADE`
carve-outs in that same convention table), so a deleted user or professional should silently
drop their favorite rows rather than block the delete.
**Indexes**: `idx_favorites_professional ON (professional_id)` — supports the correlated
subquery `bookings`/`professionals` both run to compute a `favorited` flag scoped to one
customer against many professionals' rows, and the reverse ("who favorited professional X,"
not currently exposed by any endpoint but cheap given this index).

**No surrogate id, genuinely composite PK** — mapped in Java via `@IdClass`
(`favorites.entity.FavoriteId`), not `@EmbeddedId` (a judgment call, see
`favorites/README.md`). **Idempotent by design at the service layer**: `POST /api/favorites`
on an already-favorited pair is a silent no-op success (not `409`), and `DELETE
/api/favorites/{professionalId}` on a non-favorited pair is also a silent success (not
`404`) — see §4.6/§4.7.

---

## 3. Error code taxonomy — this feature set's additions

Codes already defined in prior contract docs are reused as-is (`VALIDATION_ERROR`,
`FORBIDDEN`, `NOT_FOUND`, `UNAUTHORIZED`, `CATEGORY_MISMATCH`,
`UNSUPPORTED_IMAGE_TYPE`, etc.). New codes, both added to `common.exception.ErrorCode`:

| `error.code` | HTTP status | Meaning |
|---|---|---|
| `REVIEW_ORDER_NOT_COMPLETED` | 409 | `POST /api/reviews` called against an order whose `order_status != 'COMPLETED'` (still in-progress, or terminal via `CANCELLED`/`REJECTED`/`EXPIRED` — none of those are reviewable). |
| `REVIEW_ALREADY_EXISTS` | 409 | `POST /api/reviews` called against an order that already has a review — returned both from the pre-check (`ReviewRepository.existsByOrderId`) and as the race-condition backstop when a concurrent request wins the `ux_reviews_order` unique-constraint race between that pre-check and the insert. Both paths return the identical error code — the caller can't distinguish "lost the race" from "checked first and it already existed," by design (no meaningful difference to the client). |

**No new error code was needed for `favorites`** — every `favorites` endpoint is designed
to be idempotent (§2.2/§4.6/§4.7), so there is no "already favorited"/"not favorited" error
case to name. **No new error code was needed for `professionals`'s new endpoints** — `404
NOT_FOUND` (professional/user not found), `403 FORBIDDEN` (no professional profile for this
account, on `/me` routes), and `400 VALIDATION_ERROR`/`400 UNSUPPORTED_IMAGE_TYPE` (profile
image content-type, reusing the existing `storage`-owned code) cover every case. **No new
error code was needed for `matching`** — it's pure computation with no failure mode of its
own (a `null`/malformed input degrades to a conservative default, §6.4, never an exception).

---

## 4. Endpoints

### 4.1 `GET /api/professionals/me`

Auth: **yes**. Role: **PROFESSIONAL**.

Returns the caller's own professional profile, enriched with the average-rating/
review-count aggregate (§4.4's cross-package read) and a resolved profile-image URL (if
set). `favorited` is always `null` on this self-view (meaningless for a self-view — a
professional can't favorite themself).

**Behavior**: resolve caller → `403 FORBIDDEN` if the caller (despite passing the route-level
`PROFESSIONAL` role gate) has no `professionals` row at all (defense-in-depth, not expected
to be reachable in practice since every `PROFESSIONAL`-role registration creates one) → load
the caller's own `User`/`Professional` rows → build response.

**Response `200`:**
```json
{
  "id": 43,
  "categoryId": 1,
  "fullName": "דוד כהן",
  "serviceArea": "תל אביב",
  "city": "תל אביב",
  "bio": "אינסטלטור עם 10 שנות ניסיון",
  "basePrice": 150.00,
  "profileImageUrl": "https://.../api/storage/images/professionals/43/profile/...jpg",
  "averageRating": 4.60,
  "reviewCount": 12,
  "approvalStatus": "APPROVED",
  "favorited": null,
  "createdAt": "2026-08-01T09:00:00Z",
  "updatedAt": "2026-08-15T10:00:00Z"
}
```

**Status codes**: `200` · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

### 4.2 `PUT /api/professionals/me`

Auth: **yes**. Role: **PROFESSIONAL**.

**Allowlist DTO** — updates `fullName` (on the underlying `users` row, not `professionals`),
`serviceArea`, `city`, `bio`, `basePrice`. Deliberately excludes (no field exists to set
them through): `id`, `userId`, `categoryId`, `approvalStatus`, `reliabilityScore`, any
rating/review-count field (derived, never client-writable), `profileImageKey` (its own
endpoint, §4.3).

**Request:**
```json
{
  "fullName": "דוד כהן",
  "serviceArea": "תל אביב",
  "city": "תל אביב",
  "bio": "אינסטלטור עם 10 שנות ניסיון",
  "basePrice": 160.00
}
```

**Field validation**: `fullName`/`serviceArea`/`city` required non-blank (`@NotBlank`,
max length 150/150/100 respectively); `bio` optional, max 2000 chars; `basePrice` required,
`@PositiveOrZero`.

**Behavior**: resolve caller's own `Professional`/`User` rows (same `403 FORBIDDEN`
defense-in-depth as §4.1) → mutate both in memory, plain `save()` on each within one
`@Transactional` method (a **load-mutate-save** write, not the guarded-atomic-`UPDATE`
pattern reserved elsewhere in this codebase for concurrency-contended state machines like
`orders`/`availability_slots` — a single owner's own profile edit has no meaningful
concurrent-writer race to guard against) → return the updated profile (same shape as §4.1).

**Status codes**: `200` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

### 4.3 `POST /api/professionals/me/profile-image`

Auth: **yes**. Role: **PROFESSIONAL**. `multipart/form-data`, field name `file`.

Uploads a profile image, storing it under a `professionals/{professionalId}/profile/
{uuid}.{ext}` key (via `storage.service.StorageService#uploadWithKey` — a different code
path than `issues`' `customers/{callerId}/issues/temp/...` upload, reusing the same
underlying `StorageClient` abstraction). Sets `professionals.profile_image_key` to the new
key, replacing (not appending to) any prior value — a professional has at most one profile
image at a time, no history/gallery.

**Behavior**: resolve caller's own `Professional` (§4.1's `403` check) → validate content
type via the existing `storage.ImageContentType` allow-list (`image/jpeg`/`image/png`/
`image/webp`) → `400 UNSUPPORTED_IMAGE_TYPE` otherwise → upload → persist the new key →
return.

**Response `201`:**
```json
{
  "imageKey": "professionals/43/profile/9f1c2e4a-....jpg",
  "imageUrl": "https://.../api/storage/images/professionals/43/profile/9f1c2e4a-....jpg",
  "contentType": "image/jpeg",
  "sizeBytes": 204800
}
```

**Status codes**: `201` · `400 UNSUPPORTED_IMAGE_TYPE` · `401 UNAUTHORIZED` ·
`403 FORBIDDEN` · `502 STORAGE_SERVICE_ERROR` (reused from `storage`).

**Retrieval** of the resulting `imageUrl` is publicly readable by any authenticated caller
of either role, no ownership check — see `storage/README.md`'s "Role enforcement" section
(`ImageKeyUtils.isPubliclyReadable`) for the full mechanism; not re-specified here.

### 4.4 `GET /api/professionals/{professionalId}`

Auth: **yes**. Role: **either** (no route-level gate — same "route-level gate abstains,
service layer authorizes what it needs to" precedent as `GET /api/issues/{id}`).

Public professional detail view — same response shape as §4.1, but `favorited` is populated
only when the caller's role is `CUSTOMER` (a `PROFESSIONAL` caller, including the
professional viewing their own card by id, always gets `favorited: null`).

**Behavior**: load professional by path id → `404 NOT_FOUND` if missing → if caller role is
`CUSTOMER`, resolve `favorited` via `FavoriteRepository.existsByCustomerIdAndProfessionalId`
→ build response (same average-rating/review-count aggregate as §4.1, via
`professionals.repository.ReviewAggregateRepository` — a narrow, read-only cross-package
query into `reviews.entity.Review`, the same intentional pattern
`bookings.repository.ProfessionalListingRepository` already established for reading
`professionals`/`sos_availability` from outside their owning packages).

**Status codes**: `200` · `401 UNAUTHORIZED` · `404 NOT_FOUND`.

### 4.5 `POST /api/reviews`

Auth: **yes**. Role: **CUSTOMER**.

**Request:**
```json
{ "orderId": 900, "rating": 5, "comment": "עבודה מצוינת, הגיע בזמן" }
```

**Field validation**: `orderId` required; `rating` required, `1`–`5` inclusive
(`@Min(1) @Max(5)`); `comment` optional, max 2000 chars.

**Behavior:**
1. Resolve caller; `403 FORBIDDEN` if `role != CUSTOMER` (route-level gate, §0).
2. Load order by `orderId` → `404 NOT_FOUND` if missing.
3. `order.customerId != caller.id` → `403 FORBIDDEN`.
4. `order.orderStatus != COMPLETED` → `409 REVIEW_ORDER_NOT_COMPLETED`.
5. `ReviewRepository.existsByOrderId(order.id)` → `409 REVIEW_ALREADY_EXISTS`.
6. Insert the review — `professionalId`/`customerId` derived server-side from the loaded
   order, **never trusted from the request body** (the DTO carries no such fields at all).
   A `DataIntegrityViolationException` on the insert (the `ux_reviews_order` race backstop)
   is also mapped to `409 REVIEW_ALREADY_EXISTS`.
7. Return `201` with the created review.

**Response `201`:**
```json
{
  "id": 501,
  "professionalId": 43,
  "customerId": 42,
  "customerName": "משה לוי",
  "orderId": 900,
  "rating": 5,
  "comment": "עבודה מצוינת, הגיע בזמן",
  "createdAt": "2026-08-15T12:00:00Z",
  "updatedAt": "2026-08-15T12:00:00Z"
}
```

**Status codes**: `201` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND` · `409 REVIEW_ORDER_NOT_COMPLETED` · `409 REVIEW_ALREADY_EXISTS`.

### 4.6 `GET /api/reviews?professionalId={id}`

Auth: **yes**. Role: **either** (no route-level gate — shares the identical literal path
`/api/reviews` with the `CUSTOMER`-only `POST` above, distinguished by
`RoleRequiredInterceptor`'s new HTTP-method-scoped constructor, §0).

**Behavior**: `professionalId` required query param → `400 VALIDATION_ERROR` if
missing/non-positive/unparsable → `professionalRepository.existsById` → `404 NOT_FOUND` if
the professional itself doesn't exist → load every review for that professional
(`created_at DESC`) → compute `averageRating` (rounded half-up to 2 decimals, `null` if zero
reviews — never `0.00`) and `reviewCount` → return both the list and the aggregate in one
response (no separate aggregate-only endpoint).

**Response `200`:**
```json
{
  "professionalId": 43,
  "averageRating": 4.60,
  "reviewCount": 12,
  "reviews": [
    { "id": 501, "professionalId": 43, "customerId": 42, "customerName": "משה לוי",
      "orderId": 900, "rating": 5, "comment": "עבודה מצוינת, הגיע בזמן",
      "createdAt": "2026-08-15T12:00:00Z", "updatedAt": "2026-08-15T12:00:00Z" }
  ]
}
```

**Status codes**: `200` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `404 NOT_FOUND`.

### 4.7 `PUT /api/reviews/{reviewId}`

Auth: **yes**. Role: **CUSTOMER**, owner-only.

**Request:** `{ "rating": 4, "comment": "עדכון: היו עיכובים קלים" }` — allowlist DTO,
`orderId`/`professionalId`/`customerId` are immutable and never appear here.

**Behavior**: load review → `404 NOT_FOUND` if missing → `review.customerId != caller.id` →
`403 FORBIDDEN` → atomic guarded `UPDATE ... WHERE id = :reviewId AND customer_id =
:customerId` (mirrors `availability.repository.AvailabilitySlotRepository#updateSlotTimes`'s
pattern) → `0` affected rows at this point means the row was concurrently deleted between
the ownership-check read and this write → `404 NOT_FOUND` (not a `409` — the row is now
genuinely gone, there's nothing to conflict with) → reload and return.

**Status codes**: `200` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND`.

### 4.8 `DELETE /api/reviews/{reviewId}`

Auth: **yes**. Role: **CUSTOMER**, owner-only. Same ownership pattern as §4.7 (load → check
→ atomic guarded `DELETE ... WHERE id = ? AND customer_id = ?` → `404` if `0` rows).
`204 No Content` on success, no body.

**Status codes**: `204` · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND`.

### 4.9 `POST /api/favorites`

Auth: **yes**. Role: **CUSTOMER**.

**Request:** `{ "professionalId": 43 }`.

**Behavior**: `professionalId` must reference an existing professional (`400
VALIDATION_ERROR` otherwise, the same body-field-reference convention `CreateOrderRequest`'s
`professionalId` already uses) → **idempotent**: if the `(customer, professional)` pair
already exists, silently return success with no error; otherwise insert (a
`DataIntegrityViolationException` race — another request won the PK-uniqueness race between
the existence pre-check and this insert — is also swallowed as success, not surfaced as an
error). **`204 No Content` on success, always** — there is no "already favorited" error
path by design, so no `409` code exists for this endpoint (§3).

**Status codes**: `204` · `400 VALIDATION_ERROR` · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

### 4.10 `DELETE /api/favorites/{professionalId}`

Auth: **yes**. Role: **CUSTOMER**. Also idempotent — `204 No Content` regardless of whether
the pair existed (no `404` for "wasn't favorited"). A malformed/non-positive path id (not
"doesn't exist," which is silently a no-op, but genuinely unparsable) still `404 NOT_FOUND`s
via the standard path-id-parsing convention (§0).

**Status codes**: `204` · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND` (malformed
id only).

### 4.11 `GET /api/favorites`

Auth: **yes**. Role: **CUSTOMER**.

The caller's own favorited professionals, `created_at DESC`, each entry enriched with a
lean, dedicated `FavoriteProfessionalSummary` (not a reuse of `bookings.dto.ProfessionalCard`
— see §6.5 for why). A favorited professional row missing at read time (would only happen if
`ON DELETE CASCADE` somehow raced with this read, not expected in practice) is silently
filtered out of the response rather than causing a `500`.

**Response `200`:**
```json
{
  "favorites": [
    {
      "professionalId": 43,
      "fullName": "דוד כהן",
      "serviceArea": "תל אביב",
      "city": "תל אביב",
      "basePrice": 150.00,
      "profileImageUrl": "https://.../professionals/43/profile/....jpg",
      "averageRating": 4.60,
      "reviewCount": 12,
      "favoritedAt": "2026-08-10T08:00:00Z"
    }
  ]
}
```

**Status codes**: `200` · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

---

## 5. ETA/distance scope — explicit override record (load-bearing, read this section)

`docs/architecture/data-model.md` §4 previously stated, verbatim:

> **ETA display — resolved, not open**: PRD §7.4/§7.5 wireframe text mentions ETA on SOS
> cards and the tracking screen, but PRD §3.4.8/§3.5.5 (functional requirements, more
> authoritative than the wireframe description) explicitly label ETA/tracking display as
> **"(future version)"** — i.e. out of v1.0 scope, consistent with the already-settled GPS
> exclusion. No ETA column was added to this schema.

**This ruling is now overridden, by explicit, detailed user instruction, 2026-08-15** — not
a `pronto-planning`/`pronto-lead` reinterpretation of the same PRD text. The user directly
specified the exact ETA/distance computation this feature set implements: same-city vs.
different-city base travel times (15 / 40 minutes), two peak-hour windows in
`Asia/Jerusalem` local time (`[08:00,11:00)`, `[15:00,18:00)`) with fixed traffic surcharges
(+20 / +30 minutes), and required unit-test coverage of the exact boundary behavior — all
implemented in `matching.ApproximateDistanceEtaStrategy` (§6 below) and consumed by
`bookings.service.BookingsService` to enrich `GET /api/bookings/professionals`/
`sos-professionals`'s `ProfessionalCard` entries and to power the new `sort=FASTEST` mode
(§7).

**What changed, precisely**: "distance/ETA between a professional and a customer, computed
dynamically per search request" is now in v1.0 scope. **What did not change**: no ETA value
is ever persisted (`EtaResult`, §6.2, is computed fresh on every listing request, never
written to any table — `matching` owns no table and no migration); the tracking screen
(`GET /api/bookings/orders/{orderId}`) gained **no** new field from this feature set — ETA
is a search/listing-time concept only, not a tracking-screen concept, so PRD §3.4.8/§3.5.5's
"ETA/tracking display, future version" framing is **not** contradicted for the tracking
screen specifically, only for the professional-search/listing context, which is the part
this override actually touches. GPS/live-location tracking remains completely untouched and
still out of scope — nothing here computes or exposes a real geographic route, live position,
or map.

**Where this override is recorded** (kept mutually consistent, not contradictory in any of
the three places a reader might look):
1. `docs/architecture/data-model.md` §4 — the original entry is kept verbatim (per this
   project's convention of preserving the historical record, the same way §3 item 10 there
   handles the `REJECTED`-status override), with a new note directly beneath it stating the
   override and pointing back to this section.
2. `docs/architecture/overview.md` §2 (resolved-decisions table) — a new row records the
   override at the same table granularity as every other resolved decision, distinguishing
   it clearly from the still-valid, separate GPS/live-tracking exclusion row.
3. This section (§5), the canonical full write-up both of the above point back to.

**FURTHER OVERRIDDEN (Active Booking Floating Indicator feature, 2026-08-17)** — the "no ETA
value is ever persisted" / "the tracking screen (`GET /api/bookings/orders/{orderId}`) gained
**no** new field" claims two paragraphs above are now narrowly superseded, kept verbatim
above per the same preserve-the-historical-record convention. `orders` gained a new nullable
column, `expected_arrival_at` (`V23__alter_orders_add_expected_arrival_at.sql`), computed
once via this same `matching.DistanceEtaStrategy#calculate` call and persisted by
`bookings.service.BookingsService.onTheWay` at the `ON_THE_WAY` transition — `GET
/api/bookings/orders/{orderId}` now does carry this field, and it drives a live countdown on
the tracking screen plus the new floating active-order indicator. What is still true and
unchanged: `EtaResult` (§6.2) is still computed fresh on every listing request and the
`matching` package itself still persists nothing and owns no table — it is the **caller**
(`bookings`) that persists the *result* of one specific `calculate()` call, once, at one
specific transition, not a new responsibility inside `matching`. GPS/live-location tracking
remains completely untouched. Full record: `docs/architecture/active-booking-floating-
indicator.md` §0.1 (canonical write-up); also reflected in `overview.md` §2 and
`data-model.md` §4.

---

## 6. `matching` package — distance/ETA design (no endpoint, consumed in-process)

### 6.1 Why a separate package, not inline in `bookings`

Pure, stateless computation (`DistanceEtaStrategy` interface + one implementation,
`ApproximateDistanceEtaStrategy`) — no `@Entity`, no `@Repository`, no controller, no table,
no migration. Kept out of `bookings` so the computation is independently unit-testable
(`ApproximateDistanceEtaStrategyTest`, 12 cases: 4 same/different-city × peak/off-peak
combinations, case-insensitive/trimmed city matching, `null`-professional-city handling, and
all 6 named half-open-interval boundary times) without needing any Spring context, database,
or HTTP layer — the same "kept separate for independent testability" reasoning `ai` already
used relative to `issues` in Milestone 2.

### 6.2 Inputs/outputs

`DistanceEtaStrategy.calculate(String professionalCity, ServiceLocation customerLocation,
Instant requestTime)` → `EtaResult(sameCity, distanceKm, baseTravelTimeMinutes,
trafficAdjustmentMinutes, etaMinutes)`. `ServiceLocation(city, street, houseNumber,
apartment)` is the same shape `bookings.controller.BookingsController` parses from
`GET /api/bookings/professionals`/`sos-professionals`'s new `city`/`street`/`houseNumber`/
`apartment` query params (§7.1) — `street`/`houseNumber`/`apartment` are accepted and
validated (§7.1) but **not currently used by the distance/ETA computation itself**, which
reads only `city` — carried in the record for forward-compatibility (a future, more precise
strategy implementation could use them) and because the same address shape is what gets
persisted onto `orders.service_*` at booking time (§8).

### 6.3 The approximation model, exactly as implemented

- **Same city** (case-insensitive, trimmed string equality between the professional's
  `city` and the customer's `ServiceLocation.city`): base travel time **15 minutes**,
  placeholder distance **8.0 km**.
- **Different city** (or either city string is blank/unset after trimming): base travel time
  **40 minutes**, placeholder distance **35.0 km**.
- **Peak hours**, evaluated against `Asia/Jerusalem` local time (hardcoded, not
  configurable — no multi-region deployment exists or is planned), two half-open windows:
  `[08:00, 11:00)` and `[15:00, 18:00)`. Inside either window: **+20 minutes** (same city) or
  **+30 minutes** (different city) added to the base travel time. Outside both: **+0**.
- `etaMinutes = baseTravelTimeMinutes + trafficAdjustmentMinutes`.
- All five numeric constants (15/40 min base, 8.0/35.0 km, +20/+30 min peak surcharge) are
  **coarse, documented placeholders/approximations, not real routing/traffic-provider
  data** — good enough to produce a stable, deterministic "cheapest vs. fastest" ordering
  signal, nothing more. The peak-hour windows and surcharge values were given directly by
  the product requirement itself (the user's explicit instruction), not invented by
  `pronto-planning`/`pronto-coding` — the base-travel-time/distance figures **were** chosen
  as reasonable placeholders by the implementation, since no source document specified them.

### 6.4 `null`/missing-city handling — conservative default, deliberate

A `null` **professional** city (e.g. a professional who registered before this feature
existed and has never edited their profile, §9) is treated as "**different city**" — never
as "matches everywhere." This is a deliberate conservative default: silently treating an
unset city as always-matching would *understate* distance/ETA for exactly the professionals
whose location is least known, the opposite of the safe direction to be wrong in. See §9 for
the concrete, accepted consequence this produces for newly-registered professionals.

### 6.5 Where results are (and aren't) used

Consumed exclusively by `bookings.service.BookingsService`'s `enrichAndSort` step, applied
to every `ProfessionalCard` in a `GET /api/bookings/professionals`/`sos-professionals`
response (§7), using one uniform `requestTime = Instant.now()` for the whole listing (so
every card in one response is evaluated against the same instant, not re-evaluated per
card). **Never used by**: `favorites` (§4.11's `FavoriteProfessionalSummary` is a
deliberately leaner DTO with no service-location context to compute against — see that
section's Javadoc for the "favor the simpler option" judgment call), `professionals` (§4.1/
§4.4's profile responses), or `reviews`. **Never persisted** — recomputed fresh on every
listing request, per §5's scope note.

---

## 7. `bookings` amendments — service-location query params, `sort`, enriched `ProfessionalCard`

Amends `api-contract-bookings.md` §2.2 (`GET /api/bookings/professionals`) and §2.12
(`GET /api/bookings/sos-professionals`) in place. Full existing behavior (issue-ownership/
urgency-type/bookable-status checks, category filtering, soft-delete exclusion) is
**unchanged** — only the additions below are new.

### 7.1 New required query params: `city`, `street`, `houseNumber` (+ optional `apartment`)

Both listing endpoints now require these four params (three mandatory, `apartment`
optional), representing the customer's service address for this specific listing request —
**this is a per-request input, not read from any stored customer address** (no such stored
address concept exists anywhere in this schema). Missing/blank `city`/`street`/
`houseNumber` → `400 VALIDATION_ERROR`, one `FieldError` per missing field (all reported in
one response, same "collect every failure" spirit `@Valid` body validation already uses
elsewhere) — parsed manually in `BookingsController`, not via `@RequestParam` presence
checks alone, so multiple simultaneous missing fields are all reported at once rather than
failing fast on the first.

### 7.2 New optional query param: `sort` (`CHEAPEST` | `RECOMMENDED` | `FASTEST`, both
endpoints default to `CHEAPEST`)

`ProfessionalSort` has three values. `parseSort(String raw, ProfessionalSort defaultSort)`
takes an explicit default **per call site** (not a single hardcoded default shared by both
endpoints), but both listing endpoints currently pass the same value:
- `GET /api/bookings/professionals` (Standard): defaults to `CHEAPEST`.
- `GET /api/bookings/sos-professionals` (SOS): also defaults to `CHEAPEST`.

**Reconciliation note (MS3/MS4 product-corrections pass, 2026-08-17)**: an earlier,
uncommitted draft of this work briefly had the SOS listing defaulting to `FASTEST` instead,
paired with a frontend `Recommended | Fastest` chip pair for SOS (dropping `Cheapest` from
that flow) — introduced without authorization by an agent that went out of scope on an
unrelated task, and asserted in this section's prose as if it were a settled product
decision ("SOS prioritizes speed by default..."). That was reconciled back to the state
described above before the corrections branch was finalized: **both flows now expose an
identical, 2-way `Recommended | Cheapest` chip toggle** (Recommended shown first), and both
listing endpoints default to `CHEAPEST` when `sort` is omitted. `FASTEST` remains a valid
enum value and ranking (see below) — reachable via a direct API call — but is not wired to
any chip in either flow this pass. See `docs/architecture/ms3-ms4-corrections-design.md` §3
for the full reconciliation record.

Sort-value behavior:
- `CHEAPEST`: the pre-existing DB-level `ORDER BY base_price ASC` — **unchanged behavior**,
  not a re-sort.
- `RECOMMENDED`: an **in-memory re-sort** of the already-fetched, already-enriched list by
  `averageRating` descending — professionals with a `null` `averageRating` (no reviews yet)
  sort last — tiebroken by `reviewCount` descending. Necessarily in-memory, not a DB
  `ORDER BY` — the average/count are read via correlated subqueries per §7.3, not a
  precomputed, sortable column.
- `FASTEST`: an **in-memory re-sort** of the already-fetched, already-ETA-enriched list by
  `etaMinutes` ascending. Necessarily in-memory, not a DB `ORDER BY` — `etaMinutes` is never
  a database column (§5/§6.5, computed fresh per request), so no SQL query could sort by it.
- Any non-blank value that isn't `CHEAPEST`/`RECOMMENDED`/`FASTEST` → `400 VALIDATION_ERROR`
  (message: "must be one of CHEAPEST, RECOMMENDED, FASTEST").
- Distance/ETA enrichment and the rating/review-count/favorited enrichment (§6.5/§7.3) both
  happen **unconditionally**, regardless of `sort` — even a `CHEAPEST`-sorted response carries
  fully-computed `distanceKm`/`etaMinutes`/`averageRating`/`reviewCount`/etc. on every card;
  `sort` only controls final ordering, never whether the fields are populated.

**Frontend consumption** (`frontend/src/features/professionals/ProfessionalList.tsx`): both
the Standard booking flow (`BookingFlowPage.tsx`) and the SOS booking flow
(`SosBookingFlowPage.tsx`) offer the identical `RECOMMENDED`/`CHEAPEST` chip pair
(`STANDARD_SORT_OPTIONS`/`SOS_SORT_OPTIONS`, both `[RECOMMENDED, CHEAPEST]`, label "הכי
מומלצים" for `RECOMMENDED` and "הזולים ביותר" for `CHEAPEST`), each defaulting to `CHEAPEST`
on first load. Neither flow exposes a `FASTEST` chip — `FASTEST` is a valid backend
enum/query value and ranking (below), reachable only via a direct API call, not through
either flow's UI in this pass. See the reconciliation note above for why this replaced an
earlier, differently-scoped (and unauthorized) draft of this same feature.

### 7.3 `ProfessionalCard`'s new fields

`ProfessionalCard` (§2.2's response shape) gains: `profileImageUrl` (resolved via
`StorageClient`, `null` if the professional has no profile image), `averageRating`/
`reviewCount` (the same aggregate §4.4 computes, via a correlated `AVG`/`COUNT` subquery
over `reviews` — deliberately a correlated subquery, not `LEFT JOIN + GROUP BY`, to avoid a
wide, error-prone `GROUP BY` column list across three joined tables), `favorited` (a
correlated `COUNT` subquery over `favorites` scoped to the calling customer, `true` iff
`> 0`), `sameCity`/`distanceKm`/`baseTravelTimeMinutes`/`trafficAdjustmentMinutes`/
`etaMinutes` (§6's `EtaResult`, computed in Java post-fetch, never in SQL — the DB query
itself only ever reads `professionals.city`/`profile_image_key`, threading the raw values
through for the service layer to resolve/compute afterward).

**Example, one entry** (`sort=FASTEST` — an API-level example only; neither the Standard nor
the SOS listing's frontend UI can currently produce this value, see §7.2):
```json
{
  "professionalId": 43,
  "fullName": "דוד כהן",
  "serviceArea": "תל אביב",
  "basePrice": 150.00,
  "reliabilityScore": null,
  "city": "תל אביב",
  "profileImageUrl": "https://.../professionals/43/profile/....jpg",
  "averageRating": 4.60,
  "reviewCount": 12,
  "favorited": true,
  "sameCity": true,
  "distanceKm": 8.0,
  "baseTravelTimeMinutes": 15,
  "trafficAdjustmentMinutes": 0,
  "etaMinutes": 15
}
```

### 7.4 `POST /api/bookings/orders` / `POST /api/bookings/sos-orders` — service-address body fields

Both request DTOs gain required `serviceCity`/`serviceStreet`/`serviceHouseNumber`
(`@NotBlank`) and optional `serviceApartment`, persisted verbatim onto the new
`orders.service_*` columns (§1.4) at order-creation time — a **snapshot** of the address at
booking time, not a reference to any stored customer-address record (none exists). No new
validation beyond non-blank presence — no format/geocoding check against `matching` or
anywhere else (the value isn't re-validated against the listing request's own `city`/
`street`/`houseNumber`, so a customer could in principle book against a different address
than the one they searched with; not prevented, not flagged as a defect — see §9).

**Extended, MS3/MS4 product-corrections pass (2026-08-17)**: both request DTOs gain 3 further
**optional** fields — `serviceFloor`/`serviceEntrance`/`serviceAddressNotes` — persisted onto
3 new `orders` columns added by `V22__alter_orders_add_service_address_details.sql`
(`service_floor VARCHAR(20)`/`service_entrance VARCHAR(20)`/`service_address_notes
VARCHAR(500)`, all nullable at the DB level, no `@NotBlank`), bringing the service-address
snapshot to the full 7-field shape already established on `users.default_*` (§1). Persisted
regardless of whether the customer's `AddressSelectionStep` (frontend) used their saved
default address or a one-off custom address — the backend accepts and stores whatever 7
values arrive in the request body without needing to know which source they came from; no
`addressSource` field was added to either request or to `orders`. `OrderResponse`/
`OrderDetailResponse` (§7.5) mirror the same 3 new fields.

### 7.5 SOS surcharge — `basePriceSnapshot` / `sosSurcharge` split

`OrderResponse`/`OrderDetailResponse` gain `basePriceSnapshot` (the professional's
`base_price` at the moment of booking, copied verbatim — same "snapshot, not a live
reference" reasoning `final_price` already used) and `sosSurcharge`. `finalPrice =
basePriceSnapshot + sosSurcharge` when both are non-null (computed once at order-creation
time, stored, not recomputed later). **Standard orders**: `sosSurcharge = 0.00`, always,
explicitly set in the insert (not relying on the DB column's `DEFAULT 0` alone in that code
path). **SOS orders**: `sosSurcharge = 50.00`, a flat, hardcoded `static final BigDecimal`
constant (`BookingsService.SOS_SURCHARGE_AMOUNT`) — **explicitly flagged in the code's own
Javadoc as a placeholder/approximation business figure, not sourced from any pricing model or
source document**. See §9 for this flagged as a known, deliberate placeholder.

---

## 8. `orders.service_*` — relationship to `matching.ServiceLocation`

The two are structurally identical in shape (`city`/`street`/`houseNumber`/`apartment`) but
serve different purposes and are **not** the same value flowing through the system
unchanged: `ServiceLocation` (§6.2) is the ephemeral, per-listing-request input to
`DistanceEtaStrategy`, discarded after the listing response is built; `orders.service_*`
(§7.4) is the **persisted snapshot** written once, at order-creation time, from the
order-creation request body's own `serviceCity`/`serviceStreet`/`serviceHouseNumber`/
`serviceApartment` fields — which are supplied independently by the client, not
automatically carried over from whatever `city`/`street`/`houseNumber`/`apartment` the
customer used on the preceding listing call (§7.4's flagged gap). `matching` itself never
reads or writes `orders.service_*` — that persistence is entirely `bookings`/`Order`'s own
responsibility (§7.5), consistent with `matching`'s package-level contract of owning no
table.

---

## 9. Flagged decisions / known gaps (this doc's own findings, not fixed here — code is out
of scope for `pronto-documentation`)

Every item below was verified directly against the real code; none are hypothetical.

1. **Newly-registered professionals get `city = NULL`** — `auth.service.AuthService#register`
   (unchanged by this feature set) still only sets `professionals.service_area` from
   `RegisterRequest`, never `city`. `V15`'s backfill (§1.1) only ran once, against
   already-existing rows at migration time — it does not retroactively apply to rows
   inserted afterward. Combined with §6.4's conservative `null`-city-means-different-city
   default, a professional who registers after this feature shipped and never visits `PUT
   /api/professionals/me` (§4.2) to set their `city` will show `sameCity: false` and the
   worse (different-city) ETA/distance figures to every customer, regardless of their actual
   `service_area`. **Not a bug** — an accepted, documented consequence of the approved
   design (conservative-default + no registration-flow change in this feature's scope), not
   silently omitted. Recorded here and in `implementation-plan.md`'s Milestone 8 entry as a
   known, non-blocking gap. **Not fixed by this doc** — fixing it would mean either editing
   `AuthService.register()` (backend source, out of scope for a documentation-only pass) or
   changing the ETA strategy's default (a design change, not a documentation task) —
   reported to the user/lead instead.
2. **`SOS_SURCHARGE_AMOUNT = 50.00` is a flat, hardcoded placeholder**, not sourced from any
   pricing model, PRD figure, or user-provided business number — flagged directly in the
   implementing code's own Javadoc (§7.5), restated here for visibility at the design-doc
   level too. Trivial to change later (a single constant, no migration implied either way,
   same pattern as Milestone 5's `STANDARD_PENDING_TIMEOUT`/`SOS_PENDING_TIMEOUT`).
3. **Distance/ETA base-travel-time and distance-km figures (15/40 min, 8.0/35.0 km) are
   `pronto-coding`-chosen placeholders**, not sourced from any routing/traffic provider or
   source document — only the peak-hour windows and their surcharge minutes were given
   directly by the user's own instruction (§6.3). Both categories are equally "not real
   routing data," but worth distinguishing their provenance for anyone revisiting these
   constants later.
4. **A booking's `serviceCity`/`serviceStreet`/`serviceHouseNumber`/`serviceApartment` is
   not required or verified to match the `city`/`street`/`houseNumber`/`apartment` query
   params the customer used on the preceding listing call** (§7.4) — nothing links the two
   requests. A customer could search professionals near address A, then book against address
   B in the same request flow, with no validation error and no re-computed ETA against the
   booking address. Judged low-risk/low-impact (the same category of gap as the
   already-accepted "same `imageKey` attached to two different issues" gap from Milestone 2,
   `overview.md` §6) — not built as a cross-check in this pass, not silently unconsidered.
5. **`GET /api/favorites`'s `FavoriteProfessionalSummary` carries no distance/ETA fields at
   all** (§6.5) — a deliberate, simpler-option judgment call (no service-location context
   exists for this endpoint, unlike a booking listing), not an oversight or a missed
   enrichment opportunity.
6. **No review-edit time window** — `PUT /api/reviews/{id}` has no "only within N days of
   creation" restriction; a review can be edited indefinitely by its owning customer. Not
   specified by any source document, not built as a restriction, not flagged as a defect —
   simplest correct behavior given no stated requirement either way.
7. **No pagination on `GET /api/reviews`/`GET /api/favorites`** — consistent with every
   other list endpoint in this codebase to date (`bookings`'s professional/slot/order
   listings), per the project's already-established "acceptable at MVP scale, revisit if a
   real payload-size problem appears" posture (`hardening-plan.md` §5.5).
8. **`professionals.reliability_score` remains permanently `null`/unused by this feature
   set** — this feature introduces `averageRating`/`reviewCount` (derived live from
   `reviews`) as a genuinely populated, separate concept; it does **not** retrofit
   `reliability_score` to be computed from review data or anything else. `data-model.md` §4's
   pre-existing "where does this number come from" open question is **unaffected and still
   open** — not resolved, not touched, by this feature.

---

## 10. Cross-references — what this doc does *not* restate

- Full existing `bookings` behavior (issue-ownership/urgency/bookable checks,
  accept/reject/cancel/on-the-way/complete, notification hooks, expiry sweep) — unchanged,
  see `api-contract-bookings.md` in full and `bookings/README.md`.
- `storage`'s upload/retrieval mechanics, including the `professionals/`-prefix
  public-readability rule this feature set's profile images rely on — see
  `storage/README.md` (verified accurate against the real code during this same
  documentation pass, no changes needed).
- JWT/error-envelope/role-gating base mechanism — `api-contract.md` §0/§1, reused verbatim
  everywhere in this doc.

---

## 11. MS11 — Services & Sub-services (new endpoints, 2026-08-19)

Full design record: `docs/architecture/product-ms11-sub-services-design.md`. Adds a new
child reference table one level below `categories` (`sub_services`, §2.1 of that doc /
`data-model.md` §2.15) and a professional's selected subset of their own category's
sub-services (`professional_sub_services`, §2.2 / `data-model.md` §2.16). Does **not**
reopen the single-category-per-professional decision (§0/§1 of that doc) — sub-services are
a finer-grained descriptive attribute *within* the one category a professional already has.

### 11.1 `GET /api/categories`

Auth: **none** — public, unauthenticated. `professionals.controller.CategoriesController`.

Returns every category, ordered by `display_order`, each with its `subServices` nested list
(also ordered by `display_order`, scoped within that category). The concrete mechanism
satisfying "support future service/sub-service changes without hardcoding the entire
structure into the UI" — adding/renaming/reordering a category or sub-service is a migration,
not a frontend redeploy.

**Response `200`:**
```json
[
  {
    "id": 1,
    "code": "plumbing",
    "nameHe": "אינסטלציה",
    "nameEn": "Plumbing",
    "displayOrder": 1,
    "subServices": [
      { "id": 101, "code": "plumbing_unclog", "nameHe": "פתיחת סתימות", "nameEn": "Unclogging", "displayOrder": 1 }
    ]
  }
]
```

**Status codes**: `200` only (no auth/role gate to fail, no path/body input to validate).

### 11.2 `GET /api/professionals/me/sub-services`

Auth: **yes**. Role: **PROFESSIONAL**.

Returns only the caller's currently-selected sub-service ids — deliberately not full
sub-service objects, since the frontend already has (or separately fetches) the full catalog
via §11.1 and only needs to know which ids are checked.

**Response `200`:**
```json
{ "subServiceIds": [101, 102, 104] }
```

**Status codes**: `200` · `401 UNAUTHORIZED` · `403 FORBIDDEN`.

### 11.3 `PUT /api/professionals/me/sub-services`

Auth: **yes**. Role: **PROFESSIONAL**. Same full-replace shape precedent as `PUT
/api/availability/working-hours` (`availability.service.AvailabilityService
#updateWorkingHours`), per the design doc's explicit instruction to reuse that shape.

**Request:**
```json
{ "subServiceIds": [101, 104, 106] }
```

`@NotNull List<@NotNull Long> subServiceIds` — deliberately **no `@NotEmpty`**: an empty
list is a valid, un-blocking save (design doc §6 item 2, lead-approved — a professional who
hasn't picked any sub-services yet is a valid state, same bias toward optional fields `bio`
already has). Server-side dedupe via a `Set` before persisting (defensive; a checkbox UI
can't itself produce duplicates).

**Field validation, in order**:
1. Every id must exist in `sub_services` — any unknown id → **`400 VALIDATION_ERROR`** (a
   body-referenced id pointing at another entity, per §0's existing rule, not a path id
   naming the resource itself).
2. Every id's `category_id` must equal the caller's own `professionals.category_id` — a
   mismatch → **`400 CATEGORY_MISMATCH`**, reusing the **existing** `ErrorCode
   .CATEGORY_MISMATCH` (already used by `bookings.service.BookingsService#categoryMismatch`
   for "the professional's category doesn't match the issue's category") — a direct semantic
   fit, no new error code added.

**Behavior — diff-based update, not delete-all-then-reinsert**: load the caller's existing
`professional_sub_services` rows, compute the symmetric difference against the requested id
set, delete only the removed rows, insert only the newly-added rows, leave unchanged rows
untouched — preserves `created_at` for sub-services that stay selected across an edit, all
inside one `@Transactional` method.

**Response `200`**: same shape as §11.2, the canonical post-save state.

**Status codes**: `200` · `400 VALIDATION_ERROR` · `400 CATEGORY_MISMATCH` ·
`401 UNAUTHORIZED` · `403 FORBIDDEN`.

### 11.4 What is deliberately not changed

- `shared/api/categories.ts`'s static `CATEGORIES` mirror (frontend) is left as-is, not
  migrated to §11.1 — deliberate proportionality call, not an oversight (design doc §3.3/§6
  item 5). Flagged as a candidate follow-up, not built in this pass.
- No customer-facing sub-service filter/matching — Standard/SOS professional-listing queries
  are unchanged, still filtered only by `issue.category_id` vs. `professional.category_id`
  (design doc §4).
- `professionals.category_id`, `UpdateProfessionalProfileRequest`, and `PUT
  /api/professionals/me`'s existing behavior are all unchanged — sub-service selection is a
  fully separate endpoint pair.
