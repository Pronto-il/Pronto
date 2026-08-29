import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfessionalReviewsModal } from './ProfessionalReviewsModal';
import { ReviewList } from './ReviewList';
import { setAuthTokenGetter } from '../../shared/api/httpClient';
import type { PublicReviewResponse } from '../../shared/api';

/**
 * Reading reviews with no session at all.
 *
 * The 401 that prompted this was entirely server-side — no screen here ever gated the fetch on a
 * token — so what these tests pin is the half that *could* silently regress from the client: the
 * request must not carry an `Authorization` header it does not need, and the review card must
 * render from the narrower public payload, which no longer carries `customerId`/`orderId`.
 */

let fetchCalls: Array<{ url: string; headers: Record<string, string> }>;

/** Exactly what the backend's `PublicReviewResponse` sends — no `customerId`, no `orderId`. */
const PUBLIC_REVIEW: PublicReviewResponse = {
  id: 1,
  professionalId: 20,
  customerName: 'דנה כהן',
  rating: 5,
  comment: 'עבודה מצוינת, הגיע בזמן',
  createdAt: '2026-08-01T10:00:00Z',
  updatedAt: '2026-08-01T10:00:00Z',
};

beforeEach(() => {
  fetchCalls = [];
  // A guest: nothing injected the token getter, so there is no session of any kind.
  setAuthTokenGetter(() => null);
  localStorage.clear();

  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      fetchCalls.push({ url, headers: (init.headers ?? {}) as Record<string, string> });
      return {
        ok: true,
        status: 200,
        json: async () => ({
          professionalId: 20,
          averageRating: 5,
          reviewCount: 1,
          reviews: [PUBLIC_REVIEW],
        }),
      } as unknown as Response;
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  setAuthTokenGetter(() => null);
});

describe('a guest reading reviews', () => {
  it('loads and renders a professional\'s reviews with no session', async () => {
    render(<ProfessionalReviewsModal isOpen onClose={() => {}} professionalId={20} />);

    expect(await screen.findByText('עבודה מצוינת, הגיע בזמן')).toBeInTheDocument();
    expect(screen.getByText('דנה כהן')).toBeInTheDocument();
    expect(screen.getByLabelText('5 מתוך 5 כוכבים')).toBeInTheDocument();
  });

  it('calls the professional-filtered reviews endpoint', async () => {
    render(<ProfessionalReviewsModal isOpen onClose={() => {}} professionalId={20} />);

    await waitFor(() => expect(fetchCalls).toHaveLength(1));
    expect(fetchCalls[0].url).toContain('/api/reviews?professionalId=20');
  });

  it('sends no Authorization header on a public read', async () => {
    // `auth: false`, deliberately. A stale token left in localStorage must not be able to turn a
    // public read into a 401 that fires the global dead-session handler and bounces a browsing
    // guest to the login screen.
    setAuthTokenGetter(() => 'a-stale-expired-token');
    render(<ProfessionalReviewsModal isOpen onClose={() => {}} professionalId={20} />);

    await waitFor(() => expect(fetchCalls).toHaveLength(1));
    expect(fetchCalls[0].headers.Authorization).toBeUndefined();
  });

  it('does not fetch at all while the modal is closed', async () => {
    render(<ProfessionalReviewsModal isOpen={false} onClose={() => {}} professionalId={20} />);

    await waitFor(() => expect(fetchCalls).toHaveLength(0));
  });
});

describe('the review card renders from public fields only', () => {
  it('needs neither customerId nor orderId to display a review', () => {
    // `PUBLIC_REVIEW` is typed as `PublicReviewResponse`, so this failing to compile IS the
    // assertion that the component never reached for a private field.
    render(<ReviewList reviews={[PUBLIC_REVIEW]} isLoading={false} error={null} />);

    expect(screen.getByText('דנה כהן')).toBeInTheDocument();
    expect(screen.getByText('עבודה מצוינת, הגיע בזמן')).toBeInTheDocument();
  });

  it('falls back to a neutral label when the reviewer has no resolvable name', () => {
    render(
      <ReviewList reviews={[{ ...PUBLIC_REVIEW, customerName: null }]} isLoading={false} error={null} />,
    );

    expect(screen.getByText('לקוח/ה')).toBeInTheDocument();
  });

  it('renders an empty history rather than an error', () => {
    render(<ReviewList reviews={[]} isLoading={false} error={null} />);

    expect(screen.getByText('עדיין אין ביקורות')).toBeInTheDocument();
  });
});
