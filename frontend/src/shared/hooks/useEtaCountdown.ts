import { useEffect, useState } from 'react';

export interface UseEtaCountdownResult {
  remainingMinutes: number | null;
  /** True once the countdown has reached zero — display-only; never fabricates a
   *  `COMPLETED` transition (that only ever comes from the backend's own polled status). */
  isArriving: boolean;
}

/**
 * Pure presentational hook: given the order's persisted `expectedArrivalAt` absolute
 * timestamp, ticks every second and always recomputes `remainingMinutes` from
 * `Date.now()` vs. that timestamp — never a locally-decremented counter. This is what
 * makes the countdown survive a remount/page refresh by construction (the source of truth
 * is the absolute timestamp already on the order, not any client-held countdown state).
 * Shared by `ActiveOrderIndicator` and `OrderTrackingPage`.
 */
export function useEtaCountdown(expectedArrivalAt: string | null): UseEtaCountdownResult {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!expectedArrivalAt) {
      return;
    }
    const intervalId = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(intervalId);
  }, [expectedArrivalAt]);

  if (!expectedArrivalAt) {
    return { remainingMinutes: null, isArriving: false };
  }

  const remainingMs = new Date(expectedArrivalAt).getTime() - now;
  const remainingMinutes = Math.max(0, Math.ceil(remainingMs / 60000));
  return { remainingMinutes, isArriving: remainingMs <= 0 };
}
