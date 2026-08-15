# `issues`

## Purpose

Issue creation, category selection, and image metadata; orchestrates the `ai` package for
classification. Since Milestone 3, also serves the single-issue lookup the booking flow
needs.

Implements `docs/architecture/api-contract-issues.md` §2.1–2.2 and
`docs/architecture/api-contract-bookings.md` §2.1.

## Responsibilities

- `POST /api/issues/classify` — stateless AI-suggestion preview. Validates the request,
  verifies any `imageKeys` exist in storage and belong to the caller (§3.3), delegates to
  `ai.service.ClassificationService`, and returns the suggestion. **No DB write** — may be
  called repeatedly (e.g. after the customer edits their description) with zero side
  effects. Lives under `/api/issues/*`, not a standalone `/api/ai/*` route — see the
  controller's Javadoc for the full "package placement" rationale from the contract doc.
  **Clarification-question extension**: when `ai` can't confidently pick one category
  (description/image contradiction, or two categories realistically possible), the
  response comes back `status = QUESTIONS` with 1-3 clarification questions instead of a
  suggestion. The frontend re-calls this same endpoint with the original
  `description`/`imageKeys` plus `clarificationAnswers`; `IssuesService.classify` detects
  that field and routes to `ClassificationService.classifyWithClarification` (the single
  allowed follow-up) instead of a fresh initial pass. See
  `docs/architecture/api-contract-issues.md` §2.1's "Clarification-question extension" and
  `ai/README.md` for the full design.
