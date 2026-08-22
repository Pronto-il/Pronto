package com.pronto.sos.service;

import com.pronto.sos.config.SosProperties;
import com.pronto.sos.entity.SosUrgency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The expansion policy, pinned. These are the numbers the customer's "סרוק שוב" actually buys,
 * and the bound that stops it being an infinite search.
 */
class SosSearchScopeTest {

    private final SosProperties properties = new SosProperties();

    @Test
    void theInitialScopeIsTheConfiguredPoolAndRadius() {
        SosSearchScope scope = SosSearchScope.initial(SosUrgency.URGENT, properties);

        assertThat(scope.level()).isZero();
        assertThat(scope.poolSize()).isEqualTo(8);
        assertThat(scope.maxRadiusKm()).isEqualByComparingTo("40.0");
    }

    /** An emergency starts wider, and expansion builds on that rather than resetting it. */
    @Test
    void anEmergencyStartsFromItsOwnLargerPool() {
        assertThat(SosSearchScope.initial(SosUrgency.EMERGENCY, properties).poolSize()).isEqualTo(15);
        assertThat(SosSearchScope.forLevel(1, SosUrgency.EMERGENCY, properties).poolSize()).isEqualTo(23);
    }

    /**
     * <b>The pool is a running total, not a per-wave allowance.</b> Level 2 means "at most 24
     * professionals have ever been contacted for this request", not "24 more".
     */
    @Test
    void eachExpansionAddsOneIncrementToTheRunningPoolTotal() {
        assertThat(SosSearchScope.forLevel(0, SosUrgency.URGENT, properties).poolSize()).isEqualTo(8);
        assertThat(SosSearchScope.forLevel(1, SosUrgency.URGENT, properties).poolSize()).isEqualTo(16);
        assertThat(SosSearchScope.forLevel(2, SosUrgency.URGENT, properties).poolSize()).isEqualTo(24);
    }

    /**
     * The radius seam. Inert against today's placeholder distance model (8 km same-city, 35 km
     * otherwise, against a 40 km ceiling), and deliberately so — see {@link SosSearchScope}. It
     * still has to compute the right number, because the day real geocoding lands it stops being
     * inert without anybody revisiting this file.
     */
    @Test
    void theRadiusCeilingWidensByTheConfiguredMultiplierPerLevel() {
        assertThat(SosSearchScope.forLevel(1, SosUrgency.URGENT, properties).maxRadiusKm())
                .isEqualByComparingTo("60.0");
        assertThat(SosSearchScope.forLevel(2, SosUrgency.URGENT, properties).maxRadiusKm())
                .isEqualByComparingTo("90.0");
    }

    /** A deployment with the radius filter disabled stays disabled at every level. */
    @Test
    void aDisabledRadiusFilterIsNotResurrectedByExpanding() {
        properties.setMaxDispatchRadiusKm(null);

        assertThat(SosSearchScope.forLevel(2, SosUrgency.URGENT, properties).maxRadiusKm()).isNull();
    }

    /** A multiplier of exactly 1 is legal and means "the pool grows, the radius does not". */
    @Test
    void aMultiplierOfOneLeavesTheRadiusAlone() {
        properties.setExpansionRadiusMultiplier(BigDecimal.ONE);

        assertThat(SosSearchScope.forLevel(2, SosUrgency.URGENT, properties).maxRadiusKm())
                .isEqualByComparingTo("40.0");
    }

    /** Defensive: a negative level can only come from corrupt state, and must not shrink the pool. */
    @Test
    void aNegativeLevelIsClampedToTheInitialScope() {
        assertThat(SosSearchScope.forLevel(-3, SosUrgency.URGENT, properties).poolSize()).isEqualTo(8);
        assertThat(SosSearchScope.forLevel(-3, SosUrgency.URGENT, properties).level()).isZero();
    }
}
