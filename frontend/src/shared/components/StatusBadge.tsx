import { useRef } from 'react';
import type { OrderStatus } from '../api/bookings';
import { Badge, type BadgeTone } from './Badge';
import styles from './StatusBadge.module.css';

export interface StatusBadgeProps {
  status: OrderStatus;
}

/**
 * One shared component mapping `OrderStatus` -> Hebrew label + `Badge` tone, per
 * DESIGN_SYSTEM.md §56 ("use consistent statuses globally... do not assign new colors
 * independently on different pages"). Every screen showing an order's status (tracking,
 * my-orders, professional incoming-requests) must go through this, not a per-page badge.
 *
 * Renders the shared `Badge` primitive (`size="md"`, an exact visual match for this
 * component's previous standalone sizing) rather than its own markup/colors.
 */
const STATUS_CONFIG: Record<OrderStatus, { label: string; tone: BadgeTone }> = {
  PENDING: { label: 'ממתין לאישור', tone: 'info' },
  CONFIRMED: { label: 'אושר', tone: 'primary' },
  ON_THE_WAY: { label: 'בדרך', tone: 'info' },
  COMPLETED: { label: 'הושלם', tone: 'success' },
  CANCELLED: { label: 'בוטל', tone: 'neutral' },
  REJECTED: { label: 'נדחה', tone: 'error' },
  EXPIRED: { label: 'פג תוקף', tone: 'neutral' },
};

export function StatusBadge({ status }: StatusBadgeProps) {
  const config = STATUS_CONFIG[status];

  // Re-key only the fade wrapper (not the whole component) whenever `status` changes, so the
  // CSS fade-in animation reliably re-triggers without losing any parent-managed focus/state.
  const fadeKeyRef = useRef(0);
  const prevStatusRef = useRef(status);
  if (prevStatusRef.current !== status) {
    prevStatusRef.current = status;
    fadeKeyRef.current += 1;
  }

  return (
    <span key={fadeKeyRef.current} className={styles.fadeWrap}>
      <Badge tone={config.tone} size="md">
        {config.label}
      </Badge>
    </span>
  );
}
