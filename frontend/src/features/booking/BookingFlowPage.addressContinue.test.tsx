import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BookingFlowPage from './BookingFlowPage';
import { httpClient } from '../../shared/api/httpClient';
import { EMPTY_ADDRESS } from '../../shared/components';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraft, BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';
import type { UserMeResponse } from '../../shared/api/users';
import { HeaderBackProvider } from '../../shared/hooks';

/**
 * **"המשך" after choosing an address did nothing.**
 *
 * The reported symptom, reproduced at the level the customer meets it: the booking flow's address
 * step, a signed-in customer with a saved home address, switching to "כתובת אחרת לפעם הזו". The
 * screen showed the address as confirmed and the button as ready, and pressing it produced no
 * navigation, no request, no draft write and no message — indefinitely.
 *
 * The cause was entirely client-side and is documented on `AddressFormFields.isResolved`: the
 * form's "resolved" test (`placeId !== null`) and `validateAddress`'s (`isAddressResolved`, which
 * also wants coordinates) disagreed about an address carried over from `GET /api/users/me`, whose
 * `defaultAddress` has a place id and deliberately no coordinates. The mismatch produced an error
 * under the `placeId` key, which no field rendered.
 *
 * These tests hold the *flow-level* contract that came out of it — a valid address advances, an
 * invalid one says so, and the step is re-enterable — rather than re-testing the form internals
 * that `shared/components/AddressFormFields.test.tsx` already pins.
 */

// The real screens use the `googlePlaces` module singleton (only `AddressFormFields`'s own tests
// use its `provider` prop), so the seam for a page-level test is the module itself.
vi.mock('../../shared/components/googlePlaces', () => ({
  googlePlacesProvider: {
    isConfigured: () => true,
    newSessionToken: async () => ({ token: 1 }),
    fetchCitySuggestions: async () => [{ placeId: 'city-tlv', description: 'תל אביב-יפו, ישראל' }],
    fetchStreetSuggestions: async () => [
      { placeId: 'street-dizengoff', description: 'דיזנגוף, תל אביב-יפו, ישראל' },
    ],
    resolve: async (placeId: string) => ({
      placeId,
      formattedAddress: 'דיזנגוף 100, תל אביב-יפו',
      city: 'תל אביב-יפו',
      street: 'דיזנגוף',
      houseNumber: '100',
      latitude: 32.0811,
      longitude: 34.7739,
    }),
    // The one building this fake knows about. Anything else is "Google cannot find that address".
    resolveFullAddress: async (parts: { city: string; street: string; houseNumber: string }) =>
      parts.houseNumber === '100'
        ? {
            placeId: 'ChIJdizengoff100',
            formattedAddress: 'דיזנגוף 100, תל אביב-יפו, ישראל',
            city: 'תל אביב-יפו',
            street: 'דיזנגוף',
            houseNumber: '100',
            latitude: 32.0811,
            longitude: 34.7739,
          }
        : null,
  },
}));

/** As `GET /api/users/me` returns it: a place id, and no coordinates. That asymmetry is the
 *  whole bug, so the fixture reproduces it exactly rather than filling the fields in. */
const customerWithSavedAddress = {
  id: 2,
  role: 'CUSTOMER',
  fullName: 'לקוח בדיקה',
  email: 'q@e.com',
  defaultAddress: {
    city: 'תל אביב-יפו',
    street: 'דיזנגוף',
    houseNumber: '100',
    apartment: null,
    floor: null,
    entrance: null,
    addressNotes: null,
    placeId: 'ChIJdizengoff100',
    formattedAddress: 'דיזנגוף 100, תל אביב-יפו',
  },
} as unknown as UserMeResponse;

function draftAtAddressStep(overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: 2,
    stage: 'ADDRESS_SELECTION',
    urgencyType: 'STANDARD',
    description: 'יש נזילה מתחת לכיור במטבח',
    photos: [],
    categoryId: 1,
    addressMode: 'DEFAULT',
    address: {
      ...EMPTY_ADDRESS,
      city: 'תל אביב-יפו',
      street: 'דיזנגוף',
      houseNumber: '100',
      placeId: 'ChIJdizengoff100',
      formattedAddress: 'דיזנגוף 100, תל אביב-יפו',
    },
    updatedAt: new Date().toISOString(),
    ...overrides,
  };
}

