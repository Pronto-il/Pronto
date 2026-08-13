package com.pronto.notifications.dto;

import java.util.List;

/** §3.1 response shape. {@code unreadCount} is always computed, regardless of {@code unreadOnly}. */
public record NotificationsListResponse(
        long unreadCount,
        List<NotificationResponse> notifications
) {
}
