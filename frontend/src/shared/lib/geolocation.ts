/**
 * Browser geolocation, wrapped so that every failure mode has a name and a Hebrew sentence.
 *
 * ## Why this exists rather than calling `navigator.geolocation` at each site
 *
 * The raw API fails in five genuinely different ways — unsupported, denied, unavailable,
 * timed out, and "answered, but with a fix too coarse to be worth anything" — and the last
 * one is not an error at all as far as the browser is concerned. Handling that at each call
 * site produces five slightly different behaviours and, in practice, at least one spinner
 * that never stops. Every geolocation read in this application goes through
 * {@link getCurrentPosition}, which always settles, always within a bounded time, and always
 * with a `reason` a caller can branch on and a `message` it can show.
 *
 * ## Foreground snapshots, not tracking
 *
 * Deliberately `getCurrentPosition`, never `watchPosition`. This is a web application, and
 * browsers do not reliably deliver background position updates — a `watchPosition` that keeps
 * running is mostly a battery cost that stops the moment the tab is hidden. The whole design
 * is built around taking a fresh reading at the moments that matter (see
 * `useProfessionalLocationSync`), which is also the shape a native app can later improve on
 * without redesigning the backend.
 */

/** Why a position could not be obtained. Stable codes; the UI branches on these. */
export type GeolocationFailureReason =
  /** The browser has no geolocation API at all (or the page is not in a secure context). */
  | 'UNSUPPORTED'
  /** The user refused, or has previously refused and the browser is remembering it. */
  | 'PERMISSION_DENIED'
  /** The device tried and could not get a fix — no GPS, no wifi, airplane mode. */
  | 'UNAVAILABLE'
  /** No answer within the timeout. Common indoors and on a cold GPS start. */
  | 'TIMEOUT'
  /** A fix arrived, but its reported accuracy is too poor to be worth sending. */
  | 'INACCURATE';

export interface DeviceFix {
  latitude: number;
  longitude: number;
  /** The device's own reported horizontal accuracy, metres. Always sent — never assumed. */
  accuracyMeters: number;
  /** ISO-8601. When the *device* took the reading; the server stamps its own receive time. */
  capturedAt: string;
}

export type GeolocationOutcome =
  | { ok: true; fix: DeviceFix }
  | { ok: false; reason: GeolocationFailureReason; message: string };

export interface GetPositionOptions {
  /**
   * How long to wait before giving up, ms.
   *
   * Bounded and fairly short by default. A professional pressing a button wants an answer or
   * an explanation, and a geolocation call that has not answered in fifteen seconds is
   * usually not going to — leaving it open just produces the infinite spinner this module
   * exists to prevent.
   */
  timeoutMs?: number;
  /**
   * Whether to ask for the device's best effort (GPS rather than wifi/cell triangulation).
   * Costs battery and time; worth it for arrival verification, wasteful for a routine
   * background refresh.
   */
  highAccuracy?: boolean;
  /**
   * How old a cached browser fix may be before a fresh one is required, ms.
   *
   * `0` forces a genuinely new reading. That is the right choice for arrival verification —
   * the whole point is evidence about *now* — and the wrong one for routine refreshes, where
   * reusing a fix the browser took a minute ago is free and just as good.
   */
  maximumAgeMs?: number;
  /**
   * Reject a fix reporting worse accuracy than this, metres.
   *
   * The client-side counterpart of the server's own rule, and it does not replace it: the
   * backend re-checks, because a client can send anything. Checking here as well means the
   * professional finds out immediately and locally, rather than after a round trip.
   */
  maxAccuracyMeters?: number;
}

/**
 * Client-side accuracy floor for routine refreshes, matching the server's
 * `pronto.location.max-accuracy-meters` default. Duplicated deliberately rather than fetched:
 * the server remains authoritative, and this only avoids sending something it will reject.
 */
export const ROUTING_MAX_ACCURACY_METERS = 500;

/** The much stricter floor for arrival verification — `pronto.location.arrival-max-accuracy-meters`. */
export const ARRIVAL_MAX_ACCURACY_METERS = 100;

const MESSAGES: Record<GeolocationFailureReason, string> = {
  UNSUPPORTED: 'הדפדפן הזה לא תומך בשירותי מיקום.',
  PERMISSION_DENIED: 'הגישה למיקום נחסמה. יש לאפשר שירותי מיקום בהגדרות הדפדפן ולנסות שוב.',
  UNAVAILABLE: 'לא הצלחנו לקבל את המיקום שלך. יש לוודא ששירותי המיקום פעילים ולנסות שוב.',
  TIMEOUT: 'איתור המיקום נמשך זמן רב מדי. יש לנסות שוב, רצוי בחוץ או ליד חלון.',
  INACCURATE: 'המיקום שהתקבל אינו מדויק מספיק. יש להפעיל מיקום מדויק ולנסות שוב.',
};

