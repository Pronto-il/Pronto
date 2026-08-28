import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useActiveOrder, useBookingDraft, useEtaCountdown, useToolboxPosition } from '../shared/hooks';
import { ToolboxGraphic } from '../shared/components';
import { ReviewPromptModal } from '../features/booking';
import { celebrationKindFor, isInNewIssueFlow, resolveToolboxState } from './toolboxState';
import type { CelebrationKind } from './toolboxState';
import styles from './ActiveIssueToolbox.module.css';

/**
 * How long each celebration's `kind` stays set on the toolbox before it clears — i.e. the JS-side
 * window backing the CSS animation in `ToolboxGraphic.module.css`. Each must outlast its own
 * longest keyframe there:
 *  - `ARRIVED`: bounce 700ms / ring 700ms / dots 600ms + 100ms delay = 700ms.
 *  - `COMPLETED`: lid-pop 650ms / ring 850ms / sparkles 850ms + 110ms delay = 960ms — landing
 *    within the "roughly 1-1.5s" completion-animation target once this window is applied.
 */
const CELEBRATION_DURATIONS_MS: Record<CelebrationKind, number> = {
  ARRIVED: 900,
  COMPLETED: 1300,
};

/**
 * `sessionStorage` key prefix for "this order has already had this celebration".
 *
 * Deliberately `sessionStorage`, not `localStorage`: a celebration is a moment, and a moment
 * should not be suppressed forever. Per-tab-session is the right lifetime — a hard refresh while
 * the professional is standing at the door, or while the customer is looking at "the job is
 * done", must not replay it, but a genuinely new session may.
 *
 * Keyed per **order id AND kind** — `ARRIVED` and `COMPLETED` are two independent celebratory
 * moments in one order's life (see `celebrationKindFor`'s doc comment), so a customer who saw the
 * arrival celebration for order 7 must still see the completion one when that same order finishes,
 * and vice versa for a poll gap that skipped straight to `COMPLETED`.
 */
const CELEBRATED_KEY_PREFIX = 'pronto.toolbox.celebrated.';

function celebrationKey(orderId: number, kind: CelebrationKind): string {
  return `${CELEBRATED_KEY_PREFIX}${orderId}.${kind}`;
}

/** Storage access is wrapped because Safari private mode throws on access, and a thrown
 *  exception here would take the whole toolbox down over an animation. */
function hasAlreadyCelebrated(orderId: number, kind: CelebrationKind): boolean {
  try {
    return window.sessionStorage.getItem(celebrationKey(orderId, kind)) !== null;
  } catch {
    // Unreadable storage degrades to "not yet celebrated" — replaying a ~1s animation is a far
    // better failure than suppressing it permanently.
    return false;
  }
}

function markCelebrated(orderId: number, kind: CelebrationKind): void {
  try {
    window.sessionStorage.setItem(celebrationKey(orderId, kind), '1');
  } catch {
    // See above.
  }
}

