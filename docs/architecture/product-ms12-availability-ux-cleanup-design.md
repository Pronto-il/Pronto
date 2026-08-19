# MS12 — Availability UX Cleanup

Status: **design pass, not yet built.** Written by `pronto-planning`, source of truth for
`pronto-lead` to sequence and `pronto-coding`/`pronto-documentation` to implement/document
against. Scope: `frontend/src/features/dashboard/WeeklyAvailabilityPage.tsx` only, reusing
`frontend/src/shared/components/Modal.tsx` (built in the prior "Professional Weekly
Availability Calendar redesign" milestone). No other file needs a behavioral change.

## 0. Scope

Verbatim requirements:

- Default working hours are configured once during setup/onboarding.
- Do not permanently display the working-hours list inside the main Availability screen.
- Add an Edit Working Hours action/button.
- Clicking it should open the existing/new working-hours editing interface.
- The main Availability page should focus on the weekly calendar itself.
- The calendar should clearly show: Available, Blocked, Booked.
- Preserve all existing booking/blocking logic and do not weaken existing availability
  validation.

This is a page-composition/layout change: hide a permanently-visible list, add a button,
wire that button to an existing modal primitive instead of an inline expansion. **No new
component, no new endpoint, no data-model change.**

---

## 1. Diagnosis, verified against current code

Read `WeeklyAvailabilityPage.tsx`, `Modal.tsx`, `WorkingHoursForm.tsx`,
`WeeklyCalendarGrid.tsx` in full. The brief's diagnosis is accurate; details confirmed:

- **First-time setup already satisfies "configured once during setup/onboarding."**
  `isSetupComplete()` (fewer than 7 `workingHours` rows ⇒ not configured) gates
  `showFullPageSetup`, which renders `WorkingHoursForm` full-page with a skippable
  "דלג, אגדיר מאוחר יותר" ghost action (lines 65-101). Nothing here needs to change.
- **The problem is exactly as described.** Once `setupComplete` is true, the page's second
  branch (lines 103-129) permanently renders `WorkingHoursSummary` — a `Card` with one row
  per weekday, always visible, defined at lines 134-151 — directly above
  `WeeklyCalendarGrid`, with a small "עריכת שעות עבודה" text-link (`styles.editLink`, a
  bare `<button>`, not a real `Button` component) that toggles `isEditingHours` and, when
  true, swaps the summary for `WorkingHoursForm` **inline**, inside the same `Card`
  (lines 115-121).
- **The page's own doc comment self-flags this as a known deviation** (lines 34-41):
  §7.2 of `professional-weekly-calendar-design.md` always intended the edit entry point to
  open "in a modal/drawer (reuse whatever new `Modal` primitive M5 introduces)" — confirmed
  at `professional-weekly-calendar-design.md` lines 625-627. The inline pattern was an
  explicitly-flagged stand-in only because `Modal.tsx` didn't exist at the time M3 shipped.
- **`Modal.tsx` now exists** and is already in production use elsewhere
  (`CalendarBlockModal`, per `WeeklyCalendarGrid.tsx` line 10/341-353). Its API:
  `isOpen`, `onClose`, `title?`, `children`, `footer?`, `size?: 'small' | 'normal' |
  'large'` (defaults to `'normal'`). Responsive variant (mobile bottom sheet vs. desktop
  centered dialog) is automatic via CSS breakpoint, not a prop — no `variant` decision
  needed from this page.
- **`WorkingHoursForm` already slots into `Modal` with zero prop-shape changes.** Its props
  are `workingHours`, `onSaved`, and an optional `onCancel` "designed for exactly this
  'host me in something dismissible' use case" (brief's framing, confirmed correct — see
  its own doc comment line 27-29: `onCancel` exists precisely because edit-mode "has
  something to revert to"). `Modal`'s `onClose` is exactly what `onCancel` should call.
- **`WeeklyCalendarGrid` already has a `Legend` component** (lines 193-210) rendered above
  the grid on every render, with three entries — זמין (available), חסום (blocked), תפוס
  (booked) — each an `aria-hidden` color swatch **plus a text label**. Every rendered
  segment additionally carries its own icon + text label, not just color:
  `AVAILABLE` → `CheckCircle2` icon + "זמין" (lines 468-477); `BLOCKED` → `Lock` icon +
  "חסום" (+ optional `reason` text) (lines 480-491); `BOOKED` → the shared `StatusBadge`
  component, which itself renders a status-specific label, plus a distinct `segmentBooked`/
  `segmentCompleted` fill (lines 493-507). This is a direct match for
  `professional-weekly-calendar-design.md` §7.3's accessibility requirement, confirmed
  verbatim at line 642: "distinct fill **and** a text label/icon (not color-only, per the
  explicit accessibility ...)".
  **Conclusion: this requirement is already fully met. MS12 needs no change to
  `WeeklyCalendarGrid.tsx` or its CSS.**