function failure(reason: GeolocationFailureReason): GeolocationOutcome {
  return { ok: false, reason, message: MESSAGES[reason] };
}

/**
 * Is geolocation available at all? Used to decide what to offer, not what to say.
 *
 * Checks the object is actually there rather than only that the key exists: a page served over
 * plain HTTP, and some privacy-hardened browser configurations, leave `navigator.geolocation`
 * present-but-undefined, and `'geolocation' in navigator` reports `true` for both. Calling
 * through on that basis throws a `TypeError` from inside the promise executor, which is exactly
 * the unhandled path this module exists to close.
 */
export function isGeolocationSupported(): boolean {
  return typeof navigator !== 'undefined' && navigator.geolocation != null;
}

/**
 * One position reading. Always settles; never rejects.
 *
 * Resolving with a failure rather than throwing is deliberate: every caller has to handle
 * "no position" as an ordinary branch anyway — it is the common case for a denied
 * permission — and a rejected promise invites a `catch` that swallows the reason and leaves
 * the UI in whatever state it was in.
 */
export function getCurrentPosition(options: GetPositionOptions = {}): Promise<GeolocationOutcome> {
  const {
    timeoutMs = 15_000,
    highAccuracy = true,
    maximumAgeMs = 60_000,
    maxAccuracyMeters = ROUTING_MAX_ACCURACY_METERS,
  } = options;

  if (!isGeolocationSupported()) {
    return Promise.resolve(failure('UNSUPPORTED'));
  }

  return new Promise((resolve) => {
    // The browser's own timeout is honoured, but a belt-and-braces timer runs too: some
    // mobile browsers have historically never invoked either callback when the permission
    // prompt is dismissed rather than answered, which is exactly the infinite-spinner case.
    let settled = false;
    const settle = (outcome: GeolocationOutcome) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(fallbackTimer);
      resolve(outcome);
    };
    const fallbackTimer = window.setTimeout(() => settle(failure('TIMEOUT')), timeoutMs + 2_000);

    navigator.geolocation.getCurrentPosition(
      (position) => {
        const { latitude, longitude, accuracy } = position.coords;
        // `accuracy` is required by the spec, but a device reporting a nonsensical value
        // (zero, negative, NaN) would be rejected by the server anyway -- treat it as no fix.
        if (!Number.isFinite(accuracy) || accuracy <= 0) {
          settle(failure('UNAVAILABLE'));
          return;
        }
        if (accuracy > maxAccuracyMeters) {
          settle(failure('INACCURATE'));
          return;
        }
        settle({
          ok: true,
          fix: {
            latitude,
            longitude,
            accuracyMeters: accuracy,
            capturedAt: new Date(position.timestamp).toISOString(),
          },
        });
      },
      (error) => {
        switch (error.code) {
          case error.PERMISSION_DENIED:
            settle(failure('PERMISSION_DENIED'));
            break;
          case error.TIMEOUT:
            settle(failure('TIMEOUT'));
            break;
          default:
            settle(failure('UNAVAILABLE'));
        }
      },
      { enableHighAccuracy: highAccuracy, timeout: timeoutMs, maximumAge: maximumAgeMs },
    );
  });
}

/**
 * A fresh, high-quality reading for arrival verification.
 *
 * Three deliberate differences from a routine refresh, all for the same reason — this fix is
 * not estimating anything, it is the entire evidence for a claim about where a person is
 * standing right now:
 *
 * - `maximumAgeMs: 0` — no cached fix, however recent.
 * - `highAccuracy: true` — ask the device for GPS, not a wifi triangulation.
 * - the strict accuracy floor, matching the server's arrival rule rather than its routing one.
 */
export function getArrivalFix(): Promise<GeolocationOutcome> {
  return getCurrentPosition({
    timeoutMs: 20_000,
    highAccuracy: true,
    maximumAgeMs: 0,
    maxAccuracyMeters: ARRIVAL_MAX_ACCURACY_METERS,
  });
}

/**
 * The current permission state, when the browser will tell us without prompting.
 *
 * Used only to decide what to *show* — an explanation of why location is needed, versus a
 * "location is blocked" recovery hint. Never used to decide whether to ask: the Permissions
 * API is not universally implemented for geolocation, and a missing answer must degrade to
 * "just try it".
 */
export async function readPermissionState(): Promise<PermissionState | 'unknown'> {
  if (typeof navigator === 'undefined' || !('permissions' in navigator)) {
    return 'unknown';
  }
  try {
    const status = await navigator.permissions.query({ name: 'geolocation' as PermissionName });
    return status.state;
  } catch {
    return 'unknown';
  }
}
