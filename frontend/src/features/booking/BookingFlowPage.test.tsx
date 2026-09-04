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
import { HeaderSlot } from '../../test/HeaderSlot';

/**
 * `/issues/:issueId/booking`'s Back button, from its very first ('address') step — the
 * "בחירת בעל מקצוע" / "שלב 1 מתוך 4" screen the mobile-nav fix's bug report describes.
 *
 * Root cause traced before this fix: `handleBack`'s `'address'` branch called `navigate('/')`
 * unconditionally, sending the customer to Home and abandoning the whole issue-creation context
 * they were mid-flow on. The route sequence that lands a customer here is `/issues/new` (issue
 * description + AI classification, one route/internal step machine) → `/issues/:issueId/matching`
 * (address collection + AI-matching transition, `replace: true`) → this screen (`replace: true`).
 * Both hops so far are one-way (react-router history has no entry to pop back through), so "back"
 * has to be reconstructed from the booking draft rather than browser history — the same technique
 * `ProfessionMatchPage.handleAddressBack` already uses one hop earlier in this exact chain.
 *
 * These tests mount only the `'address'` step directly (a draft with `stage: 'ADDRESS_SELECTION'`
 * short-circuits the resume-hydration effect before any network call, per that effect's own
 * guard), so no API mocking is needed to exercise the bug.
 */

const customer = { id: 2, role: 'CUSTOMER', fullName: 'לקוח בדיקה', email: 'q@e.com' } as unknown as UserMeResponse;

function renderBookingFlow(draft: BookingDraft, updateDraft = vi.fn()) {
  const auth = {
    token: 't',
    user: customer,
    isLoading: false,
    establishSession: vi.fn(),
    logout: vi.fn(),
    refreshUser: vi.fn(),
  } as unknown as AuthContextValue;
  const bookingDraft = {
    draft,
    updateDraft,
    clearDraft: vi.fn(),
  } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={[`/issues/${draft.issueId}/booking`]}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={bookingDraft}>
          {/* Back is published into the app header now (`useHeaderBackAction`), not rendered as a
              row under this page's own `PageHeader` — so the harness supplies the slot. */}
          <HeaderBackProvider>
            <HeaderSlot />
            <Routes>
              <Route path="/issues/:issueId/booking" element={<BookingFlowPage />} />
              <Route path="/issues/new" element={<div data-testid="new-issue-screen">issues/new</div>} />
              <Route path="/" element={<div data-testid="home-screen">home</div>} />
            </Routes>
          </HeaderBackProvider>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/** A draft as it exists by the time a customer reaches this screen's 'address' step — carrying
 *  everything `NewIssuePage`/`ProfessionMatchPage` already collected. */
function draftAtBookingAddressStep(overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: 2,
    stage: 'ADDRESS_SELECTION',
    urgencyType: 'STANDARD',
    description: 'יש נזילה מתחת לכיור במטבח',
    photos: [],
    categoryId: 3,
    issueId: 42,
    updatedAt: '2026-08-27T08:00:00.000Z',
    ...overrides,
  };
}

describe('Back from the professional-selection flow\'s first step (mobile-nav fix)', () => {
  it('does not navigate Home', async () => {
    const user = userEvent.setup();
    renderBookingFlow(draftAtBookingAddressStep());

    await user.click(screen.getByRole('button', { name: 'חזרה' }));

    expect(screen.queryByTestId('home-screen')).not.toBeInTheDocument();
  });

  it('returns to the AI classification screen (/issues/new), not Home', async () => {
    const user = userEvent.setup();
    renderBookingFlow(draftAtBookingAddressStep());

    await user.click(screen.getByRole('button', { name: 'חזרה' }));

    expect(screen.getByTestId('new-issue-screen')).toBeInTheDocument();
  });

  it('rewinds the draft stage to ISSUE_REVIEW so NewIssuePage resumes onto its review step', async () => {
    const user = userEvent.setup();
    const updateDraft = vi.fn();
    renderBookingFlow(draftAtBookingAddressStep(), updateDraft);

    await user.click(screen.getByRole('button', { name: 'חזרה' }));

    expect(updateDraft).toHaveBeenCalledWith({ stage: 'ISSUE_REVIEW' });
  });

  it('preserves the existing issue/classification state — only stage is patched, nothing duplicated', async () => {
    // `updateDraft` is documented (BookingDraftProvider.tsx) as a shallow merge into the existing
    // draft, so a caller only has to patch what changed. This pins that `handleBack` relies on
    // that contract rather than re-supplying categoryId/description/photos/issueId itself, which
    // would risk the two copies drifting.
    const user = userEvent.setup();
    const updateDraft = vi.fn();
    const draft = draftAtBookingAddressStep();
    renderBookingFlow(draft, updateDraft);

    await user.click(screen.getByRole('button', { name: 'חזרה' }));

    const patch = updateDraft.mock.calls[0][0];
    expect(patch).not.toHaveProperty('categoryId');
    expect(patch).not.toHaveProperty('description');
    expect(patch).not.toHaveProperty('photos');
    expect(patch).not.toHaveProperty('issueId');
    // The values themselves are still the ones already on the draft (untouched by this handler).
    expect(draft.categoryId).toBe(3);
    expect(draft.description).toBe('יש נזילה מתחת לכיור במטבח');
    expect(draft.issueId).toBe(42);
  });
});

