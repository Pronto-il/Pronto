package com.pronto.sos.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SOS tunables and their startup validation.
 *
 * <p>Worth testing because these are deadlines and money, and because every failure mode they
 * have is silent. A {@code selection-window-seconds} of {@code 0} does not throw anywhere — it
 * expires every SOS request the instant its selection window opens, which reads to everyone
 * involved as "no professional ever answers". A negative commission rate pays the professional
 * more than the customer was charged. Neither would be found quickly by looking at logs.
 */
class SosPropertiesTest {

    @Test
    void confirmationGraceDefaultsToThreeMinutes() {
        // The product's existing behaviour, preserved exactly when this moved out of a hardcoded
        // Duration constant on SosService and into configuration.
        assertThat(new SosProperties().getConfirmationGraceSeconds()).isEqualTo(180);
    }

    @Test
    void theShippedDefaultsAreInternallyConsistent() {
        assertThatCode(() -> new SosProperties().validate()).doesNotThrowAnyException();
    }

    @Test
    void everyTimingPropertyIsRejectedWhenNotPositive() {
        assertRejected("offer-ttl-seconds", p -> p.setOfferTtlSeconds(0));
        assertRejected("matching-window-seconds", p -> p.setMatchingWindowSeconds(0));
        assertRejected("selection-window-seconds", p -> p.setSelectionWindowSeconds(0));
        assertRejected("confirmation-grace-seconds", p -> p.setConfirmationGraceSeconds(0));
        assertRejected("confirmation-grace-seconds", p -> p.setConfirmationGraceSeconds(-1));
    }

    @Test
    void everyPoolSizeIsRejectedWhenNotPositive() {
        assertRejected("candidate-pool-size", p -> p.setCandidatePoolSize(0));
        assertRejected("emergency-candidate-pool-size", p -> p.setEmergencyCandidatePoolSize(-3));
        // A target of 0 would open the selection window with nobody to choose from.
        assertRejected("target-candidate-count", p -> p.setTargetCandidateCount(0));
    }

    @Test
    void aCommissionRateOutsideZeroToOneIsRejected() {
        assertRejected("commission-rate", p -> p.setCommissionRate(new BigDecimal("-0.10")));
        // 1.5 would take 150% of the fee -- i.e. the professional pays Pronto to do the job.
        assertRejected("commission-rate", p -> p.setCommissionRate(new BigDecimal("1.50")));
        assertRejected("commission-rate", p -> p.setCommissionRate(null));
    }

    @Test
    void boundaryCommissionRatesAreAccepted() {
        // 0% (a promotional period) and 100% are both coherent business decisions, so neither is
        // the validator's business to refuse -- only values that cannot mean anything are.
        assertThatCode(() -> propertiesWith(p -> p.setCommissionRate(BigDecimal.ZERO)).validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> propertiesWith(p -> p.setCommissionRate(BigDecimal.ONE)).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void aNegativeVisitSurchargeIsRejected() {
        assertRejected("visit-surcharge", p -> p.setVisitSurcharge(new BigDecimal("-1.00")));
    }

    @Test
    void aNonPositiveDispatchRadiusIsRejected() {
        assertRejected("max-dispatch-radius-km", p -> p.setMaxDispatchRadiusKm(BigDecimal.ZERO));
    }

    /** Null disables the radius filter entirely, which is a legitimate configuration. */
    @Test
    void anAbsentDispatchRadiusIsAccepted() {
        assertThatCode(() -> propertiesWith(p -> p.setMaxDispatchRadiusKm(null)).validate())
                .doesNotThrowAnyException();
    }

    /**
     * The cross-field rule: an offer that outlives the response window it belongs to has seconds
     * on it that can never be used. Not expressible as a per-field annotation, which is part of
     * why this validation is hand-written.
     */
    @Test
    void anOfferTtlLongerThanTheMatchingWindowIsRejected() {
        assertRejected("offer-ttl-seconds", p -> {
            p.setOfferTtlSeconds(300);
            p.setMatchingWindowSeconds(150);
        });
    }

    @Test
    void anOfferTtlEqualToTheMatchingWindowIsAccepted() {
        assertThatCode(() -> propertiesWith(p -> {
            p.setOfferTtlSeconds(150);
            p.setMatchingWindowSeconds(150);
        }).validate()).doesNotThrowAnyException();
    }

    // ---- search expansion ----

    /**
     * Zero is legal here and nowhere else above: it turns "סרוק שוב" off and restores single-wave
     * dispatch, which is a deployment somebody might genuinely want.
     */
    @Test
    void zeroSearchExpansionsIsAcceptedAndDisablesTheFeature() {
        assertThatCode(() -> propertiesWith(p -> p.setMaxSearchExpansions(0)).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void aNegativeExpansionCeilingIsRejected() {
        assertRejected("max-search-expansions", p -> p.setMaxSearchExpansions(-1));
    }

    /** An expansion that adds nobody is a button that does nothing — a config error, not a policy. */
    @Test
    void aNonPositiveExpansionIncrementIsRejected() {
        assertRejected("expansion-pool-increment", p -> p.setExpansionPoolIncrement(0));
    }

    /** A multiplier below 1 would <em>narrow</em> the search on expansion — the opposite of the word. */
    @Test
    void anExpansionRadiusMultiplierBelowOneIsRejected() {
        assertRejected("expansion-radius-multiplier",
                p -> p.setExpansionRadiusMultiplier(new java.math.BigDecimal("0.5")));
    }

    private static void assertRejected(String expectedPropertyName, java.util.function.Consumer<SosProperties> mutate) {
        assertThatThrownBy(() -> propertiesWith(mutate).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining(expectedPropertyName);
    }

    private static SosProperties propertiesWith(java.util.function.Consumer<SosProperties> mutate) {
        SosProperties properties = new SosProperties();
        mutate.accept(properties);
        return properties;
    }
}
