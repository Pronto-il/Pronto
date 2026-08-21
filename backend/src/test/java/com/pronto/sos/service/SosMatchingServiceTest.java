package com.pronto.sos.service;

import com.pronto.matching.DistanceEtaStrategy;
import com.pronto.matching.EtaResult;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.EligibleProfessional;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosCandidateRepository;
import com.pronto.sos.repository.SosOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Eligibility filtering, the pool cap, and the ranking model. */
class SosMatchingServiceTest {

    private static final Long CATEGORY_ID = 7L;

    private SosCandidateRepository sosCandidateRepository;
    private SosOfferRepository sosOfferRepository;
    private DistanceEtaStrategy distanceEtaStrategy;
    private SosProperties properties;
    private SosMatchingService service;

    @BeforeEach
    void setUp() {
        sosCandidateRepository = Mockito.mock(SosCandidateRepository.class);
        sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        distanceEtaStrategy = Mockito.mock(DistanceEtaStrategy.class);
        properties = new SosProperties();
        service = new SosMatchingService(sosCandidateRepository, sosOfferRepository, distanceEtaStrategy, properties);

        when(sosOfferRepository.findProfessionalIdsWithLiveOffers(anyList(), any())).thenReturn(List.of());
        when(sosOfferRepository.findAcceptanceStats(anyList(), any())).thenReturn(List.of());
        // Default: everyone same-city, 15 minutes away.
        when(distanceEtaStrategy.calculate(any(), any(), any()))
                .thenReturn(new EtaResult(true, new BigDecimal("8.0"), 15, 0, 15));
    }

    private static SosRequest request(SosUrgency urgency) {
        SosRequest request = new SosRequest(1L, 2L, CATEGORY_ID, null, "leak", urgency,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null, null, null);
        setField(request, "id", 100L);
        return request;
    }

