import { resolveActiveOrderRoute, resolveDraftRoute } from '../shared/hooks';
import type { ActiveOrderSelection } from '../shared/hooks';
import type { BookingDraft } from '../shared/hooks/bookingDraftContext';

/**
 * The floating toolbox's lifecycle, resolved in one place.
 *
 * Every rule below delegates to state that already exists — `selectActiveOrder`'s
 * `selection.state`, `useBookingDraft`'s draft, `useEtaCountdown`'s figure, and the existing
 * acknowledgement rule that already decides when a review prompt is over. Nothing here is a
 * second source of truth; this module only maps those to what the toolbox should render, so the
 * component stays free of lifecycle branching.
 */

export type ToolboxState =
  /** An open request with nobody booked yet — a draft, or a `PENDING`/`CONFIRMED` order. */
  | { kind: 'REQUEST'; route: string; primaryText: string; accessibleName: string }
  /** Booked and travelling, with a live countdown off the order's own arrival timestamp. */
  | {
      kind: 'ETA';
      route: string;
      orderId: number;
      remainingMinutes: number | null;
      primaryText: string;
      secondaryText?: string;
      accessibleName: string;
    }
  /** The real `ARRIVED` order status — the professional is at the door, job not yet done. */
  | { kind: 'ARRIVED'; route: string; orderId: number; primaryText: string; accessibleName: string }
  /** `COMPLETED` and not yet acknowledged — the existing review-prompt state. */
  | { kind: 'REVIEW'; orderId: number; primaryText: string; accessibleName: string }
  | { kind: 'HIDDEN' };

export interface ResolveToolboxStateInput {
  selection: ActiveOrderSelection | null;
  draft: BookingDraft | null;
  /** From `useEtaCountdown(order.expectedArrivalAt)` — already clamped at 0 and already derived
   *  from the absolute timestamp rather than a decremented counter. Never recomputed here. */
  remainingMinutes: number | null;
  /** From the same hook: the countdown has reached zero. Display-only — it never promotes the
   *  toolbox to `ARRIVED` or `REVIEW`, which only the order status can do. */
  isArriving: boolean;
}

/** Existing copy, reused rather than reworded: `OrderProgressStepper`'s `ARRIVED` step. */
const ARRIVED_TEXT = 'הגיע אליך';
/** Existing copy from the indicator this toolbox replaced (`describeState`'s `isArriving` branch). */
const ARRIVING_NOW_TEXT = 'מגיע/ה עכשיו';

export function resolveToolboxState({
  selection,
  draft,
  remainingMinutes,
  isArriving,
}: ResolveToolboxStateInput): ToolboxState {
  if (selection) {
    switch (selection.state) {
      case 'COMPLETED_UNACKNOWLEDGED':
        return {
          kind: 'REVIEW',
          orderId: selection.order.id,
          primaryText: 'השאר ביקורת',
          accessibleName: 'השאר ביקורת על הביקור',
        };

      case 'ARRIVED':
        return {
          kind: 'ARRIVED',
          route: resolveActiveOrderRoute(selection),
          orderId: selection.order.id,
          primaryText: ARRIVED_TEXT,
          accessibleName: 'בעל המקצוע הגיע לכתובת שלך',
        };

      case 'ON_THE_WAY': {
        // `expectedArrivalAt` is set exactly once, at the ON_THE_WAY transition, and is null for
        // an order that somehow reached this state without one. Fall back to the same static
        // "on the way" wording the previous indicator used rather than rendering an empty timer.
        if (remainingMinutes === null) {
          return {
            kind: 'ETA',
            route: resolveActiveOrderRoute(selection),
            orderId: selection.order.id,
            remainingMinutes: null,
            primaryText: 'בדרך',
            accessibleName: 'בעל המקצוע בדרך אליך',
          };
        }
        // Zero is a legitimate, expected display value: the promised minute has passed but the
        // professional has not pressed "arrived" yet. It must NOT be treated as arrival.
        if (isArriving) {
          return {
            kind: 'ETA',
            route: resolveActiveOrderRoute(selection),
            orderId: selection.order.id,
            remainingMinutes: 0,
            primaryText: ARRIVING_NOW_TEXT,
            accessibleName: 'בעל המקצוע מגיע עכשיו',
          };
        }
        return {
          kind: 'ETA',
          route: resolveActiveOrderRoute(selection),
          orderId: selection.order.id,
          remainingMinutes,
          primaryText: `${remainingMinutes} דק׳`,
          secondaryText: 'עד ההגעה',
          accessibleName: `בעל המקצוע צפוי להגיע בעוד ${remainingMinutes} דקות`,
        };
      }

      case 'PENDING_CONFIRMED':
      default:
        return {
          kind: 'REQUEST',
          route: resolveActiveOrderRoute(selection),
          primaryText: 'ההזמנה שלי',
          accessibleName: 'ההזמנה שלי',
        };
    }
  }

  // No placed order, but an issue the customer started and has not booked yet. Same wording as
  // the order-side REQUEST state: from the customer's point of view it is the same one thing in
  // progress, which is the whole point of the toolbox being a single continuous companion.
  if (draft) {
    return {
      kind: 'REQUEST',
      route: resolveDraftRoute(draft),
      primaryText: 'ההזמנה שלי',
      accessibleName: 'ההזמנה שלי',
    };
  }

  return { kind: 'HIDDEN' };
}

