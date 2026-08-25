import { afterEach, describe, expect, it, vi } from 'vitest';
import { performArrival, ARRIVAL_ERROR_MESSAGES } from './arrivalAction';
import { ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';

/**
 * The `הגעתי` flow, from the professional's side.
 *
 * The two properties worth pinning down are both about what must *not* happen. A device-side
 * failure must never reach the server — sending a stale or coarse fix would either be rejected
 * anyway or, worse, accepted — and a server-side refusal must never be flattened into a generic
 * error, because the four refusals call for four genuinely different next actions.
 */

type SuccessCallback = (position: GeolocationPosition) => void;
type ErrorCallback = (error: GeolocationPositionError) => void;

function position(accuracy: number): GeolocationPosition {
  return {
    coords: {
      latitude: 32.077,
      longitude: 34.7739,
      accuracy,
      altitude: null,
      altitudeAccuracy: null,
      heading: null,
      speed: null,
      toJSON: () => ({}),
    },
    timestamp: Date.now(),
    toJSON: () => ({}),
  } as unknown as GeolocationPosition;
}

function installGeolocation(impl: (success: SuccessCallback, error: ErrorCallback) => void) {
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: { getCurrentPosition: vi.fn(impl), watchPosition: vi.fn(), clearWatch: vi.fn() },
  });
}

function apiError(code: string): ApiError {
  return new ApiError(code, 'refused', null, 422);
}

describe('performArrival', () => {
  afterEach(() => {
    Object.defineProperty(navigator, 'geolocation', { configurable: true, value: undefined });
  });

  it('submits the device fix and reports success', async () => {
    installGeolocation((success) => success(position(12)));
    const submit = vi.fn().mockResolvedValue({});

    const outcome = await performArrival(submit);

    expect(outcome).toEqual({ ok: true });
    expect(submit).toHaveBeenCalledTimes(1);
    const fix = submit.mock.calls[0][0];
    expect(fix.latitude).toBe(32.077);
    expect(fix.accuracyMeters).toBe(12);
    expect(fix.capturedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  /**
   * A denied permission never reaches the network. Sending nothing is not merely an optimisation:
   * the order stays untouched, so the professional can simply enable location and press again.
   */
  it('does not call the server when the browser refuses to give a position', async () => {
    installGeolocation((_success, error) =>
      error({ code: 1, message: '', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as GeolocationPositionError),
    );
    const submit = vi.fn();

    const outcome = await performArrival(submit);

    expect(submit).not.toHaveBeenCalled();
    expect(outcome).toMatchObject({ ok: false, stage: 'device' });
    if (outcome.ok) return;
    expect(outcome.message).toContain('הגדרות הדפדפן');
  });

  /** Likewise for a fix the arrival bar rejects — it would be refused server-side anyway. */
  it('does not call the server with a fix too coarse to verify arrival', async () => {
    installGeolocation((success) => success(position(400)));
    const submit = vi.fn();

    const outcome = await performArrival(submit);

    expect(submit).not.toHaveBeenCalled();
    expect(outcome).toMatchObject({ ok: false, stage: 'device' });
  });

  /**
   * The heart of it. "You are not there" and "your fix is not good enough yet" need opposite
   * advice, and a client that conflated them would invite a professional to retry indefinitely
   * from the wrong place — or to walk closer when standing still for ten seconds was the answer.
   */
  it('tells the professional to move closer when the backend says they are out of range', async () => {
    installGeolocation((success) => success(position(12)));
    const submit = vi.fn().mockRejectedValue(apiError('ARRIVAL_OUT_OF_RANGE'));

    const outcome = await performArrival(submit);

    expect(outcome).toMatchObject({ ok: false, stage: 'server' });
    if (outcome.ok) return;
    expect(outcome.message).toContain('להתקרב לכתובת');
    expect(outcome.message).not.toContain('מדויק');
  });

  it('tells the professional to try again in a moment when the fix quality was the problem', async () => {
    installGeolocation((success) => success(position(12)));
    const submit = vi.fn().mockRejectedValue(apiError('LOCATION_QUALITY_INSUFFICIENT'));

    const outcome = await performArrival(submit);

    if (outcome.ok) return;
    expect(outcome.message).toContain('מדויק');
    expect(outcome.message).not.toContain('להתקרב');
  });

  it('explains that an order with no verified address cannot be confirmed automatically', async () => {
    installGeolocation((success) => success(position(12)));
    const submit = vi.fn().mockRejectedValue(apiError('ORDER_DESTINATION_UNKNOWN'));

    const outcome = await performArrival(submit);

    if (outcome.ok) return;
    // And says the job can still be finished -- this refusal must not read as a dead end.
    expect(outcome.message).toContain('לסיים את העבודה');
  });

  it('falls back to the generic message for a code it does not know', async () => {
    installGeolocation((success) => success(position(12)));
    const submit = vi.fn().mockRejectedValue(apiError('SOMETHING_NEW'));

    const outcome = await performArrival(submit);

    if (outcome.ok) return;
    expect(outcome.message).toBe(GENERIC_ERROR_MESSAGE);
  });

  it('falls back to the generic message for a non-API failure', async () => {
    installGeolocation((success) => success(position(12)));
    const submit = vi.fn().mockRejectedValue(new TypeError('network down'));

    const outcome = await performArrival(submit);

    if (outcome.ok) return;
    expect(outcome.message).toBe(GENERIC_ERROR_MESSAGE);
  });

  /** Four refusals, four distinct sentences — the whole reason the backend gives four codes. */
  it('gives every known refusal its own wording', () => {
    const messages = Object.values(ARRIVAL_ERROR_MESSAGES);

    expect(new Set(messages).size).toBe(messages.length);
    expect(messages.every((message) => message.length > 0)).toBe(true);
  });
});
