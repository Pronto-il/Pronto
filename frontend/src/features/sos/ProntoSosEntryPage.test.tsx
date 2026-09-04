import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ProntoSosEntryPage from './ProntoSosEntryPage';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraft, BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';
import { AuthContext } from '../../shared/hooks/authContext';
import { HeaderBackProvider, resolveDraftRoute } from '../../shared/hooks';
import { HeaderSlot } from '../../test/HeaderSlot';
import type { AuthContextValue } from '../../shared/hooks/authContext';

/**
 * The SOS commit boundary.
 *
 * **The Production bug.** This page read `issueId` from `useParams()`, but deferred authentication
 * flattened its route from `/issues/:issueId/sos-booking` to `/sos-booking`. `Number(undefined)` is
 * `NaN`, and `JSON.stringify` serialises `NaN` as `null` — so every activation by a signed-in
 * customer posted `"issueId": null` and the server answered `VALIDATION_ERROR / issueId must not be
 * null`. Guests never saw it because the auth boundary redirects them before the body is built.
 *
 * These tests assert the lifecycle rather than the symptom: an SOS request is only ever sent with a
 * real, persisted issue id, that issue is created exactly once, and a request is never sent at all
 * when there is nothing to create it from.
 */

const createIssue = vi.hoisted(() => vi.fn());
const createSosRequest = vi.hoisted(() => vi.fn());
const getIssue = vi.hoisted(() => vi.fn());
const getMySosRequests = vi.hoisted(() => vi.fn());

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return {
    ...actual,
    createIssue,
    createSosRequest,
    getIssue,
    getMySosRequests,
  };
});

// The live SOS screen owns polling and realtime; this suite is about what happens before it mounts.
vi.mock('./ProntoSosScreen', () => ({
  default: ({ sosRequestId }: { sosRequestId: number }) => <div>live-sos-{sosRequestId}</div>,
}));

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

function sosDraft(overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: 42,
    stage: 'BOOKING_CONFIRM',
    urgencyType: 'SOS',
    description: 'פיצוץ בצינור מים במטבח, הרצפה מוצפת',
    photos: [{ imageKey: 'customers/42/issues/temp/a.jpg' }],
    categoryId: 1,
    clarificationAnswers: [{ question: 'מאיפה מגיעים המים?', answer: 'מתחת לכיור' }],
    address: ADDRESS,
    updatedAt: new Date().toISOString(),
    ...overrides,
  } as BookingDraft;
}

let updateDraft: ReturnType<typeof vi.fn>;

function renderPage(options: { draft?: BookingDraft | null; token?: string | null; path?: string } = {}) {
  const draft = options.draft === undefined ? sosDraft() : options.draft;
  const auth = {
    user: { id: 42, fullName: 'דנה', role: 'CUSTOMER', defaultAddress: null },
    token: options.token === undefined ? 'jwt-abc' : options.token,
    isLoading: false,
  } as unknown as AuthContextValue;
  const bookingDraft = { draft, updateDraft, clearDraft: vi.fn() } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={[options.path ?? '/sos-booking']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={bookingDraft}>
          {/* Back is published into the app header now (`useHeaderBackAction`), not rendered under
              this page's own `PageHeader` — so the harness supplies the slot. */}
          <HeaderBackProvider>
            <HeaderSlot />
            <Routes>
              <Route path="/sos-booking" element={<ProntoSosEntryPage />} />
              <Route path="/login" element={<div>login-screen</div>} />
              <Route path="/issues/new" element={<div>describe-issue-screen</div>} />
              <Route path="/booking" element={<div>standard-booking-screen</div>} />
              <Route path="/" element={<div>home-screen</div>} />
            </Routes>
          </HeaderBackProvider>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  updateDraft = vi.fn();
  createIssue.mockReset().mockResolvedValue({ id: 777, categoryId: 1, urgencyType: 'SOS' });
  createSosRequest.mockReset().mockResolvedValue({ id: 555, issueId: 777, status: 'MATCHING' });
  getIssue.mockReset().mockResolvedValue({ id: 777, categoryId: 1, urgencyType: 'SOS' });
  getMySosRequests.mockReset().mockResolvedValue({ requests: [] });
});

