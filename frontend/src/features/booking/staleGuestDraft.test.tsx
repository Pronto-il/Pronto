import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BookingFlowPage from './BookingFlowPage';
import ProntoSosEntryPage from '../sos/ProntoSosEntryPage';
import { sanitizeRestoredDraft } from '../../shared/hooks';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraft, BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';

/**
 * What the freshness policy actually changes **on screen**, for both flows.
 *
 * <p>`bookingDraftFreshness.test.ts` pins the policy itself; this drives the two screens that were
 * skipping the address step with the draft that policy produces. The SOS one matters most: its
 * activation is not a navigation but a *dispatch*, so resuming a day-old address there sends real
 * professionals to a place the customer never re-confirmed.
 */

const getProfessionalsForIssue = vi.hoisted(() => vi.fn());
const getAvailableWindows = vi.hoisted(() => vi.fn());
const createIssue = vi.hoisted(() => vi.fn());
const createSosRequest = vi.hoisted(() => vi.fn());
const getIssue = vi.hoisted(() => vi.fn());
const getMySosRequests = vi.hoisted(() => vi.fn());

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return {
    ...actual,
    getProfessionalsForIssue,
    getAvailableWindows,
    createIssue,
    createSosRequest,
    getIssue,
    getMySosRequests,
  };
});

vi.mock('../sos/ProntoSosScreen', () => ({
  default: ({ sosRequestId }: { sosRequestId: number }) => <div>live-sos-{sosRequestId}</div>,
}));

const HOUR = 60 * 60 * 1000;

const ADDRESS = {
  city: 'תל אביב-יפו',
  street: 'הרצל',
  houseNumber: '10',
  apartment: '',
  floor: '',
  entrance: '',
  addressNotes: '',
  placeId: 'place-abc',
  formattedAddress: 'הרצל 10, תל אביב-יפו',
  latitude: 32.06,
  longitude: 34.77,
};

/** A guest who got as far as choosing a professional, `ageMs` ago. */
function guestDraft(ageMs: number, overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: null,
    stage: 'PROFESSIONAL_SELECTION',
    urgencyType: 'STANDARD',
    description: 'נזילה מתחת לכיור במטבח',
    photos: [{ imageKey: 'guests/abc/1.jpg' }],
    categoryId: 1,
    address: ADDRESS,
    addressMode: 'CUSTOM',
    professionalId: 7,
    sort: 'CHEAPEST',
    updatedAt: new Date(Date.now() - ageMs).toISOString(),
    ...overrides,
  } as BookingDraft;
}

/** Exactly what `BookingDraftProvider` hands a screen on load. */
function asRestored(draft: BookingDraft): BookingDraft {
  return sanitizeRestoredDraft(draft, Date.now());
}

function renderStandard(draft: BookingDraft) {
  const auth = { user: null, token: null, isLoading: false } as unknown as AuthContextValue;
  const draftValue = { draft, updateDraft: vi.fn(), clearDraft: vi.fn() } as unknown as BookingDraftContextValue;
  return render(
    <MemoryRouter initialEntries={['/booking']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={draftValue}>
          <Routes>
            <Route path="/booking" element={<BookingFlowPage />} />
          </Routes>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

function renderSos(draft: BookingDraft) {
  const auth = {
    user: { id: 42, role: 'CUSTOMER', defaultAddress: null },
    token: 'jwt-abc',
    isLoading: false,
  } as unknown as AuthContextValue;
  const draftValue = { draft, updateDraft: vi.fn(), clearDraft: vi.fn() } as unknown as BookingDraftContextValue;
  return render(
    <MemoryRouter initialEntries={['/sos-booking']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={draftValue}>
          <Routes>
            <Route path="/sos-booking" element={<ProntoSosEntryPage />} />
          </Routes>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/** The address step, identified by the one field only it renders (required, so "עיר *"). */
function addressStep() {
  return screen.queryAllByLabelText(/^עיר/)[0] ?? null;
}

beforeEach(() => {
  getProfessionalsForIssue.mockReset().mockResolvedValue({
    issueId: null,
    categoryId: 1,
    professionals: [{ professionalId: 7, fullName: 'אבי כהן', basePrice: 250 }],
  });
  getAvailableWindows.mockReset().mockResolvedValue({
    professionalId: 7,
    issueId: null,
    defaultDurationMinutes: 60,
    timezone: 'Asia/Jerusalem',
    windows: [],
  });
  createIssue.mockReset().mockResolvedValue({ id: 777, categoryId: 1, urgencyType: 'SOS' });
  createSosRequest.mockReset().mockResolvedValue({ id: 555, issueId: 777, status: 'MATCHING' });
  getIssue.mockReset().mockResolvedValue({ id: 777, categoryId: 1, urgencyType: 'SOS' });
  getMySosRequests.mockReset().mockResolvedValue({ requests: [] });
});

afterEach(() => vi.clearAllMocks());

describe('the Regular flow, restored', () => {
  it('same-session refresh resumes on the professionals list, as it always did', async () => {
    renderStandard(asRestored(guestDraft(30_000)));

    // Landed on step 2 of 4 — the page's own step indicator, which does not depend on how a
    // professional card happens to render. The address node lingers for one exit animation, so it
    // is waited out rather than asserted against in the same tick.
    expect(await screen.findByText('שלב 2 מתוך 4')).toBeInTheDocument();
    expect(getProfessionalsForIssue).toHaveBeenCalled();
    await waitFor(() => expect(addressStep()).not.toBeInTheDocument());
  });

  it('a short reopen a few hours later still resumes', async () => {
    renderStandard(asRestored(guestDraft(6 * HOUR)));

    expect(await screen.findByText('שלב 2 מתוך 4')).toBeInTheDocument();
    expect(getProfessionalsForIssue).toHaveBeenCalled();
    await waitFor(() => expect(addressStep()).not.toBeInTheDocument());
  });

  it('the next day, the address step is shown again instead of skipped', async () => {
    renderStandard(asRestored(guestDraft(20 * HOUR)));

    await waitFor(() => expect(addressStep()).toBeInTheDocument());
    expect(screen.getByText('שלב 1 מתוך 4')).toBeInTheDocument();
    // And no listing was requested for a professional the customer has not re-chosen.
    expect(getProfessionalsForIssue).not.toHaveBeenCalled();
  });

  it('keeps the description, category and photos it was carrying', () => {
    // Preserved through the restore, so the commit still has everything it needs once the customer
    // works forward again — including the guest-owned photo keys.
    const restored = asRestored(guestDraft(20 * HOUR));

    expect(restored.description).toBe('נזילה מתחת לכיור במטבח');
    expect(restored.categoryId).toBe(1);
    expect(restored.photos).toEqual([{ imageKey: 'guests/abc/1.jpg' }]);
  });
});

describe('the SOS flow, restored', () => {
  it('a fresh draft still activates without re-asking for the address', async () => {
    renderSos(asRestored(guestDraft(30_000, { urgencyType: 'SOS', stage: 'BOOKING_CONFIRM', issueId: 777 })));

    expect(await screen.findByText('live-sos-555')).toBeInTheDocument();
    expect(createSosRequest).toHaveBeenCalledTimes(1);
  });

  it('a day-old draft asks before dispatching anybody', async () => {
    renderSos(asRestored(guestDraft(20 * HOUR, { urgencyType: 'SOS', stage: 'BOOKING_CONFIRM', issueId: 777 })));

    await waitFor(() => expect(addressStep()).toBeInTheDocument());
    // The whole point: no professional has been called out to yesterday's address.
    expect(createSosRequest).not.toHaveBeenCalled();
  });
});