---

## 2. The change

All changes are confined to `WeeklyAvailabilityPage.tsx` (+ a few rules in
`WeeklyAvailabilityPage.module.css`; some existing rules become dead and should be
removed). `WeeklyCalendarGrid.tsx`, `WorkingHoursForm.tsx`, `Modal.tsx`, and every backend
file are untouched.

### 2.1 Remove the permanent summary

Delete the `WorkingHoursSummary` function and its render branch (current lines 119-121,
134-151). The post-setup branch (`workingHours !== null && !showFullPageSetup`) no longer
renders a working-hours list at all by default.

### 2.2 Replace the inline-expanding edit link with a button that opens `Modal`

- Keep a single `boolean` piece of state for whether the editor is open — rename
  `isEditingHours` to something like `isEditModalOpen` (naming only; no behavior change to
  the flag itself).
- Replace the bare `<button className={styles.editLink}>` with the codebase's real shared
  `Button` component (already imported on this page), `variant="secondary"`, keeping the
  `Pencil` icon. Keep the existing Hebrew label **"עריכת שעות עבודה"** — it's accurate and
  already established elsewhere in this feature; no copy change needed, refining it isn't
  worth the churn for a small cleanup task.
- On click, set `isEditModalOpen = true`. Render:
  ```
  <Modal isOpen={isEditModalOpen} onClose={() => setIsEditModalOpen(false)} title="עריכת שעות עבודה" size="normal">
    <WorkingHoursForm workingHours={workingHours} onSaved={handleSaved} onCancel={() => setIsEditModalOpen(false)} />
  </Modal>
  ```
