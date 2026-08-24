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
        assertRejected("scan-window-seconds", p -> p.setScanWindowSeconds(0));
        assertRejected("expansion-interval-seconds", p -> p.setExpansionIntervalSeconds(0));
        assertRejected("confirmation-grace-seconds", p -> p.setConfirmationGraceSeconds(0));
        assertRejected("confirmation-grace-seconds", p -> p.setConfirmationGraceSeconds(-1));
    }

    @Test
    void everyPoolSizeIsRejectedWhenNotPositive() {
        assertRejected("candidate-pool-size", p -> p.setCandidatePoolSize(0));
        assertRejected("emergency-candidate-pool-size", p -> p.setEmergencyCandidatePoolSize(-3));
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
     * MS3 removed the old "an offer must not outlive the scan window" cross-field rule, and this
     * test guards that removal rather than mourning it: a professional contacted in the last
     * seconds of the scan is *supposed* to keep answering after the platform has stopped looking,
     * so a configuration where the response window reaches past the scan window is the intended
     * shape of this feature, not a typo.
     */
    @Test
    void anOfferTtlLongerThanTheScanWindowIsAccepted() {
        assertThatCode(() -> propertiesWith(p -> {
            p.setOfferTtlSeconds(600);
            p.setScanWindowSeconds(300);
        }).validate()).doesNotThrowAnyException();
    }

    /** The shipped timing rules, stated once so a silent default change fails here. */
    @Test
    void theShippedTimersAreTenMinutesAndTwoMinutes() {
        SosProperties defaults = new SosProperties();
        assertThat(defaults.getScanWindowSeconds()).isEqualTo(600);
        assertThat(defaults.getOfferTtlSeconds()).isEqualTo(600);
        assertThat(defaults.getExpansionIntervalSeconds()).isEqualTo(120);
        assertThat(defaults.getMaxSearchExpansions()).isEqualTo(4);
    }

    /**
     * <b>There is no customer-decision timer, and this test is what keeps it that way.</b>
     *
     * <p>A property called anything like "decision window" reappearing on this class would mean
     * the rule it encodes has reappeared too: a clock that deletes a professional the customer
     * could still have chosen. The absence is the feature, so it is asserted rather than assumed.
     */
    @Test
    void thereIsNoCustomerDecisionTimerProperty() {
        assertThat(java.util.Arrays.stream(SosProperties.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .filter(name -> name.toLowerCase().contains("decision")
                        || name.toLowerCase().contains("selectionwindow")))
                .as("SosProperties fields describing a customer-decision deadline")
                .isEmpty();
    }

    /** Four 2-minute expansions is exactly what fits inside a 10-minute scan. */
    @Test
    void theExpansionCadenceFitsInsideTheScanWindow() {
        SosProperties defaults = new SosProperties();
        assertThat(defaults.getMaxSearchExpansions() * defaults.getExpansionIntervalSeconds())
                .isLessThan(defaults.getScanWindowSeconds());
    }

    // ---- search expansion ----

    /**
     * Zero is legal here and nowhere else above: it turns automatic expansion off and restores
     * single-wave dispatch, which is a deployment somebody might genuinely want.
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
