import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ProntoSosEntryPage from './ProntoSosEntryPage';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraft, BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import type { SavedDefaultAddress } from '../../shared/components';

/**
 * The place claim SOS activation sends, and the one it must not.
 *
 * **The Production bug.** `POST /api/sos/requests` answered
 * `VALIDATION_ERROR / serviceLatitude / "latitude and longitude are both required when a placeId
 * is supplied"` for any signed-in customer activating against their own saved address.
 *
 * The payload was built by spreading the four `service*` fields straight off the `AddressValue`.
 * That is sound for an address selected from autocomplete, which carries all four. It is not for a
 * saved default address: `GET /api/users/me` deliberately withholds its coordinates (see
 * `users.dto.DefaultAddressInfo`), so `toAddressValue` yields a `placeId` with `latitude` and
 * `longitude` both `null` — and half a place claim is the one shape `maps.SelectedPlaceValidator`
 * refuses outright.
 *
 * The server was right to refuse it. A place id it cannot locate and coordinates nobody chose are
 * both meaningless, and the validator says so. The client's job is to send the whole fact or none
 * of it, and for a saved address the honest answer is none: `SosService` recognises the caller's
 * own default address and resolves it server-side from the `users` row, which holds the place id
 * and the coordinates together.
 *
 * These tests pin the payload rather than the error message, because the failure mode worth
 * guarding is "a partial claim went out", not "this particular string came back".
 */

const createIssue = vi.hoisted(() => vi.fn());
const createSosRequest = vi.hoisted(() => vi.fn());
const getIssue = vi.hoisted(() => vi.fn());
const getMySosRequests = vi.hoisted(() => vi.fn());

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return { ...actual, createIssue, createSosRequest, getIssue, getMySosRequests };
});

vi.mock('./ProntoSosScreen', () => ({
  default: ({ sosRequestId }: { sosRequestId: number }) => <div>live-sos-{sosRequestId}</div>,
}));

/** Picked from autocomplete: the place id and the position arrived together, from one selection. */
const SELECTED_ADDRESS = {
  city: 'תל אביב-יפו',
  street: 'דיזנגוף',
  houseNumber: '100',
  apartment: '4',
  floor: '2',
  entrance: '',
  addressNotes: '',
  placeId: 'ChIJdizengoff',
  formattedAddress: 'דיזנגוף 100, תל אביב-יפו',
  latitude: 32.0811,
  longitude: 34.7739,
};

/**
 * What `GET /api/users/me` returns for a saved address that *was* selected from autocomplete when
 * it was saved — note that there are no coordinates here, and that this is the endpoint's
 * deliberate shape rather than an omission in the fixture.
 */
const SAVED_DEFAULT_ADDRESS: SavedDefaultAddress = {
  city: 'חיפה',
  street: 'הרצל',
  houseNumber: '5',
  apartment: null,
  floor: null,
  entrance: null,
  addressNotes: null,
  placeId: 'ChIJherzl-haifa',
  formattedAddress: 'הרצל 5, חיפה',
};

function sosDraft(overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: 42,
    stage: 'BOOKING_CONFIRM',
    urgencyType: 'SOS',
    description: 'פיצוץ בצינור מים במטבח, הרצפה מוצפת',
    photos: [],
    categoryId: 1,
    clarificationAnswers: [],
    address: SELECTED_ADDRESS,
    updatedAt: '2026-09-03T08:00:00.000Z',
    ...overrides,
  } as BookingDraft;
}

let updateDraft: ReturnType<typeof vi.fn>;

