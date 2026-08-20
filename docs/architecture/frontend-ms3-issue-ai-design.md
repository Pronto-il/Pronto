# Frontend MS3 — Issue Report + AI Interaction — Design

Status: **Design only, not yet implemented.** Written by the planning/architecture agent
for `pronto-lead` to review before `pronto-coding` implements. Builds on **MS1 — Visual
Foundation & Motion System** and **MS2 — Home + Authentication Experience**
(`docs/architecture/frontend-ms2-home-auth-design.md`), all currently sitting as
uncommitted working-tree edits on `frontend/MS1-visual-foundation`. This is a **visual/UX
redesign layered onto working logic, not a rewrite** — every behavior/contract called out
in the dispatch is preserved; only presentation, composition, and (in a few small,
explicitly flagged spots) copy accuracy change.

## 0. Inputs read before designing anything

Full contents of every file this doc touches or references: `features/issues/NewIssuePage.tsx`,
`DescribeIssueStep.tsx`/`.module.css`, `ClarifyQuestionsStep.tsx`/`.module.css`,
`ReviewStep.tsx`/`.module.css`, `IssueSuccessStep.tsx`/`.module.css`,
`NewIssuePage.module.css`, `features/issues/README.md`, `features/issues/index.ts`;
`shared/components/Mascot.tsx`+`.module.css`, `PageHeader.tsx`, `PhotoUploader.tsx`,
`Button.tsx`, `Card.tsx`, `Select.tsx`, `Textarea.tsx`, `Skeleton.tsx`, `Badge.tsx`,
`FilterChip.tsx`; `shared/motion/variants.ts`, `shared/motion/README.md`;
`shared/api/issues.ts` (`ClassifyIssueResponse`, `CreateIssueRequest`, `IssueResponse`
shapes); `features/auth/RegistrationWizardShell.tsx`/`.module.css` (multi-step-flow motion
precedent); `app/HomePage.tsx`/`.module.css` (mascot responsive-sizing precedent — CSS
custom-property override under a mobile media query, not a `size`-prop swap);
`docs/architecture/frontend-ms2-home-auth-design.md` (precedent doc, reuse-discipline
bar); `frontend/Pronto — DESIGN_SYSTEM.md` §37–41, §78 (full text); `frontend/FRONTEND_AGENT.md`
§14; backend classification internals directly inspected for the §5.3 `explanation`
decision — `backend/src/main/java/com/pronto/ai/client/OpenAiClassificationClient.java`
(prompt lines: `"explanation must be short and in English"`) and
`backend/src/main/java/com/pronto/ai/client/MockAiClassificationClient.java` (mock
`explanation` strings literally read `"[מוק] סיווג לפי מילת המפתח ... שזוהתה בתיאור"` —
internal keyword-matching debug language).

---

## 1. Cross-cutting resolutions

### 1.1 No backend changes required — confirmed explicitly

Every decision in this doc uses fields `shared/api/issues.ts` already exposes
(`ClassifyIssueResponse.status/suggestedCategoryId/suggestedCategoryCode/questions`,
`CreateIssueRequest`, `IssueResponse.id/urgencyType`). `explanation`/`confidence` remain
present in the type but this doc's §5.3 explicitly decides **not** to render them (see
below) — no new field, no new endpoint, no new request shape. Confirmed: **zero backend
changes needed for MS3.**

### 1.2 Motion reuse policy

