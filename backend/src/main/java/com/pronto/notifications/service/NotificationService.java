package com.pronto.notifications.service;

import com.pronto.notifications.dto.NotificationResponse;
import com.pronto.notifications.dto.NotificationsListResponse;
import com.pronto.notifications.entity.NotificationMessageType;

/**
 * See {@code docs/architecture/api-contract-notifications.md} §3/§4.1. {@link
 * #recordOrderNotification} is the {@code bookings -> notifications} call boundary (§4.1) —
 * deliberately primitive/enum parameters only, so {@code bookings} never needs to import a
 * {@code notifications}-owned entity, and {@code notifications} never needs to import
 * {@code bookings.entity.Order}. The remaining methods back {@code
 * notifications.controller.NotificationController} (§3.1-3.3) and are not part of that
 * cross-package boundary.
 */
public interface NotificationService {

    /**
     * Records a notification for one order-lifecycle event, for one recipient. Internally
     * creates two {@code notifications} rows (IN_APP + EMAIL) — see §4.3. Called by
     * {@code bookings.service.BookingsService} after every successful order-status
     * transition, inside that same {@code @Transactional} method (§4.3).
     */
    void recordOrderNotification(Long orderId, Long recipientUserId, NotificationMessageType messageType);

    /**
     * Pronto SOS equivalent of {@link #recordOrderNotification}, for the {@code sos ->
     * notifications} call boundary. Same contract in every respect (two rows, IN_APP + EMAIL,
     * inside the caller's transaction, primitives/enums only) — the subject is an
     * {@code sos_requests} row rather than an {@code orders} row, because SOS offers are
     * dispatched before any order exists.
     */
    void recordSosNotification(Long sosRequestId, Long recipientUserId, NotificationMessageType messageType);

    /** §3.1 — the in-app feed / bell. */
    NotificationsListResponse getFeed(Long callerId, boolean unreadOnly);

    /** §3.2 — idempotent single-notification read. {@code 404}/{@code 403} per that section. */
    NotificationResponse markRead(Long callerId, Long notificationId);

    /** §3.3 — "clear the bell." Returns the affected-row count. */
    int markAllRead(Long callerId);
}
