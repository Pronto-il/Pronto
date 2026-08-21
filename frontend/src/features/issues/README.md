# features/issues

## Purpose
The customer-facing issue reporting flow: describing a problem and getting an
AI-suggested service category.

## Responsibilities
- Home / New Issue screen — text description entry plus optional image attachments.
- AI Review screen — shows the AI-suggested category as a simple confirmation, and lets the
  customer confirm or override it against the seeded category list (seven categories since
  `V31` retired Carpentry).
  **Issue-classification redesign (2026-08-20):** `explanation`/`confidence` are no longer
  merely never-rendered — they are gone from the response entirely, along with candidates and
  the ambiguity reason. Those are backend diagnostics (persisted and logged); the convention
  "must never be shown to the customer" is now a structural guarantee rather than a rule this
  README has to keep restating.
- Hands off into `features/booking` once a category is confirmed (customer picks
  Standard or SOS).

## Status
Implemented, 2026-08-16: `NewIssuePage` (routed at `/issues/new`, customer-only via
`RequireAuth role="CUSTOMER"`) runs a three-step flow — `DescribeIssueStep` (description +
`PhotoUploader` photos + Standard/SOS urgency, calls `POST /api/issues/classify`),
`ClarifyQuestionsStep` (reached on `status: "QUESTIONS"`, rendering the backend's `questions`
array dynamically — no frontend-invented questions; **as of the issue-classification redesign
this step can repeat**: the backend asks one highest-value question at a time, re-classifies
against the accumulated answers, and may ask one more, up to a server-side budget. The step
carries the growing conversation forward and neither counts nor caps rounds), and `ReviewStep` (a plain
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
  **`classification.explanation`/`.confidence` no longer exist** — the issue-classification
  redesign removed them from `ClassifyIssueResponse` rather than continuing to ship fields
  this component was documented as never allowed to render. `categoryId`/`isChangingCategory`
  state and `onConfirmed` are unchanged; `handleConfirm`'s `createIssue` payload now also
  carries `clarificationAnswers`, so the conversation is persisted with the issue instead of
  being discarded at that boundary.
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

## Profession roulette — replaces the old success step (2026-08-20)

The issue flow used to end on `IssueSuccessStep` ("הבנתי. עכשיו נמצא לך מישהו" + a
"בחירת בעל מקצוע" button + "חזרה לדף הבית"). That screen asked the customer to click again for
results they had already asked for, so **it has been deleted**, not hidden: the flow is now

```text
issue confirmed -> /issues/:issueId/matching -> professionals list
                   (1. service address, 2. roulette)
```

**Address before the wheel (2026-08-20 adjustment).** `/issues/:issueId/matching` is a
two-phase screen: the customer picks the service address first, and only then does the wheel
start. The wheel is a promise about *results*, and results depend on where the professional
has to travel — the listing endpoint derives service-area relevance, distance and ETA from the
service address, so animating a match before knowing it would mean preloading against the
wrong location or not preloading at all. The address step is `features/booking`'s own
`AddressSelectionStep`, imported rather than reimplemented, so both entry points into the
booking flow ask in exactly one way — including its guarantee that choosing a one-off address
never writes back to the profile's saved default (there is no endpoint that could).

Both phases persist into the same booking draft the booking flow reads, so a refresh mid-spin
resumes into the animation rather than re-asking, and the flow that follows never asks again —
its own address step stays one explicit "back" away for a customer who wants to change it.

- **`ProfessionMatchPage.tsx`/`.module.css`** — the transition screen, at its own route rather
  than as a step inside `NewIssuePage`, so a refresh or a shared link re-derives the category
  from the issue instead of losing it with component state. `NewIssuePage.handleConfirmed`
  navigates here with `replace: true` (the back button must not return to a review step for an
  issue that already exists), passing `categoryId`/`urgencyType` in router state so the common
  path costs no extra request; on a refresh or direct visit it falls back to `getIssue`.
- **`ProfessionRoulette.tsx`/`.module.css`** — the wheel. **Deterministic**: the landing angle is
  `360 * SPINS - targetIndex * segmentAngle`, computed from the issue's own category's fixed
  position, so the same issue always lands identically. Nothing random is drawn anywhere.
- **Illustrations** come from `shared/components/ProfessionIllustration.tsx`, the single
  category-id → drawing map. (Category 6, `carpentry`, had no illustration; V31 retired that
  category into Handyman, so every surviving category now has one.) Formerly missing in
  `assets/rollete-animation-images/` — seven drawings were supplied for eight categories — so it
  renders the shared `Mascot` fallback and logs a one-time dev warning naming the gap, rather
  than borrowing another profession's drawing.
- **Preloading**: while the wheel turns, `prefetchProfessionalListing` fires the exact request
  *for the address the customer just chose* — default or one-off —
  the booking flow is about to make; that flow adopts the in-flight promise instead of issuing
  its own (verified live: one listing request across both screens). If the list is ready when
  the wheel stops, the success state holds for `SUCCESS_HOLD_MS` and the screen leaves; if not,
  the landed profession stays visible under a subtle loading line until it is, capped by
  `MAX_PRELOAD_WAIT_MS` so it can never hang.
- **Landing on the list, not the address step**: the hand-off writes the existing booking draft
  forward to `PROFESSIONAL_SELECTION` with the customer's saved default address, which the
  booking flow's own resume-hydration already knows how to open on. A customer with no saved
  address lands on the address step as before — the listing endpoint requires an address, so
  there is nothing to skip.
- **Reduced motion / degenerate wheel**: no turns at all — a 320ms move straight to the
  answer. Same for a wheel with fewer than two faces.
- **StrictMode**: navigation is ref-guarded, so a double-invoked effect or a remount cannot
  navigate twice.

### UI/timing corrections (2026-08-20)

- **Standalone figures, no cards.** The faces were rounded surfaces with a border and a tinted
  active state. The illustrations now sit directly on the page around the ring; depth comes
  from opacity and scale alone, and the landed profession is lifted by a drop-shadow that
  follows the figure's own silhouette rather than a rectangle behind it.
- **Responsive sizing, not one size.** `--wheel-size` is `min(84vw, 360px)` on mobile, `500px`
  from 768px, and `620px` from 1200px — bounded by `.focused-page`'s 680px content column, so
  the wheel owns the screen on a desktop instead of floating in it.
- **Longer result hold.** `SUCCESS_HOLD_MS` 900ms → 1900ms. At the old length the result
  flashed past before it could be read, which defeats the point of the animation. Still no
  extra click; the screen leaves on its own.
