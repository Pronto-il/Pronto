# features/issues

## Purpose
The customer-facing issue reporting flow: describing a problem and getting an
AI-suggested service category.

## Responsibilities
- Home / New Issue screen — text description entry plus optional image attachments.
- AI Review screen — shows the AI-suggested category as a simple confirmation (never the
  raw AI `explanation`/`confidence`, which are internal-only), lets the customer confirm or
  override it against the fixed 8-category list.
- Hands off into `features/booking` once a category is confirmed (customer picks
  Standard or SOS).

## Status
Implemented, 2026-08-16: `NewIssuePage` (routed at `/issues/new`, customer-only via
`RequireAuth role="CUSTOMER"`) runs a three-step flow — `DescribeIssueStep` (description +
`PhotoUploader` photos + Standard/SOS urgency, calls `POST /api/issues/classify`),
`ClarifyQuestionsStep` (only reached on `status: "QUESTIONS"`, rendering the backend's
`questions` array dynamically — no frontend-invented questions), and `ReviewStep` (a plain
category confirmation card built from `suggestedCategoryId`/`suggestedCategoryCode` only,
lets the customer confirm/override the category, calls `POST /api/issues` — the flow's only
DB write) ending in `IssueSuccessStep`. `HomePage` got its Milestone 2 entry point: a single
"יש לי תקלה" CTA per DESIGN_SYSTEM.md §35-36.

Two new shared primitives landed alongside this (`shared/components`): `Textarea` (generic,
mirrors `Input`) and `PhotoUploader` (Pronto-specific, named in DESIGN_SYSTEM.md §85 —
uploads each photo immediately via `POST /api/storage/images` on selection, unlike
`ImageUploadField` which just holds a `File` for a later multipart submit).

Not built here, by design: no route into `features/booking` after a successful create
(still a stub — Milestone 3+ frontend); no `GET /api/issues/{id}` read-back screen (not
needed by this flow, and the endpoint itself lives in the bookings contract, not this
milestone's).
