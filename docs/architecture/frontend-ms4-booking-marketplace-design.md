# Frontend MS4 — Booking & Professional Marketplace: Design/Audit

Status: **design/audit pass only, nothing built.** Written by `pronto-planning` on branch
`frontend/MS4-booking-marketplace` (checked out from `7d96b3f`, tip of `main` — MS1 visual
foundation, MS2 Home/Auth, MS3 Issue/AI, and MS6 Professional Command Center already
committed on `main`). Verified directly against real code in
`frontend/src/features/booking/**`, `frontend/src/features/professionals/**`,
`frontend/src/shared/components/**`, `frontend/src/shared/motion/**`, `frontend/src/app/**`
— not copied from prose or from the two features' own READMEs (which were read for
historical context only, per the task brief, and cross-checked against the real files).
Cross-checked against `frontend/Pronto — DESIGN_SYSTEM.md`, `frontend/FRONTEND_GUIDELINES.md`,
`frontend/FRONTEND_AGENT.md`, `docs/architecture/ms3-ms4-corrections-design.md`,
`docs/architecture/api-contract-bookings.md`, `docs/architecture/api-contract-professionals-
reviews.md`, `docs/architecture/active-booking-floating-indicator.md`, and
`docs/architecture/frontend-ms6-professional-command-center-design.md` (for format/rigor
precedent).

**No detailed MS4 brief exists in this repo** (per the task's own framing) — this doc's scope
is derived from: (a) direct inspection of the real code under
`features/booking/**`/`features/professionals/**`, (b) DESIGN_SYSTEM.md's own explicit
sections on booking/provider UI (§29-34, §42, §46-49), and (c) the confirmed core product
flows from this agent's own standing instructions (issue → AI category → browse
professionals with per-professional price offers → Standard booking [professional + time
slot] → SOS booking [available-now professionals] → accept/reject → confirmation/tracking).
No scope beyond that was invented.

## 0. Headline finding — relay this to `pronto-lead`/the user verbatim

**MS4 is the same story as MS6: mostly already built, and built well.** The functional
surface area "Booking & Professional Marketplace" should reasonably cover — professional
listing with per-professional price offers, Standard booking (address → professional → time
slot → confirm), SOS booking (address → available-now professionals → confirm with
disclosed surcharge), professional profile + reviews + favorites, order tracking — is
**fully implemented, token-compliant, and already matches DESIGN_SYSTEM.md's own
provider-card/booking-summary/availability specs (§29-33, §46-49) closely enough that no
component needs a rebuild.** Nothing here predates MS1 the way some of MS6's dispatch
assumed; this whole module was actually built (Frontend Milestones 3/4/8, old numbering)
*after* the MS1 design-token system landed, and it shows — CSS modules use `var(--space-*)`/
`var(--color-*)`/`var(--radius-*)` almost everywhere (a handful of cosmetically-inert
`#ffffff` literals aside, identical in effect to `var(--color-surface)`).

**What's actually missing is motion and a few loading/reuse polish items — not product
functionality.** Specifically: this entire module has **zero `framer-motion` usage**
(confirmed via grep — `stepTransition`/`pageTransition`/`listStagger`/`mascotSlideIn`/
`successPop` are used across `features/auth` and `features/issues` but never once in
`features/booking` or `features/professionals`), even though `BookingFlowPage`/
`SosBookingFlowPage` are step machines structurally identical to
`features/issues/NewIssuePage.tsx` (which *does* use the full motion system), and even
though `shared/motion/variants.ts`'s own doc comment names "list entrance stagger... beyond
the simple CSS case" as a `listStagger` use case that this module's professional list is the
obvious candidate for. There's also a real, purpose-built `Mascot` state
(`state="searching"`, doc comment: "Pronto is looking for someone") that exists specifically
for the "מחפשים בעלי מקצוע..." moment DESIGN_SYSTEM §41 describes, and it is currently
rendered as bare `<p>` text with no mascot in both booking flows. None of this is a
functional gap — it's a consistency gap against the sibling MS3 flow that was built in the
same session and already solved these exact problems.

## 1. What genuinely needs no rebuild (confirm and move on)

Verified file-by-file, not assumed:

