package com.pronto.notifications.scheduler;

import com.pronto.bookings.service.BookingsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * §4.5 of {@code docs/architecture/api-contract-notifications.md}. Sweeps {@code PENDING}
 * orders past their per-urgency-type timeout (15 min Standard / 5 min SOS) and transitions
 * them to {@code EXPIRED}. Every 60s — negligible worst-case overshoot relative to those
 * timeouts (see the contract doc's interval-choice reasoning).
 *
 * <p>This class depends on {@code bookings.service.BookingsService} — a deliberate,
 * flagged {@code notifications -> bookings} edge, alongside the pre-existing
 * {@code bookings -> notifications} edge ({@code NotificationService}). Together these form a
 * package-level cycle, which is the direct, unavoidable consequence of the sweep-ownership
 * split {@code data-model.md} §3 item 8 already decided (domain rule/transition lives in
 * {@code bookings}; the {@code @Scheduled} orchestrator lives in {@code notifications}). Not a
 * Java-level compile cycle (no single class pair mutually imports each other).
 */
@Component
public class OrderExpirySweepJob {

    private final BookingsService bookingsService;

    public OrderExpirySweepJob(BookingsService bookingsService) {
        this.bookingsService = bookingsService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        for (Long orderId : bookingsService.findExpiredOrderCandidateIds()) {
            bookingsService.expireIfPending(orderId);
        }
    }
}
