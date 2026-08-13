# `issues`

## Purpose

Issue creation, category selection, and image metadata; orchestrates the `ai` package for
classification.

Implements `docs/architecture/api-contract-issues.md` §2.1–2.2.

## Responsibilities

- `POST /api/issues/classify` — stateless AI-suggestion preview. Validates the request,
  verifies any `imageKeys` exist in storage and belong to the caller (§3.3), delegates to
  `ai.service.ClassificationService`, and returns the suggestion. **No DB write** — may be
  called repeatedly (e.g. after the customer edits their description) with zero side
  effects. Lives under `/api/issues/*`, not a standalone `/api/ai/*` route — see the
  controller's Javadoc for the full "package placement" rationale from the contract doc.
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
- Both endpoints require `role = CUSTOMER`, enforced via
  `common.security.RoleRequiredInterceptor` (registered for `/api/issues/**` by
  `config.IssuesWebConfig`), which calls `common.security.RoleGuard.requireRole` from
  `preHandle` — see `storage/README.md`'s "Role enforcement" section for the full
  rationale (identical pattern used here).

## Key classes

| Class | Role |
|---|---|
| `entity.Issue` | JPA entity for `issues`. `customerId`/`categoryId` are plain FK columns, not associations — same convention as `professionals.entity.Professional`. Always starts `status = OPEN`. |
| `entity.IssueImage` | JPA entity for `issue_images`. `imageUrl` is whatever `storage.StorageClient.resolveUrl` returns for the key — the underlying object is never moved/renamed on confirmation. |
| `entity.IssueUrgencyType` / `entity.IssueStatus` | Enums mirroring the `issues` table's `CHECK` constraints. |
| `repository.IssueRepository` / `repository.IssueImageRepository` | Plain `JpaRepository`s. |
| `dto.ClassifyRequest` / `dto.ClassifyResponse` | `POST /api/issues/classify` wire shapes. |
| `dto.CreateIssueRequest` / `dto.IssueResponse` / `dto.IssueImageResponse` | `POST /api/issues` wire shapes. `CreateIssueRequest` deliberately carries no AI-suggestion field (see "Responsibilities" above). |
| `service.IssuesService` | All business logic for both endpoints, including the shared `imageKeys` ownership/existence validation. |
| `controller.IssuesController` | `/api/issues/classify` + `/api/issues`. |
| `config.IssuesWebConfig` | Registers `common.security.RoleRequiredInterceptor(role = "CUSTOMER")` for `/api/issues/**` (see "Role check ordering fix" below). |

## Interactions with other packages

- Calls `ai.service.ClassificationService` for the category suggestion (`/classify` only —
  `POST /api/issues` never calls `ai` at all, since the confirmed `categoryId` is supplied
  directly by the caller).
- Calls `storage.client.StorageClient` (`exists`/`resolveUrl`) and
  `storage.ImageKeyUtils` (ownership parsing) to validate `imageKeys` before accepting them,
  in both endpoints.
- Depends on `professionals.repository.CategoryRepository` (read-only) to validate
  `categoryId` — reused as-is, not duplicated, same pattern `auth` already uses (see
  `professionals/README.md`).
- Depends on `common` for the error envelope (`ApiException`/`ErrorCode`) and
  `RoleGuard`/`AuthenticatedUser`.
- Will be consumed by `bookings` in later milestones — an order is created against a
  confirmed, persisted issue. **No `GET /api/issues/{id}` exists yet** (explicitly out of
  this milestone's scope per the contract doc §2.2) — `bookings` will need one; flagged
  here as a forward dependency, not solved in this package yet.

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