    private static EligibleProfessional professional(long id, Double rating, long reviews, String basePrice) {
        return new EligibleProfessional(id, 1000 + id, "Pro " + id, "Tel Aviv", "Center",
                new BigDecimal(basePrice), null, null, rating, reviews);
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

    @Test
    void returnsEmptyWhenNobodyIsEligible() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList())).thenReturn(List.of());

        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of())).isEmpty();
    }

    /** JPQL cannot express {@code NOT IN ()}, so an empty exclusion set must become a sentinel. */
    @Test
    void emptyExclusionSetIsPassedAsANonEmptySentinel() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList())).thenReturn(List.of());

        service.findCandidates(request(SosUrgency.URGENT), Set.of());

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(sosCandidateRepository).findEligible(eq(CATEGORY_ID), captor.capture());
        assertThat(captor.getValue()).isNotEmpty().allMatch(id -> id < 0);
    }

    @Test
    void alreadyOfferedProfessionalsAreExcluded() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList())).thenReturn(List.of());

        service.findCandidates(request(SosUrgency.URGENT), Set.of(5L, 9L));

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(sosCandidateRepository).findEligible(eq(CATEGORY_ID), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(5L, 9L);
    }

    @Test
    void professionalsAlreadyHoldingLiveOffersAreDroppedFromThePool() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250"), professional(2, 5.0, 10, "250")));
        when(sosOfferRepository.findProfessionalIdsWithLiveOffers(anyList(), any())).thenReturn(List.of(1L));

        List<RankedCandidate> candidates = service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).professional().professionalId()).isEqualTo(2L);
    }

    /**
     * "Somebody who is busy" beats "nobody at all" for a customer with an active leak — the
     * busy filter is a load-balancing preference, not an eligibility rule.
     */
    @Test
    void allCandidatesBusyFallsBackToDispatchingThemAnyway() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250")));
        when(sosOfferRepository.findProfessionalIdsWithLiveOffers(anyList(), any())).thenReturn(List.of(1L));

        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of())).hasSize(1);
    }

    @Test
    void candidatesBeyondTheDispatchRadiusAreExcluded() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250")));
        // 200 km, well past the 40 km default cap.
        when(distanceEtaStrategy.calculate(any(), any(), any()))
                .thenReturn(new EtaResult(false, new BigDecimal("200.0"), 120, 0, 120));

        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of())).isEmpty();
    }

    @Test
    void poolIsCappedAtTheConfiguredSizeForAnUrgentRequest() {
        properties.setCandidatePoolSize(3);
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList()))
                .thenReturn(IntStream.rangeClosed(1, 20)
                        .mapToObj(i -> professional(i, 5.0, 10, "250"))
                        .toList());

        assertThat(service.findCandidates(request(SosUrgency.URGENT), Set.of())).hasSize(3);
    }

    /** An emergency accepts more professional interruption for a better chance of an answer. */
    @Test
    void emergencyUsesTheWiderPool() {
        properties.setCandidatePoolSize(3);
        properties.setEmergencyCandidatePoolSize(7);
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList()))
                .thenReturn(IntStream.rangeClosed(1, 20)
                        .mapToObj(i -> professional(i, 5.0, 10, "250"))
                        .toList());

        assertThat(service.findCandidates(request(SosUrgency.EMERGENCY), Set.of())).hasSize(7);
    }

    /** ETA is the dominant weight, so with everything else equal the faster professional wins. */
    @Test
    void fasterEtaOutranksSlowerWhenAllElseIsEqual() {
        EligibleProfessional slow = professional(1, 5.0, 10, "250");
        EligibleProfessional fast = professional(2, 5.0, 10, "250");
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList())).thenReturn(List.of(slow, fast));
        when(distanceEtaStrategy.calculate(eq("Tel Aviv"), any(), any()))
                .thenReturn(new EtaResult(false, new BigDecimal("30.0"), 70, 0, 70))
                .thenReturn(new EtaResult(true, new BigDecimal("5.0"), 10, 0, 10));

        List<RankedCandidate> ranked = service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(ranked.get(0).professional().professionalId()).isEqualTo(2L);
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    /** With identical ETA, the better-rated professional wins. */
    @Test
    void higherRatingOutranksLowerWhenEtaIsEqual() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList()))
                .thenReturn(List.of(professional(1, 2.0, 40, "250"), professional(2, 5.0, 40, "250")));

        List<RankedCandidate> ranked = service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(ranked.get(0).professional().professionalId()).isEqualTo(2L);
    }

    /**
     * A new joiner with no reviews must not be structurally unable to ever win a dispatch —
     * unrated scores the midpoint, so they beat a genuinely badly-rated professional.
     */
    @Test
    void unratedProfessionalScoresAboveABadlyRatedOne() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList()))
                .thenReturn(List.of(professional(1, 1.0, 30, "250"), professional(2, null, 0, "250")));

        List<RankedCandidate> ranked = service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(ranked.get(0).professional().professionalId()).isEqualTo(2L);
    }

    @Test
    void acceptanceRateRaisesTheScore() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList()))
                .thenReturn(List.of(professional(1, 5.0, 10, "250"), professional(2, 5.0, 10, "250")));
        // Professional 1 accepted 1 of 10; professional 2 accepted 10 of 10.
        when(sosOfferRepository.findAcceptanceStats(anyList(), any())).thenReturn(List.of(
                new Object[]{1L, 10L, 1L},
                new Object[]{2L, 10L, 10L}));

        List<RankedCandidate> ranked = service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(ranked.get(0).professional().professionalId()).isEqualTo(2L);
    }

    @Test
    void scoresStayWithinZeroToOneAndComponentsSumToTheTotal() {
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList()))
                .thenReturn(List.of(professional(1, 4.2, 17, "250")));

        RankedCandidate candidate = service.findCandidates(request(SosUrgency.URGENT), Set.of()).get(0);

        assertThat(candidate.score()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        double componentSum = candidate.componentScores().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(candidate.score().doubleValue()).isCloseTo(componentSum, org.assertj.core.data.Offset.offset(0.001));
        assertThat(candidate.componentScores())
                .containsOnlyKeys("eta", "rating", "acceptance", "distance", "reliability");
    }

    /** Two runs over identical data must produce identical dispatch order. */
    @Test
    void rankingIsDeterministicForTiedScores() {
        List<EligibleProfessional> pool = List.of(
                professional(3, 5.0, 10, "250"),
                professional(1, 5.0, 10, "250"),
                professional(2, 5.0, 10, "250"));
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList())).thenReturn(pool);

        List<Long> first = service.findCandidates(request(SosUrgency.URGENT), Set.of()).stream()
                .map(c -> c.professional().professionalId()).toList();
        List<Long> second = service.findCandidates(request(SosUrgency.URGENT), Set.of()).stream()
                .map(c -> c.professional().professionalId()).toList();

        assertThat(first).isEqualTo(second).containsExactly(1L, 2L, 3L);
    }

    @Test
    void nullReliabilityAndNullBasePriceDoNotBreakScoring() {
        EligibleProfessional incomplete = new EligibleProfessional(1L, 11L, "Pro", "Tel Aviv", "Center",
                null, null, null, null, 0L);
        when(sosCandidateRepository.findEligible(eq(CATEGORY_ID), anyList())).thenReturn(List.of(incomplete));

        List<RankedCandidate> ranked = service.findCandidates(request(SosUrgency.URGENT), Set.of());

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).score()).isNotNull();
    }
}
