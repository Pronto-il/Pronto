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
 * Mirrors `notifications.entity.NotificationMessageType` (8 values). Only
 * `ORDER_CREATED`/`ORDER_CONFIRMED`/`ORDER_REJECTED`/`ORDER_CANCELLED`/`ORDER_EXPIRED` are
 * reachable today — `ORDER_ON_THE_WAY`/`ORDER_COMPLETED` are Milestone 6 scope (no caller
 * wires them yet) and `EMAIL_VERIFICATION` is never written to an `IN_APP` row — kept here
 * so this type matches the full backend enum.
 */
export type NotificationMessageType =
  | 'ORDER_CREATED'
  | 'ORDER_CONFIRMED'
  | 'ORDER_ON_THE_WAY'
  | 'ORDER_COMPLETED'
  | 'ORDER_CANCELLED'
  | 'ORDER_REJECTED'
  | 'ORDER_EXPIRED'
  | 'EMAIL_VERIFICATION';

/**
 * Shared by `GET /api/notifications`'s list items and `POST /api/notifications/{id}/read`
 * (`notifications.dto.NotificationResponse`). `channel`/`deliveryStatus` are deliberately
 * not included server-side — every row here is, by construction, `channel = IN_APP`.
 */
export interface NotificationResponse {
  id: number;
  messageType: NotificationMessageType;
  relatedOrderId: number;
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