- `POST /api/issues` — persists the `issues` row and any `issue_images` rows in a single
  transaction, only meaningfully reachable after the customer has seen/confirmed-or-overridden
  the AI suggestion (there is no server-side link back to a specific prior `/classify` call
  — the AI's original suggestion is fully discarded once confirmed, a genuine design
  decision flagged in the contract doc §2.2 for user sign-off, not silently settled).
  Validates `categoryId` against `categories` (`400 VALIDATION_ERROR` if invalid/absent,
  same convention M1 used for registration's `categoryId` — not `404`), re-validates
  `imageKeys` ownership/existence independently of whatever was passed to `/classify`, then
  inserts `issues` + one `issue_images` row per validated key, all in one `@Transactional`
  method.
- `/classify` and `POST /api/issues` require `role = CUSTOMER`, enforced via
  `common.security.RoleRequiredInterceptor` (registered for the two literal paths
  `/api/issues/classify` and `/api/issues` by `config.IssuesWebConfig`), which calls
  `common.security.RoleGuard.requireRole` from `preHandle` — see `storage/README.md`'s
  "Role enforcement" section for the full rationale (identical pattern used here).
- **`GET /api/issues/{id}`** (added Milestone 3, `docs/architecture/api-contract-bookings.md`
  §2.1) — either `CUSTOMER` or `PROFESSIONAL` may call this; no route-level role gate is
  registered for it (`config.IssuesWebConfig` only covers the two `CUSTOMER`-only paths
  above). Ownership/authorization happens in `IssuesService.getById`: a customer must own
  the issue; a professional must have any `orders` row against it (any status). Returns the
  issue, its images, and a `latestOrder` summary (the most-recently-created order for the
  issue, regardless of status, `null` if none) — the field the frontend uses to implement
  the reject-return-to-list flow without a separate list-orders call.

## Key classes

| Class | Role |
|---|---|
| `entity.Issue` | JPA entity for `issues`. `customerId`/`categoryId` are plain FK columns, not associations — same convention as `professionals.entity.Professional`. Always starts `status = OPEN`. |
| `entity.IssueImage` | JPA entity for `issue_images`. `imageUrl` is whatever `storage.StorageClient.resolveUrl` returns for the key — the underlying object is never moved/renamed on confirmation. |
| `entity.IssueUrgencyType` / `entity.IssueStatus` | Enums mirroring the `issues` table's `CHECK` constraints. |
| `repository.IssueRepository` | `JpaRepository`, plus (since Milestone 3) `bookIfOpen`/`revertToOpen` — the atomic `UPDATE ... WHERE <status guard>` transitions `bookings.service.BookingsService` uses for the booking flow (`docs/architecture/api-contract-bookings.md` §3.2/§3.3) — and (since Milestone 5) `expireIfBooked`, the same guarded-`UPDATE` shape targeting `EXPIRED` instead of `OPEN`, called by `bookings.service.BookingsService.expireIfPending` as part of the `PENDING`-order timeout sweep (`docs/architecture/api-contract-notifications.md` §4.5). |
| `repository.IssueImageRepository` | `JpaRepository`, plus `findByIssueId` (used by `GET /api/issues/{id}`). |
| `dto.ClassifyRequest` / `dto.ClassifyResponse` | `POST /api/issues/classify` wire shapes. `ClassifyRequest.clarificationAnswers` (optional, max 3) is the second-call shape of the clarification extension. |
| `dto.ClarificationAnswerRequest` | One `{question, answer}` entry inside `ClassifyRequest.clarificationAnswers`. |
| `dto.CreateIssueRequest` / `dto.IssueResponse` / `dto.IssueImageResponse` | `POST /api/issues` wire shapes. `CreateIssueRequest` deliberately carries no AI-suggestion field (see "Responsibilities" above). |
| `dto.IssueDetailResponse` / `dto.LatestOrderSummary` | `GET /api/issues/{id}` wire shapes (Milestone 3). `LatestOrderSummary` is the one place this package depends on `bookings.entity.OrderStatus`/`bookings.repository.OrderRepository` — a deliberate, documented exception to the otherwise one-directional `bookings -> issues` dependency, per the contract doc §2.1. |
| `service.IssuesService` | All business logic for all three endpoints, including the shared `imageKeys` ownership/existence validation and (Milestone 3) `getById`'s ownership/professional-order-existence authorization. |
| `controller.IssuesController` | `/api/issues/classify`, `POST /api/issues`, `GET /api/issues/{id}`. |
| `config.IssuesWebConfig` | Registers `common.security.RoleRequiredInterceptor(role = "CUSTOMER")` for exactly `/api/issues/classify` and `/api/issues` (narrowed in Milestone 3 — see "Assumptions / judgment calls" below). No interceptor is registered for `GET /api/issues/{id}` (either-role route). |

## Interactions with other packages

- Calls `ai.service.ClassificationService` for the category suggestion (`/classify` only —
  `POST /api/issues` never calls `ai` at all, since the confirmed `categoryId` is supplied
  directly by the caller).
- Calls `storage.client.StorageClient` (`exists`/`resolveUrl`) and
  `storage.ImageKeyUtils` (ownership parsing) to validate `imageKeys` before accepting them,
  in `/classify`/`POST /api/issues`.
- Depends on `professionals.repository.CategoryRepository` (read-only) to validate
  `categoryId`, and (since Milestone 3) `professionals.repository.ProfessionalRepository`
  and `users.repository.UserRepository` to authorize/enrich `GET /api/issues/{id}` for a
  professional caller and resolve a professional's display name for `latestOrder`.
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`) and
  `RoleGuard`/`AuthenticatedUser`.
- Since Milestone 3, depends on `bookings.repository.OrderRepository`/
  `bookings.entity.Order`/`bookings.entity.OrderStatus` for `GET /api/issues/{id}`'s
  `latestOrder` field — see the `dto.LatestOrderSummary` row above. `bookings` in turn
  depends on `issues` (an order is created against a confirmed, persisted issue) and calls
  `IssueRepository.bookIfOpen`/`revertToOpen` directly — the two packages are therefore
  mutually dependent, a deliberate, documented exception, not an oversight. Since Milestone
  5, `bookings.service.BookingsService.expireIfPending` (invoked by
  `notifications.scheduler.OrderExpirySweepJob`'s `@Scheduled` sweep) also calls
  `IssueRepository.expireIfBooked` directly, the same `bookings → issues` relationship as
  `bookIfOpen`/`revertToOpen`, not a new dependency edge.

## Data model

Owns `issues` (§2.6) and `issue_images` (§2.7) in `docs/architecture/data-model.md`.

## Assumptions / judgment calls made during implementation

- **AI-suggested category is not persisted anywhere.** `POST /api/issues`'s request body
  has no `suggestedCategoryId`/AI-explanation field, and nothing records what the AI
  originally suggested vs. what the customer confirmed. This is the contract doc's adopted
  default (§2.2), explicitly flagged there for user sign-off — if AI-accuracy/override-rate
  tracking is wanted, it needs a new nullable column or log table, which is a new Flyway
  migration (out of bounds for this task).
- **Role check ordering bug (fixed post-Milestone-2, QA-reported):** `@Valid` request-body
  validation used to run before `RoleGuard.requireRole` (called from the controller method
  body), so a request that was both wrong-role and malformed incorrectly returned
  `400 VALIDATION_ERROR` instead of `403 FORBIDDEN`. Fixed by moving the role check into
  `common.security.RoleRequiredInterceptor` (registered by `config.IssuesWebConfig`),
  which runs in `preHandle` — strictly before Spring resolves `@Valid` request bodies for
  the matched method — so the role check now always wins, matching
  `api-contract-issues.md` §1. The in-controller-body `RoleGuard.requireRole` calls were
  removed as redundant once the interceptor covers every `/api/issues/**` route.
- `imageKeys` cap (6) and non-blank-entry validation are enforced via Bean Validation
  (`@Size(max = 6) List<@NotBlank String>`) directly on the DTOs, not manually in the
  service — only existence/ownership (`IMAGE_KEY_INVALID`) is checked in
  `IssuesService`, since that requires a call into `storage`.
- `description`'s 10–2000 character bound and the 6-image cap are both flagged
  recommendations in the contract doc (§2.1), not hard requirements from any source
  document — trivially tunable (Bean Validation annotations only, no migration).

## Status

**Milestone 3 addition, implemented and QA-validated**, on branch `MS3` (not yet merged to
`main` — pending the user's own git operations): `GET /api/issues/{id}` implemented per
`docs/architecture/api-contract-bookings.md` §2.1, alongside the `IssuesWebConfig` narrowing
fix that same doc's §0.1 flagged (blanket `/api/issues/**` interceptor would have incorrectly
`403`'d a professional calling the new endpoint). QA live-validated this endpoint (both
`CUSTOMER`-ownership and `PROFESSIONAL`-order-existence authorization paths, and the
`IssuesWebConfig` fix) against a real Postgres instance as part of the full Milestone 3
pass — zero bugs found, no regression to the Milestone 2 `/classify`/`POST /api/issues`
role gating. See `docs/architecture/implementation-plan.md`'s Milestone 3 entry for the
full QA summary.

Implemented in **Milestone 2 (Issue creation & AI classification)**, per
`docs/architecture/implementation-plan.md`. Manually smoke-tested end-to-end against a real
local Postgres + local-disk storage + mock AI: upload image → classify (Hebrew water-leak
description correctly suggested `plumbing`) → create issue (`201`, `issues` + one
`issue_images` row persisted, `imageUrl` matches the upload response) → professional-role
caller correctly `403`s on both endpoints → an image key with a forged/mismatched owner
segment correctly `400`s with `IMAGE_KEY_INVALID` → too-short description correctly
`400`s with `VALIDATION_ERROR`. Full milestone QA sign-off is `pronto-qa`'s call, not
asserted here.

Post-Milestone-2 bug fix (QA-reported): the role-check-ordering bug described above under
"Assumptions / judgment calls" has been fixed (`config.IssuesWebConfig` +
`common.security.RoleRequiredInterceptor`) and re-verified against a real local Postgres —
professional token + malformed body now correctly `403`s on both `/api/issues/classify`
and `POST /api/issues` instead of `400`.

**Milestone 5 addition**: `IssueRepository.expireIfBooked` added, alongside the existing
`bookIfOpen`/`revertToOpen`, for the new `PENDING`-order timeout expiry sweep. This package's
role is limited to owning that one guarded-transition method; the sweep itself (scheduling,
candidate-finding, notification dispatch) lives in `bookings`/`notifications` — see
`notifications/README.md` and `docs/architecture/api-contract-notifications.md` §4.5 for the
full mechanism. QA live-validated the transition (including the `EXPIRED`-issue-side-effect
check) as part of the Milestone 5 pass.
