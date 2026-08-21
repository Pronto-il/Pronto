package com.pronto.sos.service;

import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.service.NotificationService;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.EligibleProfessional;
import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.sos.repository.SosRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Offer creation, notification fan-out, the no-professionals path — and above all the pricing
 * rule, which is the feature's business model expressed in code.
 */
class SosDispatchServiceTest {

    private static final Long REQUEST_ID = 100L;

    private SosRequestRepository sosRequestRepository;
    private SosOfferRepository sosOfferRepository;
    private SosMatchingService sosMatchingService;
    private SosEventService sosEventService;
    private NotificationService notificationService;
    private SosProperties properties;
    private SosDispatchService service;

    @BeforeEach
    void setUp() {
        sosRequestRepository = Mockito.mock(SosRequestRepository.class);
        sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        sosMatchingService = Mockito.mock(SosMatchingService.class);
        sosEventService = Mockito.mock(SosEventService.class);
        notificationService = Mockito.mock(NotificationService.class);
        properties = new SosProperties();
        service = new SosDispatchService(sosRequestRepository, sosOfferRepository, sosMatchingService,
                sosEventService, notificationService, properties);

        when(sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(anyLong())).thenReturn(List.of());
        when(sosOfferRepository.saveAndFlush(any(SosOffer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sosRequestRepository.markWaitingForProfessionals(anyLong(), any())).thenReturn(1);
        when(sosRequestRepository.markFailed(anyLong(), any())).thenReturn(1);
    }

    private static SosRequest request() {
        SosRequest request = new SosRequest(1L, 2L, 7L, null, "leak", SosUrgency.URGENT,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null, null, null);
        setField(request, "id", REQUEST_ID);
        return request;
    }

    private static RankedCandidate candidate(long professionalId, String basePrice) {
        EligibleProfessional professional = new EligibleProfessional(professionalId, 1000 + professionalId,
                "Pro " + professionalId, "Tel Aviv", "Center",
                basePrice == null ? null : new BigDecimal(basePrice), null, null, 5.0, 3L);
        return new RankedCandidate(professional, new BigDecimal("0.800"), new BigDecimal("8.0"), 15,
                Map.of("eta", 0.4));
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the business model ----

    /**
     * The defining example from the product brief: a 250 ILS visit fee yields roughly 25 ILS of
     * commission. With the default 50 ILS SOS surcharge the commission base is 300, so Pronto
     * takes 30 and the professional nets 270.
     */
    @Test
    void commissionIsTenPercentOfVisitFeePlusSosFee() {
        SosDispatchService.OfferPricing pricing = service.priceOffer(new BigDecimal("250.00"));

        assertThat(pricing.visitFee()).isEqualByComparingTo("250.00");
        assertThat(pricing.sosFee()).isEqualByComparingTo("50.00");
        assertThat(pricing.commission()).isEqualByComparingTo("30.00");
    }

    /** Commission must never be a share of the repair's value — only of the visit-related fees. */
    @Test
    void commissionIgnoresEverythingExceptTheTwoVisitFees() {
        properties.setVisitSurcharge(BigDecimal.ZERO);

        SosDispatchService.OfferPricing pricing = service.priceOffer(new BigDecimal("250.00"));

        assertThat(pricing.commission()).isEqualByComparingTo("25.00");
    }

    @Test
    void commissionRateIsConfigurableAndNotHardcoded() {
        properties.setCommissionRate(new BigDecimal("0.20"));

        assertThat(service.priceOffer(new BigDecimal("100.00")).commission()).isEqualByComparingTo("30.00");
    }

    /**
     * A professional with no base price set still gets a dispatchable offer — the surcharge and
     * its commission apply, and the visit fee is settled between the parties. Treating a missing
     * price as zero would quietly promise the customer a free visit.
     */
    @Test
    void missingBasePriceLeavesVisitFeeNullWithoutBreakingCommission() {
        SosDispatchService.OfferPricing pricing = service.priceOffer(null);

        assertThat(pricing.visitFee()).isNull();
        assertThat(pricing.sosFee()).isEqualByComparingTo("50.00");
        assertThat(pricing.commission()).isEqualByComparingTo("5.00");
    }

    @Test
    void commissionIsRoundedToTwoDecimalPlaces() {
        properties.setCommissionRate(new BigDecimal("0.075"));

        // (33.33 + 50.00) * 0.075 = 6.24975 -> 6.25
        assertThat(service.priceOffer(new BigDecimal("33.33")).commission()).isEqualByComparingTo("6.25");
    }

    // ---- dispatch ----

    @Test
    void createsOneOfferPerCandidateAndNotifiesEachOne() {
        when(sosMatchingService.findCandidates(any(), any()))
                .thenReturn(List.of(candidate(1, "250"), candidate(2, "300")));

        int dispatched = service.dispatch(request());

        assertThat(dispatched).isEqualTo(2);
        verify(sosOfferRepository, Mockito.times(2)).saveAndFlush(any(SosOffer.class));
        verify(notificationService).recordSosNotification(REQUEST_ID, 1001L,
                NotificationMessageType.SOS_OFFER_RECEIVED);
        verify(notificationService).recordSosNotification(REQUEST_ID, 1002L,
                NotificationMessageType.SOS_OFFER_RECEIVED);
        verify(sosRequestRepository).markWaitingForProfessionals(eq(REQUEST_ID), any());
    }

    @Test
    void offersCarryTheSnapshottedPricingAndRankingFigures() {
        when(sosMatchingService.findCandidates(any(), any())).thenReturn(List.of(candidate(1, "250")));

        service.dispatch(request());

        ArgumentCaptor<SosOffer> captor = ArgumentCaptor.forClass(SosOffer.class);
        verify(sosOfferRepository).saveAndFlush(captor.capture());
        SosOffer offer = captor.getValue();
        assertThat(offer.getVisitFee()).isEqualByComparingTo("250.00");
        assertThat(offer.getSosFee()).isEqualByComparingTo("50.00");
        assertThat(offer.getPlatformCommission()).isEqualByComparingTo("30.00");
        assertThat(offer.getProfessionalNet()).isEqualByComparingTo("270.00");
        assertThat(offer.getMatchRank()).isEqualTo((short) 1);
        assertThat(offer.getMatchScore()).isEqualByComparingTo("0.800");
        assertThat(offer.getEstimatedArrivalMinutes()).isEqualTo((short) 15);
    }

    @Test
    void ranksAreOneBasedAndSequential() {
        when(sosMatchingService.findCandidates(any(), any()))
                .thenReturn(List.of(candidate(1, "250"), candidate(2, "250"), candidate(3, "250")));

        service.dispatch(request());

        ArgumentCaptor<SosOffer> captor = ArgumentCaptor.forClass(SosOffer.class);
        verify(sosOfferRepository, Mockito.times(3)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues()).extracting(SosOffer::getMatchRank)
                .containsExactly((short) 1, (short) 2, (short) 3);
    }

    @Test
    void offersExpireAfterTheConfiguredTtl() {
        properties.setOfferTtlSeconds(90);
        when(sosMatchingService.findCandidates(any(), any())).thenReturn(List.of(candidate(1, "250")));

        service.dispatch(request());

        ArgumentCaptor<SosOffer> captor = ArgumentCaptor.forClass(SosOffer.class);
        verify(sosOfferRepository).saveAndFlush(captor.capture());
        SosOffer offer = captor.getValue();
        assertThat(offer.getExpiresAt()).isEqualTo(offer.getOfferedAt().plusSeconds(90));
    }

    /** Nobody eligible is FAILED, not EXPIRED — a different product problem entirely. */
    @Test
    void noCandidatesFailsTheRequestAndTellsTheCustomer() {
        when(sosMatchingService.findCandidates(any(), any())).thenReturn(List.of());

        int dispatched = service.dispatch(request());

        assertThat(dispatched).isZero();
        verify(sosRequestRepository).markFailed(eq(REQUEST_ID), any());
        verify(sosRequestRepository, never()).markWaitingForProfessionals(anyLong(), any());
        verify(notificationService).recordSosNotification(eq(REQUEST_ID), eq(2L),
                eq(NotificationMessageType.SOS_NO_PROFESSIONALS));
        verify(sosEventService).recordSystem(eq(REQUEST_ID), eq(SosEventType.FAILED),
                eq(SosRequestStatus.MATCHING), eq(SosRequestStatus.FAILED), any());
    }

    @Test
    void offersSentEventIsRecordedOnASuccessfulWave() {
        when(sosMatchingService.findCandidates(any(), any())).thenReturn(List.of(candidate(1, "250")));

        service.dispatch(request());

        verify(sosEventService).recordSystem(eq(REQUEST_ID), eq(SosEventType.OFFERS_SENT),
                eq(SosRequestStatus.MATCHING), eq(SosRequestStatus.WAITING_FOR_PROFESSIONALS), any());
    }

    /** A second wave must continue the rank sequence rather than restarting at 1. */
    @Test
    void alreadyOfferedProfessionalsArePassedToMatchingAndRanksContinue() {
        SosOffer existing = new SosOffer(REQUEST_ID, 5L, 1, new BigDecimal("0.9"), null, null,
                null, BigDecimal.ZERO, BigDecimal.ZERO, java.time.Instant.now(), java.time.Instant.now());
        when(sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(REQUEST_ID)).thenReturn(List.of(existing));
        when(sosMatchingService.findCandidates(any(), any())).thenReturn(List.of(candidate(1, "250")));

        service.dispatch(request());

        ArgumentCaptor<Set<Long>> excluded = ArgumentCaptor.forClass(Set.class);
        verify(sosMatchingService).findCandidates(any(), excluded.capture());
        assertThat(excluded.getValue()).containsExactly(5L);

        ArgumentCaptor<SosOffer> captor = ArgumentCaptor.forClass(SosOffer.class);
        verify(sosOfferRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMatchRank()).isEqualTo((short) 2);
    }
}