/**
 * The floating, draggable toolbox: one continuous companion for the whole of a customer's live
 * order, rather than four separate controls.
 *
 * ```text
 * OPEN REQUEST ──booked──▶ LIVE ETA ──ARRIVED──▶ ✨ ──COMPLETED──▶ ✨ ──▶ REVIEW ──acknowledged──▶ hidden
 * "ההזמנה שלי"            "12 דק׳"           arrival          completion  "השאר ביקורת"
 * ```
 *
 * The two ✨ are independent one-shot celebrations (`celebrationKindFor`) — completion is not a
 * replay of arrival, and either can fire without the other (a poll gap can skip straight from
 * `ON_THE_WAY` to `COMPLETED`, in which case only the completion one plays). The toolbox does
 * **not** hide the moment completion's celebration ends — it settles into the existing REVIEW
 * state ("השאר ביקורת") exactly as it always has, until the customer reviews or dismisses.
 *
 * ## Where the lifecycle actually lives
 *
 * Not here. {@link resolveToolboxState} maps existing state — `selectActiveOrder`'s
 * `selection.state`, `useBookingDraft`'s draft, and `useEtaCountdown`'s figure — onto what to
 * render. This component owns three things only: the countdown subscription, the one-shot
 * celebration latch, and what a tap does.
 *
 * ## Two things this deliberately does not do
 *
 * **It never infers arrival from the countdown.** `useEtaCountdown` clamps at zero and reports
 * `isArriving`, but that only ever changes the *wording* of the ETA state. Promotion to
 * `ARRIVED`/`REVIEW` comes from the polled order status alone.
 *
 * **It does not offer a review before the backend will accept one.** `ReviewsService` rejects
 * any review whose order is not `COMPLETED` (`REVIEW_ORDER_NOT_COMPLETED`), so the review CTA is
 * bound to the existing `COMPLETED_UNACKNOWLEDGED` state rather than to `ARRIVED`. See the
 * `ARRIVED` branch in {@link resolveToolboxState}.
 *
 * ## When it hides (redesign §2)
 *
 * A shortcut back to the active order is pointless while the customer is already on that order's
 * screen. The rule reuses the exact route the tap would navigate to — `state.route` — and hides
 * the toolbox when the current path already equals it. No new state and no second definition of
 * "the active-order screen": if tapping would be a no-op navigation, the toolbox is not shown.
 * The `REVIEW` state has no `route` (its tap opens a modal in place, over whatever screen the
 * customer is on), so it is never hidden by this rule.
 *
 * ## Also hidden during new-issue creation (mobile-nav fix, 2026-08-28)
 *
 * A shortcut back to a *different*, already-existing order/draft has nothing to resume while the
 * customer is mid-creation of a new one, and only competes with the task in front of them. This
 * is a second, independent rule from the one above — route-classified via {@link
 * isInNewIssueFlow} rather than state-derived, and it applies regardless of `state.kind`
 * (including `REVIEW`, which the rule above never suppresses).
 */