afterEach(() => vi.clearAllMocks());

// ---- 1. The reported bug ----

describe('an authenticated customer can complete SOS creation', () => {
  it('never sends a null issueId — it creates the issue, then activates with the real id', async () => {
    renderPage();

    await waitFor(() => expect(createSosRequest).toHaveBeenCalledTimes(1));

    const payload = createSosRequest.mock.calls[0][0];
    expect(payload.issueId).toBe(777);
    // The precise regression guard: NaN survives every truthiness check and only becomes visible
    // as `null` after serialisation, at the server.
    expect(Number.isFinite(payload.issueId)).toBe(true);
    expect(JSON.parse(JSON.stringify(payload)).issueId).toBe(777);
    expect(await screen.findByText('live-sos-555')).toBeInTheDocument();
  });

  it('creates the issue from the draft, preserving description, category, photos and answers', async () => {
    renderPage();

    await waitFor(() => expect(createIssue).toHaveBeenCalledTimes(1));
    expect(createIssue).toHaveBeenCalledWith({
      categoryId: 1,
      description: 'פיצוץ בצינור מים במטבח, הרצפה מוצפת',
      urgencyType: 'SOS',
      imageKeys: ['customers/42/issues/temp/a.jpg'],
      clarificationAnswers: [{ question: 'מאיפה מגיעים המים?', answer: 'מתחת לכיור' }],
    });
  });

  it('sends the address the matching screen already collected', async () => {
    // Previously gated on `draft.issueId === issueId`, i.e. `undefined === NaN` — always false —
    // so the customer's just-entered address was silently dropped.
    renderPage();

    await waitFor(() => expect(createSosRequest).toHaveBeenCalled());
    const payload = createSosRequest.mock.calls[0][0];
    expect(payload.serviceCity).toBe('תל אביב-יפו');
    expect(payload.serviceStreet).toBe('הרצל');
    expect(payload.serviceHouseNumber).toBe('10');
    expect(payload.servicePlaceId).toBe('place-abc');
  });
});

// ---- 8. Idempotency ----

describe('retry never creates a second issue', () => {
  it('persists the new issue id to the draft before activating', async () => {
    renderPage();

    await waitFor(() => expect(createIssue).toHaveBeenCalled());
    // `urgencyType` travels with the id — see the SOS-urgency suite below for why the id alone
    // was a 409 waiting to happen.
    expect(updateDraft).toHaveBeenCalledWith({ issueId: 777, urgencyType: 'SOS' });
  });

  it('reuses an issue the draft already carries instead of creating another', async () => {
    renderPage({ draft: sosDraft({ issueId: 4242 }) });

    await waitFor(() => expect(createSosRequest).toHaveBeenCalled());
    expect(createIssue).not.toHaveBeenCalled();
    expect(createSosRequest.mock.calls[0][0].issueId).toBe(4242);
  });

  it('a failed activation retried from the error card reuses the same issue', async () => {
    const user = userEvent.setup();
    createSosRequest.mockRejectedValueOnce(new Error('network'));
    renderPage();

    const retry = await screen.findByRole('button', { name: 'ניסיון נוסף' });
    createSosRequest.mockResolvedValueOnce({ id: 556, issueId: 777, status: 'MATCHING' });
    await user.click(retry);

    await waitFor(() => expect(createSosRequest).toHaveBeenCalledTimes(2));
    // One issue, two activation attempts — the retry semantics the backend is built around.
    expect(createIssue).toHaveBeenCalledTimes(1);
    expect(createSosRequest.mock.calls[1][0].issueId).toBe(777);
  });
});

// ---- 3 / 6. Invalid lifecycle state ----