No new `framer-motion` `Variants` objects are introduced. This doc reuses, exactly as
built: `stepTransition` (main step-machine transitions — its own doc comment in
`variants.ts` literally names "issue-creation wizard steps" as the intended use, currently
unused anywhere in the app; this is its first real consumer), `pageTransition`'s shape
reused as a vertical fade+rise item-enter (per-question reveal, MS2's own precedent of
reusing `pageTransition`'s shape for `RoleChooser`'s staggered cards rather than defining a
new variant), `listStagger` (success screen's coordinated reveal), `mascotSlideIn`/
`successPop` (already wired inside `Mascot.tsx` itself for `found`/`success` states — no
change needed there, just choosing which state to pass). Micro-interactions (chip
press/hover, ellipsis pulse, radio-fill transition) stay CSS-only per
`shared/motion/README.md`'s split.

### 1.3 Reuse inventory

Reused as-is, no fork: `PageHeader` (now actually wires its `steps` progress-bar prop,
previously built but unused by this flow), `Button`, `Card`, `Textarea`, `Select`,
`PhotoUploader` (byte-for-byte unchanged — no prop/behavior change), `Mascot`, `Badge`
(considered, not used — see §5.4), `FilterChip` (considered and rejected for the
clarification answer buttons — see §4.1), `useToast` (considered, not used — see §8.6).

One new component is introduced: `AiAnalyzingOverlay` (`features/issues/`, **not**
promoted to `shared/components`, since its only real consumer is this feature's own step
machine — it has two *trigger paths* (`DescribeIssueStep`'s submit, `ClarifyQuestionsStep`'s
continue) but one render site (`NewIssuePage`), same class of justification as MS2's
single-file local components, not RegistrationWizardShell's "two simultaneous page
consumers" bar). Full reasoning in §2.

### 1.4 Mobile — designed independently per screen

Every screen below has its own "Desktop" and "Mobile" treatment, per the same policy MS2
established (desktop-first CSS authoring, poster-sourced project-wide decision, but a
genuinely separate mobile composition, not a shrunk desktop layout, for every surface —
`docs/architecture/frontend-ms2-home-auth-design.md` §1.1).

---

## 2. The step machine + AI-analysis loading state — architecture decision

This is the single most consequential structural decision in this doc — read before
anything else in §3–4.

### 2.1 The two real call sites

`classifyIssue` is called from two places, each owning its own request lifecycle:

- `DescribeIssueStep.handleSubmit` — validates the description, then calls it with no
  `clarificationAnswers`.
- `ClarifyQuestionsStep.handleContinue` — calls it again with the same
  description/imageKeys plus `clarificationAnswers`, and (per that component's own doc
  comment) always resolves to `status: "CLASSIFIED"`.

Both currently show only `Button`'s built-in spinner (`isSubmitting` → `loading` prop) and
call the shared `onClassified(result)` prop on success.

### 2.2 Decision: **not** a `Step`-union member — a sibling overlay over the still-mounted step

Two options were on the table (per the dispatch): add `{ name: 'analyzing' }` to
`NewIssuePage`'s `Step` union, or render a branded overlay locally. **Resolved: a local
`isAnalyzing: boolean` state in `NewIssuePage`, rendered as an absolutely-positioned
`AiAnalyzingOverlay` sibling that covers whichever step is currently mounted, without
unmounting it.** Concretely:

- `DescribeIssueStepProps`/`ClarifyQuestionsStepProps` each gain one new callback prop:
  `onAnalyzingChange: (isAnalyzing: boolean) => void`. Each component calls
  `onAnalyzingChange(true)` immediately before its existing `classifyIssue(...)` call and
  `onAnalyzingChange(false)` in the existing `finally` block — **no other line in either
  component's `try`/`catch`/`finally` moves or reorders**. `isSubmitting`/`setIsSubmitting`
  stay exactly as they are today (defense-in-depth against double-submit; harmless
  redundancy since the overlay visually covers the button anyway).
- `NewIssuePage` passes `onAnalyzingChange={setIsAnalyzing}` to both children and renders
  `<AiAnalyzingOverlay variant="overlay" show={isAnalyzing} />` as a sibling, absolutely
  positioned (`inset: 0`) over a `position: relative` wrapper around the step-viewport.

Justification, weighed against the existing step-machine/draft-write-through structure:

1. **State-loss risk if steps were unmounted/remounted.** `ClarifyQuestionsStep` holds
   `answers: Record<string, string>` as **local, non-lifted** state — it is not part of
   `useBookingDraft()`'s persisted shape at all. Today, if `classifyIssue` fails from that
   step, the component never unmounts (only its `isSubmitting` flag toggles), so the
   customer's already-selected answers are still there when the error banner appears — they
   don't have to re-answer. Making `'analyzing'` a real `Step`-union member would force
   `ClarifyQuestionsStep` to unmount while `'analyzing'` is showing and remount on failure,
   silently losing `answers` — a real behavior regression this milestone must not introduce
   — unless `answers` were additionally lifted into `NewIssuePage`/the draft, which is
   unwarranted new surface area for a purely visual milestone whose brief is explicitly
   "preserve behavior, don't invent state."
2. **No matching durable draft stage.** `updateDraft()` is keyed 1:1 to real backend-facing
   stages (`ISSUE_DESCRIBE`/`ISSUE_CLARIFY`/`ISSUE_REVIEW`). There is no `'analyzing'`
   stage in that vocabulary, and there shouldn't be — if the tab is closed mid-request, the
   in-flight call is simply lost and a resume correctly lands back on the last *durably
   written* stage (today's exact behavior). Keeping `'analyzing'` out of the `Step` union
   keeps that 1:1 mapping intact with zero new branches at either `updateDraft()` call site.
3. **Symmetry with the existing `isResuming` pattern.** `isResuming` is already a boolean
   gate that fully replaces render content while a `classifyIssue`-driven resume call is in
   flight (§2.4 below unifies it with the same overlay). An `isAnalyzing` boolean is the
   same shape of mechanism, not a second, structurally different one.

### 2.3 What `AiAnalyzingOverlay` looks like

New files: `features/issues/AiAnalyzingOverlay.tsx` + `.module.css`.

```tsx
export interface AiAnalyzingOverlayProps {
  show: boolean;
  /** 'overlay' = absolutely positioned over a still-mounted step (in-session analyzing).
   *  'inline' = the only content on screen (resume-time, nothing mounted underneath yet). */
  variant: 'overlay' | 'inline';
}
```

- Renders `<Mascot state="thinking" size="lg" loop />` (decorative, no `label` — the
  adjacent status text already conveys the meaning, avoiding double screen-reader
  announcement) + a status line **"Pronto בודק את התקלה..."** inside a
  `role="status" aria-live="polite"` region, with a small CSS-only 3-dot pulse (reusing the
  same staggered-opacity technique `Mascot.module.css`'s own `.motionLines` already uses,
  not a new animation *pattern* — just a new, tiny keyframe local to this component's own
  `.module.css`) standing in for the "animated status text" ask. Per DESIGN_SYSTEM §41: a
  single, honest status line, **no fake percentage/progress indicator**.
- Mount/exit animated via the component's own internal `AnimatePresence` + a fade+scale
  shape identical to `modalTransition`'s non-mobile branch (reused directly, not
  redefined) — independent of `NewIssuePage`'s own step-to-step `AnimatePresence` (§2.5),
  since the overlay's visibility is driven by `isAnalyzing`/`isResuming`, not by which
  `Step.name` is current.
- `variant="overlay"`: `position: absolute; inset: 0;`, an opaque-enough backdrop
  (`--color-surface` at ~96% opacity, not a dim scrim — this isn't a modal over unrelated
  content, it *is* the content for this moment) with `pointer-events: auto` while shown, so
  clicks can't reach the hidden form underneath. The wrapper around the step content also
  gets `aria-hidden={isAnalyzing}` from `NewIssuePage` while the overlay is up.
- `variant="inline"`: same inner markup, statically centered in normal flow (no absolute
  positioning) — used only for the resume case, where nothing is mounted underneath yet
  (see §2.4).
- Reduced-motion: `useReducedMotion()` neutralizes the fade+scale to an instant swap, same
  pattern `RegistrationWizardShell.tsx` already uses for `stepTransition`. The mascot's own
  `thinking` loop is CSS and already globally neutralized under reduced motion (documented
  in `Mascot.module.css`) — nothing extra needed there.
- Mobile: `Mascot`'s `size` prop stays `"lg"` (avoids the layout-shift risk of swapping
  components) but the overlay's own mobile media query overrides `--mascot-w`/`--mascot-h`/
  `--mascot-scale` down to `md`'s footprint — the exact technique `HomePage.module.css`
  already uses for its hero mascot (see inputs list), reused here rather than invented.

### 2.4 Resume-hydration (`isResuming`) — explicitly unified with the same overlay

**Decision: yes, unify.** `isResuming` is only ever `true` when `initialDraft.stage` is
`ISSUE_CLARIFY`/`ISSUE_REVIEW` (per the existing `useState(() => canHydrate &&
initialDraft!.stage !== 'ISSUE_DESCRIBE')` initializer) — i.e. precisely the cases where the
resume `useEffect` calls the *same* `classifyIssue(...)`. Today it renders a plain "טוענים
את הבקשה שלכם…" paragraph. This is replaced with the same `AiAnalyzingOverlay`, rendered
with `variant="inline"` (nothing is mounted underneath during resume — every step render in
`NewIssuePage` is already gated `!isResuming &&`, so there is no unmount/remount risk here
at all, unlike §2.2's in-session case). No new branching logic — the existing
`isResuming`/`setIsResuming(false)` state machine is untouched; only what renders while it's
`true` changes. The `ISSUE_DESCRIBE`-resume case (photo-only re-resolution, no
`classifyIssue` call) is **not** touched — `DescribeIssueStep` still renders immediately
with `PhotoUploader`'s own existing per-item spinner placeholders, exactly as today, since
that path never sets `isResuming` to `true` in the first place.

### 2.5 Step-to-step transition (new, small addition, directly enables §2 cleanly)

Today `NewIssuePage` swaps `describe`/`clarify`/`review`/`success` with a flat conditional
chain and no motion at all between them. This doc adds one `AnimatePresence mode="wait"`
wrapper around that chain, keyed on `step.name`, using `stepTransition` (RTL-aware slide,
already built, its own doc comment names this exact use case) — the same `direction`
(`1`/`-1`) + reduced-motion-neutralization pattern `RegistrationWizardShell.tsx` already
implements (copied locally into `NewIssuePage.tsx`, not extracted into `shared/motion`
this milestone — flagged as a nice-to-have follow-on in §9). `direction` defaults to `1`,
flips to `-1` only in `handleBack`, resets to `1` in `handleClassified`/`handleConfirmed`.
This is what makes the review→success hand-off (§6) and the general "smooth interactions"
ask land architecturally, and it's what `AiAnalyzingOverlay` layers *on top of* — the
overlay is a sibling outside the keyed `motion.div`, so it never participates in the
step-key animation, only in its own independent show/hide.

One real, deliberately-accepted behavior tightening from this change, flagged rather than
silently shipped: today, nothing stops a customer from editing the description/urgency
while a `classifyIssue` call is in flight (it has no visible effect — the closure already
captured the old values — but the fields are not disabled). Once the overlay physically
covers the form and captures pointer events during `isAnalyzing`, that edit attempt is now
blocked at the UI layer instead of silently doing nothing. This is a minor, positive
tightening (prevents a confusing no-op interaction), not a functional regression, but is
called out explicitly per the "preserve exact behavior" instruction rather than left
implicit.

### 2.6 `ReviewStep`'s `createIssue` call — explicitly out of scope, unchanged

The dispatch scopes the branded loading treatment to `classifyIssue` calls only.
`ReviewStep.handleConfirm`'s `createIssue` call keeps its existing `Button loading`
spinner, untouched. Stated explicitly so this isn't mistaken for an oversight.

---

## 3. Describe Issue composer (`DescribeIssueStep`)

### 3.1 Copy

- Textarea label: **"מה קרה?" → "ספר לי מה קרה"** (per the dispatch's exact wording).
  Flagged, not silently absorbed: this introduces a small voice inconsistency against the
  app's otherwise plural/formal "אתם" register elsewhere (Login's "שמחים לראות **אתכם**
  שוב", Home's "...ש-Pronto יעזור **לך**" is actually already singular too, so this is a
  softer flag than it first looks — still worth pronto-lead's awareness, not a blocker).
- Hint (replaces "ספרו לנו בקצרה מה קרה", now redundant with the friendlier label): **"כל
  פרט קטן עוזר לנו למצוא את בעל המקצוע המתאים"**.
- Placeholder unchanged: "לדוגמה: יש נזילת מים מתחת לכיור במטבח" (already good).
- **Small, in-scope copy-accuracy fix** (touching this file anyway): the length-validation
  error message today reads "יש לתאר את התקלה בלפחות 10 תווים" (only mentions the 10-char
  minimum) even though the same `if` also enforces a 2000-char maximum — misleading if ever
  hit. Fixed to **"יש לתאר את התקלה באורך של 10 עד 2000 תווים"**, same condition
  (`trimmed.length < 10 || trimmed.length > 2000`), same early-return, only the string
  changes.
- Photo upload label unchanged: "אפשר להוסיף תמונה?" (already exactly DESIGN_SYSTEM §37
  Step 3's copy).
- Urgency section gains a heading it didn't have before: **"באיזו דחיפות מדובר?"**.

### 3.2 New: example prompts ("helpful examples")

A small, local (not shared, single consumer, plain array in this file) row of 4 example
chips shown **only while the description field is empty** (fades/collapses once the
customer starts typing — the simplest rule that fully avoids ever overwriting real typed
content): "נזילת מים" · "תקלה בחשמל" · "מזגן לא מקרר" · "דלת או מנעול תקוע". Clicking one
sets `description` to a fuller example sentence (e.g. "יש לי נזילת מים מתחת לכיור
במטבח") via the existing `onDescriptionChange` prop — no new prop, no API/behavior change,
purely a prefill convenience layered on top of the existing controlled `description` state.
Visually: small `FilterChip`-style pill row (reusing `FilterChip`'s CSS *look* via a local
class, not the component itself — see §3.4 for why).

### 3.3 Urgency control redesign

Replaces the two plain `.urgencyChip`/`.sosChip` buttons with two larger, richer toggle
buttons (kept as native `<button>`s — no ARIA/behavior change beyond what §3.5 adds),
each with an icon + title + one-line inline microcopy (previously a separate paragraph
shown only *after* selecting SOS — now visible under each option before choosing, so it can
actually inform the choice, not just confirm it after the fact; flagged as a deliberate,
minor, positive visibility-timing change, not a logic change — the underlying
`urgencyType`/`onUrgencyChange` contract is byte-for-byte identical):

- **רגיל** (`Clock` icon) — "מתאים לרוב התקלות, בוחרים זמן שנוח לכם."
- **SOS — דחוף** (`Zap` icon) — "נעדיף בעלי מקצוע שיכולים להגיע אליך במהירות. ייתכן חיוב
  נוסף." (verbatim reuse of today's existing `sosNote` copy, just relocated).

`urgencyType === 'SOS'`'s separate `<p className={styles.sosNote}>` paragraph is removed
(its content now lives inside the SOS card itself, always visible).

### 3.4 Why not `FilterChip`/`Badge` for these

`FilterChip` (36px, compact, horizontal-row filter semantics) and `Badge` (a label pill,
not interactive) were both considered and rejected as direct reuse for the urgency
cards/example chips — a genuine reuse-bar failure, not laziness: the urgency control needs
two large, icon+title+subcopy cards (a materially different visual language than a compact
chip), and the example prompts, while chip-*shaped*, aren't part of any `role="radiogroup"`
selection state `FilterChipGroup` models — they're one-shot prefill triggers. Local CSS in
`DescribeIssueStep.module.css` for both, consistent with the reuse discipline MS2 applied
to `RoleChooser`'s option cards.

### 3.5 Composer shell

Whole form wrapped in a `<Card>` (same "give the form a real surface" treatment MS2 gave
`LoginForm`) with a small `<Mascot state="idle" size="sm" />` above the "ספר לי מה קרה"
heading — same restrained, static, decorative pattern MS2 established for Login, not a new
one.

### 3.6 Desktop composition (>=1024px)

```
┌───────────────────────────────────────────┐
│ [Mascot idle sm]                            │
│ ספר לי מה קרה                                │
│ ┌─────────────────────────────────────────┐│
│ │ [banner if any]                          ││
│ │ [Textarea, large, min-height ~140px]     ││
│ │ לדוגמה: [נזילת מים] [תקלה בחשמל] ...      ││  ← only while empty
│ │                                           ││
│ │ אפשר להוסיף תמונה?                        ││
│ │ [PhotoUploader grid, unchanged]           ││
│ │                                           ││
│ │ באיזו דחיפות מדובר?                       ││
│ │ ┌───────────────┐ ┌───────────────────┐  ││
│ │ │ 🕐 רגיל        │ │ ⚡ SOS — דחוף       │  ││
│ │ │ subcopy        │ │ subcopy            │  ││
│ │ └───────────────┘ └───────────────────┘  ││
│ │                                           ││
│ │           [ המשך ]                        ││
│ └─────────────────────────────────────────┘│
└───────────────────────────────────────────┘
```

### 3.7 Mobile composition (<640px)

Same content, independently composed: `Card` padding tightens to `--space-4`; example
chips wrap to 2 rows (`flex-wrap`); urgency cards stack full-width vertically instead of
side-by-side (today's `flex: 1` row becomes `flex-direction: column` under the existing
`@media (max-width: 640px)` convention); mascot may be omitted under very short viewports,
implementer's call (same minor-refinement latitude MS2 gave Login's mascot).

---

## 4. Clarification Questions (`ClarifyQuestionsStep`)

### 4.1 Large selectable answer buttons

Existing `.option` buttons are restyled, not replaced: larger padding (min-height ~56px,
comfortably exceeds the 44px touch target), a leading radio-style icon (`Circle` unselected
→ `CheckCircle2` filled `--color-primary` selected, `lucide-react`, consistent with icons
already used elsewhere in the app rather than a new pack), a quick CSS scale/border-color
transition on select (`--duration-fast`/`--ease-out` tokens, CSS tier). **Accessibility
addition, not a behavior change**: each `question`'s option group gains proper
`role="radiogroup"`/`role="radio"`/`aria-checked` (mirroring `FilterChip`'s own pattern,
considered and rejected for direct reuse in §3.4 for the *urgency* control but a reasonable
*pattern* to mirror here for ARIA semantics only — no shared component swap, since these
buttons are visually full-width stacked "conversational" cards, not `FilterChip`'s compact
chip look). `answers` state, `allAnswered` gating, and the `handleContinue` call/payload are
byte-for-byte unchanged.

### 4.2 Per-question progressive reveal (multi-question case only)

**Decision: sequential reveal when `questions.length > 1`; all-at-once (today's existing
behavior, just restyled) when there's exactly one question** — the common case, per the
single-clarification-round design. Rationale: DESIGN_SYSTEM §40 frames clarification as
"part of the normal booking flow," and a conversational back-and-forth reads more natural
revealing one question at a time than a form dump, but staging that choreography for a
single question would add motion with nothing to actually stage.

Mechanism: a new local `visibleCount` state (`Math.max(1, answers-so-far + 1)`, capped at
`questions.length`), incremented whenever the currently-last-visible question receives an
answer. Newly revealed questions mount via `AnimatePresence` using `pageTransition`'s exact
shape (`{opacity:0,y:8} → {opacity:1,y:0}`, reused directly, not a new variant — same reuse
MS2 applied to `RoleChooser`'s staggered cards) as a per-item enter. No `setTimeout`/
artificial delay — the reveal is driven purely by the state update, avoiding timing-flake
risk. Revisiting/changing an earlier, already-revealed question's answer does **not**
re-collapse later questions — deliberate, avoids a jarring collapse-on-edit surprise; not
specified by the dispatch, called out here as an explicit implementer decision rather than
left ambiguous.

### 4.3 `onAnalyzingChange` wiring

Per §2.2 — `handleContinue` gains `onAnalyzingChange(true)` before, `onAnalyzingChange(false)`
in the existing `finally`, no other reordering. `bannerError`/`isSubmitting` unchanged.

### 4.4 Desktop / Mobile

Both already compose naturally as a single stacked column (today's `.options` is already
`flex-direction: column`), so this surface's desktop and mobile layouts are structurally
identical — only spacing/padding scale down slightly under `@media (max-width: 640px)`
(existing convention), and the intro line's font-size steps down one notch. No independent
mobile *composition* work is needed here beyond that, unlike §3/§5/§6 — noted explicitly
rather than silently assumed identical.

---

## 5. AI Review → diagnosis card (`ReviewStep`)

### 5.1 Redesign

Eyebrow **"מצאנו את בעל המקצוע המתאים"** ("we found the right professional") is factually
premature at this point in the flow — no professional has been matched yet, that only
starts in `features/booking` after this screen. Replaced with **"האבחון שלנו"** ("our
diagnosis") as the eyebrow, and a dynamic diagnosis-style headline: **"נראה שמדובר בתקלה
ב־{קטגוריה}"** (category name interpolated via the existing `getCategoryNameHe(...)` call
— no new lookup), replacing the flat `<h2>{category}</h2>`. A new, short, static
reassurance line follows: **"כך נמצא לך את בעל המקצוע הכי מתאים."** — deliberately *not*
derived from `classification.explanation` (see §5.3 for why), just honest framing that ties
the category confirmation to the real next step.

If `urgencyType === 'SOS'`, a small inline badge — **"דחוף — SOS"** — renders next to the
headline, using the same `--color-sos`/`--color-sos-bg` tokens `DescribeIssueStep`'s own
SOS chip already uses (not `Badge`, whose `BadgeTone` enum has no `'sos'` tone and adding
one is out of this milestone's scope — flagged, not silently worked around). This is a
genuinely new, additive piece of information: `ReviewStepProps.urgencyType` is already
passed into this component today but never rendered anywhere — a real, small gap this
redesign closes at no logic cost (the prop already exists, unchanged).

The existing "זה לא נכון? שינוי תחום" change-link + `isChangingCategory`/`Select` mechanism
is kept exactly, just restyled to match the new card (larger touch target, consistent with
§4.1's tap-target sizing pass). "אישור והמשך" confirm button unchanged.

### 5.2 Desktop / Mobile

Desktop: diagnosis card centered in `.focused-page`'s existing 680px container, eyebrow →
headline → optional SOS badge → optional reassurance line → change-category affordance,
confirm button full-width below. Mobile: identical structure (already single-column),
headline font-size steps down one notch under `@media (max-width: 640px)`; no other
structural change needed — this card was never desktop-multi-column to begin with.

### 5.3 The `explanation` field — explicit resolution (not left ambiguous)

The dispatch permits, but does not require, rendering a short, conversationally-reworded
one-line sentence derived from `classification.explanation`. **Decision: do not render it
— the stricter prior behavior is kept, deliberately, for a concrete reason verified against
the actual backend, not a cautious default:**

1. **Real (OpenAI) mode**: `OpenAiClassificationClient.java`'s own prompt instructs
   `"explanation must be short and in English"` (confirmed directly in source, two separate
   prompt-building branches). Pronto v1.0 is Hebrew-only. Rendering `explanation` verbatim
   would put unlocalized English text inside an otherwise fully Hebrew, RTL screen —
   contradicts the project's own stated v1.0 language scope, not just a style
   nitpick.
2. **Mock mode**: `MockAiClassificationClient.java`'s `explanation` strings are literally
   `"[מוק] סיווג לפי מילת המפתח \"X\" שזוהתה בתיאור, המתאימה לקטגוריה \"Y\""` — i.e. they
   describe the internal keyword-matching mechanism by name. This is exactly the class of
   "expose implementation terminology" DESIGN_SYSTEM §40/FRONTEND_AGENT §14 forbid, and a
   client-side "conversational rewording" would have to either strip/parse this
   mock-specific format (brittle, mode-dependent, and still leaks "we matched a keyword" as
   a concept even if reworded) or hardcode a rewrite that ignores the actual string
   entirely — at which point it's no longer "derived from `explanation`," it's fabricated
   copy wearing the field's name, which is a worse outcome than not showing it.
3. There is no backend-provided, pre-localized, customer-safe explanation string anywhere
   in the current API — building one client-side would mean inventing translation/rewrite
   logic with no backend support, out of this milestone's remit (visual redesign, not new
   AI-output shaping), and risks misrepresenting the model's actual reasoning.

**Net: `explanation`/`confidence` are never rendered, exactly as today's code comment
states.** The "supporting explanation when useful" ask from the dispatch is satisfied
instead by §5.1's static reassurance line + the SOS badge — real, accurate, always-safe
content, just not literally the AI's own reasoning text. This is a disclosed, deliberate
continuation of the prior stricter behavior, not a silent non-decision.

---

## 6. Success Transition (`IssueSuccessStep`)

### 6.1 Redesign

The plain `✓` circle + "הבקשה נשלחה" is replaced with `<Mascot state="success" size="xl"
label="הבקשה נשלחה בהצלחה" />` (meaningful now, not decorative — `label` provided per
`Mascot`'s own documented convention) + headline **"הבנתי. עכשיו נמצא לך מישהו."** (the
dispatch's exact suggested copy, replacing "הבקשה נשלחה"). Supporting body text is
**unchanged**, still branching on `isStandard` exactly as today (`'קיבלנו את הפרטים. עכשיו
אפשר לבחור בעל מקצוע ותור שמתאים לכם.'` / `'קיבלנו את הבקשה הדחופה שלכם. עכשיו אפשר לחפש
בעל מקצוע זמין לעבודה דחופה.'` — already accurate, no reason to touch). CTA button copy
(`'בחירת בעל מקצוע'`/`'חיפוש בעל מקצוע זמין'`) and destinations
(`/issues/${issueId}/booking` vs `/issues/${issueId}/sos-booking`), plus the secondary
"חזרה לדף הבית" button, are **byte-for-byte unchanged**.

`state="success"` (not `"found"`) is the deliberate choice: `"found"` reuses the same
`pointing` artwork as `idle`/`thinking` with only a slide-in, and is reserved — by naming
consistency for later milestones — for the actual "found a professional" moment inside
`features/booking`. `"success"` has its own distinct artwork + `successPop`'s celebratory
scale-in, which is the semantically correct match for "a real DB write just succeeded,"
matching this component's own doc comment ("Calm confirmation state after `POST
/api/issues` succeeds").

### 6.2 Required CTA action vs. auto-advance — explicit resolution

**Decision: the CTA button remains a required, explicit user action — no auto-navigation.**
DESIGN_SYSTEM §78's own worked example ends in a manual button
(`[ צפייה בהזמנה ]`), not an auto-redirect, and its literal rule ("do not immediately throw
the user back to the homepage") is about not *skipping* a confirmation state, which
auto-advancing into booking wouldn't technically violate — but auto-navigating away from a
screen the user just landed on removes their ability to actually read the confirmation
(especially since SOS vs STANDARD routes differ, and the two body copies say materially
different things), and costs real agency for screen-reader/keyboard users mid-announcement.
"Calm confirmation" implies deliberate pacing, not the user being whisked away the instant
`successPop` finishes. The mascot's own one-shot entrance already *is* the "short
transition" the dispatch asks for; the CTA staying manual is what keeps it "calm" rather
than jarring.

### 6.3 Motion

`Mascot`'s `successPop` fires automatically (already built into `Mascot.tsx` for
`state="success"` — no extra wiring here). The headline/body/actions block below it is
wrapped in `listStagger` (container) with each item reusing `pageTransition`'s fade+rise
shape (same reuse pattern as §4.2), so the mascot pops in first and the text/CTAs settle in
just after — a coordinated, single reveal, not simultaneous. This whole success step is
also what plays *into* view via `NewIssuePage`'s new `stepTransition` (§2.5) as `step.name`
becomes `'success'` — the two motions compose (outer slide-in-as-a-step, inner
stagger-reveal-of-contents) without conflicting, since they animate different properties in
sequence (the outer transition settles, revealing the step; only then does the inner
`listStagger` play, since it's mounted fresh at that point).

### 6.4 Desktop / Mobile

Both already single-column, centered, matching today's structure. Mobile: `Mascot`'s
`size="xl"` footprint shrinks via the same CSS-custom-property-override technique (§2.3) at
`@media (max-width: 640px)`, rather than swapping to a smaller `size` prop.

---

## 7. `NewIssuePage` shell — remaining changes

### 7.1 `PageHeader` — wiring the existing `steps` prop

`STEP_LABELS` (text) stays unchanged. New: a `STEP_NUMBERS` map (`describe: 1, clarify: 2,
review: 3`) feeds `PageHeader`'s already-built, previously-unused `steps={{current, total:
3}}` prop for `describe`/`clarify`/`review`, pairing the existing "שלב X מתוך 3" text with
the visual progress bar per DESIGN_SYSTEM §38's own suggestion ("or a simple progress
bar"). `steps` is omitted entirely on `'success'` (arrival, not an in-progress step —
matches `STEP_LABELS`'s existing `Partial<Record<...>>` already omitting a `'success'`
key). The header stays static (no "מנתחים..." text swap) while `AiAnalyzingOverlay` is
showing — the overlay covers the body, the header frame stays stable, avoiding extra
flicker between two independently-timed things.

### 7.2 Small, in-scope fix found while editing this file: dead back-button on success

`handleBack`'s existing body is `if (describe) navigate('/'); else if (step.name !==
'success') { ...back to describe... }` — meaning on the success step, `handleBack` silently
does nothing at all, yet `PageHeader`'s back arrow is still rendered (unconditionally
passed `onBack={handleBack}`) and clickable, producing a dead, confusing control.
**Fix**: `onBack={step.name === 'success' ? undefined : handleBack}` — `PageHeader` already
supports omitting `onBack` (conditionally hides the arrow entirely, per its own prop
contract). The success screen already has its own explicit "חזרה לדף הבית" action, so
nothing is lost. Flagged here explicitly (small, mechanical, discovered opportunistically)
rather than silently folded in, same spirit as MS2's §3.4 "required spillover fixes."

### 7.3 New local state

`isAnalyzing: boolean` (§2.2), `direction: number` (§2.5) — both `useState`, no persistence,
no draft interaction. `isResuming`'s existing state/effect is untouched structurally; only
what renders while it's `true` changes (§2.4).

---

## 8. Copy changes — summary table

| Location | Before | After |
|---|---|---|
| Describe — label | "מה קרה?" | "ספר לי מה קרה" |
| Describe — hint | "ספרו לנו בקצרה מה קרה" | "כל פרט קטן עוזר לנו למצוא את בעל המקצוע המתאים" |
| Describe — length error | "יש לתאר את התקלה בלפחות 10 תווים." | "יש לתאר את התקלה באורך של 10 עד 2000 תווים." |
| Describe — urgency heading | *(none)* | "באיזו דחיפות מדובר?" |
| Describe — SOS note visibility | shown only after selecting SOS | shown inline in the SOS card, always |
| Analyzing overlay | *(button spinner only)* | "Pronto בודק את התקלה..." |
| Resume loading | "טוענים את הבקשה שלכם…" | same `AiAnalyzingOverlay` (inline variant) |
| Review — eyebrow | "מצאנו את בעל המקצוע המתאים" | "האבחון שלנו" |
| Review — headline | `{קטגוריה}` alone | "נראה שמדובר בתקלה ב־{קטגוריה}" |
| Review — reassurance line | *(none)* | "כך נמצא לך את בעל המקצוע הכי מתאים." |
| Review — SOS badge | *(none)* | "דחוף — SOS" (SOS issues only) |
| Success — headline | "הבקשה נשלחה" | "הבנתי. עכשיו נמצא לך מישהו." |
| Success — body / CTAs | — | **unchanged** |

Everything else (`ClarifyQuestionsStep`'s intro line, both branches of `IssueSuccessStep`'s
body copy, both CTA labels, "זה לא נכון? שינוי תחום," "אישור והמשך") is intentionally left
verbatim.

---

## 9. Open questions / flags (explicit, not buried)

1. **`explanation`/`confidence` remain unrendered** (§5.3) — a deliberate continuation of
   the prior stricter behavior, backed by direct inspection of the backend prompt/mock
   logic, not a default/cautious non-decision. Worth pronto-lead's explicit sign-off since
   the dispatch left the door open to the opposite choice.
2. **Copy-voice inconsistency**: "ספר לי מה קרה" (singular "tell me") sits alongside the
   app's more common plural/formal "אתם" register elsewhere. Using the dispatch's exact
   requested wording as instructed; flagged for awareness, not blocking.
3. **Length-validation error message fix** (§3.1) and **dead success-screen back button
   fix** (§7.2) are both small, mechanical, in-scope corrections discovered while reading
   these files for this redesign — called out explicitly, not silently bundled in.
4. **`stepTransition`'s reduced-motion-neutralization boilerplate** is duplicated locally
   in `NewIssuePage.tsx`, copied from `RegistrationWizardShell.tsx`'s existing pattern
   rather than extracted into `shared/motion` this milestone. Recommended (not decided
   here) as a future small cleanup once a third consumer appears — two duplicated copies
   isn't yet worth a new shared hook, per this codebase's own "real second/third consumer"
   reuse bar.
5. **`useToast()` was considered and not used anywhere in this doc** — e.g. a toast on
   successful issue creation was evaluated as a nice-to-have parallel to the mascot success
   screen, but rejected as redundant: the whole point of §6 is that the *page itself* now
   carries a strong, branded confirmation moment; stacking a toast on top of it would be
   noise, not reinforcement. Noted so it isn't independently "discovered" and added later
   without this reasoning.
6. **SOS badge's tone**: uses raw `--color-sos`/`--color-sos-bg` tokens directly rather than
   extending `Badge`'s `BadgeTone` enum with a new `'sos'` value (§5.1) — a reasonable,
   low-risk implementer call, but flagged in case pronto-lead would rather formalize an
   `'sos'` `BadgeTone` for reuse beyond this one card (out of this milestone's scope to
   decide unilaterally).

---

## 10. File impact — for `pronto-coding`

**New:**
- `frontend/src/features/issues/AiAnalyzingOverlay.tsx`
- `frontend/src/features/issues/AiAnalyzingOverlay.module.css`

**Modified:**
- `frontend/src/features/issues/NewIssuePage.tsx` — `isAnalyzing`/`direction` state,
  `onAnalyzingChange` props threaded to both children, `AnimatePresence`+`stepTransition`
  wrapper around the step conditional chain, `AiAnalyzingOverlay` (overlay + inline
  variants) replacing the old `isResuming` plain-text block, `STEP_NUMBERS` → `PageHeader`
  `steps` prop, conditional `onBack` fix (§7.2). No change to `useBookingDraft()` calls,
  `handleClassified`/`handleBack`/`handleConfirmed`'s update-draft payloads, or
  `hasConflictingDraft`/`warningDismissed` logic.
- `frontend/src/features/issues/NewIssuePage.module.css` — step-viewport
  `position: relative` wrapper rule; `.resumingWrapper`'s plain-text styling removed
  (superseded by `AiAnalyzingOverlay`'s own CSS); `.warningBanner` styling untouched
  (still used for the conflicting-draft/resume-error/photo-warning banners, unchanged
  logic).
- `frontend/src/features/issues/DescribeIssueStep.tsx` — `onAnalyzingChange` prop +
  2-line wiring around the existing `classifyIssue` call; new example-chips local
  sub-render; urgency section restructured into two cards; copy per §8; error-message
  string fix (§3.1). `handleSubmit`'s validation condition, `classifyIssue` payload, and
  `onClassified`/error-banner logic unchanged.
- `frontend/src/features/issues/DescribeIssueStep.module.css` — Card/mascot wrapper rules,
  example-chip row, urgency-card layout (replacing `.urgencyChip`/`.sosChip`/`.sosNote`),
  mobile stacking.
- `frontend/src/features/issues/ClarifyQuestionsStep.tsx` — `onAnalyzingChange` prop +
  2-line wiring; `visibleCount` local state + per-question `AnimatePresence` reveal
  (multi-question case only); `role="radiogroup"`/`role="radio"` ARIA additions.
  `answers`/`allAnswered`/`handleContinue`'s payload unchanged.
- `frontend/src/features/issues/ClarifyQuestionsStep.module.css` — larger option buttons,
  radio-icon layout, reveal-motion-friendly spacing.
- `frontend/src/features/issues/ReviewStep.tsx` — eyebrow/headline/reassurance-line copy
  (§8), SOS badge (new, reads existing `urgencyType` prop), restyled change-category
  affordance. `handleConfirm`/`createIssue` payload, `isChangingCategory`/`categoryId`
  state, and `onConfirmed` call unchanged.
- `frontend/src/features/issues/ReviewStep.module.css` — diagnosis-card layout, SOS badge
  styling.
- `frontend/src/features/issues/IssueSuccessStep.tsx` — `Mascot state="success"` replacing
  the `.check` span, `listStagger`/`pageTransition`-shaped reveal wrapper, headline copy
  (§8). Body copy branch, CTA labels/destinations, secondary button — all unchanged.
- `frontend/src/features/issues/IssueSuccessStep.module.css` — mascot-led layout, mobile
  mascot-size override (§6.4), `.check` rule removed.
- `frontend/src/features/issues/README.md` — updated per this project's standing rule
  (every touched package's doc gets updated) to describe: the `AiAnalyzingOverlay`
  addition and its two trigger paths, the `isResuming` unification, the explicit
  `explanation`-field non-rendering decision (§5.3) restated for future readers, and the
  step-to-step `stepTransition` addition.

**Unchanged, confirmed by inspection:** `shared/components/PhotoUploader.tsx`/`.module.css`
(zero prop/behavior change — reused exactly), `shared/api/issues.ts` (§1.1), `shared/hooks/
useBookingDraft.ts` and `bookingDraftContext.ts` (no new draft stages/fields).

---

## 11. Suggested implementation order (sequencing only)

1. `AiAnalyzingOverlay` (new component) — self-contained, no dependents yet, easiest to
   build/verify in isolation (both `variant`s, reduced-motion, mobile sizing).
2. `NewIssuePage.tsx` shell changes (§2.2, §2.4, §2.5, §7) — wires the overlay in, adds
   `stepTransition`, fixes the back-button. Everything else depends on the new
   `onAnalyzingChange` prop existing.
3. `DescribeIssueStep` (§3) — composer redesign + `onAnalyzingChange` wiring.
4. `ClarifyQuestionsStep` (§4) — answer-button redesign, progressive reveal,
   `onAnalyzingChange` wiring.
5. `ReviewStep` (§5) — diagnosis card, SOS badge.
6. `IssueSuccessStep` (§6) — success transition, depends on nothing else here but is
   naturally last in the user-facing flow.
7. `features/issues/README.md` update — last, once the real shape of all the above is
   settled.
