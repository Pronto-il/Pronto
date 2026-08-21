package com.pronto.sos.scheduler;

import com.pronto.sos.service.SosService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep's control flow.
 *
 * <p>What is actually being pinned here is the <b>per-offer</b> shape of offer expiry. It used to
 * be a single bulk {@code UPDATE} that closed every overdue offer at once and told nobody; the
 * loop below is what lets each expiry produce its own history row and its own realtime message to
 * one professional. Driving the loop from the job rather than from inside the service is
 * load-bearing for two separate reasons — a self-invoked {@code @Transactional} method would not
 * go through the Spring proxy (so every offer would share one transaction, and one bad row would
 * roll back the batch), and the realtime layer's {@code AFTER_COMMIT} listener fires per committed
 * transaction (so a shared transaction would hold back every professional's push until the last
 * offer in the sweep was done).
 */
class SosSweepJobTest {

    private SosService sosService;
    private SosSweepJob job;

    @BeforeEach
    void setUp() {
        sosService = Mockito.mock(SosService.class);
        job = new SosSweepJob(sosService);
        when(sosService.findOverdueOfferIds()).thenReturn(List.of());
        when(sosService.findExpiryCandidateIds()).thenReturn(List.of());
        when(sosService.findUnconfirmedSelectionIds()).thenReturn(List.of());
    }

    @Test
    void everyOverdueOfferIsExpiredIndividually() {
        when(sosService.findOverdueOfferIds()).thenReturn(List.of(11L, 22L, 33L));
        when(sosService.expireOffer(anyLong())).thenReturn(true);

        job.sweep();

        verify(sosService).expireOffer(11L);
        verify(sosService).expireOffer(22L);
        verify(sosService).expireOffer(33L);
    }

    /**
     * The three passes are ordered, not incidental: offers are closed before requests are
     * assessed, so that "did anyone accept?" cannot count an offer that has already lapsed and
     * open a selection window over a candidate who is no longer reachable.
     */
    @Test
    void allThreePassesRunInOrder() {
        when(sosService.findOverdueOfferIds()).thenReturn(List.of(11L));
        when(sosService.expireOffer(11L)).thenReturn(true);
        when(sosService.findExpiryCandidateIds()).thenReturn(List.of(44L));
        when(sosService.findUnconfirmedSelectionIds()).thenReturn(List.of(55L));

        job.sweep();

        org.mockito.InOrder inOrder = Mockito.inOrder(sosService);
        inOrder.verify(sosService).expireOffer(11L);
        inOrder.verify(sosService).sweepOne(44L);
        inOrder.verify(sosService).expireUnconfirmedSelection(55L);
    }

    /** Nothing overdue is the common case, and it must not touch anything. */
    @Test
    void anEmptySweepExpiresNothing() {
        job.sweep();

        verify(sosService, never()).expireOffer(anyLong());
        verify(sosService, never()).sweepOne(anyLong());
        verify(sosService, never()).expireUnconfirmedSelection(anyLong());
    }

    /**
     * A scheduled method that throws is silently unscheduled by some executors. The sweep must
     * survive a transient failure and simply retry on its next pass — nothing about this work is
     * stateful.
     */
    @Test
    void aFailingPassDoesNotKillTheSweep() {
        when(sosService.findOverdueOfferIds()).thenThrow(new RuntimeException("database is having a moment"));

        job.sweep();
        // Reaching here without a thrown exception is the assertion; a second call proves the job
        // is still usable rather than left in some broken state.
        job.sweep();
    }
}