describe('an invalid lifecycle state never reaches the server', () => {
  it('does not send a request when there is no draft to create an issue from', async () => {
    renderPage({ draft: null });

    expect(await screen.findByRole('alert')).toHaveTextContent('לא מצאנו את פרטי התקלה');
    expect(createSosRequest).not.toHaveBeenCalled();
    expect(createIssue).not.toHaveBeenCalled();
  });

  it.each([
    ['no confirmed category', sosDraft({ categoryId: undefined })],
    ['a blank description', sosDraft({ description: '   ' })],
  ])('refuses to activate with %s', async (_label, draft) => {
    renderPage({ draft });

    await screen.findByRole('alert');
    expect(createSosRequest).not.toHaveBeenCalled();
  });

  it('offers a route back to describing the problem rather than a retry that cannot pass', async () => {
    const user = userEvent.setup();
    renderPage({ draft: null });

    await screen.findByRole('alert');
    expect(screen.queryByRole('button', { name: 'ניסיון נוסף' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'תיאור התקלה' }));

    expect(await screen.findByText('describe-issue-screen')).toBeInTheDocument();
  });

  it('does not dispatch when issue creation itself fails', async () => {
    // "Failed Issue creation must not continue into SOS dispatch" — no professional is contacted.
    createIssue.mockRejectedValueOnce(new Error('boom'));
    renderPage();

    await screen.findByRole('alert');
    expect(createSosRequest).not.toHaveBeenCalled();
  });
});

// ---- 2. The guest boundary: same rule, visible instead of automatic ----
//
// What must NOT change is the security property: a visitor creates no issue and causes no
// professional's phone to ring. What DID change is where the login screen comes from. This page
// used to call `activate` from its mount effect, and `activate` refuses without a token by
// navigating away — so a guest was redirected out of `/sos-booking` in the first frame, having
// been shown nothing: not what SOS does, not the address it was about to dispatch to, not why an
// account is needed. Deferring authentication exists precisely so a visitor can understand the
// offer before being asked to sign up, and an automatic redirect on mount is the one shape that
// cannot do that.
//
// So the boundary is unmoved and the trigger is explicit. These tests assert both halves.

describe('the guest SOS boundary', () => {
  it('shows the pre-dispatch card instead of redirecting on mount, and creates nothing', async () => {
    renderPage({ token: null });

    // The guest gets to read what is about to happen, with their own address on screen.
    expect(await screen.findByRole('button', { name: 'התחברות והפעלת SOS' })).toBeInTheDocument();
    expect(screen.queryByText('login-screen')).not.toBeInTheDocument();
    expect(createIssue).not.toHaveBeenCalled();
    expect(createSosRequest).not.toHaveBeenCalled();
  });

  it('names the trade from the draft rather than rendering a dash', async () => {
    // There is no issue yet on the guest path, and reading the category off the (absent) issue
    // printed a literal "—" where the customer's confirmed profession belongs, on the last screen
    // before real professionals are called to their home.
    renderPage({ token: null });

    await screen.findByRole('button', { name: 'התחברות והפעלת SOS' });
    expect(screen.queryByText('—')).not.toBeInTheDocument();
  });

  it('goes to login on the explicit press, still having created nothing', async () => {
    const user = userEvent.setup();
    renderPage({ token: null });

    await user.click(await screen.findByRole('button', { name: 'התחברות והפעלת SOS' }));

    expect(await screen.findByText('login-screen')).toBeInTheDocument();
    // Neither call: a visitor cannot create an issue, and cannot make a professional's phone ring.
    expect(createIssue).not.toHaveBeenCalled();
    expect(createSosRequest).not.toHaveBeenCalled();
  });

  it('persists the draft across the login round trip so nothing is re-entered', async () => {
    const user = userEvent.setup();
    renderPage({ token: null });

    await user.click(await screen.findByRole('button', { name: 'התחברות והפעלת SOS' }));

    await waitFor(() =>
      expect(updateDraft).toHaveBeenCalledWith(
        expect.objectContaining({ stage: 'BOOKING_CONFIRM', urgencyType: 'SOS' }),
      ),
    );
  });
});

// ---- Re-attachment ----

