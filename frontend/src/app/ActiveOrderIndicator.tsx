import type { JSX } from 'react';
import { useNavigate } from 'react-router-dom';
import { Clock, Truck, Star } from 'lucide-react';
import { useActiveOrder, useEtaCountdown, resolveActiveOrderRoute } from '../shared/hooks';
import type { ActiveOrderIndicatorState } from '../shared/hooks';
import styles from './ActiveOrderIndicator.module.css';

/**
 * Floating, `position: fixed` circular indicator for the customer's single
 * highest-priority active order (`docs/architecture/active-booking-floating-indicator.md`
 * §5-§7). Deliberately structurally separate from `BookingDraftIndicator` (§6.3) — mounted
 * as a sibling of `<main>` in `AppLayout`, not inside the top nav.
 *
 * No business logic lives here: `useActiveOrder()` already ran the priority-selection
 * algorithm, `useEtaCountdown()` already computes the live remaining-minutes figure from
 * the persisted `expectedArrivalAt` timestamp — this component only maps `selection.state`
 * to an icon/label/click-through route.
 */
export function ActiveOrderIndicator() {
  const navigate = useNavigate();
  const { selection } = useActiveOrder();
  const { remainingMinutes, isArriving } = useEtaCountdown(selection?.order.expectedArrivalAt ?? null);

  if (!selection) {
    return null;
  }

  const { icon, label, colorClass } = describeState(selection.state, remainingMinutes, isArriving);

  return (
    <button
      type="button"
      className={`${styles.circle} ${colorClass}`}
      onClick={() => navigate(resolveActiveOrderRoute(selection))}
    >
      {icon}
      <span className={styles.label}>{label}</span>
    </button>
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
