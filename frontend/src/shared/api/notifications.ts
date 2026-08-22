import { httpClient } from './httpClient';

/**
 * `notifications` domain types/functions for the in-app notification bell (Frontend
 * Milestone 5), consuming the already-complete backend `notifications` package
 * (`backend/src/main/java/com/pronto/notifications/`, no backend changes). Shapes
 * verified directly against `notifications.controller.NotificationController` and
 * `notifications.dto.{NotificationResponse,NotificationsListResponse,ReadAllResponse}`,
 * not copied from prose docs.
 */

/**
 * Mirrors `notifications.entity.NotificationMessageType` in full — all 20 values.
 *
 * **This type going stale is not a type error, it is a silent product bug.** The 12 `SOS_*`
 * values were added to the backend enum with Pronto SOS (`V35`) and never mirrored here, so
 * `notificationLabels.ts` — whose map is keyed on this union — had no entry for any of them and
 * every SOS notification rendered as the generic `עדכון חדש` fallback. TypeScript was perfectly
 * happy: the map was exhaustive over the union it *knew* about. Several distinct SOS events in a
 * row then looked like duplicates, because they all said the same nothing.
 *
 * So: a value here must exist for every value the backend can persist, and
 * `MESSAGE_TYPE_LABELS` must stay exhaustive over this union (it is a `Record`, so the compiler
 * enforces the second half once the first is right).
 */
export type NotificationMessageType =
  | 'ORDER_CREATED'
  | 'ORDER_CONFIRMED'
  | 'ORDER_ON_THE_WAY'
  | 'ORDER_COMPLETED'
  | 'ORDER_CANCELLED'
  | 'ORDER_REJECTED'
  | 'ORDER_EXPIRED'
  | 'EMAIL_VERIFICATION'
  // ---- Pronto SOS. Recipient noted per value: routing and copy differ by audience. ----
  /** → professional: an SOS opportunity was dispatched to them. */
  | 'SOS_OFFER_RECEIVED'
  /** → professional: their own response window lapsed unanswered. */
  | 'SOS_OFFER_EXPIRED'
  /** → customer: the candidate shortlist is settled and choosing can begin. */
  | 'SOS_CANDIDATES_READY'
  /** → professional: they responded available and the customer chose somebody else. */
  | 'SOS_NOT_SELECTED'
  /** → professional: the customer chose *them*. The award, not mere availability. */
  | 'SOS_PROFESSIONAL_SELECTED'
  /** → customer: the selected professional confirmed they are taking the job. */
  | 'SOS_PROFESSIONAL_CONFIRMED'
  /** → customer: the selected professional set off. */
  | 'SOS_ON_THE_WAY'
  /** → customer: the selected professional arrived. */
  | 'SOS_ARRIVED'
  /** → customer: the job is done. */
  | 'SOS_COMPLETED'
  /** → counterparty: the request was cancelled by the other party. */
  | 'SOS_CANCELLED'
  /** → customer: the request ran out of time. */
  | 'SOS_EXPIRED'
  /** → customer: matching found nobody eligible to ask. */
  | 'SOS_NO_PROFESSIONALS';

/**
 * Shared by `GET /api/notifications`'s list items and `POST /api/notifications/{id}/read`
 * (`notifications.dto.NotificationResponse`). `channel`/`deliveryStatus` are deliberately
 * not included server-side — every row here is, by construction, `channel = IN_APP`.
 */
export interface NotificationResponse {
  id: number;
  messageType: NotificationMessageType;
  /**
   * The order this notification is about, or `null` — which it always is for an `SOS_*` row.
   * Was typed non-nullable while SOS rows were already arriving with it unset, so the bell's
   * deep-link built `/orders/undefined`.
   */
  relatedOrderId: number | null;
  /**
   * The SOS request this notification is about, or `null` for an order row. Exactly one of this
   * and `relatedOrderId` is set on any row (`notifications.dto.NotificationResponse`); it is
   * FK-constrained to `sos_requests`, which is why it could not simply overload `relatedOrderId`.
   */
  relatedSosRequestId: number | null;
  /**
   * The issue the SOS request was activated on, or `null` for an order row.
   *
   * **Derived server-side, never stored** — there is no `related_issue_id` column; the backend
   * resolves it from `relatedSosRequestId` when it assembles the feed. It exists because
   * `relatedSosRequestId` alone could not be navigated with: the customer's live SOS screen is
   * `/issues/{issueId}/sos-booking`, keyed by the *problem* rather than by the attempt, so every
   * customer-facing SOS row in this bell used to be a dead end.
   *
   * Can be `null` even on an SOS row if the request no longer resolves. Render that as "no deep
   * link", never as a guess.
   */
  relatedIssueId: number | null;
  readAt: string | null;
  createdAt: string;
}

/** `GET /api/notifications` response shape. `unreadCount` is always computed, regardless of `unreadOnly`. */
export interface NotificationsListResponse {
  unreadCount: number;
  notifications: NotificationResponse[];
}

/** `POST /api/notifications/read-all` response shape. */
export interface MarkAllReadResponse {
  updatedCount: number;
}

/**
 * `GET /api/notifications` — either-role, self-scoped by the caller's own `user_id`
 * (`NotificationController.getFeed`). Ordered `createdAt DESC`, no pagination.
 */
export function getNotifications(unreadOnly?: boolean): Promise<NotificationsListResponse> {
  const query = unreadOnly ? '?unreadOnly=true' : '';
  return httpClient.get<NotificationsListResponse>(`/api/notifications${query}`);
}

/**
 * `POST /api/notifications/{id}/read` — idempotent (`NotificationServiceImpl.markRead`
 * only sets `readAt` if still `null`). Errors: `403 FORBIDDEN` (not the caller's own
 * notification), `404 NOT_FOUND` (id doesn't resolve).
 */
export function markNotificationRead(id: number): Promise<NotificationResponse> {
  return httpClient.post<NotificationResponse>(`/api/notifications/${id}/read`);
}

/** `POST /api/notifications/read-all` — marks every unread notification for the caller as read. */
export function markAllNotificationsRead(): Promise<MarkAllReadResponse> {
  return httpClient.post<MarkAllReadResponse>('/api/notifications/read-all');
}
