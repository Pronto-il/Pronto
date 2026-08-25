import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ARRIVAL_MAX_ACCURACY_METERS,
  getArrivalFix,
  getCurrentPosition,
  isGeolocationSupported,
  ROUTING_MAX_ACCURACY_METERS,
} from './geolocation';

/**
 * The geolocation wrapper — and specifically, the five ways it is allowed to fail.
 *
 * Every one of these is a real thing that happens to a professional standing in the street, and
 * every one of them used to be indistinguishable from the others at the call site. The property
 * being pinned down here is that {@link getCurrentPosition} **always settles, always within a
 * bounded time, and always with a reason** — the infinite spinner is the specific defect this
 * module exists to prevent, and it is the one a manual test would never reproduce.
 */

type SuccessCallback = (position: GeolocationPosition) => void;
type ErrorCallback = (error: GeolocationPositionError) => void;

/** A `GeolocationPosition`, without pulling in the whole DOM interface. */
function position(latitude: number, longitude: number, accuracy: number): GeolocationPosition {
  return {
    coords: {
      latitude,
      longitude,
      accuracy,
      altitude: null,
      altitudeAccuracy: null,
      heading: null,
      speed: null,
      toJSON: () => ({}),
    },
    timestamp: Date.UTC(2026, 7, 25, 12, 0, 0),
    toJSON: () => ({}),
  } as unknown as GeolocationPosition;
}

/** The browser's own error codes: 1 denied, 2 unavailable, 3 timeout. */
function geoError(code: 1 | 2 | 3): GeolocationPositionError {
  return {
    code,
    message: '',
    PERMISSION_DENIED: 1,
    POSITION_UNAVAILABLE: 2,
    TIMEOUT: 3,
  } as GeolocationPositionError;
}

function installGeolocation(
  impl: (success: SuccessCallback, error: ErrorCallback, options?: PositionOptions) => void,
) {
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: { getCurrentPosition: vi.fn(impl), watchPosition: vi.fn(), clearWatch: vi.fn() },
  });
}

function removeGeolocation() {
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value: undefined });
}