/**
 * The two independent one-shot celebrations the toolbox can play, keyed by order id in
 * `ActiveIssueToolbox.tsx` so each fires at most once *per order, per kind* — arriving and
 * finishing are two separate moments in one order's life, not one moment shared between them.
 */
export type CelebrationKind = 'ARRIVED' | 'COMPLETED';

/**
 * Which one-shot celebration, if any, entering `state` should fire.
 *
 * `REVIEW` (the order just reached `COMPLETED` and is awaiting a review — see
 * `resolveToolboxState`'s `COMPLETED_UNACKNOWLEDGED` branch) maps to `'COMPLETED'` and is not
 * redundant with `'ARRIVED'`: `GET /api/bookings/orders/me` is polled, so a short visit can go
 * `ON_THE_WAY -> COMPLETED` between two ticks and the client never observes `ARRIVED` at all.
 * Without this, those customers would never see *any* celebration. When both are observed (the
 * normal case), each plays its own animation once — the completion animation is a genuinely new,
 * distinct celebratory moment, not a replay of the arrival one.
 */
export function celebrationKindFor(state: ToolboxState): CelebrationKind | null {
  if (state.kind === 'ARRIVED') {
    return 'ARRIVED';
  }
  if (state.kind === 'REVIEW') {
    return 'COMPLETED';
  }
  return null;
}

/**
 * The four routes that make up the new-issue / order-creation flow (`router.tsx`'s
 * `RequireAuth role="CUSTOMER"` group): `/issues/new` (description + AI classification, one
 * route holding its own internal step machine), `/issues/:issueId/matching` (the AI-matching
 * transition screen), `/issues/:issueId/booking` (Standard professional selection / slot /
 * confirm), and `/issues/:issueId/sos-booking` (the Pronto SOS equivalent).
 *
 * Every one of these sits somewhere between "an issue was described" and "an order exists" —
 * exactly the window in which the toolbox's "resume my order" shortcut has nothing useful to
 * resume (it would point at a *different*, already-existing order or draft) and only competes
 * with the task actually in front of the customer (mobile-nav fix, 2026-08-28).
 *
 * Route-based rather than derived from component state on purpose: the route the customer is on
 * is already known unambiguously (`useLocation()`), so classifying it here keeps the rule one
 * explicit, independently testable function instead of another branch woven into
 * {@link resolveToolboxState} or the component itself.
 */
export function isInNewIssueFlow(pathname: string): boolean {
  return (
    pathname === '/issues/new' ||
    pathname.startsWith('/issues/new/') ||
    /^\/issues\/[^/]+\/(matching|booking|sos-booking)(\/|$)/.test(pathname)
  );
}