function renderFlow(draft: BookingDraft, updateDraft = vi.fn()) {
  const auth = {
    token: 't',
    user: customerWithSavedAddress,
    isLoading: false,
    establishSession: vi.fn(),
    logout: vi.fn(),
    refreshUser: vi.fn(),
  } as unknown as AuthContextValue;
  const bookingDraft = { draft, updateDraft, clearDraft: vi.fn() } as unknown as BookingDraftContextValue;

  render(
    <MemoryRouter initialEntries={['/booking']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={bookingDraft}>
          <HeaderBackProvider>
            <Routes>
              <Route path="/booking" element={<BookingFlowPage />} />
            </Routes>
          </HeaderBackProvider>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
  return { updateDraft };
}

/** The listing request the professionals step fires on arrival. Its content is irrelevant here —
 *  what matters is whether the flow gets far enough to make it. */
function stubListing() {
  return vi.spyOn(httpClient, 'get').mockResolvedValue({ categoryId: 1, professionals: [] });
}

describe('continuing from the booking address step', () => {
  beforeEach(() => {
    stubListing();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('advances to the professionals step with the saved home address', async () => {
    const { updateDraft } = renderFlow(draftAtAddressStep());

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await waitFor(() =>
      expect(updateDraft).toHaveBeenCalledWith(expect.objectContaining({ stage: 'PROFESSIONAL_SELECTION' })),
    );
  });

  it('advances after switching to a one-off address — the reported bug', async () => {
    // Before the fix this click did nothing at all: the address carried the saved place id with no
    // coordinates, so the form declared it confirmed while `validateAddress` refused it, silently.
    const { updateDraft } = renderFlow(draftAtAddressStep());

    const user = userEvent.setup();
    await user.click(screen.getByRole('radio', { name: 'כתובת אחרת לפעם הזו' }));
    // The address is re-confirmed against Google, which is what supplies the missing coordinates.
    await waitFor(() => expect(screen.getByTestId('address-confirmed')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await waitFor(() =>
      expect(updateDraft).toHaveBeenCalledWith(expect.objectContaining({ stage: 'PROFESSIONAL_SELECTION' })),
    );
  });

  it('shows an actionable error instead of a dead button when the address is incomplete', async () => {
    const { updateDraft } = renderFlow(
      draftAtAddressStep({ addressMode: 'CUSTOM', address: { ...EMPTY_ADDRESS, city: 'תל אביב-יפו' } }),
    );

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(await screen.findByText('יש להזין רחוב.')).toBeInTheDocument();
    expect(updateDraft).not.toHaveBeenCalled();
  });

  it('explains an address Google cannot confirm rather than refusing in silence', async () => {
    // House number 999 is not a building the fake provider knows. The customer must be told.
    const { updateDraft } = renderFlow(
      draftAtAddressStep({
        addressMode: 'CUSTOM',
        address: {
          ...EMPTY_ADDRESS,
          city: 'תל אביב-יפו',
          street: 'דיזנגוף',
          houseNumber: '999',
        },
      }),
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(/לא מצאנו את הכתובת הזו/);

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'המשך' }));
    expect(updateDraft).not.toHaveBeenCalled();
  });

  it('does not fire a second listing request when "המשך" is pressed twice', async () => {
    // A double tap on a phone must not produce two `GET /api/bookings/professionals` calls, nor
    // two draft writes racing each other.
    const get = stubListing();
    renderFlow(draftAtAddressStep());

    const user = userEvent.setup();
    const button = screen.getByRole('button', { name: 'המשך' });
    await user.click(button);
    await user.click(button);

    await waitFor(() => expect(get).toHaveBeenCalled());
    expect(get.mock.calls.filter(([url]) => String(url).includes('/api/bookings/professionals'))).toHaveLength(1);
  });

  it('lets the customer come back and change the address', async () => {
    const { updateDraft } = renderFlow(draftAtAddressStep());
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'המשך' }));
    await waitFor(() =>
      expect(updateDraft).toHaveBeenCalledWith(expect.objectContaining({ stage: 'PROFESSIONAL_SELECTION' })),
    );

    // Back into the step, switch to a one-off address, and continue again.
    await user.click(screen.getByRole('radio', { name: 'כתובת ברירת המחדל שלי' }));
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await waitFor(() =>
      expect(updateDraft).toHaveBeenCalledWith(expect.objectContaining({ addressMode: 'DEFAULT' })),
    );
  });
});
