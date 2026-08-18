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

**Frontend Milestone 3 update (2026-08-16)**: `ReviewStep`'s `onConfirmed` now passes the
created `IssueResponse` up to `NewIssuePage`, which threads `{ issueId, urgencyType }` into
`IssueSuccessStep`. A `STANDARD` issue's success screen now offers a real "בחירת בעל
מקצוע" CTA into `/issues/${issueId}/booking` (`features/booking`, no longer a stub) in
addition to "חזרה לדף הבית".

**Frontend Milestone 4 update (2026-08-17)**: An `SOS` issue's success screen now also
offers a real CTA — "חיפוש בעל מקצוע זמין" into `/issues/${issueId}/sos-booking`
(`features/booking`'s new SOS flow, no longer a stub), alongside "חזרה לדף הבית", same
pattern as the `STANDARD` branch. `DescribeIssueStep`'s existing Standard/SOS urgency
selector was not touched by this pass.

Not built here, by design: no `GET /api/issues/{id}` read-back screen within this feature
folder (that endpoint is consumed by `features/booking`/`features/dashboard` instead, per
`docs/architecture/api-contract-bookings.md` §2.1).

**MS3/MS4 product-corrections pass (2026-08-17) — booking-draft persistence.**
`NewIssuePage` is the **only** component in this feature that touches `useBookingDraft()`
(`shared/hooks`, see that package's README) — its three child step components
(`DescribeIssueStep`/`ClarifyQuestionsStep`/`ReviewStep`) stay draft-unaware, unchanged in
their own prop contracts. On mount, `NewIssuePage` snapshots the current draft once (via
`useRef`, deliberately not re-derived on every render, so its own later `updateDraft` calls
during a live session don't re-trigger resume logic) and, if it belongs to an in-progress
*issue-creation* stage (`ISSUE_DESCRIBE`/`ISSUE_CLARIFY`/`ISSUE_REVIEW`), hydrates local
`description`/`photos`/`urgencyType` from it. For `ISSUE_CLARIFY`/`ISSUE_REVIEW`, the AI's
raw response is **not** persisted in the draft (kept small, avoids a stale cached AI
response) — it's cheaply re-derived by re-calling `classifyIssue` with the persisted
`description`/`photos`/`clarificationAnswers` on mount, feeding the result into the existing
`handleClassified` step-transition logic (no new branching). `handleClassified`/`handleBack`
write through to `updateDraft(...)` on every step transition (forward and backward).
**Issue creation itself is explicitly not a clear-trigger**: `handleConfirmed` moves the
draft **forward** to `stage: 'ADDRESS_SELECTION'` (with the new `issueId`/`categoryId`)
instead of calling `clearDraft()` — the draft is only ever cleared by
`features/booking`'s two flow pages, on order-creation success. If a customer starts a
*fresh* issue (via "יש לי תקלה") while a draft already past issue creation exists (has an
`issueId` — i.e. a booking is still mid-flow for a different issue), a dismissible warning
banner ("יש לך בקשה פעילה בתהליך הזמנה — התחלת תקלה חדשה תבטל אותה") is shown rather than
silently overwriting that in-progress booking draft the next time this page writes through.