| File | Verified clean |
|---|---|
| `ProfessionalCard.tsx`/`.module.css` | Matches DESIGN_SYSTEM §29-33 layout/hierarchy exactly (photo/name/rating/ETA/price/single CTA). 100% `var(--space-*)`/`var(--color-*)`/`var(--radius-*)`, zero hardcoded hex. Reuses `Card`/`Button`. Sort-emphasis logic (§32) changes only styling, never structure, per FRONTEND_AGENT.md §12. Correctly omits a "Recommended" badge (§33) — no `recommended` field exists on the `ProfessionalCard` API DTO (`shared/api/bookings.ts`), so not fabricating it is correct per FRONTEND_AGENT.md §9/§10, not a gap. |
| `ProfessionalList.tsx`/`.module.css` | Sort-chip row correctly reuses shared `FilterChipGroup` (§34). Results-count heading matches §42. Token-compliant. (Two small polish items below — empty state, no stagger.) |
| `BookingSummary.tsx`/`.module.css` | Matches §48 exactly — one summary card, professional/service/date/time/address/price all visible at once, nothing hidden. Owns its own double-submission guard (`Button`'s `loading` prop). Token-compliant. |
| `SosBookingSummary.tsx`/`.module.css` | Matches §49's fee-disclosure requirement — explicit base-price + SOS-surcharge breakdown shown before confirmation, never hidden. Token-compliant. |
| `StartTimePicker.tsx`/`.module.css` | Matches §46-47 exactly — horizontal date chips + time-chip grid, selected/unselected states correct. Token-compliant (two `#ffffff` literals for selected-chip text, see §4 below — trivial). |
| `AddressSelectionStep.tsx` | Correct product logic (default-address read-only confirmation vs. one-off custom address, never mutates `users.default_*`). Functionally solid. (One reuse gap below — see §3.C1.) |
| `ProfessionalProfilePage.tsx`/`ProfessionalProfileDisplay.tsx`/`ReviewList.tsx` | Match §43-45 exactly. Sticky mobile CTA bar (§21) already correct. Trust indicators (favorite state, rating) only rendered when real (§44 — "never visually claim... if it does not exist"). Token-compliant. |
| `OrderTrackingPage.tsx`/`.module.css` | Full booking-summary content checklist (FRONTEND_AGENT.md §59) already satisfied: professional, service, date, time, address, price, additional context (issue photos, SOS tag), role-correct actions. Token-compliant. This screen straddles MS4/MS5 (see §5) — not re-scoped here beyond the loading-state nit in §3.B. |
| `router.tsx` wiring | Route gating (`RequireAuth role=...`) matches the product's role model; `/professionals/:id`, `/issues/:issueId/booking`, `/issues/:issueId/sos-booking`, `/orders`, `/orders/:orderId` all present and correctly reachable from `IssueSuccessStep`'s hand-off (MS3) and `ProfessionalCard`'s profile-link hand-off. No routing gap. |

## 2. Verification method

No browser-automation tool is available in this environment (consistent with every prior
frontend milestone's own README notes in this project). This pass is a **static/design
audit**, not a live click-through — findings below are based on direct source-file reading
(component + `.module.css` pairs), `grep`-level verification of claims (e.g. the
zero-framer-motion claim, the zero-hardcoded-hex claim), and cross-referencing DESIGN_SYSTEM.md
section numbers against the actual rendered structure in each component's JSX. This mirrors
the MS6 design doc's own stated method.

## 3. Real gaps found, with a concrete fix per gap

### A. Motion — the module's one substantial, well-evidenced gap

**A1. No step-transition animation in either booking flow.** `BookingFlowPage.tsx`/
`SosBookingFlowPage.tsx` swap `{step.name === 'x' && <div>...</div>}` blocks with a plain
conditional render — no `AnimatePresence`/`motion.div`/`stepTransition`. `NewIssuePage.tsx`
(same step-machine shape, same milestone family) already solved this exact problem with a
`stepViewport` wrapper + `AnimatePresence mode="wait"` + `stepTransition` + a `direction`
state variable (`1`/`-1`) + `useReducedMotion()` neutralization (lines 233-246, 285-333 of
that file).
- **Fix**: add a `direction` state to both flow pages (set `1` on every forward transition,
  `-1` in `handleBack`, mirroring `NewIssuePage`'s `setDirection` calls exactly), wrap each
  step's content in a `styles.stepViewport` + `AnimatePresence mode="wait" custom={direction}`
  + `motion.div` using `stepTransition`, with the same `useReducedMotion()`-neutralization
  `useMemo` block copied verbatim (that block is already duplicated, by design, across
  `NewIssuePage`/`RegistrationWizardShell`/`AiAnalyzingOverlay` per that file's own comment —
  a fourth copy is consistent with the established pattern, not a new violation).
- **Files**: `BookingFlowPage.tsx`, `BookingFlowPage.module.css` (add `.stepViewport`),
  `SosBookingFlowPage.tsx`, `SosBookingFlowPage.module.css` (same).

**A2. No list-entrance stagger for professional results.** `shared/motion/variants.ts`'s own
doc comment names this exact case ("List entrance stagger beyond the simple CSS case") as a
`listStagger` consumer; `ProfessionalList.tsx` renders `.list`'s children with no motion at
all.
- **Fix**: wrap `ProfessionalList`'s `.list` in a `motion.div` using `listStagger` as the
  container variant (`initial="initial" animate="animate"`), with each `ProfessionalCard`
  wrapped in a small item-level `motion.div` (a simple opacity/y fade, no new named variant
  needed — `pageTransition`'s existing shape is reusable here the same way
  `IssueSuccessStep.tsx` reuses it for its own `listStagger` children). Respect
  `variants.ts`'s own documented ~8-item stagger-cap guideline: stagger only the first 8
  cards, render the rest without per-item motion (a `index < 8` check is enough — no new
  abstraction needed). Gate on `useReducedMotion()` the same way `IssueSuccessStep.tsx`
  already does.
- **Files**: `ProfessionalList.tsx` only.

**A3. Success screens duplicate hand-rolled JSX/CSS instead of reusing the established
`Mascot` success pattern — and duplicate each other.** `BookingFlowPage.tsx` (lines
396-412) and `SosBookingFlowPage.tsx` (lines 285-301) both render a near-identical
hand-rolled `successWrapper`/`successCheck` (a plain `✓` glyph in a circle)/`successTitle`/
`successText`/`successActions` block — the exact same shape twice, and neither uses
`Mascot state="success"` + `listStagger`, the pattern `IssueSuccessStep.tsx` already
established for this exact kind of moment ("your submission to Pronto succeeded, here's
what's next") one step earlier in the same product flow.
- **Fix**: extract a new, small `BookingSuccessStep.tsx` component in `features/booking/`
  (co-located, not `shared/components` — mirrors this codebase's own repeated "co-locate
  until cross-feature reuse is needed" convention already used for `ReviewList.tsx`/
  `ProfessionalProfileDisplay.tsx`; this component has exactly 2 in-module consumers at
  creation time, both in `features/booking`), taking `professionalName`/`orderId`/a
  `variant: 'standard' | 'sos'` (or just plain title/body text props — simplest option, see
  open question in §4) and rendering `<Mascot state="success" size="xl" />` +
  `listStagger`-staggered heading/text/actions, mirroring `IssueSuccessStep.tsx`'s structure.
  This both closes the motion gap and removes the pre-existing JSX duplication between the
  two flow pages — a real, in-scope cleanup, not scope creep (DESIGN_SYSTEM §86, FRONTEND_AGENT
  §7).
- **Files**: new `BookingSuccessStep.tsx` + `.module.css` (adapted from
  `IssueSuccessStep.tsx`/`.module.css`, not copied verbatim — copy differs); `BookingFlowPage.tsx`/
  `SosBookingFlowPage.tsx` each replace their inline success block with
  `<BookingSuccessStep ... />`; both flow pages' `.module.css` lose their now-dead
  `.successWrapper`/`.successCheck`/`.successTitle`/`.successText`/`.successActions` rules.

**A4. The professional-search loading moment has no mascot.** Both flow pages render
`{isLoadingProfessionals && <p className={styles.transitionText}>מחפשים בעלי מקצוע זמינים
באזור שלך…</p>}` — plain text only. `Mascot`'s `state="searching"` exists specifically for
this ("Pronto is looking for someone", per `Mascot.tsx`'s own `STATE_POSE` doc comment,
using the distinct `runningScrewdriver` pose so it reads visually different from
`state="running"`/"Pronto is coming to you"). DESIGN_SYSTEM §41 explicitly asks for more than
a bare spinner/text at this transition.
- **Fix**: render `<Mascot state="searching" loop size="lg" />` alongside the existing
  transition text while `isLoadingProfessionals` is true, in both flow pages — same
  mascot-plus-copy pairing `AiAnalyzingOverlay.tsx` already established for the AI-thinking
  moment in `features/issues`.
- **Files**: `BookingFlowPage.tsx`, `SosBookingFlowPage.tsx` (their `.module.css` files need a
  small layout tweak to stack the mascot above/beside the existing `.transitionText`).

**A5. `PageHeader`'s `steps` progress-bar prop is never passed.** `PageHeader.tsx` has a
`steps?: { current: number; total: number }` prop that renders a real animated progress track
(§38) — `NewIssuePage.tsx` passes it (`steps={stepNumber !== undefined ? { current:
stepNumber, total: 3 } : undefined}`); `BookingFlowPage.tsx`/`SosBookingFlowPage.tsx` only
pass `description={STEP_LABELS[step.name]}` (the text "שלב 2 מתוך 4"), never `steps` — so the
visual progress bar DESIGN_SYSTEM §38 asks for ("a subtle indicator... or a simple progress
bar") is silently absent from this module even though the text label implies one exists.
- **Fix**: both files already have a `STEP_NUMBERS`-shaped mapping available for free (their
  `Step['name']` union is small and static) — add a `STEP_NUMBERS` const mirroring
  `NewIssuePage.tsx`'s and pass `steps={{ current: STEP_NUMBERS[step.name], total: 4 }}`
  (`total: 3` for SOS), omitted for the `'success'` step exactly as `NewIssuePage` already
  does.
- **Files**: `BookingFlowPage.tsx`, `SosBookingFlowPage.tsx` only (no `PageHeader.tsx`
  change needed — the prop already exists and works).

### B. Loading states — plain "טוען…" text where a skeleton is trivially available

`FRONTEND_AGENT.md` §24 is explicit: "Provider results should use ProviderCard skeletons...
rather than a giant centered spinner" — and the shared `Skeleton` component already exists
and is already used for exactly this purpose in `ProfessionalList.tsx`/`StartTimePicker.tsx`.
It is **not** applied consistently at the top level of every page in this module — several
screens fall back to a bare `<p>טוען…</p>`, which is not even a spinner, just static text,
for a layout that's entirely predictable:

- `MyOrdersPage.tsx` line 49 — order-row list, predictable card-row shape.
- `OrderTrackingPage.tsx` line 161 — the whole tracking card, predictable shape (note: this
  file *does* already use one hand-rolled inline skeleton span for the category sub-loading
  case, `.skeleton` in its own CSS module, background: `var(--color-surface-secondary)` with
  no shimmer animation — inconsistent with the shared `Skeleton` component's shimmer, and
  the top-level `isLoading` case right above it has no skeleton treatment at all).
- `ProfessionalProfilePage.tsx` line 144 — predictable identity/info/bio layout.
- `CompletionReviewPage.tsx` line 108 — predictable summary-card + star-row layout.

**Fix (all four)**: replace each bare `<p>טוען…</p>` with 1-3 `Skeleton variant="rect"`
placeholders sized (via `className`/inline `style`, per `Skeleton.tsx`'s own documented
sizing convention) to roughly match each page's real content — a single tall rect for
`MyOrdersPage`'s/`OrderTrackingPage`'s/`CompletionReviewPage`'s card, and a small avatar
(`variant="circle"`) + a couple of text-line skeletons for `ProfessionalProfilePage`'s
identity block. `OrderTrackingPage.tsx`'s existing inline `.skeleton` span (category-loading
sub-case) should also be swapped for the shared `Skeleton` component for shimmer consistency
— low-risk, purely visual, same behavior.

### C. Component reuse — two real, small "should have used the existing component" findings

**C1. `AddressSelectionStep.tsx` hand-rolls a two-option chip toggle instead of reusing
`FilterChipGroup`.** Its own `.chips`/`.chip`/`.chipActive` (in
`AddressSelectionStep.module.css`) duplicates the shared `FilterChip`/`FilterChipGroup`
component — which `ProfessionalList.tsx` already uses for its sort chips, and which has
correct `role="radiogroup"`/`role="radio"` accessibility semantics the hand-rolled version
lacks entirely (plain `<button>`s in a plain `<div>`, no group semantics for
screen-reader users).
- **Fix**: replace the `.chips` block with
  `<FilterChipGroup options={[{ value: 'DEFAULT', label: 'כתובת ברירת המחדל שלי' }, { value:
  'CUSTOM', label: 'כתובת אחרת לפעם הזו' }]} value={effectiveMode} onChange={handleModeChange}
  aria-label="מקור הכתובת" />`, delete the now-unused `.chips`/`.chip`/`.chipActive` CSS rules.
- **Files**: `AddressSelectionStep.tsx`, `AddressSelectionStep.module.css`.

**C2. `ProfessionalList.tsx`'s and `MyOrdersPage.tsx`'s empty states hand-roll their own
markup instead of the shared `EmptyState` component.** Both render their own
`.empty`/`.emptyTitle` div/paragraph pair; `shared/components/EmptyState.tsx` exists
specifically for this (DESIGN_SYSTEM §60, FRONTEND_GUIDELINES §25) and is already
structured to accept a title/description/action — `MyOrdersPage.tsx` even already has the
exact copy + CTA (`<Button onClick={() => navigate('/issues/new')}>יש לי תקלה</Button>`) that
would just move into `EmptyState`'s `action` prop unchanged.
- **Fix**: replace both hand-rolled empty blocks with `<EmptyState title="..."
  description="..." action={<Button ...>} />`, delete the now-unused
  `.empty`/`.emptyTitle`/`.emptyText` CSS rules in each `.module.css`.
- **Files**: `ProfessionalList.tsx`/`.module.css`, `MyOrdersPage.tsx`/`.module.css`.

### D. Token hygiene — trivial, optional, not prioritized

A handful of `#ffffff` literals, all cosmetically identical to the existing
`var(--color-surface)` token (same category the MS6 design doc explicitly called
"cosmetically-inert" and declined to prioritize):
`AddressSelectionStep.module.css` (`.chipActive`, moot if §3.C1 is done — `FilterChip`
already tokenizes this correctly), `StartTimePicker.module.css` (`.dateChipActive`,
`.timeChipSelected`), `ProfessionalProfilePage.module.css` (`.favoriteButton { background:
white; }`). Optional: swap each for `var(--color-surface)` while touching these files for
other reasons; not worth a dedicated pass on its own.

### E. Content/UX gap check — none found beyond the polish items above

Walking the confirmed core flow end to end against the real code: issue → AI category
(MS3, unaffected) → professional list with per-professional price offers
(`ProfessionalCard`/`ProfessionalList`, done) → Standard booking, address → professional →
time slot → confirm (`BookingFlowPage`/`StartTimePicker`/`BookingSummary`, done) → SOS
booking, address → available-now professionals → confirm with disclosed surcharge
(`SosBookingFlowPage`/`SosBookingSummary`, done, surcharge shown before confirmation per
§49) → professional accept/reject (built in `features/dashboard`, correctly out of this
module's scope) → confirmation/tracking (`OrderTrackingPage`, done, straddles MS5, see §5) →
professional profile detail + reviews + favorites (done, MS8). **No missing screen, no
missing API integration, no faked/mocked data found anywhere in this module.** The gaps are
entirely consistency/polish (motion, loading skeletons, two component-reuse misses), not
missing product surface area — the same shape of finding MS6 produced for the professional
dashboard.

## 4. Open questions for `pronto-lead`/the user (resolved — see below)

**Status update (2026-08-20, `pronto-documentation`): both items below were decided during
implementation and are now built. Q2's "identical Standard/SOS copy is acceptable" outcome
was subsequently revisited by the user and changed — see §4b F2. This section is kept as a historical record of the
questions as originally posed; each item's resolution is recorded inline. Treat this
milestone's design as closed, not pending.**

**Q1 — Should `MyOrdersPage` prioritize active/upcoming orders over historical ones?**
`FRONTEND_GUIDELINES.md` §22 ("Customer Dashboard") states: "The dashboard should prioritize
active and upcoming requests. Historical requests should be secondary." The real
`MyOrdersPage.tsx` calls `getMyOrders()` with no `status` filter and renders whatever order
the backend returns, with no client-side grouping or re-sorting by status — a `COMPLETED`
order from last month and a `PENDING` order from five minutes ago render in the same flat
list, in API order. This is a genuine information-architecture question, not a styling
choice: should this page (a) group into "Active"/"History" sections, (b) sort
active-status orders first while keeping a single flat list, or (c) stay as-is (arguably
fine today given the floating `ActiveOrderIndicator` already surfaces the single
highest-priority active order product-wide, so `MyOrdersPage` may be intentionally just a
full history)? Not deciding this unilaterally — it changes what the page *is*, not just how
it looks, and there's no existing precedent in this codebase for how such grouping should be
presented.

**Resolved: option (a), by explicit user decision.** `MyOrdersPage.tsx` now splits into two
client-side sections via a new `bucketOrders()` pure function (no new endpoint, still one
unfiltered `getMyOrders()` call): "פעילות וקרובות" (Active/Upcoming — everything not in a
terminal status, sorted soonest-first) and "היסטוריה" (History —
`COMPLETED`/`CANCELLED`/`REJECTED`/`EXPIRED`, sorted most-recently-updated first), each with
its own `EmptyState` when empty. This mirrors `features/dashboard/MyJobsPage.tsx`'s MS6
sectioning pattern, adapted to 2 sections instead of 3 (no separate "Today" bucket — a
customer's own order volume doesn't warrant the professional side's finer split). See
`features/booking/README.md`'s MS4 section for the full implementation record.

**Q2 — `BookingSuccessStep`'s prop shape (§3.A3).** Minor implementation-level question,
flagged rather than defaulted since it affects a new component's public API: should the new
shared success component take a `variant: 'standard' | 'sos'` prop (keeping all copy
centralized in the component) or plain `title`/`body` string props (keeping copy in each
caller, component stays copy-agnostic)? Both are reasonable; recommend the latter (matches
`EmptyState.tsx`'s own existing "component owns layout, caller owns copy" convention already
used elsewhere in `shared/components`) but flagging since it's the one real API design choice
in this doc's fixes.

**Resolved: the recommended option (plain `title`/`body` string props) was built.**
`BookingSuccessStep.tsx` takes `title: string`/`body: string`/`orderId: number` — no `variant`
enum. Both `BookingFlowPage.tsx` and `SosBookingFlowPage.tsx` currently pass the same copy
("ההזמנה נשלחה" / "הבקשה נשלחה ל{professionalName}. ממתינים לאישור בעל המקצוע."), so the two
success screens are visually and textually identical today — acceptable per this resolved
decision (the component is deliberately copy-agnostic; SOS-specific tone, if ever wanted, is
a caller-side change, not a component change). Flagged by `pronto-qa` as a non-blocking
observation, not a defect. See `features/booking/README.md`'s MS4 section.

Everything else in §3 is a scoped, low-ambiguity fix with one clear direction (reuse an
existing component/pattern that already has a precedent elsewhere in this exact codebase) —
not listed as an open question.

## 4b. Final corrections before MS4 close (2026-08-20)

Three follow-up items, requested by the user after reading the QA sign-off, are **implemented
and verified** (43/43 live assertions, see `features/booking/README.md`). They are recorded
here because two of them revise decisions this doc originally made.

**F1 — the stale-slot booking flow (was §4's "known limitation 2", pre-existing).** Root
cause confirmed in the backend, not guessed: `BookingsService.createOrder` rejects a
non-future `bookedStart` at step 0 with a plain `400 VALIDATION_ERROR` carrying
`FieldError("bookedStart", "must be strictly in the future")` — there is **no dedicated error
code**, so `BookingSummary`'s `ORDER_ERROR_MESSAGES` lookup missed it and fell through to
`GENERIC_ERROR_MESSAGE`, dead-ending the customer on the confirm screen. Fixed frontend-only,
in four layers, no backend change and no invented lead-time rule:
1. `deriveStartTimeCandidates` takes an optional `notBeforeMs` and drops candidates at or
   before it — a chip the server would reject is never offered.
2. `StartTimePicker` re-derives against a live clock every 30s and reports an expired
   selection up via a new `onSelectedExpired` callback; the flow page clears the selection in
   place and explains why.
3. `BookingSummary` pre-flight-checks `bookedStart > now` before the POST, mirroring exactly
   what the server checks — no round trip for the common case.
4. `BookingSummary` recognises the 400 by its `bookedStart` field-error entry (for clock skew
   or a slot expiring in flight) and routes through the existing `onTimeUnavailable` path,
   which already returns to the picker **and re-fetches availability**.
   The returning message is rendered as an info `notice` (`role="status"`), not the red error
   banner — the customer did nothing wrong. Its copy is distinct from
   `BOOKING_TIME_UNAVAILABLE`'s "someone else took it" message.

**F2 — Standard vs SOS success copy (revises §4 Q2's accepted consequence).** The component
stays copy-agnostic — the `title`/`body` prop shape is unchanged, which is exactly what made
this a caller-side edit. `BookingFlowPage` now reads the confirmed date/time back to the
customer (`הבקשה נשלחה ל{name} ל{date} בשעה {time}…`); `SosBookingFlowPage` has no slot to
confirm, so it speaks to the immediate wait instead (`הבקשה הדחופה נשלחה` / `שלחנו התראה
דחופה ל{name}…`). The §4 Q2 resolution's "identical copy is acceptable" note is superseded.

**F3 — visual pass against the design goals (revises §0's "no component needs a rebuild").**
That verdict was made from a static reading of the CSS modules and was right about *token*
compliance, but a live look at the rendered screens found real hierarchy gaps against
DESIGN_SYSTEM.md that a token audit cannot see:
- **§29's profession line was missing** from `ProfessionalCard` (`serviceArea` sat where the
  profession belongs). Now rendered from the listing response's own `categoryId`; the service
  area moved into the meta strip beside the distance.
- **§33's "מומלץ עבורך" badge did not exist anywhere**, even though MS1 built `Badge`'s
  `tone="primary"` specifically for it. Now on the first card while the `RECOMMENDED` sort is
  active — the backend's own top-ranked result, no client-side scoring — with a tinted ring
  on that card. §32 is respected: emphasis changes, structure does not. This corrects §1's
  table, which recorded the missing badge as *correct* behaviour; that conclusion confused
  two different things — there is indeed no `recommended` **field** to fabricate, but the
  `RECOMMENDED` **sort order** is real backend output and can be surfaced honestly.
- **§46's clock times.** `deriveStartTimeCandidates` gridded relative to each window's own
  `startAt`, and today's first window opens at `Instant.now()` — so the picker really showed
  `14:02 · 14:32 · 15:02`. Candidates now snap up to the wall-clock grid (`14:30`, `15:00`),
  are grouped by part of day (בוקר/צהריים/ערב), and carry the step's own question plus the
  real visit duration.
- **§48's visit-price line** was missing from `BookingSummary` (total only); added, matching
  `SosBookingSummary`'s existing breakdown treatment.
- **§43/§44 trust on the profile page**: the page showed no trust signal beyond an optional
  rating. It now renders a verification badge and a stats strip built strictly from fields the
  profile DTO actually returns (`approvalStatus`, `createdAt`, `averageRating`,
  `reviewCount`). The listing card deliberately still shows **no** verification mark: its DTO
  carries no such field and the listing filters only on "not deleted"
  (`BookingsService.isProfessionalActive`), so a checkmark there would breach §44.
- Copy/state honesty: an unrated card says `עדיין אין ביקורות` instead of rendering nothing;
  an unrated profile stat shows `—`, never `0.0`; `1 ביקורות` (ungrammatical in Hebrew) is now
  `ביקורת אחת` via a shared `formatReviewCount` helper; the favourite button's active label
  was `הוסר ממועדפים` ("was removed") and is now the action, `הסרה ממועדפים`.

**Known limitation carried forward (not MS4's to fix):** on mobile, the global
`ActiveOrderIndicator` FAB overlaps the left edge of the step CTA and of the profile page's
favourite button. It is a pre-existing global overlay, and the active-order experience is
MS5's subject — flagged there rather than patched here.

## 5. MS5 boundary — noted, not designed here

`OrderTrackingPage.tsx`/`CompletionReviewPage.tsx` are the natural entry points for MS5
("Active Order & Customer Experience"). This doc's §3.B loading-skeleton fix touches
`OrderTrackingPage.tsx` lightly (its top-level loading state only) because it's a
module-wide consistency fix, not because this doc is designing the "Active Job Screen"
experience (DESIGN_SYSTEM §79) — the ETA-prominence/status-as-hero-element treatment §79
describes, and any further mascot/motion work on the *active* (post-booking,
`CONFIRMED`/`ON_THE_WAY`) states specifically, is left for MS5 to design properly against
its own brief. `MyOrdersPage.tsx` (Q1 above) is also a plausible MS5 touchpoint if the
active/historical grouping question resolves toward a real IA change — flagged for
awareness, not claimed as this doc's decision to make.

## 6. What's explicitly untouched by this doc's recommendations

`ProfessionalCard.tsx`, `ProfessionalProfilePage.tsx`/`ProfessionalProfileDisplay.tsx`/
`ReviewList.tsx`, `BookingSummary.tsx`, `SosBookingSummary.tsx`, `StartTimePicker.tsx`'s
core chip logic, all backend/API contracts (`shared/api/bookings.ts`,
`shared/api/professionals.ts`, `shared/api/reviews.ts`, `shared/api/favorites.ts`), the
booking-draft persistence mechanism, the resume-hydration logic in both flow pages, and all
routing in `app/router.tsx`. No backend change is proposed or required by anything in this
doc — every fix is frontend-only, reusing components/variants that already exist in this
codebase.

## 7. File-level plan summary (for `pronto-coding`)

| File | Change |
|---|---|
| `features/booking/BookingFlowPage.tsx` | Add `direction` state + `STEP_NUMBERS`; wrap steps in `AnimatePresence`/`stepTransition` (§3.A1); pass `steps=` to `PageHeader` (§3.A5); render `Mascot state="searching"` during professional-list loading (§3.A4); replace inline success block with `<BookingSuccessStep>` (§3.A3). |
| `features/booking/BookingFlowPage.module.css` | Add `.stepViewport`; remove now-dead `.successWrapper`/`.successCheck`/`.successTitle`/`.successText`/`.successActions`. |
| `features/booking/SosBookingFlowPage.tsx` | Same as above (A1, A4, A5, A3), 3-step total instead of 4. |
| `features/booking/SosBookingFlowPage.module.css` | Same as `BookingFlowPage.module.css`. |
| `features/booking/BookingSuccessStep.tsx` (new) | Extracted, `Mascot`+`listStagger`-based success screen (§3.A3), consumed by both flow pages. |
| `features/booking/BookingSuccessStep.module.css` (new) | Adapted from `IssueSuccessStep.module.css`. |
| `features/booking/MyOrdersPage.tsx`/`.module.css` | Skeleton loading state (§3.B); `EmptyState` reuse (§3.C2). Pending Q1's answer before any IA/grouping change. |
| `features/booking/OrderTrackingPage.tsx`/`.module.css` | Skeleton loading state, top-level + category sub-case (§3.B) only — no other change, MS5's territory otherwise (§5). |
| `features/booking/CompletionReviewPage.tsx` | Skeleton loading state (§3.B). |
| `features/booking/AddressSelectionStep.tsx`/`.module.css` | Replace hand-rolled chips with `FilterChipGroup` (§3.C1). |
| `features/professionals/ProfessionalList.tsx`/`.module.css` | `listStagger` motion (§3.A2); `EmptyState` reuse (§3.C2). |
| `features/professionals/ProfessionalProfilePage.tsx`/`.module.css` | Skeleton loading state (§3.B). |

No changes proposed to `ProfessionalCard.tsx`, `ProfessionalProfileDisplay.tsx`,
`ReviewList.tsx`, `BookingSummary.tsx`, `SosBookingSummary.tsx`, `StartTimePicker.tsx`'s
logic, `shared/api/**`, or `app/router.tsx`.
