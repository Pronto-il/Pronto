package com.pronto.sos.scheduler;

import com.pronto.sos.service.SosService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Terminates SOS requests and offers whose deadlines have passed.
 *
 * <p><b>This is a completeness mechanism, not the enforcement mechanism.</b> Deadlines are
 * enforced lazily on every API path that acts on a request ({@code SosService#enforceDeadlines}),
 * so a stale request can never be observed or operated on as though it were live even if this
 * job is delayed or disabled. What the sweep adds is termination for requests <em>nobody is
 * looking at</em>: without it, an abandoned request would sit in
 * {@code WAITING_FOR_CUSTOMER_SELECTION} forever, its professionals' offers never released and
 * its customer never notified that it lapsed.
 *
 * <p>Every 15 seconds. Much tighter than {@code notifications.scheduler.OrderExpirySweepJob}'s
 * 60s, because the deadlines here are two minutes rather than fifteen — a 60s worst-case
 * overshoot on a 120s selection window would be a 50% error, whereas 15s is a tolerable ~12%.
 * Each pass is two indexed queries that return nothing at all in the common case.
 *
 * <p>{@code @Scheduled} is already enabled application-wide by
 * {@code notifications.config.SchedulingConfig}'s {@code @EnableScheduling}, so this package
 * deliberately adds no scheduling configuration of its own — a second {@code @EnableScheduling}
 * would be redundant, not additive.
 *
 * <p>Each id is transitioned in its own transaction inside the service, so one problem row
 * cannot roll back the whole sweep; and each transition is a guarded update, so racing with a
 * live API call is safe — whoever loses simply does nothing.
 */
@Component
public class SosSweepJob {

    private static final Logger log = LoggerFactory.getLogger(SosSweepJob.class);

    private final SosService sosService;

    public SosSweepJob(SosService sosService) {
        this.sosService = sosService;
    }

    @Scheduled(fixedDelay = 15_000)
    public void sweep() {
        try {
            // 1. Individually overdue offers. Done first so that step 2's "did anyone accept?"
            //    check does not count an offer that has already lapsed.
            int expiredOffers = sosService.expireOverdueOffers();

            // 2. Requests past their matching or selection deadline. sweepOne re-derives which
            //    of the two applies rather than trusting the query that produced the id.
            List<Long> expiring = sosService.findExpiryCandidateIds();
            for (Long sosRequestId : expiring) {
                sosService.sweepOne(sosRequestId);
            }

            // 3. Selections the professional never confirmed.
            List<Long> unconfirmed = sosService.findUnconfirmedSelectionIds();
            for (Long sosRequestId : unconfirmed) {
                sosService.expireUnconfirmedSelection(sosRequestId);
            }

            if (expiredOffers > 0 || !expiring.isEmpty() || !unconfirmed.isEmpty()) {
                log.info("sos.sweep expiredOffers={} expiredRequests={} unconfirmedSelections={}",
                        expiredOffers, expiring.size(), unconfirmed.size());
            }
        } catch (RuntimeException e) {
            // A scheduled method that throws is silently unscheduled by some executors and, at
            // best, logs a stack trace with no context. Swallowing here keeps the sweep alive
            // across a transient failure -- the next pass simply retries, since nothing about
            // this work is stateful.
            log.error("sos.sweep.failed", e);
        }
    }
}
