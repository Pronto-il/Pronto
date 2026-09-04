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
  **Iterative clarification** (issue-classification redesign — supersedes the old
  "Clarification-question extension"): there is no longer an initial-pass branch and a
  follow-up branch. Every call runs the same classification over the same complete context —
  original `description`/`imageKeys`, the optional `selectedCategoryId` hint, and the
  **accumulated** `clarificationAnswers`. When a specific missing fact could still change
  which professional is sent, the response comes back `status = QUESTIONS` with exactly
  **one** question; the client answers it and calls again with the answer appended. That can
  legitimately repeat until the server-side budget
  (`pronto.ai.routing.max-clarification-questions`) is spent, after which a `CLASSIFIED`
  result is guaranteed — by the routing policy committing, never by throwing. The response
  carries no confidence, candidates or ambiguity reason; those are backend diagnostics. See
  `docs/architecture/ai-issue-classification-redesign.md` and `ai/README.md`.
- `POST /api/issues` — persists the `issues` row, any `issue_images` rows, **the clarification
  conversation**, and the two AI-artefact rows, in a single transaction. Validates
  `categoryId` against `categories` (`400 VALIDATION_ERROR` if invalid/absent, same
  convention M1 used for registration's `categoryId` — not `404`) and re-validates
  `imageKeys` ownership/existence independently of whatever was passed to `/classify`.
  `categoryId` remains whatever the customer confirmed or overrode — the AI suggestion never
  wins over an explicit customer choice.
  - `clarificationAnswers` are now persisted as `issue_clarifications` rows rather than
    discarded at this boundary. They were the highest-signal context in the whole flow and
    are what the Professional Brief is built from.
  - An `issue_classifications` row (round count) and a `PENDING` `issue_briefs` row are
    seeded here, so the round count survives even if the AI is entirely unavailable and the
    professional's screen reads an explicit state rather than a missing object.
  - An `IssueCreatedEvent` is published inside the transaction and consumed **after commit**
    by `IssueBriefService`, so no model call can affect whether the issue is saved.
  - The request still deliberately carries no AI confidence/candidate fields — those are
    recorded server-side, where they can be vouched for, rather than accepted from a client.
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
  **As of backend MS9 (2026-08-18)**: this method's already-established role-based check
  (customer owns the issue, OR professional has an order on it) is now also what grants
  image access — see "Image URLs (backend MS9)" below, a fix for a gap that was open since
  Milestone 2 and never picked up through Milestone 6.
  **Issue-classification redesign**: the response also carries `clarifications` (the
  customer's own answers, verbatim, for both roles) and `prontoAnalysis` — the Professional
  Brief, resolved **only** for the `PROFESSIONAL` branch above, since it is preparation
  material for whoever is going rather than customer-facing content. It is `null` for a
  customer and for any issue created before briefs existed. See "Customer report vs Pronto
  analysis" below.

## Guest image keys and the promotion at commit (2026-08-29)

Guests could already classify; they could not attach photos, because `validateImageKeys` resolved
ownership out of a `customers/{callerId}/...` key and a guest has no caller id. Two things changed
here, and nothing else did.

**1. `validateImageKeys` asks the same question of a larger set of proved identities.** It now takes
a `common.security.UploadOwner` — the customer id from the JWT, the guest id from a signed
`X-Pronto-Guest-Session` token, or (mid-flow, having just registered) both — and accepts a key whose
owner segment matches either. A key naming a namespace the caller did not prove is still
`IMAGE_KEY_INVALID`, unchanged. That single rule is what stops guest A attaching guest B's photo and
what stops an authenticated customer attaching any guest's photo by quoting its key: possession of
the token that named the namespace is the evidence, never the key itself.

**Images do participate in AI classification, and guest images participate identically.**
`classify` passes `imageKeys` to `ai.service.ClassificationService`, which downloads and encodes
each one via `IssueImageResolver#resolveRequired` before the model call. Nothing on that path knows
or asks who owns an image — only that the caller was allowed to attach it — so this feature changed
no classification behaviour at all. `service.GuestIssueImagesTest` pins both directions: a guest's
keys reach the classifier, and an authenticated customer's call forwards exactly what it always did.

**2. `create` promotes guest keys onto the account, once.** `POST /api/issues` is still
`CUSTOMER`-gated — a guest cannot create an issue — but the person confirming may have been a guest
thirty seconds ago and still holds the session token their photos were uploaded under. Presenting
both credentials is the *claim*: each `guests/...` key is server-side copied to
`customers/{userId}/issues/temp/{sameFilename}` (`storage.service.StorageService#promoteGuestImage`)
and it is the **promoted** key that `issue_images` records. So every read path downstream —
`getById`, `toResponse`, the batch presign, a resumed draft — keeps seeing the one key format it
already understands, and no row ever outlives the session that owns it.

Ordering matters and is deliberate: the copy runs inside the transaction and is idempotent (same
destination filename, so a retried commit overwrites rather than accumulating orphans), while the
guest original is deleted in an **after-commit** callback. Deleting inline would mean a rolled-back
booking had destroyed the customer's photos, and their retry — still holding the guest key in their
draft — would fail with `IMAGE_KEY_INVALID`. Anything the callback misses is reclaimed by the
`guests/` lifecycle rule on the uploads bucket. See `storage/README.md`'s "Guest image upload"
section for the storage-side half.

## Customer report vs Pronto analysis (issue-classification redesign)

**The customer's own report and Pronto's AI interpretation are separate at every layer, and
that separation is the point** — a professional must never have to read carefully to tell an
AI hypothesis from something the customer actually said.

- **Storage** — `issues.description` is never written by AI. The brief lives in its own
  `issue_briefs` table; the conversation lives in `issue_clarifications`.
- **API** — `IssueDetailResponse.description`, `.images` and `.clarifications` are verbatim
  customer content. `.prontoAnalysis` is the only AI-authored object in the response, and it
  is a distinct field rather than enriched prose folded into the description.
- **UI** — the customer's words stay quoted on the plain card under "מה הלקוח תיאר";
  `ProntoAnalysisCard` is a separately styled, labelled card carrying an explicit
  "not a site inspection" disclaimer.

`ProntoAnalysisResponse.status` (`PENDING`/`READY`/`FAILED`) exists because generation is
asynchronous: the professional's screen must distinguish "not ready yet" from "we tried and
could not", and neither may ever block a booking.

## Persistence added by the classification redesign

| Table | Entity / repository | Notes |
|---|---|---|
| `issue_clarifications` | `entity.IssueClarification` / `repository.IssueClarificationRepository` | One row per question+answer, ordered by `position` (unique per issue). Immutable. |
| `issue_classifications` | `entity.IssueClassification` / `repository.IssueClassificationRepository` | What the AI independently routed to, plus confidence/candidates/round count and the `low_confidence` / `unresolved` flags. **Telemetry only** — `issues.category_id` stays the source of truth for dispatch. Only the round count is written unless `record-final-classification` is on. |
| `issue_briefs` | `entity.IssueBrief` / `repository.IssueBriefRepository` | The Professional Brief and its status. |

Created by `V32__create_issue_classification_and_brief.sql`, plus
`V33__alter_issue_classifications_add_unresolved.sql` for the `unresolved` flag. No pre-existing
table or column was changed; all three cascade from `issues`. List-valued columns are `TEXT` holding JSON via
`entity.converter` `AttributeConverter`s — nothing queries inside them, and `TEXT` keeps
`ddl-auto: validate` unambiguous.

`service.IssueBriefService` is the one asynchronous consumer: it listens for
`IssueCreatedEvent` **after commit** on the `aiTaskExecutor` pool, optionally records the AI's
independent routing (`pronto.ai.record-final-classification`, **off by default** — it is a
second model call on every created issue), then generates and stores the brief. Images are
resolved once per run and shared by both calls.

A failure is recorded as `FAILED`, logged with its cause, and **not retried**: the booking is
unaffected either way, a brief is preparation material rather than a transactional obligation,
and a retry loop around a paid model call is a cost risk out of proportion to what is lost.

Because generation is asynchronous, `prontoAnalysis` is routinely absent or `PENDING` when a
professional opens a job — that is a normal state, not an error. `GET /api/issues/{id}` returns
the issue exactly as before in that case, and the professional still has the description,
photos, clarification answers and category. The UI omits the analysis card entirely on `FAILED`
or when the brief came back empty, and shows one unobtrusive line on `PENDING`; there is no
polling.

## Image URLs (backend MS9, 2026-08-18)

**`issue_images` now stores a raw storage key, not a resolved URL — fixes a real bug, not
just a naming cleanup.** Before MS9, `IssuesService.create` called
`storageClient.resolveUrl(key)` once, at issue-creation time, and persisted the *result*
into what was then `issue_images.image_url`; `getById` read that string straight back out
of the DB, unchanged, on every later request. This was harmless while `resolveUrl` returned
a permanent, non-expiring backend-proxy URL — it stopped being harmless the moment backend
MS9 made every URL this app issues presigned and time-limited (300s TTL,
`pronto.storage.presigned-url-ttl-seconds`): a URL saved at creation time would already be
expired long before a professional later opened an incoming request, or a customer revisited
their issue days later. Fixed via
`V24__rename_issue_images_image_url_to_image_key.sql` (`IssueImage.imageUrl`/`getImageUrl()`
renamed to `imageKey`/`getImageKey()`) plus resolving the presigned URL fresh, at *read*
time, in both `create` (building the response, still within the same request so the TTL is
irrelevant there) and `getById` (every time this endpoint is hit — necessary now, not just
correct). This brings `issue_images` in line with `professionals.profile_image_key`'s
pre-existing key-not-URL pattern, which `data-model.md` §2.8 had already flagged as the more
future-proof shape back when the column was originally designed ("later" is now).

**A professional with a confirmed order on an issue can now actually view that issue's
photos — a gap explicitly deferred since Milestone 2, never picked up before this round.**
`storage.ImageKeyUtils.belongsTo` matches a `customers/{callerId}/...` key's embedded owner
id against the *viewing* caller's id — this always fails for a professional caller, since a
professional's caller id is never the customer's id, regardless of whether they have a
legitimate order on the issue. This was invisible until MS9 because every `<img>`-tag
request was already failing earlier, at the missing-`Authorization`-header stage (the ORB
bug `storage/README.md` describes) — the 403-for-a-legitimate-professional case was never
actually exercised end-to-end through a real browser before now. `getById`'s own
role-based check (established well before MS9: customer owns the issue, OR professional has
an order on it) is already a strict superset of "may view every image this issue owns," so
`getById`'s image-resolution loop now calls
`storage.service.StorageService#getPresignedUrlAssumingCallerAuthorized` — a narrow,
explicitly-named bypass of the general per-key ownership check, safe here specifically
because `getById` has already independently proven the caller may view this issue and
everything attached to it. **This bypass method must not be reused at a new call site
without re-justifying the exemption to `pronto-lead`** — see `storage.service.StorageService`'s
own Javadoc. `IssuesService.create`'s image-resolution loop, by contrast, keeps using the
general, ownership-checked `getPresignedUrl` — it's `CUSTOMER`-only and every key in it is
already guaranteed to belong to the caller (`validateImageKeys` enforced that before any
image was ever persisted), so there's no mismatch case to bypass there.

Full design record: `docs/architecture/backend-ms9-presigned-image-urls-design.md` §0, §9.4.

## Key classes

| Class | Role |
|---|---|
| `entity.Issue` | JPA entity for `issues`. `customerId`/`categoryId` are plain FK columns, not associations — same convention as `professionals.entity.Professional`. Always starts `status = OPEN`. |
| `entity.IssueImage` | JPA entity for `issue_images`. **As of backend MS9**: `imageKey` (renamed from `imageUrl`) is the raw storage key the image was uploaded to — never a resolved URL, resolved fresh to a presigned URL only at read time (see "Image URLs (backend MS9)" below). The underlying object is never moved/renamed on confirmation. |
| `entity.IssueUrgencyType` / `entity.IssueStatus` | Enums mirroring the `issues` table's `CHECK` constraints. |
| `repository.IssueRepository` | `JpaRepository`, plus (since Milestone 3) `bookIfOpen`/`revertToOpen` — the atomic `UPDATE ... WHERE <status guard>` transitions `bookings.service.BookingsService` uses for the booking flow (`docs/architecture/api-contract-bookings.md` §3.2/§3.3) — and (since Milestone 5) `reopenIfBooked`, the same guarded-`UPDATE` shape called by `bookings.service.BookingsService.expireIfPending` as part of the `PENDING`-order timeout sweep (`docs/architecture/api-contract-notifications.md` §4.5). It targeted `EXPIRED` until 2026-08-21 and now targets `OPEN`: an order nobody answered must not cost the customer the issue they already described, so it becomes bookable again instead of dying with the order. |
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
- Calls `storage.client.StorageClient#exists` and `storage.ImageKeyUtils` (ownership
  parsing) to validate `imageKeys` before accepting them, in `/classify`/`POST /api/issues`
  — unrelated to and unchanged by backend MS9. Also calls `storage.service.StorageService`
  (**new dependency, backend MS9**) — `getPresignedUrl` (the general, ownership-checked
  path, used by `create`) and `getPresignedUrlAssumingCallerAuthorized` (the narrow bypass,
  used only by `getById` — see "Image URLs (backend MS9)" below) — to resolve `imageKey`s to
  displayable URLs.
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
  `IssueRepository.reopenIfBooked` directly (called `expireIfBooked` until 2026-08-21, when an
  expiring order stopped expiring its issue and started reopening it), the same
  `bookings → issues` relationship as
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

**Milestone 5 addition**: `IssueRepository.expireIfBooked` added (renamed to
`reopenIfBooked` on 2026-08-21, and repointed at `OPEN` — see the note in the key-classes
table above), alongside the existing
`bookIfOpen`/`revertToOpen`, for the new `PENDING`-order timeout expiry sweep. This package's
role is limited to owning that one guarded-transition method; the sweep itself (scheduling,
candidate-finding, notification dispatch) lives in `bookings`/`notifications` — see
`notifications/README.md` and `docs/architecture/api-contract-notifications.md` §4.5 for the
full mechanism. QA live-validated the transition (including the `EXPIRED`-issue-side-effect
check) as part of the Milestone 5 pass.

**Backend MS9 — presigned image URLs (2026-08-18)**: `IssueImage.imageUrl` renamed to
`imageKey` (`V24`), `IssuesService.create`/`getById` both stopped persisting/reading a
resolved URL and now resolve one fresh at read time, and `getById` gained the
professional-with-an-order image-access fix — see "Image URLs (backend MS9)" above for the
full writeup. Backend: 163/163 tests pass. QA live-verified issue photos render for both the
owning customer and an authorized professional with a real order, and that unauthorized
access is genuinely rejected. Full design record:
`docs/architecture/backend-ms9-presigned-image-urls-design.md`. Not yet committed at the
time this doc was written — branch `frontend/MS9-gap-fixes`, pending the user's own git
operations.


## Free-text length limits (2026-09-04)

`IssueText` holds the description's bounds — **10–300 characters** — and both request bodies that
carry a description (`ClassifyRequest`, `CreateIssueRequest`) read them from it rather than
repeating literals. They have to agree: the customer types the description once and it travels
through classification *and* the commit, so a bound on one and not the other would refuse at
booking time something already classified.

The maximum was 2000. It is now 300, which is a deliberate product decision, not a schema one —
`issues.description` is a `TEXT` column, so this annotation is the only bound there is. It also
means the classifier sees a focused description rather than an essay.

`ClarificationAnswerRequest` was **entirely unbounded** and now caps `answer` at 200 (the
free-text answer limit) and `question` at 500 (a wire-level sanity bound on text this service
generated itself and only receives back). Over-long values are rejected in the standard
`VALIDATION_ERROR` envelope with the field named — never trimmed to fit.

The frontend mirrors these numbers in `shared/api/fieldLimits.ts`, stops the caret at them and
shows a counter; that is a courtesy to the person typing. `common.validation.FreeTextLengthLimitsTest`
covers both edges of each bound, against the request objects directly — i.e. with the client out
of the picture.