- `size="normal"` (560px desktop dialog per `Modal.tsx`'s own doc comment) — `WorkingHoursForm`
  is a 7-row form with per-row time inputs; `normal` is the closest fit to its existing
  `Card`-wrapped inline width and avoids the excess whitespace `large` (720px) would add for
  a form with no wide content (no `small` either — 420px is cramped for two side-by-side
  time inputs per row on the existing `WorkingHoursForm.module.css` layout). This is a minor
  visual judgment call for `pronto-coding`/QA to confirm once built, not a load-bearing
  decision — either `small` or `normal` would function correctly.
- `handleSaved` (already existing, lines 60-63) needs one addition: after `setWorkingHours`,
  also close the modal (`setIsEditModalOpen(false)`) instead of the old `setIsEditingHours
  (false)` — same call, renamed target, no logic change. (`WorkingHoursForm`'s own `onCancel`
  path already calls `Modal`'s `onClose` directly, covering the cancel case.)
- Place this button in a slim header row near the top of the page, **outside and above**
  `WeeklyCalendarGrid`'s own wrapper — reuse the existing `sectionHeaderRow` pattern, e.g. a
  row containing just the page's existing "יומן זמינות שבועי" section title plus the new
  edit button, so the button reads as acting on the whole availability page rather than as
  a calendar-internal control. Do not place it inside `WeeklyCalendarGrid.tsx` itself — that
  component is deliberately untouched, and the button is conceptually about working-hours
  configuration, not calendar navigation.

### 2.3 Resulting page structure (post-setup branch)

Top to bottom: `SosAvailabilityToggle` (unchanged, unchanged position) → a slim header row
with the section title and the "עריכת שעות עבודה" button → `WeeklyCalendarGrid` (now the
first and only substantial content block, immediately visible without scrolling past a
7-row list) → `Modal` (rendered but invisible unless open).

### 2.4 CSS cleanup

`WeeklyAvailabilityPage.module.css`'s `.summaryCard`, `.summaryRow`, `.summaryDay`,
`.summaryHours`, `.summaryOff` rules (lines 73-98) become dead once §2.1 lands — remove
them. `.editLink` also becomes dead once §2.2 switches to the shared `Button` — remove it
too, unless `pronto-coding` finds another use for that exact style during implementation
(unlikely; flag for removal, not a hard requirement to hunt down every reference first).

---

## 3. What replaces the summary as an orientation cue

**Recommendation: nothing.** Rely on the button (whose presence signals "hours are
editable here") plus the calendar's own visual language — `WeeklyCalendarGrid` already
renders time outside configured working hours with no segment at all (a muted/empty
background, per its existing implementation), which already communicates the configured
schedule shape at a glance once a professional looks at the grid for more than a second.
Adding a one-line compact summary (e.g. "עובד/ת 5 ימים בשבוע") would partially reintroduce
the exact permanently-visible list the brief asks to remove, in miniature, for
questionable benefit — the calendar itself is a strictly more complete and more accurate
answer to "what are my hours" than any one-line text could be, and it's now the dominant
element on the page per requirement 5. This is a small judgment call, not a strong
technical constraint — if `pronto-lead`/the user wants a one-line cue after seeing the
built page, adding it later is a trivial, isolated follow-up (one `<p>` under the header
row) and does not require revisiting anything else in this doc.

---

## 4. Explicit confirmation: no validation logic is touched

This change does **not** touch, weaken, or risk any existing booking/blocking validation,
client or server:

- `WorkingHoursForm`'s own validation (required start/end times, end-after-start check,
  lines 70-84 of `WorkingHoursForm.tsx`) is internal to that component and is reused
  completely unchanged — it is merely rendered inside `Modal` instead of inline. Nothing
  about its `handleSubmit`, its field-level error state, or its `updateWorkingHours` API
  call is touched.
- The same `getWorkingHours`/`updateWorkingHours` API calls, same `PUT
  /api/availability/working-hours` full-week-replace contract, same `handleSaved` data
  flow (`setWorkingHours(saved)`) are reused verbatim — only what closes the editor changes
  (modal `onClose` instead of collapsing an inline `Card`).
- `WeeklyCalendarGrid`'s own data fetching (`getAvailabilityCalendar`), polling, segment
  click-routing (`AVAILABLE`/`BLOCKED` → `CalendarBlockModal`, `BOOKED` → order navigation),
  and `CalendarBlockModal`'s own block-create/edit/delete validation are entirely untouched
  — this component is not modified at all by MS12.
- No backend endpoint, controller, service, or validation rule is touched. `GET`/`PUT
  /api/availability/working-hours`, `GET /api/availability/calendar`, and the block CRUD
  endpoints are unmodified — this is confirmed explicitly, per the brief's own framing, as
  a pure frontend page-composition change.

---

## 5. AVAILABLE/BLOCKED/BOOKED visual-clarity requirement

**Already met — no change needed.** See §1 above: `WeeklyCalendarGrid`'s `Legend` plus each
`SegmentBlock` branch already render a distinct fill *and* an icon *and* a text label per
segment type (`CheckCircle2`/"זמין", `Lock`/"חסום", `StatusBadge`'s own status label for
booked), satisfying the design doc's own accessibility bar (not color-only). MS12 does not
modify `WeeklyCalendarGrid.tsx` or its CSS at all.

---

## 6. Ambiguity / open items

None blocking. Two minor, explicitly-flagged, non-blocking judgment calls, both called out
above and safe to resolve during implementation without a design round-trip:

- `Modal` `size` — recommended `normal`; `small` would also function, purely a spacing
  preference (§2.2).
- Whether to add a one-line compact summary under the header row — recommended against,
  for now (§3); trivial to add later if the built page reads as too sparse.

Everything else in the brief maps directly onto existing, already-built primitives with no
new design decisions required. This is intentionally a small task: one branch of one page
loses a permanent list and a bare inline-toggle link, and gains a `Button` + an already-
existing `Modal`.

---

## 7. Implementation checklist (for `pronto-lead`/`pronto-coding`)

1. `WeeklyAvailabilityPage.tsx`: remove `WorkingHoursSummary`; rename `isEditingHours` →
   `isEditModalOpen` (or equivalent); replace the inline conditional render with a `Button`
   + `Modal` per §2.2; update `handleSaved` to close the modal; move the button into a
   slim header row above `WeeklyCalendarGrid` per §2.3; update the page's own doc comment
   (lines 19-41) to describe the new modal-based flow and remove the now-resolved
   "deviation, flagged" paragraph (§7.2's original intent is now implemented as designed).
2. `WeeklyAvailabilityPage.module.css`: remove `.summaryCard`/`.summaryRow`/`.summaryDay`/
   `.summaryHours`/`.summaryOff`/`.editLink`; no new classes are strictly required (`Button`
   and `Modal` bring their own styling), but a header-row class may already exist
   (`.sectionHeaderRow`) and can be reused.
3. No backend work. No changes to `WeeklyCalendarGrid.tsx`, `WorkingHoursForm.tsx`, or
   `Modal.tsx`.
4. `pronto-documentation`: update whichever package doc currently describes
   `WeeklyAvailabilityPage`'s composition (per the doc-comment cross-reference to
   `professional-weekly-calendar-design.md` §7.2) to reflect that the modal-based edit flow
   is now implemented as originally designed, not a pending deviation.
