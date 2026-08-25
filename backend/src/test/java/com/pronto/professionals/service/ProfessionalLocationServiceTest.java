package com.pronto.professionals.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.maps.config.LocationProperties;
import com.pronto.professionals.entity.ProfessionalLocation;
import com.pronto.professionals.repository.ProfessionalLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The single authority on whether a professional's position may be used — freshness, accuracy, and
 * the clock-skew rule.
 *
 * <p>Every boundary here decides whether a customer is shown an arrival estimate, so the tests
 * assert the boundary itself rather than a comfortable value either side of it: exactly at the
 * freshness limit is usable, one second past is not.
 */
class ProfessionalLocationServiceTest {

    private static final Long PROFESSIONAL_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final GeoCoordinates TEL_AVIV = GeoCoordinates.of(32.0853, 34.7818);

    private ProfessionalLocationRepository repository;
    private LocationProperties properties;
    private ProfessionalLocationService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ProfessionalLocationRepository.class);
        properties = new LocationProperties();
        service = new ProfessionalLocationService(repository, properties);
        Mockito.lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ProfessionalLocation location(Instant capturedAt, Instant receivedAt, String accuracyMeters) {
        return new ProfessionalLocation(PROFESSIONAL_ID, TEL_AVIV, new BigDecimal(accuracyMeters),
                capturedAt, receivedAt);
    }

    private void stored(ProfessionalLocation location) {
        when(repository.findByProfessionalIdIn(any())).thenReturn(List.of(location));
        when(repository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(location));
    }

    // ---- freshness ----

    @Test
    void aFixTakenJustNowIsUsable() {
        stored(location(NOW.minusSeconds(5), NOW.minusSeconds(5), "12"));

        assertThat(service.lookup(List.of(PROFESSIONAL_ID), NOW).usable()).containsKey(PROFESSIONAL_ID);
    }

    @Test
    void aFixExactlyAtTheFreshnessLimitIsStillUsable() {
        Instant atLimit = NOW.minus(properties.getProfessionalFreshness());
        stored(location(atLimit, atLimit, "12"));

        assertThat(service.lookup(List.of(PROFESSIONAL_ID), NOW).usable()).containsKey(PROFESSIONAL_ID);
    }

    @Test
    void aFixOneSecondPastTheFreshnessLimitIsStale() {
        Instant pastLimit = NOW.minus(properties.getProfessionalFreshness()).minusSeconds(1);
        stored(location(pastLimit, pastLimit, "12"));

        ProfessionalLocationService.LocationLookup lookup = service.lookup(List.of(PROFESSIONAL_ID), NOW);

        assertThat(lookup.usable()).isEmpty();
        assertThat(lookup.reasonFor(PROFESSIONAL_ID))
                .isEqualTo(RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE);
    }

    /**
     * The rule that makes a lying client harmless. A device claiming it captured the fix a second
     * ago, on a reading the server received an hour ago, is stale — freshness is the stricter of
     * the two clocks, and only one of them is client-controlled.
     */
    @Test
    void aFreshCaptureTimeCannotRescueAnOldServerReceiveTime() {
        stored(location(NOW.minusSeconds(1), NOW.minus(Duration.ofHours(1)), "12"));

        assertThat(service.lookup(List.of(PROFESSIONAL_ID), NOW).reasonFor(PROFESSIONAL_ID))
                .isEqualTo(RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE);
    }

    /** And the converse: a just-uploaded reading that the device took an hour ago is also stale. */
    @Test
    void aFreshServerReceiveTimeCannotRescueAnOldCapture() {
        stored(location(NOW.minus(Duration.ofHours(1)), NOW.minusSeconds(1), "12"));

        assertThat(service.lookup(List.of(PROFESSIONAL_ID), NOW).reasonFor(PROFESSIONAL_ID))
                .isEqualTo(RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE);
    }

    // ---- accuracy ----

    @Test
    void aFixExactlyAtTheAccuracyLimitIsStillUsable() {
        stored(location(NOW, NOW, String.valueOf((long) properties.getMaxAccuracyMeters())));

        assertThat(service.lookup(List.of(PROFESSIONAL_ID), NOW).usable()).containsKey(PROFESSIONAL_ID);
    }

    @Test
    void aFixCoarserThanTheAccuracyLimitIsRejected() {
        stored(location(NOW, NOW, String.valueOf((long) properties.getMaxAccuracyMeters() + 1)));

        assertThat(service.lookup(List.of(PROFESSIONAL_ID), NOW).reasonFor(PROFESSIONAL_ID))
                .isEqualTo(RouteUnavailableReason.PROFESSIONAL_LOCATION_INACCURATE);
    }

    /**
     * Order of evaluation, and it is deliberate: somebody who is both stale and imprecise is told
     * they are stale, because that is the one they can fix by opening the app.
     */
    @Test
    void staleBeatsInaccurateWhenAFixIsBoth() {
        Instant old = NOW.minus(Duration.ofHours(2));
        stored(location(old, old, "5000"));

        assertThat(service.lookup(List.of(PROFESSIONAL_ID), NOW).reasonFor(PROFESSIONAL_ID))
                .isEqualTo(RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE);
    }

    // ---- absence ----

    @Test
    void aProfessionalWhoHasNeverSentAPositionIsMissingNotStale() {
        when(repository.findByProfessionalIdIn(any())).thenReturn(List.of());

        assertThat(service.lookup(List.of(PROFESSIONAL_ID), NOW).reasonFor(PROFESSIONAL_ID))
                .isEqualTo(RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING);
    }

    /**
     * The property callers depend on to avoid silently losing candidates: every id asked about is
     * accounted for exactly once, in one map or the other.
     */
    @Test
    void everyRequestedIdIsAccountedForExactlyOnce() {
        ProfessionalLocation good = location(NOW, NOW, "10");
        when(repository.findByProfessionalIdIn(any())).thenReturn(List.of(good));

        ProfessionalLocationService.LocationLookup lookup =
                service.lookup(List.of(PROFESSIONAL_ID, 7L, 8L), NOW);

        assertThat(lookup.usable().keySet()).containsExactly(PROFESSIONAL_ID);
        assertThat(lookup.rejected().keySet()).containsExactlyInAnyOrder(7L, 8L);
        assertThat(lookup.usable().size() + lookup.rejected().size()).isEqualTo(3);
    }

    @Test
    void anEmptyRequestDoesNotTouchTheDatabase() {
        service.lookup(List.of(), NOW);

        Mockito.verify(repository, Mockito.never()).findByProfessionalIdIn(any());
    }

    // ---- write-path validation ----

    @Test
    void anOutOfRangeCoordinateIsRejectedRatherThanClamped() {
        assertThatThrownBy(() -> service.record(PROFESSIONAL_ID, new BigDecimal("120.0"),
                new BigDecimal("34.0"), new BigDecimal("10"), NOW, NOW))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        Mockito.verify(repository, Mockito.never()).save(any());
    }

    @Test
    void aNonPositiveAccuracyIsRejected() {
        // Zero would claim a perfect fix, which no receiver can produce.
        assertThatThrownBy(() -> service.record(PROFESSIONAL_ID, new BigDecimal("32.0"),
                new BigDecimal("34.0"), BigDecimal.ZERO, NOW, NOW))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.record(PROFESSIONAL_ID, new BigDecimal("32.0"),
                new BigDecimal("34.0"), new BigDecimal("-5"), NOW, NOW))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anImplausiblyLargeAccuracyIsAMalformedClientNotAPoorFix() {
        assertThatThrownBy(() -> service.record(PROFESSIONAL_ID, new BigDecimal("32.0"),
                new BigDecimal("34.0"), new BigDecimal("999999"), NOW, NOW))
                .isInstanceOf(ApiException.class);
    }

    // ---- clock skew ----

    @Test
    void modestFutureClockSkewIsClampedToNowRatherThanRefused() {
        Instant slightlyAhead = NOW.plusSeconds(30);

        ProfessionalLocation saved = service.record(PROFESSIONAL_ID, new BigDecimal("32.0"),
                new BigDecimal("34.0"), new BigDecimal("10"), slightlyAhead, NOW);

        assertThat(saved.getCapturedAt()).isEqualTo(NOW);
    }

    /**
     * A wildly future timestamp is refused rather than clamped. Accepting it would hand a client a
     * way to manufacture permanent freshness, which is the direction an attacker would push.
     */
    @Test
    void aWildlyFutureCaptureTimeIsRefused() {
        Instant wayAhead = NOW.plus(Duration.ofHours(3));

        assertThatThrownBy(() -> service.record(PROFESSIONAL_ID, new BigDecimal("32.0"),
                new BigDecimal("34.0"), new BigDecimal("10"), wayAhead, NOW))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aMissingCaptureTimeIsRefused() {
        assertThatThrownBy(() -> service.record(PROFESSIONAL_ID, new BigDecimal("32.0"),
                new BigDecimal("34.0"), new BigDecimal("10"), null, NOW))
                .isInstanceOf(ApiException.class);
    }

    // ---- replace semantics ----

    /**
     * One row per professional, rewritten. MS2 stores no GPS history, and the shape of the write
     * path is what guarantees that rather than a comment saying so.
     */
    @Test
    void asecondReadingReplacesTheFirstRatherThanAppending() {
        ProfessionalLocation existing = location(NOW.minusSeconds(600), NOW.minusSeconds(600), "50");
        when(repository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(existing));

        ProfessionalLocation saved = service.record(PROFESSIONAL_ID, new BigDecimal("31.0"),
                new BigDecimal("35.0"), new BigDecimal("8"), NOW, NOW);

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getLatitude()).isEqualByComparingTo("31.0");
        assertThat(saved.getAccuracyMeters()).isEqualByComparingTo("8");
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        Mockito.verify(repository, Mockito.times(1)).save(any());
    }

    // ---- the professional's own status view ----

    @Test
    void statusReportsUsabilityAndReasonButNeverCoordinates() {
        Instant old = NOW.minus(Duration.ofHours(1));
        stored(location(old, old, "10"));

        ProfessionalLocationService.LocationStatus status = service.status(PROFESSIONAL_ID, NOW);

        assertThat(status.usable()).isFalse();
        assertThat(status.reason()).isEqualTo(RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE);
        assertThat(status.updatedAt()).isEqualTo(old);
        assertThat(status.accuracyMeters()).isEqualByComparingTo("10");
        // The record has no coordinate component at all -- see its Javadoc.
        assertThat(status.toString()).doesNotContain("32.08").doesNotContain("34.78");
    }

    @Test
    void statusForSomebodyWhoHasNeverSentAPositionSaysSo() {
        when(repository.findById(anyLong())).thenReturn(Optional.empty());

        ProfessionalLocationService.LocationStatus status = service.status(PROFESSIONAL_ID, NOW);

        assertThat(status.usable()).isFalse();
        assertThat(status.reason()).isEqualTo(RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING);
        assertThat(status.updatedAt()).isNull();
    }
}
