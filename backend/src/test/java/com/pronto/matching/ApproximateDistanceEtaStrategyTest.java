package com.pronto.matching;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApproximateDistanceEtaStrategy} — same/different-city x peak/off-peak
 * combinations, plus the exact half-open-interval boundary times the approved design calls
 * out by name (08:00:00, 10:59:59, 11:00:00, 15:00:00, 17:59:59, 18:00:00).
 */
class ApproximateDistanceEtaStrategyTest {

    private static final ZoneId BUSINESS_TIMEZONE = ZoneId.of("Asia/Jerusalem");
    private static final LocalDate ANY_WEEKDAY = LocalDate.of(2026, 8, 17); // a Monday

    private final ApproximateDistanceEtaStrategy strategy = new ApproximateDistanceEtaStrategy();

    private Instant at(LocalTime time) {
        return ZonedDateTime.of(ANY_WEEKDAY, time, BUSINESS_TIMEZONE).toInstant();
    }

    private ServiceLocation location(String city) {
        return new ServiceLocation(city, "Some St", "1", null);
    }

    // ---- same/different city x peak/off-peak ----

    @Test
    void sameCity_offPeak_usesBaseFiguresWithNoTrafficAdjustment() {
        EtaResult result = strategy.calculate("Tel Aviv", location("tel aviv"), at(LocalTime.of(6, 0)));

        assertThat(result.sameCity()).isTrue();
        assertThat(result.distanceKm()).isEqualByComparingTo("8.0");
        assertThat(result.baseTravelTimeMinutes()).isEqualTo(34);
        assertThat(result.trafficAdjustmentMinutes()).isEqualTo(0);
        assertThat(result.etaMinutes()).isEqualTo(34);
    }

    @Test
    void differentCity_offPeak_usesBaseFiguresWithNoTrafficAdjustment() {
        EtaResult result = strategy.calculate("Haifa", location("Eilat"), at(LocalTime.of(6, 0)));

        assertThat(result.sameCity()).isFalse();
        assertThat(result.distanceKm()).isEqualByComparingTo("35.0");
        assertThat(result.baseTravelTimeMinutes()).isEqualTo(40);
        assertThat(result.trafficAdjustmentMinutes()).isEqualTo(0);
        assertThat(result.etaMinutes()).isEqualTo(40);
    }

    @Test
    void sameCity_peak_addsSameCityTrafficAdjustment() {
        EtaResult result = strategy.calculate("Haifa", location("Haifa"), at(LocalTime.of(9, 0)));

        assertThat(result.sameCity()).isTrue();
        assertThat(result.baseTravelTimeMinutes()).isEqualTo(34);
        assertThat(result.trafficAdjustmentMinutes()).isEqualTo(20);
        assertThat(result.etaMinutes()).isEqualTo(54);
    }

    @Test
    void differentCity_peak_addsDifferentCityTrafficAdjustment() {
        EtaResult result = strategy.calculate("Haifa", location("Eilat"), at(LocalTime.of(16, 0)));

        assertThat(result.sameCity()).isFalse();
        assertThat(result.baseTravelTimeMinutes()).isEqualTo(40);
        assertThat(result.trafficAdjustmentMinutes()).isEqualTo(30);
        assertThat(result.etaMinutes()).isEqualTo(70);
    }

    // ---- city matching semantics ----

    @Test
    void sameCity_isCaseInsensitiveAndTrimmed() {
        EtaResult result = strategy.calculate("  Tel Aviv  ", location("TEL AVIV"), at(LocalTime.of(6, 0)));
        assertThat(result.sameCity()).isTrue();
    }

    @Test
    void nullProfessionalCity_isTreatedAsDifferentCity() {
        EtaResult result = strategy.calculate(null, location("Tel Aviv"), at(LocalTime.of(6, 0)));
        assertThat(result.sameCity()).isFalse();
    }

    // ---- exact boundary values (half-open intervals) ----

    @Test
    void boundary_0800_00_isPeak() {
        EtaResult result = strategy.calculate("Haifa", location("Haifa"), at(LocalTime.of(8, 0, 0)));
        assertThat(result.trafficAdjustmentMinutes()).isEqualTo(20);
    }

    @Test
    void boundary_1059_59_isPeak() {
        EtaResult result = strategy.calculate("Haifa", location("Haifa"), at(LocalTime.of(10, 59, 59)));
        assertThat(result.trafficAdjustmentMinutes()).isEqualTo(20);
    }

    @Test
    void boundary_1100_00_isOffPeak() {
        EtaResult result = strategy.calculate("Haifa", location("Haifa"), at(LocalTime.of(11, 0, 0)));
        assertThat(result.trafficAdjustmentMinutes()).isEqualTo(0);
    }

    @Test
    void boundary_1500_00_isPeak() {
        EtaResult result = strategy.calculate("Haifa", location("Haifa"), at(LocalTime.of(15, 0, 0)));
        assertThat(result.trafficAdjustmentMinutes()).isEqualTo(20);
    }

    @Test
    void boundary_1759_59_isPeak() {
        EtaResult result = strategy.calculate("Haifa", location("Haifa"), at(LocalTime.of(17, 59, 59)));
        assertThat(result.trafficAdjustmentMinutes()).isEqualTo(20);
    }

    @Test
    void boundary_1800_00_isOffPeak() {
        EtaResult result = strategy.calculate("Haifa", location("Haifa"), at(LocalTime.of(18, 0, 0)));
        assertThat(result.trafficAdjustmentMinutes()).isEqualTo(0);
    }
}
