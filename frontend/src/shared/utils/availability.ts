import type { AvailableWindow } from '../api/bookings';

export interface StartTimeCandidateOptions {
  /** Chip grid, in minutes. Defaults to 30 — the same grid `WeeklyCalendarGrid` draws. */
  gridMinutes?: number;
  /**
   * Epoch-ms "now". When provided, candidates at or before it are dropped — the server
   * rejects a non-future `bookedStart` outright (`BookingsService.createOrder` step 0:
   * `400 VALIDATION_ERROR`, field `bookedStart`), so offering one is offering a chip that
   * cannot be booked. Optional, and never defaulted to `Date.now()` internally, so this
   * function stays pure and its callers stay explicit about which clock they mean.
   */
  notBeforeMs?: number;
}

/**
 * Professional weekly availability calendar feature, M6
 * (`docs/architecture/professional-weekly-calendar-design.md` §9.2.3/§7.6). Pure derivation
 * of the customer-facing booking flow's selectable start-time chips from a professional's
 * derived `AVAILABLE` windows (`GET .../available-windows?issueId=`) — replaces the retired
 * `availability_slots`-based "one row per bookable window" model.
 *
 * For each window, enumerates every `gridMinutes`-aligned instant from `window.startAt` up
 * to and including `window.endAt - defaultDurationMinutes` (the last instant that still
 * leaves a full `defaultDurationMinutes`-long job before the window closes).
 *
 * **MS4 final corrections (2026-08-20): "grid-aligned" now means snapped to the local wall
 * clock (`:00`/`:30`), not to each window's own `startAt`.** A window that opens at an
 * arbitrary instant — which is the norm, since today's first window opens at
 * `Instant.now()` (`BookingsService.getAvailableWindows`) — used to produce chips like
 * `14:02 · 14:32 · 15:02`. DESIGN_SYSTEM.md §46's own example is `10:00 / 11:30 / 14:00 /
 * 16:30`: whole clock times. The first candidate in each window is therefore rounded *up*
 * to the next grid boundary (never down — that would offer a start time before the
 * professional is actually free), which costs at most one sub-grid chip per window.
 *
 * Grid alignment is computed on local wall-clock minutes rather than epoch arithmetic, so
 * it stays correct in a half-hour-offset timezone. Within a single window, subsequent
 * candidates step by a fixed `gridMinutes` of elapsed time; a window spanning a DST
 * transition would drift off the wall-clock grid after the jump — not corrected, as windows
 * are bounded by a professional's working day.
 *
 * Pure, no I/O, no component/JSX coupling — this codebase has no frontend unit-test runner
 * configured (checked: no `*.test.ts`/`vitest`/`jest` setup anywhere under `frontend/`), so
 * none is introduced here just for this function; correctness was instead verified via live
 * API-contract testing against a real backend (see `features/booking/README.md`).
 */
export function deriveStartTimeCandidates(
  windows: AvailableWindow[],
  defaultDurationMinutes: number,
  options: StartTimeCandidateOptions = {},
): string[] {
  const { gridMinutes = 30, notBeforeMs } = options;
  const gridMs = gridMinutes * 60_000;
  const durationMs = defaultDurationMinutes * 60_000;
  const candidates: string[] = [];

  for (const window of windows) {
    const windowStartMs = new Date(window.startAt).getTime();
    const windowEndMs = new Date(window.endAt).getTime();
    const lastStartMs = windowEndMs - durationMs;
    const firstStartMs = ceilToClockGrid(windowStartMs, gridMinutes);

    for (let candidateMs = firstStartMs; candidateMs <= lastStartMs; candidateMs += gridMs) {
      if (notBeforeMs !== undefined && candidateMs <= notBeforeMs) {
        continue;
      }
      candidates.push(new Date(candidateMs).toISOString());
    }
  }

  return candidates;
}

/** Smallest `gridMinutes`-aligned wall-clock instant that is `>= ms` (e.g. 14:02:07 → 14:30). */
function ceilToClockGrid(ms: number, gridMinutes: number): number {
  const snapped = new Date(ms);
  snapped.setSeconds(0, 0);
  const remainder = snapped.getMinutes() % gridMinutes;
  let result = snapped.getTime();
  if (remainder !== 0) {
    result += (gridMinutes - remainder) * 60_000;
  }
  // Only reachable when `ms` carried seconds/milliseconds past an already-aligned minute
  // (e.g. 14:00:30) — `setSeconds(0, 0)` moved it backwards, so step one grid forward.
  if (result < ms) {
    result += gridMinutes * 60_000;
  }
  return result;
}
