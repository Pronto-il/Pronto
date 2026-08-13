package com.pronto.notifications.controller;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.notifications.dto.NotificationResponse;
import com.pronto.notifications.dto.NotificationsListResponse;
import com.pronto.notifications.dto.ReadAllResponse;
import com.pronto.notifications.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/notifications/*} — in-app notification feed / bell (§3 of
 * {@code docs/architecture/api-contract-notifications.md}). Every route here is either-role,
 * self-scoped by the caller's own {@code user_id} — no route-level role gate is needed (§0.1),
 * unlike {@code bookings.config.BookingsWebConfig}'s {@code RoleRequiredInterceptor}
 * registrations for role-restricted routes. {@code {id}} is parsed manually, matching
 * {@code issues.controller.IssuesController}'s convention, so a malformed value produces
 * {@code 404 NOT_FOUND} through this app's own error envelope rather than falling through
 * to {@code common.exception.GlobalExceptionHandler}'s generic {@code 500 INTERNAL_ERROR}
 * catch-all (Spring has no built-in conversion-failure handler registered here).
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<NotificationsListResponse> getFeed(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(name = "unreadOnly", required = false, defaultValue = "false") boolean unreadOnly) {
        return ResponseEntity.ok(notificationService.getFeed(principal.id(), unreadOnly));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markRead(@AuthenticationPrincipal AuthenticatedUser principal,
                                                           @PathVariable("id") String idRaw) {
        Long id = parsePathId(idRaw);
        return ResponseEntity.ok(notificationService.markRead(principal.id(), id));
    }

    @PostMapping("/read-all")
    public ResponseEntity<ReadAllResponse> markAllRead(@AuthenticationPrincipal AuthenticatedUser principal) {
        int updatedCount = notificationService.markAllRead(principal.id());
        return ResponseEntity.ok(new ReadAllResponse(updatedCount));
    }

    private Long parsePathId(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Not found.");
        }
    }
}
