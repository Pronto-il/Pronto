import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ProntoSosScreen from '../sos/ProntoSosScreen';
import CompletionReviewPage from './CompletionReviewPage';
import { selectActiveOrder } from '../../shared/hooks';
import type { OrderSummary, SosRequestResponse, SosRequestStatus } from '../../shared/api';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';
import { ActiveOrderContext } from '../../shared/hooks/activeOrderContext';
import type { ActiveOrderContextValue } from '../../shared/hooks/activeOrderContext';

/**
 * **A review is offered for a job that was done — not for a flow that ended.**
 *
 * The authoritative state is `bookings.entity.OrderStatus.COMPLETED` on the *order*, which is also
 * the only thing `reviews.service.ReviewsService.createReview` accepts (anything else is
 * `409 REVIEW_ORDER_NOT_COMPLETED`, covered by `ReviewsServiceTest`). Three of the four surfaces
 * that can offer a review already asked exactly that question. The SOS screen did not: it asked its
 * own `DONE` phase, which comes from the **SOS request's** status — and the two can disagree,
 * because `SosOfferService.complete` marks the request `COMPLETED` unconditionally and then
 * completes the order through `completeIfOnTheWay`, which fires only for `ON_THE_WAY`/`ARRIVED`,
 * ignores its own result, and is skipped when there is no order at all.
 */

const getOrder = vi.hoisted(() => vi.fn());
const useSosRequest = vi.hoisted(() => vi.fn());

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return { ...actual, getOrder, createReview: vi.fn() };
});

vi.mock('../../shared/hooks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/hooks')>();
  return { ...actual, useSosRequest };
});

function sosRequest(status: SosRequestStatus, orderId: number | null = 555): SosRequestResponse {
  return {
    id: 1,
    issueId: 777,
    status,
    orderId,
    serviceCity: 'תל אביב-יפו',
    serviceStreet: 'הרצל',
    serviceHouseNumber: '10',
    offerCount: 3,
    acceptedCandidateCount: 1,
    canExpandSearch: false,
    selectedOfferId: 9,
  } as unknown as SosRequestResponse;
}

function order(orderStatus: string) {
  return { id: 555, orderStatus, issueId: 777, professionalName: 'אבי כהן', bookedStart: '2026-09-03T10:00:00Z' };
}

function renderSosScreen() {
  const auth = { user: { id: 42, role: 'CUSTOMER' }, token: 'jwt', isLoading: false } as unknown as AuthContextValue;
  const draftValue = { draft: null, updateDraft: vi.fn(), clearDraft: vi.fn() } as unknown as BookingDraftContextValue;
  return render(
    <MemoryRouter initialEntries={['/sos-booking']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={draftValue}>
          <ProntoSosScreen sosRequestId={1} onRetry={vi.fn()} isRetrying={false} />
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

const REVIEW_CTA = 'השארת ביקורת';

beforeEach(() => {
  getOrder.mockReset();
  useSosRequest.mockReset().mockReturnValue({
    request: sosRequest('COMPLETED'),
    candidates: [],
    selectionOpen: false,
    isLoading: false,
    error: null,
    refetch: vi.fn(),
    realtimeStatus: 'connected',
  });
});

afterEach(() => vi.clearAllMocks());

describe('the SOS screen offers a review only for a completed order', () => {
  it('offers it when the order really is COMPLETED', async () => {
    getOrder.mockResolvedValue(order('COMPLETED'));
    renderSosScreen();

    expect(await screen.findByRole('button', { name: REVIEW_CTA })).toBeInTheDocument();
  });

  it('does not offer it when the SOS attempt finished but the order did not', async () => {
    // The exact disagreement `SosOfferService.complete` can leave behind.
    getOrder.mockResolvedValue(order('CONFIRMED'));
    renderSosScreen();

    await waitFor(() => expect(getOrder).toHaveBeenCalledWith(555));
    expect(screen.queryByRole('button', { name: REVIEW_CTA })).not.toBeInTheDocument();
    // The way to see what happened is still there — only the review is gated.
    expect(screen.getByRole('button', { name: 'לפרטי ההזמנה' })).toBeInTheDocument();
  });

  it('does not offer it when the order was cancelled', async () => {
    getOrder.mockResolvedValue(order('CANCELLED'));
    renderSosScreen();

    await waitFor(() => expect(getOrder).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: REVIEW_CTA })).not.toBeInTheDocument();
  });

  it('does not offer it when the order lookup fails', async () => {
    // An unanswerable question is not a yes: a review that cannot be created is worse than none.
    getOrder.mockRejectedValue(new Error('network'));
    renderSosScreen();

    await waitFor(() => expect(getOrder).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: REVIEW_CTA })).not.toBeInTheDocument();
  });

  it.each(['EXPIRED', 'CANCELLED', 'FAILED'] as SosRequestStatus[])(
    'asks nothing and offers nothing when the attempt ended as %s',
    async (status) => {
      // Nobody accepted, or it was called off. These land on the "ended" card, which offers a retry
      // and the regular queue — never a review.
      useSosRequest.mockReturnValue({
        request: sosRequest(status),
        candidates: [],
        selectionOpen: false,
        isLoading: false,
        error: null,
        refetch: vi.fn(),
        realtimeStatus: 'connected',
      });
      renderSosScreen();

      expect(screen.queryByRole('button', { name: REVIEW_CTA })).not.toBeInTheDocument();
      expect(getOrder).not.toHaveBeenCalled();
    },
  );

  it('asks nothing when the attempt completed without ever producing an order', async () => {
    useSosRequest.mockReturnValue({
      request: sosRequest('COMPLETED', null),
      candidates: [],
      selectionOpen: false,
      isLoading: false,
      error: null,
      refetch: vi.fn(),
      realtimeStatus: 'connected',
    });
    renderSosScreen();

    expect(screen.queryByRole('button', { name: REVIEW_CTA })).not.toBeInTheDocument();
    expect(getOrder).not.toHaveBeenCalled();
  });
});

