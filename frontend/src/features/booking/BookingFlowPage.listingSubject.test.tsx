import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import BookingFlowPage from './BookingFlowPage';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraft, BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';

/**
 * **What the booking flow is allowed to name to the server, and the Production 403 it caused.**
 *
 * `GET /api/bookings/professionals` takes either an `issueId` or a `categoryId`. An issue belongs
 * to an account and is authorized against the caller; a category is owned by nobody, which is
 * exactly why the guest journey is keyed on it.
 *
 * Deferred authentication made this route `permitAll`, and that is what turned a latent mismatch
 * into an outage. `auth.security.JwtAuthenticationFilter` does not reject an expired or revoked
 * token — it leaves the security context empty and lets the request continue — so a request
 * carrying a *dead* token reaches the handler indistinguishably from a genuine guest's. The
 * handler then found `issueId` present and `callerId == null` and answered
 * `403 FORBIDDEN / You are not authorized to perform this action.`
 *
 * That was unrecoverable from the customer's side: `httpClient` ends a dead session on `401` and
 * only on `401`, so the token was never cleared, every retry produced the same `403`, and the
 * screen showed "משהו השתבש" forever.
 *
 * Both ends are fixed and both are tested. The server now answers `401` there
 * (`BookingsServiceTest.guest_cannotReadAnIssueByNamingItsId`), which ends the dead session. This
 * suite pins the client half: a screen with no token never sends `?issueId=` at all, so it
 * degrades to the ordinary guest listing instead of a request that can only be refused.
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

/** A draft parked on the professionals step, so the page resumes straight into a listing call. */
function draftAt(overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: null,
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

function renderFlow(options: { token?: string | null; draft?: BookingDraft; path?: string } = {}) {
  const auth = {
    user: options.token === null ? null : { id: 42, role: 'CUSTOMER' },
    token: options.token === undefined ? 'jwt-abc' : options.token,
    isLoading: false,
  } as unknown as AuthContextValue;

  const draftValue = {
    draft: options.draft ?? draftAt(),
    updateDraft: vi.fn(),
    clearDraft: vi.fn(),
  } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={[options.path ?? '/booking']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={draftValue}>
          <Routes>
            <Route path="/booking" element={<BookingFlowPage />} />
            <Route path="/issues/:issueId/booking" element={<BookingFlowPage />} />
          </Routes>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/** The subject argument of the most recent listing call. */
function lastSubject(): unknown {
  const calls = getProfessionalsForIssue.mock.calls;
  return calls[calls.length - 1]?.[0];
}

beforeEach(() => {
  getProfessionalsForIssue.mockResolvedValue({ issueId: null, categoryId: 1, professionals: [] });
  getAvailableWindows.mockResolvedValue({
    professionalId: 7,
    issueId: null,
    defaultDurationMinutes: 60,
    timezone: 'Asia/Jerusalem',
    windows: [],
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('a caller with no token never names an issue', () => {
  it('falls back to the draft category when the draft carries an issueId', async () => {
    // The exact Production shape: a draft that reached the commit once (so it has an issueId)
    // being read by a session that is no longer valid.
    renderFlow({ token: null, draft: draftAt({ issueId: 4242 }) });

    await waitFor(() => expect(getProfessionalsForIssue).toHaveBeenCalled());
    expect(lastSubject()).toEqual({ categoryId: 1 });
    expect(lastSubject()).not.toHaveProperty('issueId');
  });

  it('ignores an issueId in the URL too', async () => {
    // `/issues/:issueId/booking` is the re-entry route. The id is trustworthy as a *route*, but it
    // still cannot be authorized without a caller, so it must not be sent either. The draft names
    // the same issue so this page's resume guard (`draft.issueId !== issueId` bails out) lets the
    // listing actually happen — the assertion is about the subject, not about the guard.
    renderFlow({ token: null, draft: draftAt({ issueId: 4242 }), path: '/issues/4242/booking' });

    await waitFor(() => expect(getProfessionalsForIssue).toHaveBeenCalled());
    expect(lastSubject()).toEqual({ categoryId: 1 });
  });

  it('omits the issueId from the available-windows call as well', async () => {
    // The next request the flow makes, and the one an expired session hit a screen later.
    // `issueId` is an optional refinement there, so omitting it is a supported state.
    getProfessionalsForIssue.mockResolvedValue({
      issueId: null,
      categoryId: 1,
      professionals: [{ professionalId: 7, fullName: 'אבי כהן', basePrice: 250 }],
    });
    renderFlow({
      token: null,
      draft: draftAt({ stage: 'SLOT_SELECTION', issueId: 4242, professionalId: 7 }),
    });

    await waitFor(() => expect(getAvailableWindows).toHaveBeenCalled());
    expect(getAvailableWindows).toHaveBeenCalledWith(7, undefined);
  });
});

describe('a signed-in customer is unchanged', () => {
  it('still names the issue it owns', async () => {
    // The guard must not have cost the authenticated path anything: naming the issue is strictly
    // better when it can be authorized (the server derives the category from it and re-checks
    // ownership, urgency and bookability).
    renderFlow({ draft: draftAt({ issueId: 4242 }) });

    await waitFor(() => expect(getProfessionalsForIssue).toHaveBeenCalled());
    expect(lastSubject()).toEqual({ issueId: 4242 });
  });

  it('uses the category when no issue exists yet — the normal deferred-auth path', async () => {
    renderFlow({ draft: draftAt() });

    await waitFor(() => expect(getProfessionalsForIssue).toHaveBeenCalled());
    expect(lastSubject()).toEqual({ categoryId: 1 });
  });

  it('names the issue from the re-entry route', async () => {
    // `/issues/:issueId/booking` — `OrderTrackingPage`'s "choose another professional" after an
    // order was cancelled/rejected/expired. The id belongs in the URL here because there is no
    // draft to carry it, and a signed-in customer can be authorized for it.
    renderFlow({ draft: draftAt({ issueId: 4242 }), path: '/issues/4242/booking' });

    await waitFor(() => expect(getProfessionalsForIssue).toHaveBeenCalled());
    expect(lastSubject()).toEqual({ issueId: 4242 });
  });
});

describe('the resume path uses the same rule as the live path', () => {
  it('does not re-derive the subject with its own copy of the logic', async () => {
    // The resume effect used to build `{ issueId: draft.issueId }` inline, without the token
    // guard — so a resumed draft was the one remaining way an expired session still sent
    // `?issueId=` and collected the 403.
    renderFlow({ token: null, draft: draftAt({ issueId: 4242 }) });

    await waitFor(() => expect(getProfessionalsForIssue).toHaveBeenCalled());
    for (const call of getProfessionalsForIssue.mock.calls) {
      expect(call[0]).not.toHaveProperty('issueId');
    }
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
