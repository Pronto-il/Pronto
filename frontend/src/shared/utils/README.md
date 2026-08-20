# shared/utils

## Purpose
Small, pure, framework-agnostic utility functions shared across more than one feature —
no React, no JSX, no component/hook coupling, no I/O. This is the file-level equivalent of
`backend/.../common` for pure logic that doesn't belong to any single feature folder.

## Responsibilities
- Formatting helpers for values every screen that renders backend data needs consistently
  (dates/times in Hebrew, relative age labels).
- Pure derivation functions that turn a backend response shape into UI-ready data, when the
  transformation is reused by more than one component and has no side effects worth
  wrapping in a hook.

This folder is deliberately narrow — a function only belongs here once at least one other
consumer needs it (or is expected to soon); a one-off helper stays local to its component
file until that happens, per this codebase's existing convention.

## Structure
- `formatDateTime.ts` — Hebrew date/time formatting shared by every screen that renders an
  ISO timestamp (`features/booking`'s `StartTimePicker`/`BookingSummary`/
  `OrderTrackingPage`/`MyOrdersPage`, `features/dashboard`'s `IncomingRequestCard`,
  `features/professionals`'s `ReviewList`):
  - `formatDateLabel(isoString)` — `"היום"`/`"מחר"` for the next two days, otherwise a full
    Hebrew weekday/day/month label (e.g. `"יום ראשון, 16 באוגוסט"`).
  - `formatTimeLabel(isoString)` — `"14:30"`-style, `Intl.DateTimeFormat('he-IL', ...)`.
  - `formatDateTimeLabel(isoString)` — the two combined (`"יום ראשון, 16 באוגוסט · 14:30"`).
  - `formatRelativeAgeLabel(isoString)` — **added Frontend Milestone 8 (2026-08-18)**, for
    `features/professionals/ReviewList.tsx`'s review-card age display (DESIGN_SYSTEM.md
    §45). Extends this file's existing `"היום"`/`"מחר"` precedent onto the past axis
    (`"היום"`/`"אתמול"` for the two most recent days), then falls back to day/month/year
    counts. **Fixed the same milestone** (flagged by QA, small/low-risk): the singular
    month/year boundary produced grammatically incorrect Hebrew (`"1 חודשים"`/`"1 שנים"`) —
    corrected to the no-numeral forms `"חודש"`/`"שנה"`, matching this file's existing
    no-numeral `"היום"`/`"אתמול"` convention.
  - `formatMonthYearLabel(isoString)` — **added MS4 final corrections (2026-08-20)**, e.g.
    `"אוגוסט 2026"`, for `features/professionals`'s profile "בפרונטו מאז" trust stat
    (DESIGN_SYSTEM.md §43/§44). Month granularity on purpose — a to-the-day join date is
    noisier and more personal than the signal needs.
  - `dateKey(isoString)` — a local-time (not UTC-truncated) calendar-day grouping key,
    consumed by `StartTimePicker`'s date-chip-row grouping logic.
- `hebrewText.ts` — **new, MS4 final corrections (2026-08-20)**.
  `formatReviewCount(count): string` — DESIGN_SYSTEM.md §31's review-count fragment with the
  singular spelled out (`"ביקורת אחת"`, not the ungrammatical `"1 ביקורות"`); same class of fix
  as `formatRelativeAgeLabel`'s `"1 חודשים"` → `"חודש"` boundary. Consumers:
  `features/professionals`'s `ProfessionalCard`/`ProfessionalProfileDisplay` and
  `features/favorites`'s `FavoriteProfessionalCard` — three call sites of the same string,
  which is what earned it a place in this folder per the "at least one other consumer" rule
  above.
