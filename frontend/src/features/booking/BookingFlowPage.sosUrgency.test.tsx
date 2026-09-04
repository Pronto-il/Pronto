import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BookingFlowPage from './BookingFlowPage';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraft, BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';
import { HeaderBackProvider, resolveDraftRoute } from '../../shared/hooks';
import { HeaderSlot } from '../../test/HeaderSlot';

/**
 * **`409 ISSUE_URGENCY_MISMATCH` on `GET /api/bookings/professionals`, from the client side.**
 *
 * Every endpoint the Standard flow names an issue to is Standard-only: `BookingsService` refuses
 * an issue whose `urgency_type != STANDARD`. Which flow the customer is in is decided by the
 * draft's `urgencyType` (`resolveDraftRoute`), while the issue that gets created is written by
 * whichever screen commits it. The 409 happened when those two disagreed.
 *
 * The crossing that broke it is here: "נסו SOS" on the slot step sent the customer to
 * `/sos-booking` without touching the draft, so it still said `STANDARD`. `ProntoSosEntryPage`
 * then created an SOS issue and wrote back its id alone — leaving a draft that claimed STANDARD
 * about an SOS issue, which every later resume routed to `/booking`, which named that issue to a
 * listing endpoint that can only refuse it.
 *
 * These tests pin the invariant rather than the symptom: **the draft's urgency always describes
 * the draft's issue**. The backend guard is untouched and is not expected to be reached.
 */

const getProfessionalsForIssue = vi.hoisted(() => vi.fn());
const getAvailableWindows = vi.hoisted(() => vi.fn());

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return { ...actual, getProfessionalsForIssue, getAvailableWindows };
});

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

/** A window opening a minute from now, so there are start times on both sides of the boundary. */
function windowsFromNow() {
  const start = new Date(Date.now() + 60_000);
  return [{ startAt: start.toISOString(), endAt: new Date(start.getTime() + 12 * 60 * 60_000).toISOString() }];
}

function draftAt(overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: 42,
    stage: 'PROFESSIONAL_SELECTION',
    urgencyType: 'STANDARD',
    description: 'נזילה מתחת לכיור',
    photos: [],
    categoryId: 1,
    address: ADDRESS,
    addressMode: 'CUSTOM',
    sort: 'CHEAPEST',
    updatedAt: new Date().toISOString(),
    ...overrides,
  } as BookingDraft;
}

let updateDraft: ReturnType<typeof vi.fn>;

function renderFlow(draft: BookingDraft) {
  updateDraft = vi.fn();
  const auth = {
    user: { id: 42, role: 'CUSTOMER' },
    token: 'jwt-abc',
    isLoading: false,
  } as unknown as AuthContextValue;
  const draftValue = { draft, updateDraft, clearDraft: vi.fn() } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={['/booking']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={draftValue}>
          <HeaderBackProvider>
            <HeaderSlot />
            <Routes>
              <Route path="/booking" element={<BookingFlowPage />} />
              <Route path="/sos-booking" element={<div>sos-screen</div>} />
              <Route path="/issues/new" element={<div>describe-issue-screen</div>} />
            </Routes>
          </HeaderBackProvider>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/** Every subject the page has named to the Standard listing endpoint so far. */
function listingSubjects(): unknown[] {
  return getProfessionalsForIssue.mock.calls.map((call) => call[0]);
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
    windows: windowsFromNow(),
    // Everything inside the next 2.5 hours is too soon for a standard booking, which is what
    // puts the "נסו SOS" offer on screen.
    earliestBookableAt: new Date(Date.now() + 150 * 60_000).toISOString(),
    minLeadMinutes: 150,
  });
});

afterEach(() => vi.clearAllMocks());

describe('the Regular flow is unchanged', () => {
  it('names its own STANDARD issue to the listing and stays on the Regular path', async () => {
    const draft = draftAt({ issueId: 4242 });
    renderFlow(draft);

    await waitFor(() => expect(getProfessionalsForIssue).toHaveBeenCalled());
    expect(listingSubjects()).toEqual([{ issueId: 4242 }]);
    expect(resolveDraftRoute(draft)).toBe('/booking');
  });
});

describe('"נסו SOS" hands the SOS flow a draft that says SOS', () => {
  // Generous per-test timeout: this one drives two chained requests before the button it needs
  // exists, and 5s of vitest default is tight when the whole suite runs in parallel.
  it('marks the draft SOS — with the address it already has — before entering /sos-booking', async () => {
    const user = userEvent.setup();
    // Resumes onto the slot step, where the lead-time notice carries the SOS offer.
    renderFlow(draftAt({ stage: 'SLOT_SELECTION', issueId: 4242, professionalId: 7 }));

    // Two chained requests (listing, then windows) stand between mount and this button, so the
    // default 1s poll is tight when the suite runs in parallel with everything else.
    const trySos = await screen.findByRole('button', { name: 'נסו SOS' }, { timeout: 5000 });
    await user.click(trySos);

    // The whole fix in one assertion: the draft is switched to the SOS path *before* the SOS page
    // creates an SOS issue against it. Without this the draft kept claiming STANDARD, and the next
    // resume routed back to `/booking` and named the SOS issue to a Standard-only endpoint.
    expect(updateDraft).toHaveBeenCalledWith({
      urgencyType: 'SOS',
      address: ADDRESS,
      addressMode: 'CUSTOM',
    });
    expect(await screen.findByText('sos-screen', undefined, { timeout: 5000 })).toBeInTheDocument();
  }, 20_000);

  it('the resulting draft resumes to the SOS route, not the Regular one', () => {
    // What `resolveDraftRoute` (the toolbox, the draft indicator, the post-login landing) does with
    // the draft the click above leaves behind — this is "SOS survives navigation".
    const afterTrySos = draftAt({ urgencyType: 'SOS', issueId: 4242, stage: 'SLOT_SELECTION' });

    expect(resolveDraftRoute(afterTrySos)).toBe('/sos-booking');
  });
});

describe('an SOS draft never reaches the Regular-only listing', () => {
  it('does not name — or even ask for — a Standard listing for an SOS issue', async () => {
    // A draft in the shape the bug used to produce (and which may still be sitting in a
    // localStorage somewhere): SOS, carrying an SOS issue id, pointed at `/booking`.
    renderFlow(draftAt({ urgencyType: 'SOS', issueId: 4242 }));

    // Two independent reasons this holds: the resume effect refuses a non-STANDARD draft, and
    // `canNameIssue` excludes an issue the draft itself marks SOS.
    // The address step, i.e. the flow did not resume into a professionals listing.
    await screen.findByRole('button', { name: 'המשך' });
    expect(listingSubjects().every((subject) => !Object.hasOwn(subject as object, 'issueId'))).toBe(true);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('Back is published to the shared app header', () => {
  it('renders in the header slot, and nowhere else on the page', async () => {
    renderFlow(draftAt({ stage: 'ADDRESS_SELECTION', issueId: 4242 }));

    const backControls = await screen.findAllByRole('button', { name: 'חזרה' });
    expect(backControls).toHaveLength(1);
    expect(screen.getByTestId('header-slot')).toContainElement(backControls[0]);
  });
});
