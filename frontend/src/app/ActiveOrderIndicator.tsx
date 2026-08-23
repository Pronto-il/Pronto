import { useEffect, useState } from 'react';
import type { JSX } from 'react';
import { useNavigate } from 'react-router-dom';
import { Clock, Truck, Star } from 'lucide-react';
import { useActiveOrder, useEtaCountdown, resolveActiveOrderRoute } from '../shared/hooks';
import type { ActiveOrderIndicatorState } from '../shared/hooks';
import { ReviewPromptModal } from '../features/booking';
import styles from './ActiveOrderIndicator.module.css';

/**
 * Floating, `position: fixed` circular indicator for the customer's single
 * highest-priority active order (`docs/architecture/active-booking-floating-indicator.md`
 * §5-§7). Deliberately structurally separate from `BookingDraftIndicator` (§6.3) — mounted
 * as a sibling of `<main>` in `AppLayout`, not inside the top nav.
 *
 * Almost no business logic lives here: `useActiveOrder()` already ran the priority-selection
 * algorithm, `useEtaCountdown()` already computes the live remaining-minutes figure from
 * the persisted `expectedArrivalAt` timestamp — this component only maps `selection.state`
 * to an icon/label and to what clicking it does.
 *
 * **Review prompt (`COMPLETED_UNACKNOWLEDGED`)**: opens `ReviewPromptModal` in place instead of
 * navigating to `/orders/:id/review`. Two reasons. It is a prompt, and a prompt that hijacks the
 * screen to a full page has no natural "not now" — the customer had to use the back button, which
 * left the bubble sitting there afterwards. And rating a finished job is a 5-second interaction
 * that shouldn't cost the customer whatever they were doing. Both the modal's actions —
 * submitting and "לא עכשיו" — acknowledge the order, so the prompt is asked once and does not
 * come back. `useActiveOrder`'s selection rule only ever offers the *latest* completed order, so
 * dismissing one never promotes an older one behind it (see `selectActiveOrder`).
 *
 * `/orders/:id/review` is unchanged and still reachable from order tracking and the SOS
 * completion screen — this only changes what the floating prompt does.
 */
/** Toggled on `<body>` while the indicator is on screen, so `AppLayout.module.css` can add
 *  just enough mobile scroll clearance for it — see that file's `:global(body.…)` rule.
 *  A body class rather than page padding, because the indicator is a sibling of `<main>` and
 *  every screen with a bottom CTA (booking steps, the profile's favourite button) is affected
 *  (MS5 design doc §3.G). */
const BODY_CLASS = 'has-active-order-indicator';

export function ActiveOrderIndicator() {
  const navigate = useNavigate();
  const { selection, acknowledgeOrder } = useActiveOrder();
  const { remainingMinutes, isArriving } = useEtaCountdown(selection?.order.expectedArrivalAt ?? null);
  const [isReviewPromptOpen, setIsReviewPromptOpen] = useState(false);

  const isVisible = Boolean(selection);
  useEffect(() => {
    if (!isVisible) {
      return;
    }
    document.body.classList.add(BODY_CLASS);
    return () => document.body.classList.remove(BODY_CLASS);
  }, [isVisible]);

  if (!selection) {
    return null;
  }

  const isReviewPrompt = selection.state === 'COMPLETED_UNACKNOWLEDGED';
  const { icon, label, colorClass } = describeState(selection.state, remainingMinutes, isArriving);

  function handleDismissReviewPrompt() {
    setIsReviewPromptOpen(false);
    if (selection) {
      // Whether they rated or chose "לא עכשיו": this order has been asked about.
      acknowledgeOrder(selection.order.id);
    }
  }

  return (
    <>
      <button
        type="button"
        className={`${styles.circle} ${colorClass}`}
        onClick={() =>
          isReviewPrompt ? setIsReviewPromptOpen(true) : navigate(resolveActiveOrderRoute(selection))
        }
      >
        {icon}
        <span className={styles.label}>{label}</span>
      </button>

      {isReviewPrompt && (
        <ReviewPromptModal
          isOpen={isReviewPromptOpen}
          orderId={selection.order.id}
          onDismiss={handleDismissReviewPrompt}
        />
      )}
    </>
  );
}

function describeState(
  state: ActiveOrderIndicatorState,
  remainingMinutes: number | null,
  isArriving: boolean,
): { icon: JSX.Element; label: string; colorClass: string } {
  switch (state) {
    case 'ON_THE_WAY':
      return {
        icon: <Truck size={20} aria-hidden="true" />,
        label: isArriving ? 'מגיע/ה עכשיו' : remainingMinutes !== null ? `בעוד ${remainingMinutes} דק׳` : 'בדרך',
        colorClass: styles.onTheWay,
      };
    case 'COMPLETED_UNACKNOWLEDGED':
      return {
        icon: <Star size={20} aria-hidden="true" fill="currentColor" />,
        label: 'השאירו ביקורת',
        colorClass: styles.completed,
      };
    case 'PENDING_CONFIRMED':
    default:
      return {
        icon: <Clock size={20} aria-hidden="true" />,
        label: 'ההזמנה שלי',
        colorClass: styles.pendingConfirmed,
      };
  }
}