- `availability.ts` — **new, professional weekly availability calendar feature, M6
  (2026-08-18)**; signature and grid behaviour revised in **MS4 final corrections
  (2026-08-20)**, see the note at the end of this entry.
  `deriveStartTimeCandidates(windows, defaultDurationMinutes, options?): string[]` — the pure
  derivation behind the customer-facing booking flow's
  start-time-picking step (see `docs/architecture/professional-weekly-calendar-design.md`
  §9.2.3/§7.6). Given a professional's derived `AVAILABLE` windows (`GET
  .../professionals/{id}/available-windows?issueId=`, `shared/api/bookings.ts`'s
  `AvailableWindow[]`), enumerates every `gridMinutes`-aligned ISO instant from each
  window's own `startAt` up to and including `endAt - defaultDurationMinutes` (the last
  instant that still leaves a full job before the window closes) — mirrors this same
  feature's already-established 30-minute grid convention
  (`features/dashboard/WeeklyCalendarGrid.tsx`'s gridlines), not a new number invented for
  this sub-feature. Pure, no I/O, no component/JSX coupling. Consumers:
  `features/booking/StartTimePicker.tsx` (renamed from `SlotPicker.tsx` this same milestone)
  and `BookingFlowPage`'s draft resume-hydration.

  **Revised in MS4 final corrections (2026-08-20)**, both changes driven by a live look at the
  rendered picker:
  1. "Grid-aligned" now means snapped to the **local wall clock** (`:00`/`:30`), not to each
     window's own `startAt`. Today's first window opens at `Instant.now()`
     (`BookingsService.getAvailableWindows`), so the old rule really did produce
     `14:02 · 14:32 · 15:02` on screen — DESIGN_SYSTEM.md §46's own example is whole clock
     times. The first candidate per window rounds **up** (never down — that would offer a time
     before the professional is free), costing at most one sub-grid chip per window.
     Alignment is computed on wall-clock minutes, not epoch arithmetic, so it holds in a
     half-hour-offset timezone; a window spanning a DST transition would drift after the jump,
     which is documented in the function and left uncorrected (windows are bounded by a
     working day).
  2. The third parameter changed from positional `gridMinutes = 30` to an options object
     `{ gridMinutes?, notBeforeMs? }`. `notBeforeMs` drops candidates at or before that
     instant — the server rejects a non-future `bookedStart` outright, so such a chip is
     unbookable. It is never defaulted to `Date.now()` internally: the function stays pure and
     callers stay explicit about which clock they mean. No caller had passed the old positional
     `gridMinutes`, so nothing broke.

## Testing note (applies to every function in this folder)
No frontend unit-test runner is configured anywhere in this codebase (checked repeatedly
across milestones — no `*.test.ts`/`vitest`/`jest` setup under `frontend/`), and none has
been introduced solely for this folder's functions. Correctness for `deriveStartTimeCandidates`
was instead verified by reproducing its exact output in a standalone Node script against
real API response data from a running backend (25 candidates across 3 real derived windows,
hand-verified against each window's own bounds — `(windowMinutes − defaultDurationMinutes) /
gridMinutes + 1` per window) and by code review against the design doc's own spec text — see
`frontend/src/features/booking/README.md`'s M6 section for the full verification record.
Any future function added here should get the same treatment (a throwaway verification
script against real data, or thorough code review) until a real test runner exists.

## Interactions with other packages
- `formatDateTime.ts` and `availability.ts` are both pure — no dependency on `shared/api`
  beyond importing type shapes (`availability.ts` imports `AvailableWindow`'s type only, no
  runtime dependency on `shared/api/bookings.ts`'s HTTP functions).
- Consumed by `features/booking`, `features/dashboard`, and `features/professionals` — see
  each function's entry above for its exact consumer(s).

## Assumptions / judgment calls
- `formatDateTime.ts`'s helpers format in the browser's own local timezone
  (`new Date(isoString)`), not the fixed `Asia/Jerusalem` business timezone the backend uses
  internally for availability derivation — correct for a user physically in Israel (the
  v1.0 audience) and consistent with every other timestamp display already in this app;
  called out explicitly here (and in `WeeklyCalendarGrid.tsx`'s own doc comment) rather than
  left as a silent assumption, since this is the one place in the app where a backend
  computation is explicitly pinned to a named, non-browser-local timezone.
- This folder was created without its own `.md` doc when `formatDateTime.ts` first shipped
  (Frontend Milestone 3, 2026-08-16) — a gap not caught until this feature's closing
  documentation pass flagged it (per this project's "every package/module gets a named
  `.md` doc" rule). No functional impact; recorded here for the record, not to assign
  blame — `formatDateTime.ts`'s own behavior was accurately documented inline (via its own
  file-header comment) the whole time, just not in a discoverable per-folder `README.md`.

## Status
`formatDateTime.ts` implemented in **Frontend Milestone 3 — Standard booking flow
(2026-08-16)**; gained `formatRelativeAgeLabel` in **Frontend Milestone 8 (2026-08-18)**,
with the singular-boundary Hebrew grammar fix noted above. `availability.ts` is new in the
**professional weekly availability calendar feature, M6 (2026-08-18, final implementation
milestone)** — see `docs/architecture/implementation-plan.md`'s corresponding milestone
entry and `frontend/src/features/booking/README.md`'s M6 section for full detail. This
`README.md` itself is new as of this feature's closing documentation pass (2026-08-18),
closing the gap noted above.