describe('getCurrentPosition', () => {
  afterEach(() => {
    vi.useRealTimers();
    removeGeolocation();
  });

  it('returns the fix, its accuracy and a capture timestamp on success', async () => {
    installGeolocation((success) => success(position(32.0853, 34.7818, 12)));

    const outcome = await getCurrentPosition();

    expect(outcome.ok).toBe(true);
    if (!outcome.ok) return;
    expect(outcome.fix.latitude).toBe(32.0853);
    expect(outcome.fix.longitude).toBe(34.7818);
    // Accuracy is always carried: the server cannot quality-check a fix that does not report it.
    expect(outcome.fix.accuracyMeters).toBe(12);
    expect(outcome.fix.capturedAt).toBe('2026-08-25T12:00:00.000Z');
  });

  it('reports a denied permission as its own reason, with a recovery hint', async () => {
    installGeolocation((_success, error) => error(geoError(1)));

    const outcome = await getCurrentPosition();

    expect(outcome).toMatchObject({ ok: false, reason: 'PERMISSION_DENIED' });
    if (outcome.ok) return;
    // The message has to say what to do, not just what went wrong.
    expect(outcome.message).toContain('הגדרות הדפדפן');
  });

  it('reports a timeout separately from an unavailable position', async () => {
    installGeolocation((_success, error) => error(geoError(3)));
    await expect(getCurrentPosition()).resolves.toMatchObject({ ok: false, reason: 'TIMEOUT' });

    installGeolocation((_success, error) => error(geoError(2)));
    await expect(getCurrentPosition()).resolves.toMatchObject({ ok: false, reason: 'UNAVAILABLE' });
  });

  it('says so plainly when the browser has no geolocation at all', async () => {
    removeGeolocation();

    expect(isGeolocationSupported()).toBe(false);
    await expect(getCurrentPosition()).resolves.toMatchObject({ ok: false, reason: 'UNSUPPORTED' });
  });

  /**
   * The failure the browser does not consider a failure. A wifi/IP triangulation answers
   * successfully with a several-kilometre radius, and sending that would have the server reject
   * it — or worse, accept it and route from it.
   */
  it('rejects a fix whose reported accuracy is worse than the caller allows', async () => {
    installGeolocation((success) =>
      success(position(32.0853, 34.7818, ROUTING_MAX_ACCURACY_METERS + 1)),
    );

    await expect(getCurrentPosition()).resolves.toMatchObject({ ok: false, reason: 'INACCURATE' });
  });

  it('accepts a fix exactly at the accuracy limit', async () => {
    installGeolocation((success) => success(position(32.0853, 34.7818, ROUTING_MAX_ACCURACY_METERS)));

    await expect(getCurrentPosition()).resolves.toMatchObject({ ok: true });
  });

  it('treats a nonsensical accuracy figure as no fix rather than a perfect one', async () => {
    installGeolocation((success) => success(position(32.0853, 34.7818, 0)));

    await expect(getCurrentPosition()).resolves.toMatchObject({ ok: false, reason: 'UNAVAILABLE' });
  });

  /**
   * **The infinite spinner.** Some mobile browsers have historically invoked neither callback
   * when a permission prompt is dismissed rather than answered. Without the belt-and-braces
   * timer this promise would never settle and the professional would be stuck watching a button
   * spin forever.
   */
  it('settles even when the browser invokes neither callback', async () => {
    vi.useFakeTimers();
    installGeolocation(() => {
      /* deliberately silent — the browser never answers */
    });

    const pending = getCurrentPosition({ timeoutMs: 1_000 });
    await vi.advanceTimersByTimeAsync(4_000);

    await expect(pending).resolves.toMatchObject({ ok: false, reason: 'TIMEOUT' });
  });

  it('never rejects, so no caller has to wrap it in a try/catch that swallows the reason', async () => {
    installGeolocation((_success, error) => error(geoError(1)));

    // If this ever rejected, the assertion below would not run at all.
    await expect(getCurrentPosition()).resolves.toBeDefined();
  });
});

describe('getArrivalFix', () => {
  afterEach(() => {
    removeGeolocation();
  });

  /**
   * Arrival is held to a much stricter bar than routing, and for a different reason: routing
   * asks "roughly where are you", arrival asks "are you at this door". A fix whose own error
   * circle is wider than the geofence cannot answer the second question.
   */
  it('rejects a fix that routing would happily accept', async () => {
    const betweenTheTwoBars = ARRIVAL_MAX_ACCURACY_METERS + 50;
    expect(betweenTheTwoBars).toBeLessThan(ROUTING_MAX_ACCURACY_METERS);
    installGeolocation((success) => success(position(32.077, 34.7739, betweenTheTwoBars)));

    await expect(getCurrentPosition()).resolves.toMatchObject({ ok: true });
    await expect(getArrivalFix()).resolves.toMatchObject({ ok: false, reason: 'INACCURATE' });
  });

  it('demands a genuinely new reading rather than a cached one', async () => {
    let seenOptions: PositionOptions | undefined;
    installGeolocation((success, _error, options) => {
      seenOptions = options;
      success(position(32.077, 34.7739, 10));
    });

    await getArrivalFix();

    // maximumAge: 0 is the difference between evidence about now and a fix taken at the address
    // earlier in the day.
    expect(seenOptions?.maximumAge).toBe(0);
    expect(seenOptions?.enableHighAccuracy).toBe(true);
  });
});

describe('routine refreshes', () => {
  beforeEach(() => {
    installGeolocation((success, _error, options) => {
      expect(options?.enableHighAccuracy).toBe(false);
      success(position(32.0853, 34.7818, 30));
    });
  });

  afterEach(() => {
    removeGeolocation();
  });

  it('allows a recent cached fix, because a background refresh is not evidence', async () => {
    await expect(
      getCurrentPosition({ highAccuracy: false, maximumAgeMs: 60_000 }),
    ).resolves.toMatchObject({ ok: true });
  });
});
