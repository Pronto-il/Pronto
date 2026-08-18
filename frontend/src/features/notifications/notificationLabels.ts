import type { NotificationMessageType } from '../../shared/api';

/**
 * Hebrew label for each `messageType` (Frontend Milestone 5). Covers the full 8-member
 * backend enum (`notifications.entity.NotificationMessageType`) even though only 5 values
 * are reachable today (`ORDER_CREATED`/`ORDER_CONFIRMED`/`ORDER_REJECTED`/`ORDER_CANCELLED`/
 * `ORDER_EXPIRED`) — `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` are Milestone 6 scope (no caller
 * wires them yet) and `EMAIL_VERIFICATION` is never written to an `IN_APP` row, but a
 * notification row with either type must still render without crashing.
 */
const MESSAGE_TYPE_LABELS: Record<NotificationMessageType, string> = {
  ORDER_CREATED: 'בקשה חדשה התקבלה',
  ORDER_CONFIRMED: 'ההזמנה שלך אושרה',
  ORDER_ON_THE_WAY: 'בעל המקצוע בדרך אליך',
  ORDER_COMPLETED: 'העבודה הושלמה',
  ORDER_CANCELLED: 'ההזמנה בוטלה',
  ORDER_REJECTED: 'הבקשה שלך נדחתה',
  ORDER_EXPIRED: 'הבקשה פגה תוקף',
  EMAIL_VERIFICATION: 'קוד אימות נשלח לאימייל',
};

const FALLBACK_LABEL = 'עדכון חדש';

/**
 * Looks up the Hebrew label for a notification's `messageType`. Uses an explicit `??`
 * fallback rather than a bare object index that could silently return `undefined` — a
 * future backend enum value not yet mirrored in `MESSAGE_TYPE_LABELS` (the frontend's
 * `NotificationMessageType` union is typed to today's 8 values, but the runtime payload
 * isn't guaranteed to match) falls back to a generic label instead of crashing the row.
 */
export function getMessageTypeLabel(messageType: NotificationMessageType): string {
  return MESSAGE_TYPE_LABELS[messageType] ?? FALLBACK_LABEL;
}
