package com.pronto.maps.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup validation for {@code pronto.maps.*} and {@code pronto.location.*}.
 *
 * <p>The same reasoning {@code SosPropertiesTest} records for the SOS timers applies here, and one
 * rule in particular is not a matter of taste: an arrival accuracy tolerance looser than the
 * geofence radius would make the geofence decorative, and that is far better caught at boot than
 * discovered from a dispute six months later.
 */
class MapsConfigurationTest {

    // ---- pronto.maps ----

    @Test
    void defaultsAreValid() {
        assertThatCode(() -> new MapsProperties().validate()).doesNotThrowAnyException();
    }

    @Test
    void theDefaultModeIsTheOfflineOneSoAFreshCheckoutNeedsNoCredential() {
        MapsProperties properties = new MapsProperties();

        assertThat(properties.isFakeMode()).isTrue();
        assertThat(properties.getApiKey()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "GOOGLE", "  fake  ", "Fake"})
    void modeIsCaseAndWhitespaceInsensitive(String mode) {
        MapsProperties properties = new MapsProperties();
        properties.setMode(mode);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"here", "osm", "aws", "googl", ""})
    void anUnrecognisedModeRefusesToStartRatherThanFallingBackToAnything(String mode) {
        MapsProperties properties = new MapsProperties();
        properties.setMode(mode);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAPS_MODE");
    }

    @Test
    void nonPositiveBudgetsAndTimeoutsAreRefused() {
        assertThatThrownBy(() -> withMaps(p -> p.setTimeoutMs(0)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withMaps(p -> p.setMatrixBatchSize(0)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withMaps(p -> p.setMaxRoutedCandidates(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Zero disables a cache, which is a deployment somebody might genuinely want. Negative is not. */
    @Test
    void aZeroCacheTtlIsLegalButANegativeOneIsNot() {
        assertThatCode(() -> withMaps(p -> p.setDistanceCacheTtlSeconds(0))).doesNotThrowAnyException();
        assertThatThrownBy(() -> withMaps(p -> p.setTrafficDurationCacheTtlSeconds(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** A batch larger than the whole budget makes the batch size unreachable — a config mistake. */
    @Test
    void aBatchSizeLargerThanTheRoutingBudgetIsRefused() {
        assertThatThrownBy(() -> withMaps(p -> {
            p.setMaxRoutedCandidates(10);
            p.setMatrixBatchSize(25);
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matrix-batch-size");
    }

    /**
     * Traffic-aware durations must not be cached anywhere near as long as road distances. Not
     * validated as a hard rule (an operator may deliberately want both short), but the shipped
     * defaults have to be right, because they are what almost every deployment will run.
     */
    @Test
    void theShippedTrafficTtlIsFarShorterThanTheDistanceTtl() {
        MapsProperties properties = new MapsProperties();

        assertThat(properties.getTrafficDurationCacheTtlSeconds())
                .isLessThan(properties.getDistanceCacheTtlSeconds() / 10);
    }

    private static void withMaps(java.util.function.Consumer<MapsProperties> mutation) {
        MapsProperties properties = new MapsProperties();
        mutation.accept(properties);
        properties.validate();
    }

    // ---- pronto.location ----

    @Test
    void locationDefaultsAreValid() {
        assertThatCode(() -> new LocationProperties().validate()).doesNotThrowAnyException();
    }

    /**
     * The rule that keeps the geofence meaningful: a fix whose own error circle is wider than the
     * radius cannot establish presence inside it.
     */
    @Test
    void anArrivalAccuracyToleranceLooserThanTheGeofenceRefusesToStart() {
        LocationProperties properties = new LocationProperties();
        properties.setArrivalRadiusMeters(50);
        properties.setArrivalMaxAccuracyMeters(200);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arrival-max-accuracy-meters");
    }

    @Test
    void equalRadiusAndAccuracyIsTheBoundaryAndIsAllowed() {
        LocationProperties properties = new LocationProperties();
        properties.setArrivalRadiusMeters(100);
        properties.setArrivalMaxAccuracyMeters(100);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void nonPositiveDurationsAndDistancesAreRefused() {
        assertThatThrownBy(() -> withLocation(p -> p.setProfessionalFreshness(Duration.ZERO)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withLocation(p -> p.setArrivalMaxAge(Duration.ofMinutes(-1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withLocation(p -> p.setArrivalRadiusMeters(0)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> withLocation(p -> p.setMaxAccuracyMeters(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The shipped defaults encode the milestone's central asymmetry: routing asks "roughly where
     * are you", arrival asks "are you at this door". If a future edit ever made them equal, the
     * arrival check would silently become as permissive as the routing one.
     */
    @Test
    void arrivalIsHeldToAStrictlyTighterBarThanRouting() {
        LocationProperties properties = new LocationProperties();

        assertThat(properties.getArrivalMaxAge()).isLessThan(properties.getProfessionalFreshness());
        assertThat(properties.getArrivalMaxAccuracyMeters()).isLessThan(properties.getMaxAccuracyMeters());
    }

    private static void withLocation(java.util.function.Consumer<LocationProperties> mutation) {
        LocationProperties properties = new LocationProperties();
        mutation.accept(properties);
        properties.validate();
    }
}
