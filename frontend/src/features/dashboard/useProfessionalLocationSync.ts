import { useCallback, useEffect, useRef, useState } from 'react';
import { getMyLocationStatus, updateMyLocation, ApiError } from '../../shared/api';
import type { ProfessionalLocationStatusResponse } from '../../shared/api';
import {
  getCurrentPosition,
  isGeolocationSupported,
  readPermissionState,
  type GeolocationFailureReason,
} from '../../shared/lib/geolocation';

/**
 * Keeps the professional's current device position fresh on the backend, for as long as they
 * are inside `/pro/*`.
 *
 * ## What "fresh" costs, and why this is a snapshot strategy
 *
 * Every routing decision the platform makes about this professional — their ETA on a customer's
 * card, whether they are inside an SOS radius, the arrival estimate on an order they are driving
 * to — reads one row, and that row is only usable while it is younger than
 * `pronto.location.professional-freshness` (ten minutes by default, which this hook reads off
 * the server rather than hardcoding).
 *
 * The naive way to keep it fresh is `watchPosition`, and it is the wrong one here. This is a web
 * application: browsers throttle or suspend timers and geolocation in background tabs, and stop
 * entirely when the tab is closed — so a continuous watch buys unreliable coverage at a real
 * battery cost, and the coverage it does buy disappears exactly when the professional puts their
 * phone in their pocket. So this takes **snapshots at the moments that actually matter**:
 *
 * - when the dashboard mounts (they have opened the app);
 * - when the tab becomes visible again (they have come back to it);
 * - when the browser reports the connection has returned;
 * - on a modest interval while the tab is visible, sized from the server's own freshness window
 *   rather than from a number invented here.
 *
 * Callers can also force a refresh at a decision point — {@link refresh} is exposed for exactly
 * that, and the arrival flow takes its own, stricter reading rather than relying on this one.
 *
 * ## Failure is a state, not an exception
 *
 * A professional who denies location permission is in a completely normal state: they can still
 * browse, still accept scheduled work, still be listed. What they cannot do is appear in SOS
 * matching or show an ETA. This hook therefore never throws and never blocks — it reports
 * {@link ProfessionalLocationSync.failureReason} and lets the UI explain the consequence.
 */
export interface ProfessionalLocationSync {
  /** The server's view of the stored position, or `null` before the first answer. */
  status: ProfessionalLocationStatusResponse | null;
  /** Why the last device-side attempt failed, or `null` if it did not. */
  failureReason: GeolocationFailureReason | null;
  /** A Hebrew sentence for {@link failureReason}, or `null`. */
  failureMessage: string | null;
  /** Whether a reading is in flight — for a button's loading state, never a page-blocking spinner. */
  isRefreshing: boolean;
  /** The browser's permission state, when it will tell us without prompting. */
  permission: PermissionState | 'unknown';
  /** Take a reading now. Safe to call repeatedly; concurrent calls collapse into one. */
  refresh: () => Promise<void>;
}

/**
 * Fraction of the server's freshness window at which a routine refresh is scheduled.
 *
 * Comfortably inside it rather than at its edge: a refresh that fires exactly when the previous
 * reading expires leaves a window in which the professional is briefly unroutable every cycle,
 * and a GPS fix can take several seconds to arrive. At the ten-minute default this is every four
 * minutes.
 */
const REFRESH_FRACTION = 0.4;

/** Floor and ceiling on that interval, so a misconfigured server cannot produce a busy loop. */
const MIN_REFRESH_MS = 60_000;
const MAX_REFRESH_MS = 15 * 60_000;

export function useProfessionalLocationSync(enabled: boolean): ProfessionalLocationSync {
  const [status, setStatus] = useState<ProfessionalLocationStatusResponse | null>(null);
  const [failureReason, setFailureReason] = useState<GeolocationFailureReason | null>(null);
  const [failureMessage, setFailureMessage] = useState<string | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [permission, setPermission] = useState<PermissionState | 'unknown'>('unknown');

  /**
   * Guards against overlapping reads. Two triggers can fire close together — a tab becoming
   * visible while the interval is due, say — and a second geolocation call while the first is
   * still waiting on the device buys nothing and doubles the battery cost.
   */
  const inFlight = useRef(false);
  /** So an answer arriving after unmount does not set state on a dead component. */
  const mounted = useRef(true);

  const refresh = useCallback(async () => {
    if (!enabled || inFlight.current) {
      return;
    }
    if (!isGeolocationSupported()) {
      setFailureReason('UNSUPPORTED');
      setFailureMessage('הדפדפן הזה לא תומך בשירותי מיקום.');
      return;
    }

    inFlight.current = true;
    setIsRefreshing(true);
    try {
      // A routine refresh, not arrival evidence: a fix the browser took within the last minute
      // is perfectly good and free, and high accuracy is not worth the battery here.
      const outcome = await getCurrentPosition({ highAccuracy: false, maximumAgeMs: 60_000 });
      if (!mounted.current) {
        return;
      }
      if (!outcome.ok) {
        setFailureReason(outcome.reason);
        setFailureMessage(outcome.message);
        // Keep whatever the server last told us: a failed read does not mean the stored
        // position has expired, and blanking it would flash "no location" at a professional
        // whose position is actually fine.
        return;
      }
      setFailureReason(null);
      setFailureMessage(null);
      const updated = await updateMyLocation(outcome.fix);
      if (mounted.current) {
        setStatus(updated);
      }
    } catch (err) {
      // A network blip or a server refusal. Deliberately quiet: this runs in the background and
      // is not something the professional asked for, so it must not raise a toast or an error
      // banner. The next trigger will try again.
      if (mounted.current && err instanceof ApiError) {
        setStatus((previous) => previous);
      }
    } finally {
      inFlight.current = false;
      if (mounted.current) {
        setIsRefreshing(false);
      }
    }
  }, [enabled]);

  // Initial state: what does the server already think, and has the browser already decided?
  useEffect(() => {
    mounted.current = true;
    if (!enabled) {
      return;
    }
    void readPermissionState().then((state) => {
      if (mounted.current) {
        setPermission(state);
      }
    });
    void getMyLocationStatus()
      .then((current) => {
        if (mounted.current) {
          setStatus(current);
        }
      })
      // Absent status is not an error worth surfacing -- the refresh below will produce one.
      .catch(() => undefined);
    void refresh();
    return () => {
      mounted.current = false;
    };
  }, [enabled, refresh]);

  // The periodic refresh, plus the two event-driven ones. All three are deliberately tied to
  // the tab being visible: a hidden tab's timers are throttled to the point of uselessness, and
  // a professional who is not looking at the app is not somebody whose ETA anyone is reading.
  useEffect(() => {
    if (!enabled) {
      return;
    }
    const windowSeconds = status?.staleAfterSeconds ?? 600;
    const intervalMs = Math.min(
      MAX_REFRESH_MS,
      Math.max(MIN_REFRESH_MS, windowSeconds * 1000 * REFRESH_FRACTION),
    );

    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        void refresh();
      }
    }, intervalMs);

    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        void refresh();
      }
    };
    const onOnline = () => void refresh();

    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('online', onOnline);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('online', onOnline);
    };
  }, [enabled, refresh, status?.staleAfterSeconds]);

  return { status, failureReason, failureMessage, isRefreshing, permission, refresh };
}
