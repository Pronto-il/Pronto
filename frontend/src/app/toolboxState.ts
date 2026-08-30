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
 * The routes that make up booking creation — **the single source of truth for "is the customer
 * currently building an order?"**, and the only place that question is answered.
 *
 * Read straight off `router.tsx`'s guest-journey block:
 *
 * | Route | What it holds |
 * |---|---|
 * | `/issues/new` | describe → clarify → review → unsupported (one route, internal step machine) |
 * | `/matching` | the AI profession-matching transition screen |
 * | `/booking` | Standard: address → professionals → slot → confirm |
 * | `/sos-booking` | Pronto SOS: address → activation → live scan, until a professional is chosen |
 *
 * Every one of these sits between "an issue was described" and "an order exists" — exactly the
 * window in which the toolbox's "resume my order" shortcut has nothing useful to resume (it points
 * at a *different*, already-existing order or draft) and only competes with the task in front of
 * the customer.
 *
 * <h2>Why this was broken in Production</h2>
 *
 * This function used to match `/issues/:issueId/(matching|booking|sos-booking)`. Deferred
 * authentication flattened those routes to `/matching`, `/booking` and `/sos-booking` — issue
 * creation moved to the booking commit, so during matching and slot selection there is no issue id
 * to put in a URL. The routes changed; this matcher did not, so it silently stopped matching and
 * the toolbox reappeared throughout booking creation. `/issues/new` kept working purely because
 * that one route kept its path.
 *
 * The legacy `/issues/:id/...` forms are still recognised below. Not for the router — those paths
 * no longer resolve — but because `features/notifications/NotificationBell`,
 * `features/booking/OrderTrackingPage` and `features/professionals/ProfessionalProfilePage` still
 * *navigate* to them (a separate stale-route defect, reported not fixed here). If any of those
 * links is ever repaired by restoring the route, this rule must not need a second edit to keep up.
 *
 * <h2>Two deliberate exclusions</h2>
 *
 * `/professionals/:id` is **not** here even though it is reachable mid-booking. It is equally a
 * normal browsing route (it is part of the public guest journey), and the only signal separating
 * the two is `location.state`, which does not survive a refresh — so including it would make
 * visibility depend on how the customer arrived and flip after F5. `/orders/:id` is not here
 * either: that is order *tracking*, not creation, and the existing "don't link to the screen you
 * are already on" rule in `ActiveIssueToolbox` already covers it.
 *
 * Route-based rather than state-derived on purpose: the current path is known unambiguously and
 * synchronously from `useLocation()`, so this stays one explicit, independently testable function
 * — and, because `ActiveIssueToolbox` consults it during render rather than in an effect, the
 * toolbox cannot flash before being hidden on a deep link or a refresh.
 */
const BOOKING_FLOW_ROUTES = ['/issues/new', '/matching', '/booking', '/sos-booking'] as const;

export function isInBookingFlow(pathname: string): boolean {
  // Exact match, or a nested path beneath one of them — never a prefix match on the bare string,
  // which would wrongly catch a sibling like `/bookings` or `/matching-history`.
  const isCurrentShape = BOOKING_FLOW_ROUTES.some(
    (route) => pathname === route || pathname.startsWith(`${route}/`),
  );
  const isLegacyShape = /^\/issues\/[^/]+\/(matching|booking|sos-booking)(\/|$)/.test(pathname);
  return isCurrentShape || isLegacyShape;
}
