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

**Frontend Milestone 3 (visual/UX redesign, 2026-08-19) — AI-analysis loading state,
step-to-step motion, diagnosis-card/success-screen redesign.** Layered onto the working logic
above without changing any of it — see `docs/architecture/frontend-ms3-issue-ai-design.md` for
the full design record.

- **New: `AiAnalyzingOverlay.tsx`/`.module.css`.** Branded "Pronto בודק את התקלה..." loading
  state (`Mascot state="thinking" size="lg" loop` + a status line in a
  `role="status" aria-live="polite"` region + a CSS-only 3-dot pulse — no fake
  percentage/progress indicator), replacing the bare `Button` spinner both `classifyIssue`
  call sites previously showed alone. **Two trigger paths, one render site**: `DescribeIssueStep.
  handleSubmit` and `ClarifyQuestionsStep.handleContinue` each gained an `onAnalyzingChange`
  prop, called `true` immediately before their existing `classifyIssue(...)` call and `false`
  in the existing `finally` (no other line moved) — `NewIssuePage` is the single place that
  actually renders `<AiAnalyzingOverlay variant="overlay" show={isAnalyzing} />`, absolutely
  positioned over a `position: relative` step-viewport wrapper (`aria-hidden={isAnalyzing}`
  while shown, `pointer-events: auto` on the overlay so the covered form can't be interacted
  with mid-request — a deliberate, minor, positive tightening over the prior behavior, not a
  regression: today's editable-but-no-effect fields become blocked instead of silently
  no-op'ing). Deliberately **not** a `Step`-union member — see the doc comment at the top of
  `NewIssuePage.tsx` / design doc §2.2 for why (unmounting `ClarifyQuestionsStep` while
  `'analyzing'` showed would lose its local, non-lifted `answers` state on a failed request).
- **`isResuming` unified with the same overlay.** The old plain "טוענים את הבקשה שלכם…"
  paragraph is gone; `isResuming` (only ever `true` for `ISSUE_CLARIFY`/`ISSUE_REVIEW` resume,
  which re-calls `classifyIssue` the same way) now renders
  `<AiAnalyzingOverlay variant="inline" show={isResuming} />` — always mounted (not
  conditionally rendered) so its own internal `AnimatePresence` can play an exit transition
  instead of disappearing instantly. The `ISSUE_DESCRIBE`-only resume case (photo-only
  re-resolution, no `classifyIssue` call) is untouched — `DescribeIssueStep` still renders
  immediately with `PhotoUploader`'s own per-item spinner placeholders, since that path never
  sets `isResuming` to `true`.
- **Step-to-step motion.** `NewIssuePage`'s `describe`/`clarify`/`review`/`success`
  conditional chain is now wrapped in `AnimatePresence mode="wait"` keyed on `step.name`, using
  `shared/motion/variants.ts`'s `stepTransition` (its first real consumer — the RTL-aware
  slide-in/out `variants.ts` already documented this exact use case for). `direction`
  (`1`/`-1`) is local `NewIssuePage` state, copied from `RegistrationWizardShell.tsx`'s
  existing reduced-motion-neutralization pattern rather than extracted into `shared/motion`
  this milestone.
- **`ReviewStep` diagnosis card.** Eyebrow "מצאנו את בעל המקצוע המתאים" → "האבחון שלנו"
  (factually premature — no professional is matched yet at this point); headline is now
  dynamic, "נראה שמדובר בתקלה ב־{קטגוריה}"; a new static reassurance line follows ("כך נמצא
  לך את בעל המקצוע הכי מתאים."). If `urgencyType === 'SOS'`, an inline "דחוף — SOS" badge
  renders next to the headline using `--color-sos`/`--color-sos-bg` directly (not `Badge` —
  its `BadgeTone` enum has no `'sos'` value, out of this milestone's scope to add).
  **`classification.explanation`/`.confidence` are still never rendered anywhere in this
  component** — a deliberate, backend-verified continuation of the prior stricter behavior
  (design doc §5.3): in real (OpenAI) mode `explanation` is English-only prose, contradicting
  Pronto's Hebrew-only v1.0 scope; in mock mode it literally names the internal
  keyword-matching mechanism (`OpenAiClassificationClient.java`/`MockAiClassificationClient.
  java`, both directly inspected). `handleConfirm`/`createIssue`'s payload, `categoryId`/
  `isChangingCategory` state, and `onConfirmed` are unchanged.
- **`IssueSuccessStep`.** The `✓` circle is now `<Mascot state="success" size="xl" label="הבקשה
  נשלחה בהצלחה" />` (meaningful, not decorative); headline "הבקשה נשלחה" → "הבנתי. עכשיו נמצא
  לך מישהו." Body copy (both `isStandard` branches), CTA labels, and both navigation
  destinations (`/issues/${issueId}/booking` / `/issues/${issueId}/sos-booking` / "חזרה לדף
  הבית") are byte-for-byte unchanged — the CTA stays a required manual click, no
  auto-navigation (design doc §6.2).
- **`DescribeIssueStep`/`ClarifyQuestionsStep` visual redesign** (composer wrapped in a
  `Card` with a small static `Mascot state="idle" size="sm"`; new example-prompt chips shown
  only while `description` is empty; urgency control redesigned into two icon+title+subcopy
  cards; `ClarifyQuestionsStep`'s option buttons restyled larger with a leading `Circle`/
  `CheckCircle2` selection icon and proper `role="radiogroup"`/`role="radio"`/`aria-checked`,
  plus per-question progressive reveal when `questions.length > 1`) — none of these touch
  `handleSubmit`/`handleContinue`'s validation, `classifyIssue` payload, `answers`/
  `allAnswered` state, or the `onClassified`/error-banner logic.