function renderPage(
  options: {
    draft?: BookingDraft | null;
    token?: string | null;
    defaultAddress?: SavedDefaultAddress | null;
  } = {},
) {
  const draft = options.draft === undefined ? sosDraft() : options.draft;
  const auth = {
    user: {
      id: 42,
      fullName: 'דנה',
      role: 'CUSTOMER',
      defaultAddress: options.defaultAddress ?? null,
    },
    token: options.token === undefined ? 'jwt-abc' : options.token,
    isLoading: false,
  } as unknown as AuthContextValue;
  const bookingDraft = { draft, updateDraft, clearDraft: vi.fn() } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={['/sos-booking']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={bookingDraft}>
          <Routes>
            <Route path="/sos-booking" element={<ProntoSosEntryPage />} />
            <Route path="/login" element={<div>login-screen</div>} />
            <Route path="/issues/new" element={<div>describe-issue-screen</div>} />
          </Routes>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/**
 * The invariant `SelectedPlaceValidator` enforces, asserted against whatever actually goes over
 * the wire. Checked after a JSON round-trip because that is what the server parses: a key holding
 * `undefined` disappears there, and a test reading the in-memory object would not notice.
 */
function expectCoherentPlaceClaim(payload: unknown) {
  const wire = JSON.parse(JSON.stringify(payload)) as Record<string, unknown>;
  const hasPlaceId = wire.servicePlaceId != null;
  const hasCoordinates = wire.serviceLatitude != null && wire.serviceLongitude != null;
  expect(hasPlaceId).toBe(hasCoordinates);
  return wire;
}

beforeEach(() => {
  updateDraft = vi.fn();
  createIssue.mockReset().mockResolvedValue({ id: 777, categoryId: 1, urgencyType: 'SOS' });
  createSosRequest.mockReset().mockResolvedValue({ id: 555, issueId: 777, status: 'MATCHING' });
  getIssue.mockReset().mockResolvedValue({ id: 777, categoryId: 1, urgencyType: 'SOS' });
  getMySosRequests.mockReset().mockResolvedValue({ requests: [] });
});

afterEach(() => vi.clearAllMocks());

// ---- 1. An address selected from autocomplete ----

describe('an address selected from autocomplete sends its whole place claim', () => {
  it('sends placeId, latitude and longitude together', async () => {
    renderPage();

    await waitFor(() => expect(createSosRequest).toHaveBeenCalledTimes(1));

    const wire = expectCoherentPlaceClaim(createSosRequest.mock.calls[0][0]);
    expect(wire.servicePlaceId).toBe('ChIJdizengoff');
    expect(wire.serviceLatitude).toBe(32.0811);
    expect(wire.serviceLongitude).toBe(34.7739);
    expect(wire.serviceFormattedAddress).toBe('דיזנגוף 100, תל אביב-יפו');
  });

  it('still sends the address text alongside it', async () => {
    // The place claim supplements the address, it does not replace it — the professional reads
    // the text at the door.
    renderPage();

    await waitFor(() => expect(createSosRequest).toHaveBeenCalledTimes(1));

    expect(createSosRequest.mock.calls[0][0]).toMatchObject({
      issueId: 777,
      serviceCity: 'תל אביב-יפו',
      serviceStreet: 'דיזנגוף',
      serviceHouseNumber: '100',
      serviceApartment: '4',
      serviceFloor: '2',
    });
  });

  it('creates the SOS request successfully and reaches the live screen', async () => {
    renderPage();

    expect(await screen.findByText('live-sos-555')).toBeInTheDocument();
    expect(createSosRequest).toHaveBeenCalledTimes(1);
  });
});

// ---- 2. The reported regression: the customer's own saved address ----

describe('a saved default address activates instead of failing validation', () => {
  /** No draft address, so the page falls back to the profile default in `DEFAULT` mode. */
  function renderWithSavedAddress() {
    return renderPage({
      draft: sosDraft({ address: undefined }),
      defaultAddress: SAVED_DEFAULT_ADDRESS,
    });
  }

  it('never sends a placeId without coordinates', async () => {
    renderWithSavedAddress();

    await waitFor(() => expect(createSosRequest).toHaveBeenCalledTimes(1));

    const wire = expectCoherentPlaceClaim(createSosRequest.mock.calls[0][0]);
    // The exact payload the server rejected: a place id, and nothing to locate it with.
    expect(wire.servicePlaceId).toBeUndefined();
    expect(wire.serviceLatitude).toBeUndefined();
    expect(wire.serviceLongitude).toBeUndefined();
  });

  it('omits the place fields entirely rather than sending them as null', async () => {
    // `null` is not the same as absent here: `SelectedPlaceValidator` reads a present-but-null
    // placeId as "no claim", but a null *coordinate* alongside a real place id is the failure.
    // Omitting the keys keeps the request unambiguous.
    renderWithSavedAddress();

    await waitFor(() => expect(createSosRequest).toHaveBeenCalledTimes(1));

    const wire = JSON.parse(JSON.stringify(createSosRequest.mock.calls[0][0]));
    expect('servicePlaceId' in wire).toBe(false);
    expect('serviceLatitude' in wire).toBe(false);
    expect('serviceLongitude' in wire).toBe(false);
  });

  it('still sends the saved address text, which is what the server resolves', async () => {
    renderWithSavedAddress();

    await waitFor(() => expect(createSosRequest).toHaveBeenCalledTimes(1));

    expect(createSosRequest.mock.calls[0][0]).toMatchObject({
      issueId: 777,
      serviceCity: 'חיפה',
      serviceStreet: 'הרצל',
      serviceHouseNumber: '5',
    });
  });

  it('reaches the live screen, which is the whole point of the fix', async () => {
    renderWithSavedAddress();

    expect(await screen.findByText('live-sos-555')).toBeInTheDocument();
  });

  it('behaves identically for a legacy saved address that has no placeId at all', async () => {
    // The grandfathered case, which worked before and must keep working: it reaches the same
    // "no claim" payload by a different route.
    renderPage({
      draft: sosDraft({ address: undefined }),
      defaultAddress: { ...SAVED_DEFAULT_ADDRESS, placeId: null, formattedAddress: null },
    });

    await waitFor(() => expect(createSosRequest).toHaveBeenCalledTimes(1));

    const wire = expectCoherentPlaceClaim(createSosRequest.mock.calls[0][0]);
    expect(wire.servicePlaceId).toBeUndefined();
    expect(wire.serviceCity).toBe('חיפה');
  });
});

// ---- 3. The guest path, which reaches activation through a different door ----

describe('the guest path preserves the place claim across the auth boundary', () => {
  it('does not activate, and saves the full claim to the draft for after sign-in', async () => {
    const user = userEvent.setup();
    renderPage({ token: null });

    await user.click(await screen.findByRole('button', { name: 'התחברות והפעלת SOS' }));

    expect(await screen.findByText('login-screen')).toBeInTheDocument();
    // The SOS auth boundary: no professional's phone rings for an anonymous visitor.
    expect(createSosRequest).not.toHaveBeenCalled();

    const saved = updateDraft.mock.calls.at(-1)?.[0];
    expect(saved.address).toMatchObject({
      placeId: 'ChIJdizengoff',
      latitude: 32.0811,
      longitude: 34.7739,
    });
  });

  it('survives the localStorage round-trip the draft actually makes', async () => {
    // The draft is persisted with plain `JSON.stringify`/`JSON.parse`, so the coordinates come
    // back as numbers rather than strings — the claim stays whole and stays typed.
    const user = userEvent.setup();
    renderPage({ token: null });

    await user.click(await screen.findByRole('button', { name: 'התחברות והפעלת SOS' }));
    await screen.findByText('login-screen');

    const saved = updateDraft.mock.calls.at(-1)?.[0];
    const restored = JSON.parse(JSON.stringify(saved.address));
    expect(restored.latitude).toBe(32.0811);
    expect(typeof restored.latitude).toBe('number');
  });

  it('sends the whole claim once the guest signs in and the draft is resumed', async () => {
    // The same page, now with a token and the draft the guest left behind — which is exactly what
    // mounts after the redirect back from /login.
    renderPage({ token: 'jwt-after-login' });

    await waitFor(() => expect(createSosRequest).toHaveBeenCalledTimes(1));

    const wire = expectCoherentPlaceClaim(createSosRequest.mock.calls[0][0]);
    expect(wire.servicePlaceId).toBe('ChIJdizengoff');
    expect(wire.serviceLatitude).toBe(32.0811);
  });
});
