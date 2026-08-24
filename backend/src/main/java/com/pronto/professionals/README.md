# `professionals`

## Purpose

Professional profile: category, service area/city, standing price offer, bio, profile
image, and the derived average-rating/review-count aggregate. **As of Production Roadmap MS1
(2026-08-22), also the approval lifecycle and the single definition of marketplace
eligibility** — see "Approval lifecycle and marketplace eligibility" below. (This README
previously said "no approval workflow — v1.0 auto-approves professional accounts
(`approval_status` defaults to and stays `'APPROVED'`)". That is **superseded**: a new
professional now starts `PENDING`, and being `APPROVED` is not by itself enough to be
bookable.)
**As of MS11 (Services & Sub-services, 2026-08-19)**: also owns the `sub_services` reference
table (child of `categories`, one level down) and `professional_sub_services` (a
professional's selected subset of their own category's sub-services), plus the public `GET
/api/categories` endpoint and the `PROFESSIONAL`-only `GET`/`PUT
/api/professionals/me/sub-services` pair. See "MS11" subsections below and
`docs/architecture/product-ms11-sub-services-design.md` for the full design record.

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
- **Services & Sub-services layer (MS11, new this pass)**:
  - `GET /api/categories` — public, unauthenticated. Every category (ordered by
    `display_order`) with its nested `subServices` (also `display_order`-sorted, scoped
    within that category). The concrete answer to "support future service/sub-service
    changes without hardcoding the entire structure into the UI."
  - `GET /api/professionals/me/sub-services` — the caller's currently-selected sub-service
    ids only (not full objects — the frontend already has the catalog via `GET
    /api/categories`).
  - `PUT /api/professionals/me/sub-services` — full-replace of the caller's selection, same
    shape precedent as `PUT /api/availability/working-hours`. Validates every id exists
    (`400 VALIDATION_ERROR` if not) and belongs to the caller's own `category_id` (`400
    CATEGORY_MISMATCH`, reusing the existing error code — no new one added). Diff-based
    update (not delete-all-then-reinsert): only removed rows are deleted, only newly-added
    rows are inserted, unchanged rows (and their `created_at`) are left untouched.
- **Approval lifecycle and eligibility (Production Roadmap MS1, new this pass)**: owns the
  `PENDING`/`APPROVED`/`REJECTED` state machine on `entity.Professional`, the one JPQL
  definition of marketplace eligibility (`ProfessionalEligibility`), the `ADMIN`-only
  operator surface `/api/admin/professionals/**` (five endpoints), and
  `service.SubServiceSelectionValidator` — the category-membership rule now shared with
  `auth`'s registration flow. Full detail in the next section.

## Approval lifecycle and marketplace eligibility (Production Roadmap MS1, 2026-08-22)

Design record: `docs/architecture/ms1-professional-verification-design.md` (decisions D-A …
D-G); governing roadmap decisions **D4** (eligibility = approval **and** completed
onboarding), **D5** (no automatic migration of existing rows), **D6** (`DISABLED` reserved),
**D7** (reuse the existing registration surface) in
`docs/production-roadmap/README.md`. Migration: `V40__alter_professionals_approval_lifecycle.sql`.

### The state machine lives on the entity

`entity.Professional` carries the transition rules itself — `approve(reviewerUserId,
reviewedAt)` and `reject(reviewerUserId, reviewedAt, reason)`, guarded by `canApprove()`/
`canReject()` — rather than in the service, so no future caller can invent an illegal
transition by writing the column directly. There is no `setApprovalStatus`.

| From | `approve` | `reject` |
|---|---|---|
| `PENDING` | → `APPROVED` | → `REJECTED` |
| `REJECTED` | → `APPROVED` (the professional fixed whatever was wrong and was re-reviewed) | refused |
| `APPROVED` | refused | refused |

A refused transition surfaces as **`409 PROFESSIONAL_APPROVAL_INVALID_TRANSITION`**, raised by
`service.ProfessionalApprovalService` after checking `canApprove()`/`canReject()`; the
entity's own `IllegalStateException` is the backstop for the case where two operators decided
the same row between one's read and their write, and is deliberately loud rather than a silent
overwrite. `APPROVED → REJECTED` is refused specifically because that is a *suspension*, which
is MS7's job — this endpoint may not quietly stand in for it. `approve` also clears
`approval_rejection_reason` (required by `ck_professionals_rejection_reason`, and correct
independently: these columns record the decision currently in force, not a history, so a stale
reason on an approved row would mislead the next operator who reads it).

`STATUS_DISABLED` exists as a constant and `V40` added `DISABLED` to
`ck_professionals_approval_status`, so MS7's suspend capability needs no second lifecycle
migration against a live column. **It is unreachable in MS1**: `approve`/`reject` are the only
writers of `approvalStatus` and neither targets it, and no suspend endpoint exists. Because
eligibility is a *positive* test (`= 'APPROVED'`) rather than a blacklist, `DISABLED` is
already ineligible everywhere the moment MS7 makes it reachable.

New audit columns (`V40`): `approval_reviewed_at`, `approval_reviewed_by` (FK → `users(id)`,
`ON DELETE RESTRICT`), `approval_rejection_reason`. All `null` for every row that predates
`V40` — deliberately, per D5: nobody reviewed those, and naming a reviewer for them would
fabricate the very record the trail exists to make trustworthy. No existing row's status was
flipped by the migration.

### `ProfessionalEligibility` — the single definition of "may be given new work"

`com.pronto.professionals.ProfessionalEligibility` (a package-root final class, not under
`entity`/`service` — it is a rule, not a component) holds two JPQL string constants:

```text
ELIGIBLE_JPQL  :=  p.approvalStatus = 'APPROVED' AND ONBOARDING_COMPLETE_JPQL

ONBOARDING_COMPLETE_JPQL
               :=  p.verificationDocumentKey IS NOT NULL
               AND EXISTS (an enabled availability.entity.ProfessionalWorkingHours row for p)
               AND EXISTS (a ProfessionalSubService for p whose SubService.categoryId = p.categoryId)
```

**Approval alone never makes anyone bookable** (D4). Approving a professional whose onboarding
is incomplete leaves them non-bookable, and nothing in the system invents the missing working
hours or sub-services to rescue that.

**Alias contract.** Both constants are bare boolean fragments, not complete queries. They
assume exactly one thing about the host query: **the alias `p` is bound to
`entity.Professional`**. Concatenate into a `WHERE` clause that already has such an alias,
always joined with `AND`, and — because the fragment is itself a conjunction — never inside an
`OR` without the caller's own parentheses.

**Why computed per query rather than stored.** A maintained `is_eligible` column would have
five writers (the sub-services update, the working-hours update, registration, a future
category change — which invalidates a sub-service selection without touching either child
table — and the approval transition), and its failure mode is a stale `true`: an incomplete
professional who is bookable, which is the exact defect MS1 exists to close. No integration
test in this repository could catch that staleness until MS5 builds one (D3). Recomputing costs
two index-anchored semi-joins (`idx_professional_working_hours_professional`, and the
`professional_sub_services` primary-key prefix) over tables bounded at one row per weekday and
one per sub-service, added to queries already dominated by per-row correlated `AVG`/`COUNT`
subqueries over `reviews`. The accepted trade-off: a string constant is not compile-time
checked — but Hibernate parses every `@Query` while building the persistence context, so a
malformed fragment fails **application startup**, not one request.

**What is deliberately not inside it.** `users.deleted_at IS NULL` stays *adjacent*:
`bookings.repository.ProfessionalListingRepository.listByCategory` and
`sos.repository.SosCandidateRepository.findEligible` already join `users` and already apply it,
while `BookingsService.listAvailableWindows` does not join `users` at all — folding the join in
would force every consumer to carry a join it may not want in order to restate a rule it mostly
already has. Each gated path applies it in its own idiom (see
`BookingsService#isProfessionalBookable`). Sub-service-*level* matching is also out: the rule
checks only that the professional has *some* valid sub-service under their own category, not
that it matches the customer's request, because `issues` has no `sub_service_id` column and
`SosRequest.subServiceId` is always `null` — there is nothing to match against yet.

**Every consumer reads this constant; nothing re-implements it in Java.** That is the whole
point: drift between a SQL filter and a hand-written Java check is the realistic failure mode,
and it would let the listing and the booking guard disagree about the same person.

| Reader | What it is |
|---|---|
| `bookings.repository.ProfessionalListingRepository#listByCategory` | The customer's Standard discovery surface — concatenates `ELIGIBLE_JPQL` into its `WHERE` |
| `sos.repository.SosCandidateRepository#findEligible` | SOS dispatch's hard filter — replaced the old bare `p.approvalStatus = 'APPROVED'` clause |
| `repository.ProfessionalRepository#existsEligibleById` | The single-row form, built from the same constant. Returns `false` for an id that does not exist at all, which is what every caller wants: "not bookable" and "not there" are the same answer here |

Every service that must answer "may *this* professional be given new work?" delegates to
`existsEligibleById`: `bookings.service.BookingsService#isProfessionalBookable` (available
windows and order creation), `sos.service.SosService#selectProfessional`,
`favorites.service.FavoritesService#addFavorite`, plus the read-only `bookable` signal on
`ProfessionalsService#toResponse`, `FavoritesService#toSummary` and
`availability.service.AvailabilityService`'s two SOS-availability responses.

`repository.ProfessionalRepository#hasCompleteOnboarding` is built the same way from
`ONBOARDING_COMPLETE_JPQL`, and is **never a gate** — no request is allowed or refused on its
answer. It exists so the operator review screen can show that approving this person would
leave them non-bookable *before* a decision is spent on them.

### The operator surface — `ADMIN` only

`controller.AdminProfessionalsController` + `service.ProfessionalApprovalService`, five
endpoints under `/api/admin/professionals`: list the queue (`?approvalStatus=` optional,
oldest first, an unrecognised value is a `400` rather than a silently empty list), read one
professional's review detail, mint a verification-document URL, approve, reject.

This is deliberately **the minimum capability MS1 needs and nothing more** — the full admin
portal is MS7's. Without these five actions the lifecycle MS1 introduces would have nobody able
to drive it and every new professional would sit at `PENDING` forever.

- **Own path prefix, not folded into `ProfessionalsController`.** `/api/professionals/*` mixes
  a `PROFESSIONAL`-only surface with an either-role one; hanging an `ADMIN`-only third audience
  off the same prefix would make `ProfessionalsWebConfig`'s literal path list the only thing
  separating three audiences. A separate prefix is what makes the blanket
  `/api/admin/professionals/**` pattern safe to write — `config.ProfessionalsWebConfig`
  registers **both** `/api/admin/professionals` and `/api/admin/professionals/**`, deliberately,
  rather than relying on a particular path matcher's treatment of `/**` against the bare prefix.
- **Gating is at the route only.** `RoleRequiredInterceptor(ADMIN)` runs in `preHandle`, before
  Spring resolves the `@Valid` body on `reject` — so a customer or professional probing the
  endpoint with a malformed body gets `403 FORBIDDEN` rather than a `400` that would confirm the
  endpoint exists and describe its shape. `ProfessionalApprovalService` deliberately does *not*
  re-check the role: a second, divergent copy of a gate is how one of them ends up wrong.
- **Approval is not refused for incomplete onboarding.** The two judgments are different — "is
  this a real, verified tradesperson" is the operator's; "have they finished setting up their
  calendar" is the professional's own, and self-heals the moment they do it. Coupling them would
  either block a legitimate approval or invite an operator to fill in someone else's
  availability to unblock it. `approve` logs `onboardingComplete=` so the outcome is visible
  rather than mysterious.
- **Concurrency: load-mutate-save**, the same pattern `ProfessionalsService#updateMyProfile` and
  `UsersService#deleteMe` use, not the guarded atomic `UPDATE ... WHERE <state>` reserved for
  genuinely contended state machines (orders, SOS requests, slots). Approval is a human queue
  worked by a handful of operators. The honest limit, stated rather than hidden: two operators
  who genuinely decide the same professional at the same instant both write and the later commit
  wins — a lost audit attribution on one row, not corrupted state, since both were making the
  same legal transition from the same status.
- **The rejection reason is stored, not logged** — it is a judgment about a named person and
  belongs in the row an operator can see, not in an application log stream. The
  verification-document endpoint logs *that* an operator viewed a professional's document (who,
  and whose) and never the key or the URL; see `storage/README.md`'s operator-read-path section.

### `SubServiceSelectionValidator` — one rule, two callers

"Every one of these sub-service ids exists, and belongs to that category" was written inline in
`ProfessionalsService#updateMySubServices`. MS1 made sub-services a *registration* requirement
(D4/D7), so the rule moved into `service.SubServiceSelectionValidator` and is now called by
both `ProfessionalsService#updateMySubServices` and `auth.service.AuthService`'s registration
validation — rather than copied into `auth`, which is precisely how a backend ends up enforcing
a rule on the edit endpoint and not on the one that creates the account. Behaviour and both
error codes are unchanged from the inline version: an unknown id is `400 VALIDATION_ERROR` with
a field error, a cross-category id is `400 CATEGORY_MISMATCH`. The caller supplies the field
path to report against (`subServiceIds` for the edit body, `professional.subServiceIds` for
registration's nested payload).

It is its own `@Service` rather than a public method on `ProfessionalsService` because that
class carries storage, favorites and review-aggregate collaborators that registration has no
business depending on. An empty collection is a no-op here — "at least one" is a
*registration* requirement enforced by `AuthService`, not a property of this rule; the edit
endpoint still accepts an empty selection (the lead-approved MS11 decision, unchanged).

## Key classes

| Class | Role |
|---|---|
| `entity.Professional` | JPA entity for `professionals`. `userId`/`categoryId` are plain FK columns (not `@ManyToOne`/`@OneToOne` associations) — unchanged reasoning since Milestone 1. Carries `bio`/`profileImageKey`/`city` (all nullable, plain `String` columns) and `verificationDocumentKey`. **As of MS1**: also the approval state machine — `STATUS_PENDING`/`APPROVED`/`REJECTED`/`DISABLED` constants, `approve`/`reject`/`canApprove`/`canReject`, the `approvalReviewedAt`/`approvalReviewedBy`/`approvalRejectionReason` audit fields, and a constructor that starts every new professional at `PENDING`. No `setApprovalStatus` exists. See "Approval lifecycle" above. |
| `entity.Category` | Read-only reference entity for `categories` — unchanged since Milestone 1. (This row previously said "8 fixed rows"; that is stale, not an MS1 change — `V31__replace_carpentry_with_handyman.sql` retired Carpentry into General Handyman, leaving **seven** categories.) |
| `ProfessionalEligibility` | **New, MS1.** The single JPQL definition of "may be discovered and given new work" — `ELIGIBLE_JPQL` / `ONBOARDING_COMPLETE_JPQL`, plus the `APPROVED` constant. A package-root final class with a private constructor: it is a rule, not a component. Read by `bookings.repository.ProfessionalListingRepository`, `sos.repository.SosCandidateRepository` and `ProfessionalRepository#existsEligibleById`; re-implemented nowhere. See "Approval lifecycle and marketplace eligibility" above for the alias contract and why it is computed rather than stored. |
| `service.ProfessionalApprovalService` | **New, MS1.** All logic behind `/api/admin/professionals/**`: the queue (`list`), the review detail (`getReviewDetail`, which surfaces both `existsEligibleById` and `hasCompleteOnboarding`), the verification-document URL, `approve` and `reject`. Does not re-check the caller's role — that is the interceptor's job, exclusively. |
| `controller.AdminProfessionalsController` | **New, MS1.** The five `ADMIN`-only routes under `/api/admin/professionals`. Same manual path-id parsing convention as `ProfessionalsController`. |
| `service.SubServiceSelectionValidator` | **New, MS1.** The shared "these sub-service ids exist and belong to this category" check, called by both `ProfessionalsService#updateMySubServices` and `auth.service.AuthService` at registration. `400 VALIDATION_ERROR` (unknown id, reported against a caller-supplied field path) / `400 CATEGORY_MISMATCH` (wrong category) — both unchanged from the inline version it replaced. |
| `dto.ProfessionalApprovalListResponse` / `dto.ProfessionalApprovalSummary` | **New, MS1.** The operator queue shape — identity, category, service area/city, `approvalStatus`, `onboardingComplete`, `createdAt`, `approvalReviewedAt`. |
| `dto.ProfessionalReviewDetailResponse` | **New, MS1.** Everything an operator needs to decide, including `bookable` (`existsEligibleById`), `onboardingComplete`, `hasVerificationDocument` (a boolean — the key itself is never returned) and the selected `subServiceIds`. Also the response body of `approve`/`reject`, so a decision returns the row's new truth. |
| `dto.RejectProfessionalRequest` | **New, MS1.** `{ reason }`, required and trimmed by the service — stored on the row so the professional can eventually be told something actionable rather than "no". |
| `dto.VerificationDocumentUrlResponse` | **New, MS1.** `{ professionalId, url, expiresInSeconds }`. The URL is a short-lived bearer capability — see `storage/README.md`. |
| `repository.ProfessionalRepository` | `findByUserId` — unchanged since Milestone 1, now also the primary lookup this package's own new service layer uses to resolve "the caller's own professional row." |
| `repository.CategoryRepository` | Plain `JpaRepository`, `existsById`-only usage — unchanged since Milestone 1. |
| `repository.ReviewAggregateRepository` | **New this pass.** A narrow, read-only `Repository<Review, Long>` (not `JpaRepository` — exists purely to expose one aggregate query, not full CRUD) reading `reviews.entity.Review` from outside its owning package, projecting into `ProfessionalRatingAggregate`. Deliberately lives here, not in `reviews` — mirrors the intentional narrow-cross-package-repository pattern `bookings.repository.ProfessionalListingRepository` already established (a package reading another package's entity for its own projection need, rather than that dependency running the other direction). |
| `repository.ProfessionalRatingAggregate` | Projection record, `(averageRating, reviewCount)`. `averageRating` is `null` and `reviewCount` is `0` when the professional has no reviews (JPQL `AVG`/`COUNT` over zero rows) — always exactly one row is returned (aggregate functions never produce zero result rows), so callers never need an `Optional`. |
| `service.ProfessionalsService` | **New this pass.** All business logic for the four endpoints above — resolving "the caller's own professional" (with a defense-in-depth `403 FORBIDDEN` if a `PROFESSIONAL`-role caller somehow has no `professionals` row, not expected to be reachable in practice), the profile-image upload flow, and the shared response-building helper that resolves the profile-image URL and the rating aggregate (via `ReviewAggregateRepository`) for both the self-view and the public-detail-view endpoints. **As of backend MS9 (2026-08-18)**: the profile-image URL is resolved via `storage.service.StorageService#getPresignedUrl(callerId, key)` (a time-limited presigned URL) — this class previously also injected `storage.client.StorageClient` directly and called `resolveUrl` (a permanent, non-expiring proxy URL); that field/constructor param was dropped since this class already separately injected `StorageService` (for `uploadWithKey`). `toResponse` now takes a `callerId` param, threaded in from all three of its call sites. **As of MS11**: gained `getMySubServices`/`updateMySubServices`, and two new constructor-injected dependencies (`SubServiceRepository`, `ProfessionalSubServiceRepository`) — `updateMySubServices` validates (unknown id → `VALIDATION_ERROR`, wrong category → `CATEGORY_MISMATCH`) then computes a symmetric-difference diff against the caller's existing selection, deleting only removed rows and inserting only added rows inside one `@Transactional` method (never a blanket delete-all-then-reinsert). **As of MS1**: the inline validation was replaced by the injected `SubServiceSelectionValidator` (the `SubServiceRepository` dependency went with it), and `toResponse` gained the D-G disclosure split — `approvalStatus` is returned only when the loaded row's own `userId` equals the caller (so a professional gets the same honest answer from `/me` and from their own public card by id, and a customer gets `null` from either), while everyone gets the neutral `bookable` flag from `existsEligibleById`. |
| `controller.ProfessionalsController` | **New this pass**, extended MS11. `/api/professionals/me` (`GET`/`PUT`), `/api/professionals/me/profile-image` (`POST`, `multipart/form-data`), `/api/professionals/{professionalId}` (`GET`), and, as of MS11, `/api/professionals/me/sub-services` (`GET`/`PUT`). Manual path-id parsing, same convention as every other controller in this codebase. |
| `config.ProfessionalsWebConfig` | **New this pass**, extended MS11 and MS1. **MS1** adds a second registration — `RoleRequiredInterceptor(ADMIN)` on both `/api/admin/professionals` and `/api/admin/professionals/**` (both patterns deliberately; whether `/**` matches the bare prefix is not a thing to leave to a path matcher's interpretation when the answer decides whether an endpoint is gated). That prefix has exactly one audience, which is what makes a wildcard correct there and literal patterns necessary below. The pre-existing registration: a single `RoleRequiredInterceptor(PROFESSIONAL)` on the literal paths `/api/professionals/me`, `/api/professionals/me/profile-image`, and, as of MS11, `/api/professionals/me/sub-services` (not a blanket `/api/professionals/**` — this package mixes a `PROFESSIONAL`-only surface with the either-role `{professionalId}` detail route, the same reason `bookings`/`issues` use literal-pattern lists instead of a wildcard). `GET /api/professionals/{professionalId}` is left ungated at the route level; `GET /api/categories` (MS11) is public and isn't even a route this config's path list touches. |
| `dto.ProfessionalProfileResponse` | Shared response shape for `GET`/`PUT /api/professionals/me` and `GET /api/professionals/{professionalId}` — `favorited` is `Boolean` (not `boolean`), since it's meaningfully three-valued (`null` on self-views/non-`CUSTOMER` callers, `true`/`false` for a `CUSTOMER` viewing another professional's card). **As of MS1 (D-G)**: `approvalStatus` is **self-view only** and `null` for every other caller — harmless while the column was permanently `APPROVED`, but now that it carries a real decision, returning it to a browsing customer would disclose "this professional was rejected", a judgment about a named person. The new `boolean bookable` is the neutral replacement everyone gets: enough for the UI to withhold a booking affordance that would lead into a dead end, and it reveals nothing about which of the several possible reasons applies. |
| `dto.UpdateProfessionalProfileRequest` | Allowlist DTO for `PUT /api/professionals/me` — deliberately excludes (by omission) `id`, `userId`, `categoryId`, `approvalStatus`, `reliabilityScore`, any rating/review-count field, `profileImageKey` (its own endpoint), and the timestamps. |
| `dto.ProfileImageUploadResponse` | Response shape for the profile-image upload — mirrors `storage.dto.ImageUploadResponse`'s shape, scoped to this endpoint's own key/URL. |
| `entity.SubService` | **New, MS11.** Read-only reference entity for `sub_services`, seeded by `V29__create_sub_services.sql` and re-shaped by `V31__replace_carpentry_with_handyman.sql` (this row previously quoted the pre-`V31` count of 34 rows across 8 categories — stale, not an MS1 change) — a child, one level down from `Category`. `categoryId` is a plain FK column, same convention as every other FK field in this package. |
| `entity.ProfessionalSubService` / `entity.ProfessionalSubServiceId` | **New, MS11.** Composite-key (`professionalId`, `subServiceId`) join entity for `professional_sub_services`, mirroring `favorites.entity.Favorite`/`FavoriteId`'s exact existing pattern (`@IdClass`, not `@EmbeddedId` — no separate embeddable value object needed in this API surface). Matches `V30__create_professional_sub_services.sql` exactly. |
| `repository.SubServiceRepository` | **New, MS11.** `findAllByOrderByCategoryIdAscDisplayOrderAsc` (the `GET /api/categories` catalog read) and `findByCategoryIdOrderByDisplayOrderAsc` (unused directly today — kept for symmetry/future use, the controller currently groups the flat catalog result in memory instead). |
| `repository.ProfessionalSubServiceRepository` | **New, MS11.** `findByProfessionalId` — the sole query needed for both `GET`/`PUT /api/professionals/me/sub-services` (no `sub_service_id`-only lookup exists — see `data-model.md` §2.16's "no index on `sub_service_id` alone" note). |
| `controller.CategoriesController` | **New, MS11.** `GET /api/categories` only — public, no `RoleRequiredInterceptor` registration at all (not even in `ProfessionalsWebConfig`, since this isn't a `/api/professionals/*` route). Assembles the response by joining `CategoryRepository`'s and `SubServiceRepository`'s results in memory (8 categories × ~4 sub-services each — trivial data volume, no JPA `@OneToMany` graph needed). |
| `dto.SubServiceResponse` / `dto.CategoryWithSubServicesResponse` | **New, MS11.** `GET /api/categories`'s nested response shape. |
| `dto.MySubServicesResponse` | **New, MS11.** Shared `GET`/`PUT /api/professionals/me/sub-services` response shape — `subServiceIds: List<Long>` only. |
| `dto.UpdateSubServicesRequest` | **New, MS11.** `PUT /api/professionals/me/sub-services`'s request shape — `@NotNull List<@NotNull Long> subServiceIds`, deliberately no `@NotEmpty` (an empty selection is a valid, un-blocking state — design doc §6 item 2, lead-approved). |

## Interactions with other packages

- Depended on by `auth` (`AuthService`) to create a `Professional` row at professional
  registration and to validate `categoryId` via `CategoryRepository` — unchanged since
  Milestone 1. **As of MS1**, that dependency widened in two ways: `AuthService` now also
  injects `service.SubServiceSelectionValidator` (the shared category-membership rule) and
  `repository.ProfessionalSubServiceRepository` (it writes the registrant's selection inside
  the registration transaction), and the `Professional` it constructs starts `PENDING`
  instead of `APPROVED`. **Known gap, not fixed by this pass**: `AuthService#register` still only sets
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
  controller/service layer didn't exist before this pass to need it. **As of MS1**, one new
  code lives there: `PROFESSIONAL_APPROVAL_INVALID_TRANSITION` (`409`).
- **New, MS1 — this package's rule is now read by three others.**
  `bookings.repository.ProfessionalListingRepository` and
  `sos.repository.SosCandidateRepository` concatenate `ProfessionalEligibility.ELIGIBLE_JPQL`
  into their own `@Query` strings; `bookings.service.BookingsService`,
  `sos.service.SosService`, `favorites.service.FavoritesService` and
  `availability.service.AvailabilityService` all call
  `ProfessionalRepository#existsEligibleById`. The dependency arrow points *into* this package
  in every case — no package re-expresses the rule itself, which is the property that keeps the
  listing and the booking guard from disagreeing about the same person.
- **New, MS1 — `storage`.** `ProfessionalApprovalService` calls
  `StorageService#getVerificationDocumentUrlForOperator` (and `#getPresignedUrlTtlSeconds`), a
  deliberately narrow, prefix-locked operator read path rather than a widening of the general
  ownership rule. The key is read off the `professionals` row this package just loaded by id and
  is never client-supplied. See `storage/README.md`'s
  "Operator read path — verification documents" section.
- **New, MS1 — `users`.** `ProfessionalApprovalService` reads `UserRepository` for the
  reviewed professional's name/email on the queue and detail screens, and
  `config.ProfessionalsWebConfig` references `users.entity.UserRole.ADMIN`.

## Data model

Owns the `professionals` table (`docs/architecture/data-model.md` §2.4, amended by
`V15__alter_professionals_add_profile_fields.sql` — adds `bio`/`profile_image_key`/`city`,
the last backfilled from `service_area` for existing rows at migration time only). Also maps
`categories` (§2.1) as a read-only reference entity, unchanged since Milestone 1. **As of
MS11 (2026-08-19)**: also owns `sub_services` (§2.15, `V29__create_sub_services.sql` —
create + seed in one migration) and `professional_sub_services` (§2.16,
`V30__create_professional_sub_services.sql`, empty at migration time).

**As of Production Roadmap MS1 (2026-08-22)**:
`V40__alter_professionals_approval_lifecycle.sql`, forward-only and **purely additive** — it
changes no existing row's data. It widens `ck_professionals_approval_status` to include
`DISABLED` (reserved for MS7, unreachable in MS1) and `ck_users_role` to include `ADMIN`; adds
`approval_reviewed_at`, `approval_reviewed_by` and `approval_rejection_reason` to
`professionals`; adds `fk_professionals_approval_reviewer` (→ `users(id)`, `ON DELETE
RESTRICT` — an audit pointer the database will silently blank is a weaker record); adds
`ck_professionals_rejection_reason` (a reason may be non-`null` only while the status is
`REJECTED`); and adds `idx_professionals_approval_status`. **No data migration**: per D5, no
existing `APPROVED` professional was bulk-flipped to `PENDING`, and no working hours or
sub-services were fabricated for anyone — an existing row simply keeps its status and becomes
eligible or not on the merits of its own onboarding data.

## Assumptions / judgment calls made during implementation

- **`Category` entity placement** — unchanged since Milestone 2's resolution (see prior
  revisions of this doc / `data-model.md` §4 if needed); not revisited by this pass.
- ~~`Professional.approvalStatus` remains a plain `String`, functionally inert in v1.0.~~
  **Superseded by MS1 (2026-08-22).** It is still a plain `String` column mapped against the
  DB `CHECK` (the four legal values are `public static final String` constants on
  `entity.Professional`, not a JPA enum), but it is no longer inert: it is a real state machine
  with an audit trail, and one half of the eligibility rule. See "Approval lifecycle and
  marketplace eligibility" above.
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
- **MS11 sub-service seed content is placeholder, not sourced from any product document** —
  34 rows across the 8 categories, explicitly flagged in the design doc §2.3/§6 item 1 as
  needing real product sign-off before being treated as final; trivially editable later via
  a fresh migration.
- **MS11's `sub_services.category_id` uses `ON DELETE RESTRICT`, but
  `professional_sub_services`'s two FKs both use `ON DELETE CASCADE`** — a deliberate split,
  not an inconsistency: `sub_services` is core reference data (same `RESTRICT` policy
  `categories` itself gets when referenced), while `professional_sub_services` is a pure
  join/bookmark row with no independent meaning, same reasoning `favorites` already uses for
  both of its own FKs. See `data-model.md` §2.16 for the full record.
- **`GET /api/categories` is deliberately public/unauthenticated** — categories/sub-services
  are non-sensitive reference data (the pre-existing static `CATEGORIES` frontend mirror was
  already effectively public), and leaving it open is what would let it someday also serve
  the pre-login registration screen without redesign (not built this pass).
- **MS1: eligibility is computed per query, never stored** — the five-writers/stale-`true`
  reasoning is in "Approval lifecycle and marketplace eligibility" above and in
  `ProfessionalEligibility`'s own Javadoc. If this ever becomes a measured performance problem,
  the replacement is a materialised view or a trigger-maintained column with a reconciliation
  job — not a hand-maintained boolean that five call sites remember to update.
- **MS1: `approve` is not refused for incomplete onboarding**, and the operator screen shows
  `onboardingComplete` so the decision is made with that visible. See the operator-surface
  section above for why coupling the two judgments was rejected.
- **MS1: no reviewer/decision *history* table** — `approval_reviewed_at`/`_by`/
  `approval_rejection_reason` record the decision currently in force, not an audit log. A
  `REJECTED → APPROVED` transition overwrites the previous decision's attribution. Accepted for
  MS1 (the log line records each decision as it happens); a real audit trail is MS7's admin/
  operations scope.
- **MS1: `ADMIN` is a role with no creation procedure in this repository** — nothing in
  `auth` can produce one (registration explicitly refuses `role = ADMIN`), so the operator
  surface has no operator until one is created by a deliberate operational step. Recorded as a
  known limitation in `docs/production-roadmap/reports/MS1-report.md`, owned by MS7.

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

**MS11 — Services & Sub-services (2026-08-19)**: implemented per
`docs/architecture/product-ms11-sub-services-design.md`, all five §6 ambiguities
lead-approved exactly as recommended (placeholder seed content pending real product
sign-off; empty sub-service selection allowed; one unified always-editable checklist, no
separate onboarding step; two independent save actions on `/pro/profile`;
`shared/api/categories.ts`'s static mirror left untouched). New: `V29__create_sub_services
.sql`/`V30__create_professional_sub_services.sql`; `entity.SubService`/
`entity.ProfessionalSubService`/`ProfessionalSubServiceId`; `repository
.SubServiceRepository`/`ProfessionalSubServiceRepository`; `dto.SubServiceResponse`/
`CategoryWithSubServicesResponse`/`MySubServicesResponse`/`UpdateSubServicesRequest`;
`controller.CategoriesController` (`GET /api/categories`, public). Changed:
`ProfessionalsController`/`ProfessionalsService`/`ProfessionalsWebConfig` (the
`/me/sub-services` `GET`/`PUT` pair). Unit-tested (`ProfessionalsServiceTest`, new cases:
category-mismatch rejection, unknown-id rejection, diff-based update preserving unchanged
rows, empty-list save). Backend: full suite passing (`mvnd -q -o test`, zero failures).
Frontend: `shared/components/Checkbox.tsx` (new primitive), `shared/api/professionals.ts`
gained the three client functions, `features/dashboard/ProfileEditorPage.tsx` gained the
checklist section — see those packages' own READMEs for the frontend-side record. `npx tsc
-b` clean.

**Production Roadmap MS1 — Professional Verification & Marketplace Eligibility
(2026-08-22)**: this package became the owner of the approval lifecycle and of the one
definition of marketplace eligibility — see "Approval lifecycle and marketplace eligibility"
above for the substance. New: `ProfessionalEligibility`;
`service.ProfessionalApprovalService`; `service.SubServiceSelectionValidator`;
`controller.AdminProfessionalsController`; `dto.ProfessionalApprovalListResponse`/
`ProfessionalApprovalSummary`/`ProfessionalReviewDetailResponse`/`RejectProfessionalRequest`/
`VerificationDocumentUrlResponse`; `V40__alter_professionals_approval_lifecycle.sql`.
Changed: `entity.Professional` (state machine + audit fields + `PENDING` constructor),
`repository.ProfessionalRepository` (`existsEligibleById`, `hasCompleteOnboarding`, the two
queue finders), `service.ProfessionalsService` (the D-G disclosure split, `bookable`, and the
switch to the shared validator), `dto.ProfessionalProfileResponse`,
`config.ProfessionalsWebConfig` (the `ADMIN` interceptor). Unit-tested:
`professionals.ProfessionalEligibilityTest`, `professionals.entity.ProfessionalTest`,
`professionals.service.ProfessionalApprovalServiceTest` (all new),
`professionals.service.ProfessionalsServiceTest` (extended), plus
`common.security.AdminRouteGatingTest` for the route gate. Backend `mvn -B clean verify`:
**623 tests, 0 failures, 0 errors, 1 skipped** (the pre-existing OpenAI eval). Live-validated
against PostgreSQL 16.14 — clean `V1→V40` and an upgrade from the real baseline, registration
→ `PENDING` → operator review → approve → appears in the Standard listing, reject with a
reason, `409` on an illegal transition, and four simultaneous approves producing exactly one
`200` and three `409`s with a single reviewer recorded. Full record, including known
limitations and what was **not** verified:
`docs/production-roadmap/reports/MS1-report.md`; decision record:
`docs/architecture/ms1-professional-verification-design.md`. **Status at the time of writing:
uncommitted on branch `production/ms1-professional-verification`, pending the Lead gate and
the user's own git operations.**

## MS4 (2026-08-24) — multiple categories, controlled service coverage

Three columns left `professionals` and became relations. See `docs/architecture/data-model.md`
§2.4's MS4 note and §2.20–2.23 for the schema; the code consequences are:

- **`entity/ProfessionalCategory`** (+ `ProfessionalCategoryId`) maps
  `professional_categories`. `professionals.category_id` is gone. There is no "primary category"
  flag — ordering on `categories.display_order` is what makes "the first one" mean the same thing
  on every surface.
- **`entity/ProfessionalServiceCity`** (+ id class) maps `professional_service_cities`.
  `Professional.baseCityId` is the single city ETA is measured from, and is always a member of
  that set.
- **`service/ProfessionalCoverageService` is the only reader and writer of both relations.** Six
  services need to read some part of this back (the profile endpoints, favourites, the operator
  review screen, `GET /api/users/me`, the SOS candidate assembler, the booking listing) and two
  need to write it (registration and the profile edit). Left alone, each would grow its own
  two-repository join and its own idea of ordering. Writes are diff-based, never
  delete-all-then-reinsert — the same semantics `updateMySubServices` already established.
- **`ProfessionalCategoryMatch.SERVES_CATEGORY_JPQL`** is the single definition of "this
  professional serves that category", concatenated into both `bookings`' listing query and the
  SOS hard filter. Before MS4 the rule was three tokens written out twice, which was survivable;
  a membership test over a relation written out twice is not — the failure mode is a professional
  a customer can find by browsing but SOS will never dispatch to.
- **`ProfessionalEligibility`** widened its sub-service clause from "under p's own category" to
  "under one of p's own categories". Unchanged in outcome for every single-category professional.
- **`SubServiceSelectionValidator.validate`** now takes a *collection* of category ids. Same rule,
  same two error codes.
- Region/city validation is **not** here: it lives in `com.pronto.locations`, which this package
  depends on. See that package's README.

