import type { OrderStatus } from '../api/bookings';
import styles from './StatusBadge.module.css';

export interface StatusBadgeProps {
  status: OrderStatus;
}

/**
 * One shared component mapping `OrderStatus` -> Hebrew label + color, per DESIGN_SYSTEM.md
 * §56 ("use consistent statuses globally... do not assign new colors independently on
 * different pages"). Every screen showing an order's status (tracking, my-orders,
 * professional incoming-requests) must go through this, not a per-page badge.
 */
const STATUS_CONFIG: Record<OrderStatus, { label: string; className: string }> = {
  PENDING: { label: 'ממתין לאישור', className: styles.pending },
  CONFIRMED: { label: 'אושר', className: styles.confirmed },
  ON_THE_WAY: { label: 'בדרך', className: styles.onTheWay },
  COMPLETED: { label: 'הושלם', className: styles.completed },
  CANCELLED: { label: 'בוטל', className: styles.cancelled },
  REJECTED: { label: 'נדחה', className: styles.rejected },
  EXPIRED: { label: 'פג תוקף', className: styles.cancelled },
};

export function StatusBadge({ status }: StatusBadgeProps) {
  const config = STATUS_CONFIG[status];
  return <span className={`${styles.badge} ${config.className}`}>{config.label}</span>;
}