export function ActiveIssueToolbox() {
  const navigate = useNavigate();
  const location = useLocation();
  const { selection, acknowledgeOrder } = useActiveOrder();
  const { draft } = useBookingDraft();
  const [isReviewPromptOpen, setIsReviewPromptOpen] = useState(false);

  // The single ETA subscription, fed by the order's own immutable arrival timestamp. Passing
  // `null` when there is no order keeps the hook's interval torn down.
  const { remainingMinutes, isArriving } = useEtaCountdown(selection?.order.expectedArrivalAt ?? null);

  const { top, left, elementRef, onPointerDown, onPointerMove, onPointerUp, isDragging, wasTap } =
    useToolboxPosition();

  const state = resolveToolboxState({ selection, draft, remainingMinutes, isArriving });

  /**
   * One-shot celebration latch, per **order id + kind**, per browser session.
   *
   * Two layers, and both are load-bearing:
   *
   *  - **`sessionStorage`** answers "did a *previous page load* in this tab already play this
   *    exact (order, kind) celebration?", which is what stops a hard refresh — at the customer's
   *    door, or right after the job finishes — from replaying it. This is also what makes
   *    repeated polling of the same `COMPLETED` state a no-op: every poll tick that still resolves
   *    to the same order/kind re-runs this effect (see the dependency array below) and finds the
   *    flag already set.
   *  - **`startedForRef`** answers "did *this component instance* already start this exact
   *    (order, kind) celebration?". Without it, React 18 StrictMode — which is on, see
   *    `main.tsx` — breaks the animation in development: it mounts effects twice, so the first
   *    pass would write the storage flag and arm the timer, the cleanup would clear that timer,
   *    and the second pass would read the flag it had just written, bail out early, and leave the
   *    celebration stuck on with nothing left to turn it off. Re-entering for the same instance
   *    re-arms the timer instead.
   *
   * Keyed by a composite `"{orderId}:{kind}"` string (not just order id) so `ARRIVED` and
   * `COMPLETED` are tracked independently: the effect re-runs when either the order or the kind
   * changes (e.g. the same order moving from `ARRIVED` to `COMPLETED`), triggering the completion
   * celebration even though the arrival one already played for this order — see
   * `celebrationKindFor`'s doc comment for why that is correct, not a double-fire.
   */
  const startedForRef = useRef<string | null>(null);
  const [celebration, setCelebration] = useState<CelebrationKind | null>(null);

  const celebrationOrderId = state.kind === 'ARRIVED' || state.kind === 'REVIEW' ? state.orderId : null;
  const celebrationKind = celebrationKindFor(state);
  useEffect(() => {
    if (celebrationOrderId === null || celebrationKind === null) {
      return;
    }
    const compositeKey = `${celebrationOrderId}:${celebrationKind}`;
    const startedHere = startedForRef.current === compositeKey;
    if (hasAlreadyCelebrated(celebrationOrderId, celebrationKind) && !startedHere) {
      return; // an earlier page load in this session (or an earlier poll tick) already played it
    }
    startedForRef.current = compositeKey;
    markCelebrated(celebrationOrderId, celebrationKind);
    setCelebration(celebrationKind);
    const timeoutId = window.setTimeout(() => setCelebration(null), CELEBRATION_DURATIONS_MS[celebrationKind]);
    return () => window.clearTimeout(timeoutId);
  }, [celebrationOrderId, celebrationKind]);

  // Hooks above, early return below — the toolbox mounts and unmounts as orders come and go, so
  // the hook order has to be stable across both states.
  if (state.kind === 'HIDDEN') {
    return null;
  }

  // Mobile-nav fix (2026-08-28): hidden everywhere in the new-issue/order-creation flow,
  // regardless of `state.kind` — including `REVIEW`, which has no `route` and would otherwise
  // survive the check below. See `isInNewIssueFlow`'s own doc comment for why.
  if (isInNewIssueFlow(location.pathname)) {
    return null;
  }

  // §2: don't offer a shortcut to a screen the customer is already on. `route` is exactly where
  // a tap would go, so equal path => the tap is a no-op => hide. `REVIEW` has no `route` (modal),
  // so it is never suppressed here.
  if ('route' in state && location.pathname === state.route) {
    return null;
  }

  function handleClick() {
    // A gesture that moved past the drag threshold already did its job by moving the toolbox.
    if (!wasTap()) {
      return;
    }
    // Re-checked rather than relying on the early return above: `handleClick` is a hoisted
    // function declaration, so TypeScript conservatively discards the outer narrowing inside it.
    if (state.kind === 'HIDDEN') {
      return;
    }
    if (state.kind === 'REVIEW') {
      // In place rather than navigating: a prompt that hijacks the screen to a full page has no
      // natural "not now", and rating a finished job is a 5-second interaction that should not
      // cost the customer whatever they were doing. Unchanged from the previous indicator.
      setIsReviewPromptOpen(true);
      return;
    }
    navigate(state.route);
  }

  function handleDismissReviewPrompt() {
    setIsReviewPromptOpen(false);
    if (state.kind === 'REVIEW') {
      // Whether they rated or chose "לא עכשיו": this order has been asked about. This is the
      // existing acknowledgement rule — `selectActiveOrder` then stops selecting the order, and
      // the toolbox disappears on the next render. No second "reviewed" flag anywhere.
      acknowledgeOrder(state.orderId);
    }
  }

  return (
    <>
      <button
        type="button"
        ref={elementRef}
        className={`${styles.toolbox} ${isDragging ? styles.dragging : ''}`}
        // Both axes are set here, from `useToolboxPosition` — vertical free-drag, horizontal
        // snap-to-nearest-edge (mobile-nav fix, 2026-08-28; see that hook's own doc comment).
        // Until the element has been measured neither is a real position, so it is rendered
        // invisible for that one frame rather than flashing at the wrong spot.
        style={
          top !== null && left !== null
            ? { top: `${top}px`, left: `${left}px` }
            : { top: 0, left: 0, visibility: 'hidden' }
        }
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onClick={handleClick}
        aria-label={state.accessibleName}
        title={state.accessibleName}
      >
        <ToolboxGraphic
          primaryText={state.primaryText}
          secondaryText={state.kind === 'ETA' ? state.secondaryText : undefined}
          celebration={celebration === 'ARRIVED' ? 'arrival' : celebration === 'COMPLETED' ? 'completion' : null}
          className={styles.graphic}
        />
      </button>

      {state.kind === 'REVIEW' && (
        <ReviewPromptModal
          isOpen={isReviewPromptOpen}
          orderId={state.orderId}
          onDismiss={handleDismissReviewPrompt}
        />
      )}
    </>
  );
}
