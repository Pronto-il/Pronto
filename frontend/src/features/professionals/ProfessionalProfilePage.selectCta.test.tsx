import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ProfessionalProfilePage from './ProfessionalProfilePage';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';

/**
 * **"בחירת בעל מקצוע" must appear when the customer arrived from a booking flow.**
 *
 * The gate used to be `fromIssueId && urgencyType`. Deferred authentication moved issue creation
 * to the booking commit, so during selection there is normally no issue at all — `fromIssueId` is
 * `undefined` for every guest and for every signed-in customer who has not committed yet. The
 * `&&` therefore evaluated false on the *normal* path: a customer who tapped a professional's name
 * to read their reviews landed on a profile with no way to choose them and no way forward except
 * the back button, mid-booking.
 *
 * `urgencyType` is the field that actually answers the question the flag asks — which flow sent me,
 * and therefore which one to return to. These tests pin that the CTA follows the flow context and
 * not the presence of an id the flow no longer has, while a genuinely context-free visit (a
 * refresh, a direct link, `/favorites`) still degrades to a view-only page.
 */

const getProfessionalProfile = vi.hoisted(() => vi.fn());
const getReviews = vi.hoisted(() => vi.fn());

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return { ...actual, getProfessionalProfile, getReviews };
});

const SELECT_CTA = 'בחירת בעל מקצוע';

function renderProfile(state: unknown, options: { bookable?: boolean } = {}) {
  // The full `ProfessionalProfileResponse` shape — `ProfessionalProfileDisplay` reads `basePrice`
  // unconditionally (`.toFixed`), so a partial fixture crashes the subtree and every assertion
  // below would fail for a reason that has nothing to do with the CTA.
  getProfessionalProfile.mockResolvedValue({
    professionalId: 7,
    fullName: 'אבי כהן',
    bookable: options.bookable ?? true,
    approvalStatus: 'APPROVED',
    averageRating: null,
    basePrice: 250,
    bio: null,
    categoryIds: [1],
    city: 'תל אביב-יפו',
    createdAt: '2026-01-01T00:00:00Z',
    profileImageUrl: null,
    reviewCount: 0,
    serviceCityNamesHe: ['תל אביב-יפו'],
    serviceRegionNameHe: 'גוש דן',
    favorited: false,
  });
  getReviews.mockResolvedValue({ reviews: [] });

  const auth = { user: null, token: null, isLoading: false } as unknown as AuthContextValue;
  const draftValue = {
    draft: null,
    updateDraft: vi.fn(),
    clearDraft: vi.fn(),
  } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={[{ pathname: '/professionals/7', state }]}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={draftValue}>
          <Routes>
            <Route path="/professionals/:professionalId" element={<ProfessionalProfilePage />} />
          </Routes>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('the select CTA follows the flow context, not an issue id', () => {
  it('renders for a guest mid-booking, who has no issue at all', async () => {
    // The regression, exactly: the normal deferred-auth path carries a flow but no issue.
    renderProfile({ urgencyType: 'STANDARD' });

    expect(await screen.findByRole('button', { name: SELECT_CTA })).toBeInTheDocument();
  });

  it('renders for an SOS flow with no issue', async () => {
    renderProfile({ urgencyType: 'SOS' });

    expect(await screen.findByRole('button', { name: SELECT_CTA })).toBeInTheDocument();
  });

  it('still renders when an issue id IS carried — the re-entry case', async () => {
    renderProfile({ fromIssueId: 4242, urgencyType: 'STANDARD' });

    expect(await screen.findByRole('button', { name: SELECT_CTA })).toBeInTheDocument();
  });
});

describe('a context-free visit stays view-only', () => {
  it('offers no CTA on a direct visit or refresh', async () => {
    // `location.state` does not survive a refresh, and a profile reached by a shared link is a
    // browsing page, not a booking step.
    renderProfile(null);

    await screen.findByText('אבי כהן');
    await waitFor(() => expect(getReviews).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: SELECT_CTA })).not.toBeInTheDocument();
  });

  it('offers no CTA when arriving with an issue id but no flow', async () => {
    // `fromIssueId` alone never meant "in a flow" — it is the id, not the context.
    renderProfile({ fromIssueId: 4242 });

    await screen.findByText('אבי כהן');
    expect(screen.queryByRole('button', { name: SELECT_CTA })).not.toBeInTheDocument();
  });
});

describe('backend eligibility still wins over flow context', () => {
  it('shows the unavailable notice instead of the CTA for an unbookable professional', async () => {
    // MS1: `bookable` is computed from `ProfessionalEligibility` and this page renders it, never
    // re-derives it. Widening the flow-context gate must not have widened this one.
    renderProfile({ urgencyType: 'STANDARD' }, { bookable: false });

    expect(await screen.findByText('בעל המקצוע הזה אינו זמין להזמנה כרגע.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: SELECT_CTA })).not.toBeInTheDocument();
  });
});