describe('the floating prompt selects only completed orders', () => {
  const summary = (orderStatus: string): OrderSummary =>
    ({
      id: 555,
      orderStatus,
      createdAt: '2026-09-03T08:00:00Z',
      updatedAt: '2026-09-03T12:00:00Z',
    }) as unknown as OrderSummary;

  it('nominates a COMPLETED order for review', () => {
    expect(selectActiveOrder([summary('COMPLETED')], [])).toMatchObject({ state: 'COMPLETED_UNACKNOWLEDGED' });
  });

  it.each(['CANCELLED', 'REJECTED', 'EXPIRED'])('never nominates a %s order', (status) => {
    expect(selectActiveOrder([summary(status)], [])).toBeNull();
  });

  it('drops the nomination once the customer has said "לא עכשיו"', () => {
    // The acknowledgement rule is untouched: dismissing a genuinely completed job still ends the
    // prompt for it, and does not resurrect an older completed order in its place.
    expect(selectActiveOrder([summary('COMPLETED')], [555])).toBeNull();
  });
});

describe('the review page itself', () => {
  function renderReviewPage(orderStatus: string) {
    getOrder.mockResolvedValue(order(orderStatus));
    const auth = { user: { id: 42, role: 'CUSTOMER' }, token: 'jwt', isLoading: false } as unknown as AuthContextValue;
    const activeOrder = {
      selection: null,
      hasLiveOrder: false,
      acknowledgeOrder: vi.fn(),
      refetch: vi.fn(),
    } as unknown as ActiveOrderContextValue;
    return render(
      <MemoryRouter initialEntries={['/orders/555/review']}>
        <AuthContext.Provider value={auth}>
          <ActiveOrderContext.Provider value={activeOrder}>
            <Routes>
              <Route path="/orders/:orderId/review" element={<CompletionReviewPage />} />
              <Route path="/orders" element={<div>orders-screen</div>} />
            </Routes>
          </ActiveOrderContext.Provider>
        </AuthContext.Provider>
      </MemoryRouter>,
    );
  }

  it('refuses to collect a review for an order that is not completed', async () => {
    renderReviewPage('CANCELLED');

    expect(await screen.findByText(/ההזמנה הזו עדיין לא הושלמה/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'שליחת ביקורת' })).not.toBeInTheDocument();
  });

  it('collects one for a completed order, and keeps "לא עכשיו" working', async () => {
    const user = userEvent.setup();
    renderReviewPage('COMPLETED');

    expect(await screen.findByRole('button', { name: 'שליחת ביקורת' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'לא עכשיו' }));
    expect(screen.getByText('orders-screen')).toBeInTheDocument();
  });
});
