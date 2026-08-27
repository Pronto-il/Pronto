import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useActiveOrder, useBookingDraft, useEtaCountdown, useToolboxPosition } from '../shared/hooks';
import { ToolboxGraphic } from '../shared/components';
import { ReviewPromptModal } from '../features/booking';
import { isCelebrationState, resolveToolboxState } from './toolboxState';
import styles from './ActiveIssueToolbox.module.css';

/** §"Animation"'s 600-900ms window. Must outlast the longest keyframe in
 *  `ToolboxGraphic.module.css` (lid 800ms, sparkles 700ms + 170ms delay = 870ms). */
const CELEBRATION_MS = 900;

/**
 * `sessionStorage` key prefix for "this order has already had its arrival celebration".
 *
 * Deliberately `sessionStorage`, not `localStorage`: the celebration is a moment, and a moment
 * should not be suppressed forever. Per-tab-session is the right lifetime — a hard refresh while
 * the professional is standing at the door must not replay it, but a genuinely new session may.
 * Keyed per order id so a later job in the same session still gets its own.
 */
const CELEBRATED_KEY_PREFIX = 'pronto.toolbox.celebrated.';

function celebrationKey(orderId: number): string {
  return `${CELEBRATED_KEY_PREFIX}${orderId}`;
}

/** Storage access is wrapped because Safari private mode throws on access, and a thrown
 *  exception here would take the whole toolbox down over an animation. */
function hasAlreadyCelebrated(orderId: number): boolean {
  try {
    return window.sessionStorage.getItem(celebrationKey(orderId)) !== null;
  } catch {
    // Unreadable storage degrades to "not yet celebrated" — replaying an 800ms animation is a
    // far better failure than suppressing it permanently.
    return false;
  }
}

function markCelebrated(orderId: number): void {
  try {
    window.sessionStorage.setItem(celebrationKey(orderId), '1');
  } catch {
    // See above.
  }
}

/**
 * The floating, draggable toolbox: one continuous companion for the whole of a customer's live
 * order, rather than four separate controls.
 *
 * ```text
 * OPEN REQUEST ──booked──▶ LIVE ETA ──ARRIVED──▶ ✨ ──COMPLETED──▶ REVIEW ──acknowledged──▶ hidden
 * "ההזמנה שלי"            "12 דק׳"                                "השאר ביקורת"
 * ```
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
 */
export function ActiveIssueToolbox() {
  const navigate = useNavigate();
  const { selection, acknowledgeOrder } = useActiveOrder();
  const { draft } = useBookingDraft();
  const [isReviewPromptOpen, setIsReviewPromptOpen] = useState(false);

  // The single ETA subscription, fed by the order's own immutable arrival timestamp. Passing
  // `null` when there is no order keeps the hook's interval torn down.
  const { remainingMinutes, isArriving } = useEtaCountdown(selection?.order.expectedArrivalAt ?? null);

  const { position, elementRef, onPointerDown, onPointerMove, onPointerUp, isDragging, wasTap } =
    useToolboxPosition();

  const state = resolveToolboxState({ selection, draft, remainingMinutes, isArriving });

  /**
   * One-shot celebration latch, per order, per browser session.
   *
   * Two layers, and both are load-bearing:
   *
   *  - **`sessionStorage`** answers "did a *previous page load* in this tab already celebrate this
   *    order?", which is what stops a hard refresh at the customer's door from replaying it.
   *  - **`startedForOrderRef`** answers "did *this component instance* already start it?". Without
   *    it, React 18 StrictMode — which is on, see `main.tsx` — breaks the animation in
   *    development: it mounts effects twice, so the first pass would write the storage flag and
   *    arm the timer, the cleanup would clear that timer, and the second pass would read the flag
   *    it had just written, bail out early, and leave `isCelebrating` stuck true with nothing left
   *    to turn it off. Re-entering for the same instance re-arms the timer instead.
   */
  const startedForOrderRef = useRef<number | null>(null);
  const [isCelebrating, setIsCelebrating] = useState(false);

  const celebrationOrderId = isCelebrationState(state) ? state.orderId : null;
  useEffect(() => {
    if (celebrationOrderId === null) {
      return;
    }
    const startedHere = startedForOrderRef.current === celebrationOrderId;
    if (hasAlreadyCelebrated(celebrationOrderId) && !startedHere) {
      return; // an earlier page load in this session already played it
    }
    startedForOrderRef.current = celebrationOrderId;
    markCelebrated(celebrationOrderId);
    setIsCelebrating(true);
    const timeoutId = window.setTimeout(() => setIsCelebrating(false), CELEBRATION_MS);
    return () => window.clearTimeout(timeoutId);
  }, [celebrationOrderId]);

  // Hooks above, early return below — the toolbox mounts and unmounts as orders come and go, so
  // the hook order has to be stable across both states.
  if (state.kind === 'HIDDEN') {
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
        // Until the element has been measured there is no correct place to put it, so it is
        // rendered invisible for that one frame rather than flashing at the top-left corner.
        style={
          position
            ? { left: `${position.x}px`, top: `${position.y}px` }
            : { left: 0, top: 0, visibility: 'hidden' }
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
          celebrating={isCelebrating}
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
