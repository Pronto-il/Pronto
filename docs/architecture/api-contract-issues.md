# Pronto — REST API Contract: Milestone 2 (Issue Creation & AI Classification)

Status: **design pass for Milestone 2, ready for `pronto-coding`**. Written by
`pronto-planning`. Builds on top of the already-applied Milestone 0 schema
(`backend/src/main/resources/db/migration/V6__create_issues.sql`,
`V7__create_issue_images.sql`, `V10__seed_categories.sql` — confirmed to already match
`docs/architecture/data-model.md` §2.1/§2.7/§2.8 exactly; **no new Flyway migration is
proposed or needed for this milestone**) and on the Milestone 1 auth mechanism specified
in `docs/architecture/api-contract.md` (JWT issuance/validation, error envelope, Spring
Security wiring — reused verbatim here, not redesigned). Scope is `issues`, `ai`, `storage`
only — no bookings/notifications/availability endpoints here (those belong to later
milestones' own contract docs).

This doc is a **precise contract spec** (request/response JSON shapes, status codes, error
codes, field-level validation), not literal Java code — writing the controllers/services is
`pronto-coding`'s job.

Cross-referenced from `docs/architecture/overview.md` §3.4, §3.5, §3.8 and §4 (`issues`,
`ai`, `storage` package rows), and `docs/architecture/implementation-plan.md`'s Milestone 2
entry.

---

## 0. Conventions used throughout this doc

Identical to `api-contract.md` §0 — repeated here for a self-contained read, not
re-decided:

| Convention | Choice |
|---|---|
| Base paths | `/api/issues/*` for the issue-creation journey (classification preview + persisted creation); `/api/storage/*` for image upload/retrieval. |
| Request/response bodies | JSON, `camelCase` field names (DTO boundary translation from the DB's `snake_case` columns), except the image-upload request which is `multipart/form-data` (binary file content doesn't fit a JSON envelope). |
| Auth header | `Authorization: Bearer <jwt>` on every endpoint in this doc — **all of them require auth**, per the milestone brief. See §0.1 below for the added role restriction. |
| Timestamps in JSON | ISO-8601 / RFC 3339 with offset, e.g. `"2026-08-13T12:34:56Z"`. |
| Language of error messages | English in the API response body, same as M1 — Hebrew presentation is the frontend's job. |

### 0.1 Role restriction (new pattern vs. M1)

**Every endpoint in this doc requires `role = CUSTOMER`.** M1's `/api/users/me` was usable
by both roles; M1 never needed a role-gated business endpoint. This milestone's flows
(issue creation, AI classification, image upload) are customer-only per the PRD's issue
flow (§3.3–§3.4) and the milestone brief — a professional account calling any of these
endpoints gets `403 FORBIDDEN` (new error code, §1). Enforced the same way M1 enforces
authentication: a `SecurityFilterChain` matcher / `@PreAuthorize("hasRole('CUSTOMER')")` on
these routes, reusing the JWT `role` claim already issued at login (`api-contract.md`
§3.1) — no new token mechanism needed.

---

## 1. Standard error response envelope (reused verbatim from `api-contract.md` §1)

```json
{
  "timestamp": "2026-08-13T12:34:56Z",
  "path": "/api/issues/classify",
  "error": {
    "code": "IMAGE_KEY_INVALID",
    "message": "One or more imageKeys were not found or do not belong to the caller.",
    "details": null
  }
}
```

Same semantics as M1: `error.code` is the stable machine-readable taxonomy for the
frontend to branch on; `error.message` is English/human-readable/not guaranteed shown
verbatim; `error.details` is nullable, shape depends on the error. Implemented via the
same global `@ControllerAdvice` in `common` — **not** a new exception-handling mechanism
per package.

### Error code taxonomy — Milestone 2 additions

Codes already defined in `api-contract.md` §1 are **reused as-is** where applicable
(`VALIDATION_ERROR`, `UNAUTHORIZED`, `NOT_FOUND`, `INTERNAL_ERROR`) — not redefined here.
New codes needed for this milestone's endpoints, kept consistent in naming style with the
existing taxonomy:

| `error.code` | HTTP status | Meaning |
|---|---|---|
| `VALIDATION_ERROR` *(reused)* | 400 | Request body failed field-level validation. `details`: `[{ "field": "...", "message": "..." }, ...]`. Also used for an invalid/nonexistent `categoryId` on issue creation — same convention M1 used for registration's `categoryId` (`api-contract.md` §2.1), not `NOT_FOUND`. |
| `FORBIDDEN` | 403 | Caller is authenticated (valid JWT) but their role doesn't permit this action. Every endpoint in this doc requires `role = CUSTOMER`; a professional's token gets this. |
| `IMAGE_KEY_INVALID` | 400 | An `imageKeys` entry passed to `/api/issues/classify` or `POST /api/issues` doesn't exist in storage, or exists but its embedded owner doesn't match the caller. See §3.3 for the ownership mechanism. |
| `UNSUPPORTED_IMAGE_TYPE` | 400 | Uploaded file's content-type isn't one of the accepted image types (`image/jpeg`, `image/png`, `image/webp`). |
| `IMAGE_TOO_LARGE` | 413 | Uploaded file exceeds the configured max size (recommended default 8 MB — see §2.3). |
| `AI_SERVICE_ERROR` | 502 | The configured AI client failed to produce a classification (timeout, non-2xx, malformed response) after retries. Only reachable when `pronto.ai.mode=openai`; the mock client (§3.1) never fails this way. |
| `STORAGE_SERVICE_ERROR` | 502 | The configured storage client failed to store or retrieve an object (S3 call error). Only reachable when `pronto.storage.mode=s3`; the local-disk client (§3.2) fails this way only on genuine disk I/O errors. |
| `UNAUTHORIZED` *(reused)* | 401 | Missing, malformed, expired, or otherwise invalid JWT. |
| `NOT_FOUND` *(reused)* | 404 | Not used by any endpoint in this doc's happy/error paths below — image lookups deliberately use `IMAGE_KEY_INVALID` (400) rather than `NOT_FOUND` (404), since an invalid key is a caller input-validation problem (they own the request, not navigating to someone else's resource by id) — consistent with how M1 treated an invalid `categoryId`. Reused code, kept in the taxonomy table for completeness. |
| `INTERNAL_ERROR` *(reused)* | 500 | Unhandled server error. |

