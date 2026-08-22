import { useEffect, useState } from 'react';

export interface UseCountdownResult {
  /** Whole seconds left, floored at 0. `null` when there is no deadline to count to. */
  remainingSeconds: number | null;
  /** `m:ss`, or `null` when there is no deadline. */
  label: string | null;
  /** True once the deadline has passed. Display-only — never treat it as a state transition. */
  isElapsed: boolean;
}

function format(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/**
 * Second-resolution countdown to an absolute ISO deadline, always recomputed from `Date.now()`
 * rather than decremented — so it survives a remount, a refresh and a backgrounded tab by
 * construction. The same principle `useEtaCountdown` uses, at the resolution a two-minute window
 * needs (that hook reports whole minutes, which would show "2 דקות" for most of the window and
 * then jump).
 *
 * **Presentation only.** The backend owns every SOS deadline and enforces it on the next read
 * regardless of what this shows; a client that hits 0:00 must still let the server say so.
 */
export function useCountdown(deadline: string | null): UseCountdownResult {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!deadline) {
      return;
    }
    setNow(Date.now());
    const intervalId = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(intervalId);
  }, [deadline]);

  if (!deadline) {
    return { remainingSeconds: null, label: null, isElapsed: false };
  }

  const remainingMs = new Date(deadline).getTime() - now;
  const remainingSeconds = Math.max(0, Math.ceil(remainingMs / 1000));
  return { remainingSeconds, label: format(remainingSeconds), isElapsed: remainingMs <= 0 };
}
