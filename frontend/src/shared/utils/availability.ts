import type { AvailableWindow } from '../api/bookings';

/**
 * Professional weekly availability calendar feature, M6
 * (`docs/architecture/professional-weekly-calendar-design.md` §9.2.3/§7.6). Pure derivation
 * of the customer-facing booking flow's selectable start-time chips from a professional's
 * derived `AVAILABLE` windows (`GET .../available-windows?issueId=`) — replaces the retired
 * `availability_slots`-based "one row per bookable window" model.
 *
 * For each window, enumerates every `gridMinutes`-aligned instant starting at
 * `window.startAt` up to and including `window.endAt - defaultDurationMinutes` (the last
 * instant that still leaves a full `defaultDurationMinutes`-long job before the window
 * closes). "Grid-aligned" here means relative to each window's own `startAt` (which is
 * itself an exact, non-rounded boundary — design §5's "grid precision" note), not snapped to
 * absolute clock times — mirrors this design's already-established 30-minute grid
 * convention (`gridMinutes` defaults to 30, same as `WeeklyCalendarGrid`'s gridlines).
 *
 * Pure, no I/O, no component/JSX coupling — this codebase has no frontend unit-test runner
 * configured (checked: no `*.test.ts`/`vitest`/`jest` setup anywhere under `frontend/`), so
 * none is introduced here just for this function; correctness was instead verified via live
 * API-contract testing against a real backend (see `features/booking/README.md`).
 */
export function deriveStartTimeCandidates(
  windows: AvailableWindow[],
  defaultDurationMinutes: number,
  gridMinutes = 30,
): string[] {
  const gridMs = gridMinutes * 60_000;
  const durationMs = defaultDurationMinutes * 60_000;
  const candidates: string[] = [];

  for (const window of windows) {
    const windowStartMs = new Date(window.startAt).getTime();
    const windowEndMs = new Date(window.endAt).getTime();
    const lastStartMs = windowEndMs - durationMs;

    for (let candidateMs = windowStartMs; candidateMs <= lastStartMs; candidateMs += gridMs) {
      candidates.push(new Date(candidateMs).toISOString());
    }
  }

  return candidates;
}
