import { describe, expect, it } from 'vitest';
import { celebrationKindFor, isInNewIssueFlow, resolveToolboxState } from './toolboxState';
import { selectActiveOrder } from '../shared/hooks';
import type { ActiveOrderSelection } from '../shared/hooks';
import type { BookingDraft } from '../shared/hooks/bookingDraftContext';
import type { OrderStatus, OrderSummary } from '../shared/api/bookings';

/**
 * The floating toolbox's lifecycle.
 *
 * The property worth pinning down hardest is the one the brief calls out twice: **arrival is a
 * server-side order status, never an expired countdown.** A timer that hits zero because the
 * professional is stuck in traffic must not congratulate the customer and offer to collect a
 * review for a visit that has not happened — and the backend would refuse that review anyway
 * (`ReviewsService` rejects anything that is not `COMPLETED`).
 */

function order(overrides: Partial<OrderSummary> & { orderStatus: OrderStatus }): OrderSummary {
  return {
    id: 1,
    issueId: 10,
    professionalId: 5,
    professionalName: 'ישראל ישראלי',
    bookedStart: '2026-08-28T09:00:00Z',
    bookedEnd: '2026-08-28T11:00:00Z',
    expectedArrivalAt: null,
    finalPrice: 350,
    createdAt: '2026-08-27T08:00:00Z',
    updatedAt: '2026-08-27T08:00:00Z',
    ...overrides,
  };
}

function selection(state: ActiveOrderSelection['state'], o?: Partial<OrderSummary>): ActiveOrderSelection {
  return { order: order({ orderStatus: 'CONFIRMED', ...o }), state };
}

const draft = { version: 2, ownerId: 2, stage: 'DESCRIBE', issueId: 77 } as unknown as BookingDraft;

const NO_ETA = { remainingMinutes: null, isArriving: false };

describe('state 1 — open request, nobody booked yet', () => {
  it('shows ההזמנה שלי for a PENDING/CONFIRMED order and routes to it', () => {
    const state = resolveToolboxState({ selection: selection('PENDING_CONFIRMED'), draft: null, ...NO_ETA });

    expect(state).toMatchObject({ kind: 'REQUEST', primaryText: 'ההזמנה שלי', route: '/orders/1' });
  });

  it('shows ההזמנה שלי for a draft with no order at all, routed by the existing draft resolver', () => {
    const state = resolveToolboxState({ selection: null, draft, ...NO_ETA });

    expect(state.kind).toBe('REQUEST');
    if (state.kind !== 'REQUEST') return;
    expect(state.primaryText).toBe('ההזמנה שלי');
    // Whatever `resolveDraftRoute` says — asserted as "not empty" rather than duplicating its rules.
    expect(state.route.length).toBeGreaterThan(1);
  });

  it('is hidden with neither an order nor a draft', () => {
    expect(resolveToolboxState({ selection: null, draft: null, ...NO_ETA })).toEqual({ kind: 'HIDDEN' });
  });

  it('prefers the placed order over a leftover draft', () => {
    const state = resolveToolboxState({ selection: selection('PENDING_CONFIRMED'), draft, ...NO_ETA });

    expect(state).toMatchObject({ kind: 'REQUEST', route: '/orders/1' });
  });
});

describe('state 2 — booked, live ETA', () => {
  it('renders the countdown figure over the secondary line', () => {
    const state = resolveToolboxState({
      selection: selection('ON_THE_WAY', { expectedArrivalAt: '2026-08-28T09:12:00Z' }),
      draft: null,
      remainingMinutes: 12,
      isArriving: false,
    });

    expect(state).toMatchObject({
      kind: 'ETA',
      primaryText: '12 דק׳',
      secondaryText: 'עד ההגעה',
      route: '/orders/1',
      accessibleName: 'בעל המקצוע צפוי להגיע בעוד 12 דקות',
    });
  });

  it('never renders a negative figure — a passed ETA becomes the arriving-now copy, not "-3 דק׳"', () => {
    // `useEtaCountdown` already clamps at 0; this pins the toolbox's own handling of that case.
    const state = resolveToolboxState({
      selection: selection('ON_THE_WAY', { expectedArrivalAt: '2026-08-28T09:00:00Z' }),
      draft: null,
      remainingMinutes: 0,
      isArriving: true,
    });

    expect(state.kind).toBe('ETA');
    if (state.kind !== 'ETA') return;
    expect(state.remainingMinutes).toBe(0);
    expect(state.primaryText).toBe('מגיע/ה עכשיו');
    expect(state.primaryText).not.toContain('-');
  });

  it('falls back to static wording when the order somehow has no arrival timestamp', () => {
    const state = resolveToolboxState({
      selection: selection('ON_THE_WAY', { expectedArrivalAt: null }),
      draft: null,
      ...NO_ETA,
    });

    expect(state).toMatchObject({ kind: 'ETA', primaryText: 'בדרך', remainingMinutes: null });
  });
});