/**
 * **No professional-search request before there is a valid address.**
 *
 * The reported `400 Bad Request` had two independent causes, and this file covers the frontend
 * one: a booking draft carrying an `EMPTY_ADDRESS` — a perfectly non-null object full of empty
 * strings — resumed straight onto the professionals step, because the resume guard asked
 * `!draft.address`. The request that went out was
 * `GET /api/bookings/professionals?...&city=&street=&houseNumber=`, which the backend refuses.
 * (The other cause was backend request binding; see `BookingsControllerLocationTest`.)
 */
describe('the professional listing is not requested before a valid address exists', () => {
  beforeEach(() => {
    vi.spyOn(httpClient, 'get').mockResolvedValue({ issueId: 42, categoryId: 3, professionals: [] });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('resumes onto the address step when the draft carries a blank address', async () => {
    // `stage: 'PROFESSIONAL_SELECTION'` is the resume path that used to fetch immediately.
    renderBookingFlow(
      draftAtBookingAddressStep({ stage: 'PROFESSIONAL_SELECTION', address: EMPTY_ADDRESS }),
    );

    expect(await screen.findByText('שלב 1 מתוך 4')).toBeInTheDocument();
    expect(httpClient.get).not.toHaveBeenCalled();
  });

  it('resumes onto the address step when the draft has a city but no house number', async () => {
    renderBookingFlow(
      draftAtBookingAddressStep({
        stage: 'PROFESSIONAL_SELECTION',
        address: { ...EMPTY_ADDRESS, city: 'חיפה', street: 'הרצל' },
      }),
    );

    expect(await screen.findByText('שלב 1 מתוך 4')).toBeInTheDocument();
    expect(httpClient.get).not.toHaveBeenCalled();
  });

  it('refuses a draft whose house number is not digits', async () => {
    renderBookingFlow(
      draftAtBookingAddressStep({
        stage: 'PROFESSIONAL_SELECTION',
        address: { ...EMPTY_ADDRESS, city: 'חיפה', street: 'הרצל', houseNumber: '12א' },
      }),
    );

    expect(await screen.findByText('שלב 1 מתוך 4')).toBeInTheDocument();
    expect(httpClient.get).not.toHaveBeenCalled();
  });

  it('does request the listing when the draft address IS complete', async () => {
    // The other half: the guard has to let real work through, or it would just be an outage with
    // better manners. A legacy address with no place id counts — the backend grandfathers the
    // caller's own saved one.
    renderBookingFlow(
      draftAtBookingAddressStep({
        stage: 'PROFESSIONAL_SELECTION',
        addressMode: 'DEFAULT',
        address: { ...EMPTY_ADDRESS, city: 'חיפה', street: 'הרצל', houseNumber: '5' },
      }),
    );

    await waitFor(() => expect(httpClient.get).toHaveBeenCalled());
    const path = vi.mocked(httpClient.get).mock.calls[0][0];
    expect(path).toContain('city=');
    expect(path).not.toMatch(/city=&/);
    expect(new URLSearchParams(path.split('?')[1]).get('houseNumber')).toBe('5');
  });
});