describe('an attempt already in flight is re-attached, not duplicated', () => {
  it('lands on the live screen for an existing non-terminal request', async () => {
    getMySosRequests.mockResolvedValue({ requests: [{ id: 999, issueId: 4242, status: 'MATCHING' }] });
    renderPage({ draft: sosDraft({ issueId: 4242 }) });

    expect(await screen.findByText('live-sos-999')).toBeInTheDocument();
    expect(createSosRequest).not.toHaveBeenCalled();
    expect(createIssue).not.toHaveBeenCalled();
  });

  it('recovers from SOS_REQUEST_ALREADY_EXISTS by attaching to the live attempt', async () => {
    const { ApiError } = await import('../../shared/api/httpClient');
    createSosRequest.mockRejectedValueOnce(
      new ApiError('SOS_REQUEST_ALREADY_EXISTS', 'already', null, 409),
    );
    getMySosRequests
      .mockResolvedValueOnce({ requests: [] })
      .mockResolvedValueOnce({ requests: [{ id: 321, issueId: 777, status: 'MATCHING' }] });

    renderPage();

    // The recovery lookup matches on the id THIS attempt sent, not on stale component state —
    // which for a freshly created issue would still have been null.
    expect(await screen.findByText('live-sos-321')).toBeInTheDocument();
  });
});

// ---- SOS urgency: the draft's urgency always describes the draft's issue ----

describe('SOS stays SOS all the way through discovery', () => {
  it('reaches professional discovery through the SOS path, never the Regular listing', async () => {
    // SOS discovery IS `POST /api/sos/requests` — Pronto dispatches on the customer's behalf.
    // `GET /api/bookings/professionals` is Regular-only (it refuses a non-STANDARD issue with
    // `409 ISSUE_URGENCY_MISMATCH`), so this flow must never touch it.
    const { getProfessionalsForIssue } = await import('../../shared/api');
    renderPage();

    expect(await screen.findByText('live-sos-555')).toBeInTheDocument();
    expect(createSosRequest).toHaveBeenCalledTimes(1);
    expect(createSosRequest.mock.calls[0][0].issueId).toBe(777);
    expect(vi.isMockFunction(getProfessionalsForIssue)).toBe(false);
  });

  it('records SOS on the draft with the issue it just created, so every resume routes to SOS', async () => {
    renderPage();

    await waitFor(() => expect(createIssue).toHaveBeenCalled());
    const patch = updateDraft.mock.calls.map((call) => call[0]).find((arg) => arg.issueId === 777);
    expect(patch).toEqual({ issueId: 777, urgencyType: 'SOS' });

    // The draft as it now stands — the id alone used to leave `urgencyType: 'STANDARD'` here,
    // which sent the next resume to `/booking` and produced the 409.
    const resumed = { ...sosDraft(), ...patch };
    expect(resolveDraftRoute(resumed)).toBe('/sos-booking');
  });

  it('corrects a stale draft when the issue turns out to be STANDARD, instead of carrying SOS over', async () => {
    // This branch exists because the draft was wrong; sending the customer to the Regular flow
    // without fixing it would bounce them straight back here on the next resume.
    const user = userEvent.setup();
    getIssue.mockResolvedValue({ id: 4242, categoryId: 1, urgencyType: 'STANDARD' });
    renderPage({ draft: sosDraft({ issueId: 4242 }) });

    await user.click(await screen.findByRole('button', { name: 'להזמנה רגילה' }));

    expect(updateDraft).toHaveBeenCalledWith({ urgencyType: 'STANDARD' });
    expect(await screen.findByText('standard-booking-screen')).toBeInTheDocument();
  });
});

describe('Back is published to the shared app header', () => {
  it('renders in the header slot, and nowhere else on the page', async () => {
    renderPage();

    await screen.findByText('live-sos-555');
    const backControls = screen.getAllByRole('button', { name: 'חזרה' });
    expect(backControls).toHaveLength(1);
    expect(screen.getByTestId('header-slot')).toContainElement(backControls[0]);
  });
});