describe('state 3 — arrival is a status, not a stopwatch', () => {
  it('a countdown at zero does NOT produce the review state', () => {
    // The single most important rule in this file.
    const state = resolveToolboxState({
      selection: selection('ON_THE_WAY', { expectedArrivalAt: '2026-08-28T09:00:00Z' }),
      draft: null,
      remainingMinutes: 0,
      isArriving: true,
    });

    expect(state.kind).toBe('ETA');
    expect(state.kind).not.toBe('REVIEW');
    expect(state.kind).not.toBe('ARRIVED');
  });

  it('the real ARRIVED status produces the arrived state, with the existing stepper copy', () => {
    const state = resolveToolboxState({ selection: selection('ARRIVED'), draft: null, ...NO_ETA });

    expect(state).toMatchObject({
      kind: 'ARRIVED',
      primaryText: 'הגיע אליך',
      route: '/orders/1',
      accessibleName: 'בעל המקצוע הגיע לכתובת שלך',
    });
  });

  it('ARRIVED does not offer a review, which the backend would reject before COMPLETED', () => {
    const state = resolveToolboxState({ selection: selection('ARRIVED'), draft: null, ...NO_ETA });

    expect(state.kind).not.toBe('REVIEW');
    if (state.kind !== 'ARRIVED') return;
    expect(state.primaryText).not.toBe('השאר ביקורת');
  });

  it('celebrates ARRIVED with the arrival kind', () => {
    expect(celebrationKindFor(resolveToolboxState({ selection: selection('ARRIVED'), draft: null, ...NO_ETA }))).toBe('ARRIVED');
  });

  it('celebrates REVIEW (i.e. the order just reached COMPLETED) with the completion kind, even when a poll gap skipped ARRIVED', () => {
    expect(
      celebrationKindFor(resolveToolboxState({ selection: selection('COMPLETED_UNACKNOWLEDGED'), draft: null, ...NO_ETA })),
    ).toBe('COMPLETED');
  });

  it('does not celebrate the request or ETA states', () => {
    expect(celebrationKindFor(resolveToolboxState({ selection: selection('PENDING_CONFIRMED'), draft: null, ...NO_ETA }))).toBeNull();
    expect(
      celebrationKindFor(
        resolveToolboxState({ selection: selection('ON_THE_WAY'), draft: null, remainingMinutes: 5, isArriving: false }),
      ),
    ).toBeNull();
  });
});

describe('state 4 — review', () => {
  it('shows השאר ביקורת for a completed, unacknowledged order', () => {
    const state = resolveToolboxState({ selection: selection('COMPLETED_UNACKNOWLEDGED'), draft: null, ...NO_ETA });

    expect(state).toMatchObject({
      kind: 'REVIEW',
      orderId: 1,
      primaryText: 'השאר ביקורת',
      accessibleName: 'השאר ביקורת על הביקור',
    });
  });

  it('disappears once the existing acknowledgement rule resolves the prompt', () => {
    // Not a new boolean: `selectActiveOrder` is the rule, and an acknowledged order selects null.
    const completed = order({ orderStatus: 'COMPLETED', id: 1 });

    expect(selectActiveOrder([completed], [])).toMatchObject({ state: 'COMPLETED_UNACKNOWLEDGED' });
    expect(selectActiveOrder([completed], [1])).toBeNull();
    expect(resolveToolboxState({ selection: null, draft: null, ...NO_ETA })).toEqual({ kind: 'HIDDEN' });
  });
});

describe('selectActiveOrder now covers ARRIVED', () => {
  it('selects an ARRIVED order instead of returning null', () => {
    // Regression guard: ARRIVED matched none of the original three tiers, so the floating
    // indicator vanished the moment the professional turned up.
    expect(selectActiveOrder([order({ orderStatus: 'ARRIVED' })], [])).toMatchObject({ state: 'ARRIVED' });
  });

  it('ranks an arrived professional above one still travelling', () => {
    const travelling = order({ id: 1, orderStatus: 'ON_THE_WAY', expectedArrivalAt: '2026-08-28T09:05:00Z' });
    const atTheDoor = order({ id: 2, orderStatus: 'ARRIVED' });

    expect(selectActiveOrder([travelling, atTheDoor], [])).toMatchObject({ state: 'ARRIVED', order: { id: 2 } });
  });

  it('keeps an ARRIVED order counting as live, so notification polling continues mid-visit', async () => {
    const { isLiveActiveOrder } = await import('../shared/hooks');

    expect(isLiveActiveOrder(selection('ARRIVED'))).toBe(true);
    expect(isLiveActiveOrder(selection('COMPLETED_UNACKNOWLEDGED'))).toBe(false);
  });
});

describe('isInNewIssueFlow (mobile-nav fix)', () => {
  it('matches every route in the new-issue / order-creation flow', () => {
    expect(isInNewIssueFlow('/issues/new')).toBe(true);
    expect(isInNewIssueFlow('/issues/42/matching')).toBe(true);
    expect(isInNewIssueFlow('/issues/42/booking')).toBe(true);
    expect(isInNewIssueFlow('/issues/42/sos-booking')).toBe(true);
  });

  it('matches nested paths under any of the flow routes', () => {
    expect(isInNewIssueFlow('/issues/new/anything')).toBe(true);
    expect(isInNewIssueFlow('/issues/42/booking/confirm')).toBe(true);
  });

  it('does not match unrelated customer routes, including other /orders and /issues paths', () => {
    expect(isInNewIssueFlow('/')).toBe(false);
    expect(isInNewIssueFlow('/orders')).toBe(false);
    expect(isInNewIssueFlow('/orders/42')).toBe(false);
    expect(isInNewIssueFlow('/orders/42/review')).toBe(false);
    expect(isInNewIssueFlow('/favorites')).toBe(false);
    expect(isInNewIssueFlow('/profile')).toBe(false);
    // A single issue id segment with no recognised flow suffix — not one of the four routes.
    expect(isInNewIssueFlow('/issues/42')).toBe(false);
    expect(isInNewIssueFlow('/issues/42/somethingelse')).toBe(false);
  });
});
