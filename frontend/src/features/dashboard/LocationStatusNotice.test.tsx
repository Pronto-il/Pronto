import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { LocationStatusNotice } from './LocationStatusNotice';

/**
 * What a professional is told when Pronto cannot see where they are.
 *
 * The rule under test is not "an error is shown" — it is that the notice **names the
 * consequence**. "Location services are disabled" is a true sentence that gives a professional
 * no reason to act; "customers will not see an arrival time for you and urgent jobs will not
 * reach you" is the fact that makes granting permission worth doing. The tests below assert the
 * consequence is present, and that the notice stays out of the way entirely when there is nothing
 * to act on.
 */

const getMyLocationStatus = vi.fn();
const updateMyLocation = vi.fn();

vi.mock('../../shared/api', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api')>('../../shared/api');
  return {
    ...actual,
    getMyLocationStatus: (...args: unknown[]) => getMyLocationStatus(...args),
    updateMyLocation: (...args: unknown[]) => updateMyLocation(...args),
  };
});

type SuccessCallback = (position: GeolocationPosition) => void;
type ErrorCallback = (error: GeolocationPositionError) => void;

function position(accuracy: number): GeolocationPosition {
  return {
    coords: {
      latitude: 32.0853,
      longitude: 34.7818,
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

const denied = { code: 1, message: '', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as GeolocationPositionError;

describe('LocationStatusNotice', () => {
  beforeEach(() => {
    getMyLocationStatus.mockReset();
    updateMyLocation.mockReset();
    // The Permissions API is optional; absence must degrade to "just try it", not to a crash.
    Object.defineProperty(navigator, 'permissions', { configurable: true, value: undefined });
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'geolocation', { configurable: true, value: undefined });
  });

  it('renders nothing at all once the position is usable', async () => {
    const usable = { usable: true, updatedAt: new Date().toISOString(), accuracyMeters: 12, reason: null, staleAfterSeconds: 600 };
    getMyLocationStatus.mockResolvedValue(usable);
    updateMyLocation.mockResolvedValue(usable);
    installGeolocation((success) => success(position(12)));

    const { container } = render(<LocationStatusNotice />);

    await waitFor(() => expect(updateMyLocation).toHaveBeenCalled());
    // A permanent "location: OK" chip on every screen would be noise for a state nobody can act
    // on, so the healthy case is silence.
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it('explains what a denied permission costs, not merely that it is denied', async () => {
    getMyLocationStatus.mockResolvedValue({ usable: false, updatedAt: null, accuracyMeters: null, reason: 'PROFESSIONAL_LOCATION_MISSING', staleAfterSeconds: 600 });
    installGeolocation((_success, error) => error(denied));

    render(<LocationStatusNotice />);

    expect(await screen.findByText('שירותי המיקום חסומים')).toBeInTheDocument();
    const body = screen.getByText(/זמן הגעה/);
    // Both named consequences, in the professional's own economic terms.
    expect(body.textContent).toContain('זמן הגעה');
    expect(body.textContent).toContain('SOS');
  });

  /**
   * No retry button while the browser is remembering a refusal: pressing it cannot help, and a
   * button that reliably does nothing is worse than no button.
   */
  it('offers no retry for a denied permission', async () => {
    getMyLocationStatus.mockResolvedValue({ usable: false, updatedAt: null, accuracyMeters: null, reason: 'PROFESSIONAL_LOCATION_MISSING', staleAfterSeconds: 600 });
    installGeolocation((_success, error) => error(denied));

    render(<LocationStatusNotice />);

    await screen.findByText('שירותי המיקום חסומים');
    expect(screen.queryByRole('button', { name: 'נסה שוב' })).not.toBeInTheDocument();
  });

  /** A coarse indoor fix is recoverable, so this one does offer a retry — and says how. */
  it('offers a retry, and practical advice, for a fix that is merely too coarse', async () => {
    getMyLocationStatus.mockResolvedValue({ usable: false, updatedAt: null, accuracyMeters: null, reason: 'PROFESSIONAL_LOCATION_MISSING', staleAfterSeconds: 600 });
    installGeolocation((success) => success(position(5_000)));

    render(<LocationStatusNotice />);

    expect(await screen.findByText('המיקום שהתקבל אינו מדויק מספיק')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'נסה שוב' })).toBeInTheDocument();
  });

  /**
   * The device is fine and the backend simply considers the stored reading too old. This is the
   * state a professional who left the app open overnight lands in, and the message has to say why
   * it matters rather than just that it happened.
   */
  it('explains a stale stored position in terms of missed SOS calls', async () => {
    const stale = { usable: false, updatedAt: new Date().toISOString(), accuracyMeters: 20, reason: 'PROFESSIONAL_LOCATION_STALE', staleAfterSeconds: 600 };
    getMyLocationStatus.mockResolvedValue(stale);
    updateMyLocation.mockResolvedValue(stale);
    installGeolocation((success) => success(position(20)));

    render(<LocationStatusNotice />);

    expect(await screen.findByText('המיקום שלך אינו עדכני')).toBeInTheDocument();
    expect(screen.getByText(/קריאות SOS/)).toBeInTheDocument();
  });

  it('says plainly when the browser has no geolocation at all, and offers no false hope', async () => {
    getMyLocationStatus.mockResolvedValue({ usable: false, updatedAt: null, accuracyMeters: null, reason: 'PROFESSIONAL_LOCATION_MISSING', staleAfterSeconds: 600 });
    Object.defineProperty(navigator, 'geolocation', { configurable: true, value: undefined });

    render(<LocationStatusNotice />);

    expect(await screen.findByText('הדפדפן לא תומך במיקום')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'נסה שוב' })).not.toBeInTheDocument();
  });

  /**
   * A notice that flashes on every dashboard load while a fix is being acquired would train
   * people to ignore it, which is the one outcome that makes it useless.
   */
  it('says nothing before the first answer arrives', async () => {
    getMyLocationStatus.mockReturnValue(new Promise(() => undefined));
    installGeolocation(() => undefined);

    const { container } = await act(async () => render(<LocationStatusNotice />));

    // The permission probe resolves immediately; the status fetch and the fix never do. Nothing
    // renders, which is the point -- a notice that flashes on every dashboard load while a fix is
    // acquired would train people to ignore it.
    expect(container).toBeEmptyDOMElement();
  });
});