---

## 2. Endpoints

### 2.1 `POST /api/issues/classify`

Auth required: **yes** (role: CUSTOMER).

**Package placement — stated explicitly, per the task brief's instruction.** This
endpoint lives in the `issues` controller (not a standalone `ai` controller), and
delegates internally to an `ai.ClassificationService`. Reasoning: the customer-facing
journey is "describe an issue → see AI suggestion → confirm/edit → create" — a single REST
namespace (`/api/issues/*`) keeps that journey's two calls (`classify`, then `POST
/api/issues`) discoverable together, mirroring how M1 kept the register→verify→login
journey under one `/api/auth/*` namespace even though `auth` and `users` are separate
packages internally. The `ai` package remains the internal implementation detail — OpenAI
client wrapper, prompt construction, response parsing — fully unit-testable/mockable
independent of the controller layer, exactly as `overview.md` §4 describes it ("kept
separate from `issues` so it's independently testable/mockable"). `pronto-coding` should
*not* expose a public `/api/ai/*` route for this milestone; nothing in the brief needs one.

**This call does NOT write to the database.** No `issues` row, no `issue_images` row, no
audit/log table row of the classification itself. It is a pure request/response
computation against the (mock or real) AI client — stateless and ephemeral, per
`data-model.md` §3 item 7. A customer may call this endpoint repeatedly with edited
descriptions (e.g. after rewording) with no side effects and no cleanup required.

**Image handling — decided, justified against the upload→classify→confirm flow order.**
This endpoint accepts already-uploaded image **keys** (returned by `POST
/api/storage/images`, §2.3), **not** raw image bytes/base64 embedded in this request.
Reasoning:

- The milestone's screen flow is Home/New Issue (customer attaches photos) → AI Review
  (sees the suggestion) → confirm → `POST /api/issues`. Images are captured on the *first*
  screen, before classification happens — so by the time this endpoint is called, the
  images already need to exist somewhere durable regardless of whether classification uses
  them, because the **same images** are what get attached to the issue on confirm (§2.2).
  Uploading them once (via `/api/storage/images`) and referencing them by key from both
  `classify` and `POST /api/issues` avoids uploading the same bytes twice (once to "show
  the AI", once to "attach to the issue") and avoids ever embedding large base64 blobs in a
  JSON request body.
- The alternative (raw bytes/base64 in the classify request) would require **either**
  re-uploading those same bytes again at issue-creation time (wasteful, and creates a
  window where classification saw one set of bytes and creation persisted a
  possibly-different set) **or** having `classify` silently persist the images as a
  side-effect to avoid re-upload — which directly contradicts the "no DB writes until
  confirm" rule this endpoint is required to follow (`data-model.md` §3 item 7).
- Uploading first also matches the PRD's implied UX: attaching a photo is a self-contained
  action on the New Issue screen (immediate thumbnail/preview), not something that only
  succeeds or fails bundled with the classification call.

**Request:**
```json
{
  "description": "יש נזילת מים מתחת לכיור במטבח",
  "imageKeys": [
    "customers/42/issues/temp/9f1c2e4a-3b7d-4e21-9a10-2f8e1c6b5a90.jpg"
  ]
}
```

**Field validation:**

| Field | Rule |
|---|---|
| `description` | required, 10–2000 characters. **Assumption, flagged**: no source document specifies a length bound; 10 is a floor to avoid classifying near-empty junk, 2000 bounds OpenAI token usage/cost. Trivial to tune later — request validation only, not a stored-column constraint (the `issues.description` column is `TEXT`, unbounded). |
| `imageKeys` | optional; if present, array of strings, each a key previously returned by `POST /api/storage/images`. **Recommendation, flagged**: capped at 6 images per call — no DB-level cap exists (`data-model.md` §2.8 explicitly leaves this to the API layer), 6 is a judgment call for cost/latency control, easy to change without a migration. Each key must exist in storage and belong to the caller (§3.3) — otherwise `400 IMAGE_KEY_INVALID`. |
| `clarificationAnswers` | optional, at most 3 entries, each `{ "question": "...", "answer": "..." }` (both required, non-blank). See "Clarification-question extension" below — omitted/empty for the initial call. |

**Behavior:**
1. Resolve caller from JWT; reject `403 FORBIDDEN` if `role != CUSTOMER`.
2. Validate `description` length; validate `imageKeys` array (cap, non-empty strings).
3. For each `imageKeys` entry: verify it exists in storage and its embedded owner matches
   the caller (§3.3). Any failure → `400 IMAGE_KEY_INVALID` (do not partially process —
   fail the whole request if any key is bad).
4. If `clarificationAnswers` is absent/empty, call
   `ai.ClassificationService.classify(description, imageKeys)` (the initial pass — may
   return `CLASSIFIED` or `QUESTIONS`, see below). If `clarificationAnswers` is present,
   call `ai.ClassificationService.classifyWithClarification(description, imageKeys,
   clarificationAnswers)` instead (the single allowed clarification round — always
   resolves to `CLASSIFIED`). Internally, the service resolves each key to raw bytes via
   the `storage` package's `StorageClient` (**not** via the public `imageUrl`, see §3.1's
   reachability note) and delegates to the configured `AiClassificationClient` (mock or
   real OpenAI, per `pronto.ai.mode`, §3.1).
5. When the AI result is `CLASSIFIED`, map its returned category identifier to a
   `categories` row (join on `code`). If the AI response doesn't match any seeded category
   code, apply the fallback in §3.1 (flagged recommendation, not a hard requirement). When
   the result is `QUESTIONS`, no category mapping is attempted.
6. On AI client failure (timeout, non-2xx, malformed/unparseable response, or a
   clarification round that fails to resolve to `CLASSIFIED`) after the configured retry
   policy → `502 AI_SERVICE_ERROR`. No DB state to roll back (nothing was written).
7. Return `200` with the suggestion or the clarification questions. **Nothing is persisted
   at any step above.**

**Response `200` (`CLASSIFIED`):**
```json
{
  "status": "CLASSIFIED",
  "suggestedCategoryId": 1,
  "suggestedCategoryCode": "plumbing",
  "confidence": 0.87,
  "explanation": "התיאור מזכיר נזילת מים מתחת לכיור, מה שמעיד באופן מובהק על בעיה באינסטלציה.",
  "questions": []
}
```

| Field | Notes |
|---|---|
| `status` | `"CLASSIFIED"` \| `"QUESTIONS"` — see "Clarification-question extension" below. |
| `suggestedCategoryId` | FK-able `categories.id` when `status = CLASSIFIED`, `null` when `status = QUESTIONS`. Ready to be echoed back (as-is or overridden) in `POST /api/issues`'s `categoryId`. |
| `suggestedCategoryCode` | `categories.code` when `status = CLASSIFIED`, `null` when `status = QUESTIONS` — included for convenience/debugging (frontend can display without a second lookup). |
| `confidence` | `0.0`–`1.0`, nullable. **Assumption, flagged**: the PRD never mentions a confidence score; included because it's a natural, low-cost byproduct of an LLM classification and gives the AI Review screen a way to signal "AI wasn't sure" (e.g. low value → nudge the customer to double-check). If `pronto-coding`/the real OpenAI prompt doesn't reliably produce a usable confidence number, this field returning `null` is acceptable — **not a hard requirement**, remove if it proves more trouble than value. |
| `explanation` | Hebrew, short human-readable justification (PRD/`overview.md` §3.4 explicitly requires "a short explanation"). English when the real OpenAI client is active — its prompt is written/validated in English (see §3.1); switching to Hebrew is a prompt-only change, not attempted this pass. |
| `questions` | Empty when `status = CLASSIFIED`; 1-3 clarification questions when `status = QUESTIONS`. |

### Clarification-question extension

**Problem this solves**: a written description and its attached images can meaningfully
disagree (e.g. "the air conditioner is leaking water" over a photo that looks like a
plumbing leak near a wall), or genuinely support two different categories equally well.
Guessing in that situation produces a worse suggestion than asking one or two short,
closed-ended questions would.

**Decision**: the *same* `POST /api/issues/classify` endpoint handles both the initial pass
and the clarification round — no new endpoint. This keeps the stateless, repeatable-call
design of §2.1/§3.4 intact (no server-side session is created to link the two calls); the
frontend simply echoes the original `description`/`imageKeys` back on the second call,
together with `clarificationAnswers`.

**Response `200` (`QUESTIONS`)** — returned by the *initial* call only, never by a call that
already included `clarificationAnswers`:
```json
{
  "status": "QUESTIONS",
  "suggestedCategoryId": null,
  "suggestedCategoryCode": null,
  "confidence": 0.61,
  "explanation": "The description suggests an air-conditioning issue, while the image may indicate a plumbing leak.",
  "questions": [
    {
      "id": "q1",
      "question": "Where does the water appear to be coming from?",
      "options": ["Directly from the air conditioner", "From a pipe or wall near the air conditioner", "I am not sure"]
    }
  ]
}
```

**Round trip**: the frontend displays `questions`, collects the customer's answers, and
calls `POST /api/issues/classify` again with the *same* `description`/`imageKeys` plus:
```json
{
  "description": "The air conditioner is leaking water.",
  "imageKeys": [],
  "clarificationAnswers": [
    { "question": "Where does the water appear to be coming from?", "answer": "Directly from the air conditioner" }
  ]
}
```
This second call always performs exactly one additional AI request and always returns
`status = "CLASSIFIED"` — the backend never returns `QUESTIONS` again for a request that
already carries `clarificationAnswers` (enforced in `OpenAiClassificationClient`; a
non-compliant AI response is treated as `502 AI_SERVICE_ERROR`, same as any other malformed
response). At most 3 questions are ever returned, and OpenAI is instructed not to inflate
confidence just because clarification answers were provided — see
`backend/src/main/java/com/pronto/ai/README.md` for the full prompt/schema design.

**Status codes**: `200` success · `400 VALIDATION_ERROR` · `400 IMAGE_KEY_INVALID` ·
`401 UNAUTHORIZED` · `403 FORBIDDEN` · `502 AI_SERVICE_ERROR`.

---

### 2.2 `POST /api/issues`

Auth required: **yes** (role: CUSTOMER).

Persists the `issues` row (and any associated `issue_images` rows) — this is the call that
actually writes to the database, **only reachable after** the customer has seen the AI
suggestion on the Review screen and confirmed or overridden it. There is **no session/token
linking this call back to a specific prior `/classify` call** — see the "AI suggestion
audit trail" decision immediately below for why, and the description-mismatch note in the
Behavior section.

**Decision: the AI-suggested category is fully discarded once the customer confirms or
overrides it — no audit/trace field is added to record what the AI originally
suggested.** This is a genuine design choice with a real tradeoff, presented per the task
brief's instruction not to silently pick:

- **Default recommendation (adopted here): don't add a column.** `POST /api/issues`'s
  request body contains only the confirmed `categoryId` (which may or may not equal
  whatever `/classify` returned) — no `suggestedCategoryId`/`aiExplanation` field is
  accepted or stored anywhere. The frontend simply doesn't send the AI's original
  suggestion back. This keeps the schema and the endpoint contract simple, requires **zero
  changes to `V6__create_issues.sql`** (confirmed already sufficient, per this task's
  brief), and matches `issues.category_id`'s existing `NOT NULL` semantics exactly as
  built: one column, one final answer.
- **The tradeoff being accepted**: there's no way to later analyze "how often do customers
  override the AI's suggestion, and to what" — a metric a product team would plausibly
  want (AI quality monitoring, prompt tuning feedback loop). Recovering this later would
  need a new nullable column on `issues` (e.g. `ai_suggested_category_id BIGINT NULL`) or a
  separate lightweight `classification_events` log table — either is a small, low-risk
  addition, but it **is** a new Flyway migration, which is explicitly out of bounds for
  `pronto-coding` this milestone per the lead's constraints.
- **This is flagged for user sign-off, not silently decided as final.** If AI-accuracy
  tracking is wanted from day one, say so now — it's cheap to add before Milestone 2's
  migration boundary is closed, materially more annoying to retrofit after data exists in
  a live `issues` table with real rows that have no suggestion recorded.

**Request:**
```json
{
  "categoryId": 1,
  "description": "יש נזילת מים מתחת לכיור במטבח",
  "urgencyType": "STANDARD",
  "imageKeys": [
    "customers/42/issues/temp/9f1c2e4a-3b7d-4e21-9a10-2f8e1c6b5a90.jpg"
  ]
}
```

**Field validation:**

| Field | Rule |
|---|---|
| `categoryId` | required; must reference an existing `categories.id` (1–8, per the seeded `V10` list). Invalid/absent → `400 VALIDATION_ERROR` (same convention as M1's registration `categoryId`, `api-contract.md` §2.1 — not `404`). **Not required to equal** whatever `/classify` returned in `suggestedCategoryId` — this is the confirmed-or-overridden value, and the endpoint has no way (and no need) to verify it was ever previewed. |
| `description` | required, 10–2000 chars — same rule as `/classify` (§2.1), but **independently validated**, not required to be byte-identical to whatever text was sent to `/classify`. A customer editing their description after seeing the AI suggestion (or before re-classifying) is expected and fine — `/classify` is stateless and doesn't "lock in" a description. |
| `urgencyType` | required, one of `STANDARD` \| `SOS` (matches `issues.urgency_type`'s `CHECK` exactly). |
| `imageKeys` | optional, same validation as `/classify` (§2.1): array of storage keys, each must exist and belong to the caller, capped at 6. **Not required to be the same set** passed to `/classify` — a customer could classify with no images, then attach some before confirming, or vice versa. |

**Behavior:**
1. Resolve caller from JWT; reject `403 FORBIDDEN` if `role != CUSTOMER`.
2. Validate all fields (400 `VALIDATION_ERROR` on any failure, aggregated per-field as M1
   does).
3. Validate `categoryId` exists in `categories`.
4. If `imageKeys` present: verify each key exists in storage and belongs to the caller
   (§3.3) → `400 IMAGE_KEY_INVALID` otherwise (fail the whole request, same as `/classify`).
5. Insert `issues` row: `customer_id = caller.id`, `category_id`, `description`,
   `urgency_type`, `status = 'OPEN'` (column default, matches `V6`).
6. For each validated `imageKeys` entry, insert an `issue_images` row: `issue_id` (just
   created), `image_url` = the URL resolved for that key (§3.3/§3.2 — same URL the upload
   response originally returned). **The underlying storage object is not moved/renamed** on
   confirm — it stays at whatever key it was uploaded to (e.g. still under a `.../temp/...`
   path segment); `issue_images.image_url` simply now has a permanent DB row pointing at
   it. See §4 for why a "promote to a permanent path" step isn't designed this milestone.
7. Steps 5–6 execute in a single transaction — either the issue and all its image rows are
   persisted, or none are (a partial `issues`-row-with-no-images outcome is never left
   behind by a mid-request failure).
8. Return `201 Created`.

**Response `201`:**
```json
{
  "id": 101,
  "customerId": 42,
  "categoryId": 1,
  "description": "יש נזילת מים מתחת לכיור במטבח",
  "urgencyType": "STANDARD",
  "status": "OPEN",
  "images": [
    {
      "id": 501,
      "imageUrl": "https://pronto-issue-images.s3.eu-central-1.amazonaws.com/customers/42/issues/temp/9f1c2e4a-3b7d-4e21-9a10-2f8e1c6b5a90.jpg",
      "uploadedAt": "2026-08-13T12:30:10Z"
    }
  ],
  "createdAt": "2026-08-13T12:34:56Z"
}
```

**Status codes**: `201` success · `400 VALIDATION_ERROR` · `400 IMAGE_KEY_INVALID` ·
`401 UNAUTHORIZED` · `403 FORBIDDEN`.

**Out of scope, flagged not built**: no `GET /api/issues/{id}` (or `GET /api/issues` list)
endpoint is designed this milestone — the brief's endpoint list is classify + create +
upload only, and inventing a fetch-back endpoint isn't needed for Milestone 2's own
acceptance criteria (confirm/override before proceeding). Milestone 3/4 (booking flows)
will need to look up an issue by id when a customer starts Standard/SOS booking against it
— that's next-milestone planning scope, flagged here so it isn't forgotten, not solved now.

**Update, Milestone 3 (2026-08-13):** `GET /api/issues/{id}` has since been designed and
built — see `docs/architecture/api-contract-bookings.md` §2.1 for the full contract
(request/response shape, either-`CUSTOMER`-or-`PROFESSIONAL` authorization, the
`latestOrder` field) and `backend/src/main/java/com/pronto/issues/README.md` for the
as-implemented summary. Not restated in full here to avoid two sources of truth drifting —
the bookings contract doc remains authoritative for this endpoint.

---

### 2.3 `POST /api/storage/images`

Auth required: **yes** (role: CUSTOMER).

**Upload mechanism — decided: backend-proxied upload, not pre-signed S3 URLs.**
`overview.md` §3.5 explicitly left this as the implementing agent's call
("backend issuing pre-signed upload URLs or proxying the upload"). Decision and reasoning:

- **AWS credentials aren't available this milestone** (stated in the task brief). A
  pre-signed-URL flow still requires the backend to hold valid AWS credentials to *generate*
  a correctly-signed URL, even though the actual byte transfer bypasses the backend — it
  doesn't avoid the credentials problem, it just moves where the bytes flow. Backend-proxy
  upload, by contrast, can be fully satisfied by a **local-disk fake storage
  implementation** (§3.2) with zero AWS involvement, exactly mirroring how M1's
  `LoggingEmailSender` let auth be built/tested with zero SMTP involvement.
- **Simpler for a two-person team.** Pre-signed uploads need a second, differently-shaped
  frontend code path (request a URL, `PUT` directly to S3 with S3-specific headers/CORS
  configured on the bucket, then a separate "confirm the upload landed" step) in addition
  to the normal JSON API client. Backend-proxy upload is a single `multipart/form-data`
  `POST`, using the same HTTP client/error-handling/auth-header pattern as every other
  endpoint in this contract.
- **Performance target is still met at this scale.** PRD's 5s max-upload-time target
  (§5.1.4) is achievable proxying through the backend for MVP-scale traffic (this isn't the
  1,000-concurrent-user page-load path; concurrent *image uploads* specifically are a much
  smaller slice of that number) — the extra backend hop for a single image is not expected
  to be the bottleneck at this scale. Revisit only if Milestone 7's hardening pass finds
  otherwise.

**Request**: `multipart/form-data`, single part named `file`.

**Field validation:**

| Field | Rule |
|---|---|
| `file` | required. Content-type must be one of `image/jpeg`, `image/png`, `image/webp` → `400 UNSUPPORTED_IMAGE_TYPE` otherwise. Max size **8 MB** (recommendation, flagged — PRD only specifies a 5s upload-time target, not a size cap; 8 MB is a judgment call sized for typical phone-camera photos, tunable via `spring.servlet.multipart.max-file-size` with no code change) → `413 IMAGE_TOO_LARGE` otherwise. |

**Behavior:**
1. Resolve caller from JWT; reject `403 FORBIDDEN` if `role != CUSTOMER`.
2. Validate content-type and size (Spring's multipart size limit throws
   `MaxUploadSizeExceededException`, mapped to `413 IMAGE_TOO_LARGE` by the same global
   `@ControllerAdvice` pattern as every other error in this doc).
3. Generate a random object key, namespaced by the caller's user id and a random UUID:
   `customers/{callerId}/issues/temp/{uuid}.{ext}` (`ext` derived from content-type). The
   embedded `{callerId}` segment is the ownership mechanism `IMAGE_KEY_INVALID` checks
   against later (§3.3) — **not** a database row (see §3.3 for why, and the residual risk
   this creates).
4. Delegate to `storage.StorageClient.upload(key, bytes, contentType)` — local-disk or S3
   depending on `pronto.storage.mode` (§3.2). No `issue_images` row is created here — this
   object has no associated `issues.id` yet and `issue_images.issue_id` is `NOT NULL`
   (`V7`), so it structurally *cannot* have one until `POST /api/issues` runs (§2.2). This
   is the resolution to the brief's "how does an uploaded image get associated with an
   issue before the issue exists" question — see §3.3 for the full writeup.
5. On storage client failure → `502 STORAGE_SERVICE_ERROR`.
6. Return `201 Created` with the key + resolved URL.

**Response `201`:**
```json
{
  "imageKey": "customers/42/issues/temp/9f1c2e4a-3b7d-4e21-9a10-2f8e1c6b5a90.jpg",
  "imageUrl": "https://pronto-issue-images.s3.eu-central-1.amazonaws.com/customers/42/issues/temp/9f1c2e4a-3b7d-4e21-9a10-2f8e1c6b5a90.jpg",
  "contentType": "image/jpeg",
  "sizeBytes": 482113
}
```

In `pronto.storage.mode=local` (dev/QA default), `imageUrl` instead points at the local
retrieval endpoint below, e.g.
`http://localhost:8080/api/storage/images/customers/42/issues/temp/9f1c2e4a-....jpg`.

**Status codes**: `201` success · `400 UNSUPPORTED_IMAGE_TYPE` · `413 IMAGE_TOO_LARGE` ·
`401 UNAUTHORIZED` · `403 FORBIDDEN` · `502 STORAGE_SERVICE_ERROR`.

---

### 2.4 `GET /api/storage/images/{key}`

Auth required: **yes** (role: CUSTOMER — the owner embedded in the key only, see below).

**Only meaningful/exposed when `pronto.storage.mode=local`.** In `s3` mode, `imageUrl`
returned by §2.3 and echoed in §2.2's response already points directly at a real,
independently-resolvable S3 object URL — this endpoint isn't needed to view it (see §4 for
the still-open question of whether that URL should be publicly fetchable or not). In
`local` mode there is no public URL at all — a locally-stored file needs *some* HTTP
endpoint to be viewable by the frontend/QA at all, and this is it.

**Implementation note for `pronto-coding`**: `key` contains literal `/` characters (e.g.
`customers/42/issues/temp/uuid.jpg`), which a normal `@PathVariable` won't capture past the
first segment. Map this with a trailing wildcard (`@GetMapping("/api/storage/images/**")`
in Spring Boot 3.x, extracting the remainder via
`request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE)` or
`PathPattern`-based extraction) rather than a single `{key}` variable — called out
explicitly since this is an easy gotcha to lose time on, same spirit as M1's
`/actuator/health` permit-all flag (`api-contract.md` §3.2).

**Behavior:**
1. Resolve caller from JWT; reject `403 FORBIDDEN` if `role != CUSTOMER`.
2. Parse `{callerId}` out of the requested key's `customers/{callerId}/...` prefix; if it
   doesn't match the authenticated caller's id → `403 FORBIDDEN` (do not leak `404` vs.
   `403` distinctions that would let a caller probe for the existence of other users'
   images — always `403` for an ownership mismatch, regardless of whether the key exists).
3. If the key doesn't exist in local storage → `404 NOT_FOUND`.
4. Otherwise stream the file bytes back with the stored `Content-Type`.

**Status codes**: `200` success (binary body) · `401 UNAUTHORIZED` · `403 FORBIDDEN` ·
`404 NOT_FOUND`.

**Flagged scope note**: this endpoint's ownership check only ever authorizes the
*uploading customer* — no professional-viewing case is designed here, since no booking
exists yet in this milestone's scope. Milestone 3/4 (bookings) will need a professional
assigned to an order to view that order's issue images; that authorization rule doesn't
exist yet and is explicitly out of this milestone's scope to design.

---

## 3. Cross-cutting mechanism decisions

### 3.1 AI classification client — mock/real split behind an interface, mirroring `EmailSender`

**Decision, stated explicitly per the task brief's instruction not to let this be a silent
shortcut**: an `AiClassificationClient` interface lives in the `ai` package, e.g.:

```java
public interface AiClassificationClient {
    ClassificationResult classify(String description, List<ImageAttachment> images);
}
```

Two implementations, selected by a config flag — the same pattern M1 used for
`EmailSender`/`LoggingEmailSender` (`api-contract.md` §3.3):

- **`MockAiClassificationClient`** — the **default** for local dev and QA (no real OpenAI
  key needed). **Recommendation** (not a hard requirement, `pronto-coding` may simplify if
  time-constrained): a lightweight keyword/substring heuristic against each seeded
  category's `name_he`/`name_en`/`code` (e.g. description containing "מים"/"נזיל"/"ברז" →
  `plumbing`; "חשמל"/"קצר" → `electrical`; falls back to `general_handyman` if nothing
  matches), returning a canned but *description-sensitive* explanation string prefixed
  clearly as a mock (e.g. `"[מוק] סיווג לפי מילות מפתח: ..."`). This is deliberately more
  than a fixed-always-the-same-category stub — mirroring `LoggingEmailSender`'s spirit of
  "does something observable/useful for manual QA," not a pure no-op — but a simpler
  always-`general_handyman` stub is an acceptable fallback if this proves like more effort
  than it's worth. **Ignores images entirely** (no vision capability needed/expected in
  mock mode).
- **`OpenAiClassificationClient`** — real HTTP calls to OpenAI's chat/vision-capable
  completion endpoint, wired via `pronto.openai.api-key` (environment variable, **never
  committed** — identical pattern to `pronto.jwt.secret`/`JWT_SECRET` in
  `api-contract.md` §3.1). Prompt includes the fixed 8-category list (code + Hebrew name,
  from `categories`) and asks the model to pick one and explain briefly in Hebrew.

**Config flag** (parallel to M1's `pronto.mail.mode=log|smtp`):

| Property | Values | Notes |
|---|---|---|
| `pronto.ai.mode` | `mock` \| `openai` | Default `mock` for local/dev profiles. |
| `pronto.openai.api-key` | env var | Never committed. Required only when `mode=openai`. |
| `pronto.openai.model` | string | e.g. `gpt-4o-mini` (or whichever current vision-capable model is appropriate at implementation time — not pinned here, that's an implementation-time/cost decision). |
| `pronto.openai.timeout-ms` | integer | Recommended default `10000`. Bounds how long `/classify` can hang before returning `502 AI_SERVICE_ERROR`. |

**Image reachability — a real design decision, not just an implementation detail.** The
real `OpenAiClassificationClient` must **not** send OpenAI a public `imageUrl` to fetch
itself. Reasoning: in `pronto.storage.mode=local` (the expected dev/QA configuration this
milestone, since S3 credentials aren't available — §3.2), `imageUrl` points at
`http://localhost:8080/...`, which OpenAI's servers cannot reach at all — a URL-based
vision request would silently/always fail in exactly the configuration this milestone is
expected to be developed and QA'd in. **Decided**: the `ai` package resolves each
`imageKeys` entry to raw bytes via `storage.StorageClient.download(key)` (an internal,
same-process call — not an HTTP fetch of the public URL) and sends those bytes
**base64-encoded inline** in the OpenAI request, exactly as OpenAI's vision API supports as
an alternative to URL-based image inputs. This works identically regardless of
`pronto.storage.mode`, and as a secondary benefit reduces the urgency of the still-open S3
bucket-privacy question in §4 (OpenAI never touches the object's URL directly).

### 3.2 Storage client — mock/real split, local-disk fake warranted (decided, not silently picked)

**Decision, stated explicitly per the task brief's instruction**: a local-disk fake storage
implementation **is warranted** for this milestone, parallel to `LoggingEmailSender`.
Reasoning: AWS S3 credentials aren't available yet (task brief), and without a working
storage implementation, the entire Milestone 2 acceptance criterion ("a customer can
describe an issue, optionally attach images...") is unbuildable/untestable end-to-end. The
same "build against an interface, defer the real integration" pattern M1 used for email
applies directly here.

**Local disk over in-memory** — considered and rejected in-memory in favor of disk-backed
storage:
- Images need to survive across requests within a dev/QA session in a way that's
  inspectable (a QA tester or developer can literally open the saved file to verify an
  upload worked) — an in-memory `Map<String, byte[]>` provides neither persistence across
  app restarts nor easy manual inspection.
- Local disk more closely mirrors S3's actual semantics (durable object storage keyed by a
  string path) than an in-memory map does, so the fake behaves more like the real thing
  it's standing in for.
- Trivial to implement with `java.nio.file` — no meaningful extra effort over an in-memory
  map to justify picking the weaker option.

```java
public interface StorageClient {
    StoredObject upload(String key, byte[] content, String contentType);
    byte[] download(String key);
    boolean exists(String key);
}
```

Two implementations:
- **`LocalDiskStorageClient`** (default, `pronto.storage.mode=local`) — writes/reads under
  a configured base directory (`pronto.storage.local.base-dir`, e.g. `./data/uploads`),
  preserving the `key` as a relative file path. `imageUrl` returned to callers points at
  `GET /api/storage/images/{key}` (§2.4).
- **`S3StorageClient`** (`pronto.storage.mode=s3`) — real AWS SDK v2 `S3Client`, uploading
  to `pronto.storage.bucket` under the given key. `imageUrl` returned is the resulting S3
  object URL (exact form — virtual-hosted-style `https://{bucket}.s3.{region}.amazonaws.com/{key}`
  — depends on bucket/region config at implementation time).

**Config flags** (parallel to M1's `pronto.mail.mode`):

| Property | Values | Notes |
|---|---|---|
| `pronto.storage.mode` | `local` \| `s3` | Default `local` for dev/QA profiles. |
| `pronto.storage.local.base-dir` | path | Only used in `local` mode. |
| `pronto.storage.bucket` | string | Only used in `s3` mode. |
| `pronto.storage.region` | string | Only used in `s3` mode, e.g. `eu-central-1` (placeholder — actual region TBD at deploy time). |
| AWS credentials | env vars (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, or the AWS SDK's default credential provider chain) | **Never committed**, same treatment as `JWT_SECRET`/`pronto.openai.api-key`. Not yet available this milestone — `S3StorageClient` can be written and unit-tested against a mocked `S3Client`, but not live-integration-tested until credentials exist. |

### 3.3 Image-key ownership & the pre-issue-association problem

**The core mechanism problem, stated precisely**: `issue_images.issue_id` is `NOT NULL`
(`V7__create_issue_images.sql`), so an `issue_images` row **cannot** exist before an
`issues` row does — but images are captured on the New Issue screen, *before*
classification and confirmation, i.e. before an issue exists. Two shapes were considered
(per the brief):

- **(a) — chosen.** Images upload standalone via `POST /api/storage/images` (§2.3), landing
  in S3/local-disk with **no DB row at all** at upload time. `POST /api/issues` (§2.2) later
  takes a list of those keys and is the **only** point that ever writes an `issue_images`
  row, once an `issues.id` exists to satisfy the `NOT NULL` FK.
- **(b) — not chosen.** Some other staging mechanism, e.g. a new `pending_images` /
  `draft_issues` table recording "this customer uploaded this image, not yet attached to
  anything." Rejected because it requires a new Flyway migration, which is explicitly out
  of scope for `pronto-coding` this milestone — and (a) fully satisfies the requirement
  without one, since `V6`/`V7` already support it as-is.

**Ownership without a DB row — the resulting gap and its mitigation.** Because (a) means no
database record exists linking "this storage key" to "this uploading user" until the issue
is created, some other mechanism has to prevent customer A from passing customer B's
`imageKey` into `/classify` or `POST /api/issues`. **Decided mitigation**: the object key
itself embeds the uploading user's id in its path
(`customers/{callerId}/issues/temp/{uuid}.{ext}`, §2.3 step 3), and every endpoint that
accepts an `imageKeys` array (§2.1, §2.2) — plus the retrieval endpoint (§2.4) — parses that
prefix and rejects (`400 IMAGE_KEY_INVALID` for classify/create, `403 FORBIDDEN` for
retrieval) if it doesn't match the caller's own id. This is a **lightweight, in-band
mitigation, not a complete access-control system** — its residual limitations are listed
explicitly in §4, not silently accepted as a non-issue.

### 3.4 Upload → classify → confirm/create — end-to-end flow summary

For clarity, the full sequence this contract implements (no single endpoint captures the
whole thing):

1. Customer attaches 0–6 photos on the New Issue screen → each triggers `POST
   /api/storage/images` (§2.3) → frontend collects the returned `imageKey`s.
2. Customer submits their description (+ collected `imageKey`s) → `POST /api/issues/classify`
   (§2.1) → **no DB write** → AI Review screen shows `suggestedCategoryId` + `explanation`.
3. Customer confirms or overrides the category (frontend just picks whichever `categoryId`
   it ends up submitting) → `POST /api/issues` (§2.2) → **first DB write**, persists
   `issues` + `issue_images` rows in one transaction.

Step 2 can be repeated any number of times (e.g. customer edits the description and
re-classifies) with zero side effects before step 3 actually commits anything.

---

## 4. Open items / risks (flagged, not silently resolved)

- **AI-suggested category is not persisted anywhere (§2.2's main decision).** Flagged for
  explicit user sign-off, not treated as settled. If AI-accuracy/override-rate tracking is
  wanted, say so before this milestone's migration boundary closes — retrofitting a column
  or log table after `issues` rows already exist without one is more work than adding it
  now.
- **Image-key ownership is a path-prefix convention, not a real access-control record
  (§3.3).** Two residual gaps, both accepted as MVP limitations rather than solved this
  milestone:
  - **No expiry/cleanup for orphaned uploads.** A customer who uploads photos and then
    abandons the New Issue flow (closes the tab, never calls `POST /api/issues`) leaves
    those objects in storage forever — no DB row ever existed to track or clean them up,
    and no TTL/lifecycle job is designed here. A future scheduled cleanup job (same
    category of mechanism as the `orders` PENDING-timeout sweep, `data-model.md` §3 item 8)
    could sweep `customers/*/issues/temp/*` objects older than some threshold with no
    corresponding `issue_images` row — **not built this milestone**, flagging so it isn't
    forgotten.
  - **No prevention of key reuse across two different issues.** Nothing stops a customer
    from passing the same `imageKey` into two separate `POST /api/issues` calls, resulting
    in two `issue_images` rows (different issues) both pointing at the same underlying
    object. Judged low-risk/low-impact (a customer attaching the same photo to two issues
    isn't a security or data-integrity problem, just a minor redundancy) — not treated as a
    defect, just noted for completeness.
- **S3 image URL privacy / bucket access policy — resolved (updated, Milestone 7,
  2026-08-14).** At the time this doc was originally written, this was flagged as
  genuinely undecided. It has since been decided and built: the actual, already-implemented
  `backend/src/main/java/com/pronto/storage/client/S3StorageClient.java` Javadoc states
  that the bucket blocks all public access (default SSE-S3 encryption, no public-read
  policy), so `resolveUrl` deliberately does **not** return a raw S3 URL (which would
  always `403`). Instead, exactly like `LocalDiskStorageClient`, it points at this
  backend's own `GET /api/storage/images/**` retrieval endpoint (§2.4), built from the
  shared `pronto.storage.public-base-url` config property — the controller/service layer
  downloads the bytes from S3 server-side and streams them back to the caller. In other
  words, **every image fetch is backend-proxied in both `local` and `s3` storage modes**,
  never a direct-to-S3 redirect or a pre-signed URL — this is a deliberate, already-built
  decision, not a placeholder pending one. This doc's own §3.1 already anticipated this
  outcome ("reduces the urgency of the still-open S3 bucket-privacy question... OpenAI
  never touches the object's URL directly") without confirming it was actually resolved
  this way — it now is, per `S3StorageClient.java`'s Javadoc, cross-referenced here so this
  section stops describing a closed question as open. (Flagged as a doc-drift finding in
  `docs/architecture/hardening-plan.md` §5.3, corrected here per that finding.)
- **No rate limiting on `/classify`.** Since it's stateless/cheap-to-call-repeatedly by
  design (§3.4), nothing currently stops a customer from spamming it, which is a real
  OpenAI-cost exposure once `pronto.ai.mode=openai` is live (mock mode has no such cost).
  Not designed this milestone — flag as a candidate for Milestone 7's hardening pass if AI
  API costs become a concern.
- **AI category-mapping fallback (§2.1 step 5) is a recommendation, not confirmed.** If the
  real OpenAI response doesn't cleanly map to one of the 8 seeded `categories.code` values
  (a genuinely possible failure mode with any LLM classification), the proposed fallback is
  "default to `general_handyman` with `confidence = null`, logged at WARN" — reasonable,
  but not something any source document specifies, flagged the same way M1 flagged its
  password-length default: a judgment call, easy to change, not battle-tested.
- **No `GET /api/issues/{id}` this milestone (§2.2's "out of scope" note).** Restated here
  because it's a real forward dependency: Milestone 3/4's booking flows will need to
  resolve an issue by id, and that endpoint doesn't exist yet — next milestone's planning
  pass needs to account for it, it isn't silently assumed to already exist.
  **Resolved, Milestone 3 (2026-08-13)** — see the note under §2.2 above and
  `docs/architecture/api-contract-bookings.md` §2.1 for the full spec.
- **Storage object "permanence" after issue confirmation (§2.2 step 6).** Uploaded objects
  keep their original `.../temp/...`-style key forever; nothing "promotes" them to a
  permanent, issue-scoped path (e.g. `issues/{issueId}/...`) on confirm. Functionally
  harmless (the DB row's `image_url` is authoritative regardless of the key's naming), but
  worth a note in case a future S3 lifecycle policy or cost-management pass assumes
  `.../temp/...` objects are safe to expire — they aren't, once referenced by a persisted
  `issue_images` row. Not solved here — flagging so it isn't mistaken for an oversight.
