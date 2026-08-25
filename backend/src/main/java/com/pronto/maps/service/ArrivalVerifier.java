package com.pronto.maps.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.GeoDistance;
import com.pronto.maps.config.LocationProperties;
import com.pronto.professionals.service.ProfessionalLocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * <b>"Is this professional physically at that address?"</b> — asked once, answered once, for both
 * flows that ask it.
 *
 * <p>The Standard booking flow ({@code bookings.service.BookingsService#arrived}) and the SOS
 * flow ({@code sos.service.SosOfferService#arrived}) are separate lifecycles with separate state
 * machines, separate tables and separate notifications — but "the professional pressed הגעתי" has
 * to mean exactly the same thing in both, or the platform would be verifying arrival on the
 * calm flow and taking somebody's word for it on the urgent one. This class is that shared rule.
 *
 * <h2>What it checks, in order</h2>
 *
 * <ol>
 *   <li><b>Shape</b> — coordinates in range, accuracy positive and plausible, capture time
 *       present and not implausibly in the future. {@code 400}.</li>
 *   <li><b>Freshness</b> — within {@code pronto.location.arrival-max-age} (two minutes). Far
 *       tighter than the routing freshness window, because this fix is not estimating anything:
 *       it is the entire evidence for a claim about where a person is standing right now.
 *       {@code 422}.</li>
 *   <li><b>Precision</b> — within {@code pronto.location.arrival-max-accuracy-meters}. A fix
 *       whose own error circle is wider than the geofence cannot establish presence inside it,
 *       so accepting one would be a verification in name only. {@code 422}.</li>
 *   <li><b>Proximity</b> — great-circle distance to the destination within
 *       {@code pronto.location.arrival-radius-meters}. {@code 422}.</li>
 * </ol>
 *
 * <h2>Haversine, not a routing call</h2>
 *
 * The question is "are they near the door", not "how far must they drive". Asking the routing
 * provider would be slower, cost money on every arrival, fail during an outage, and be
 * <em>less</em> correct — road distance from a point to itself is not zero when the nearest road
 * segment is forty metres away. See {@code maps.GeoDistance}.
 *
 * <h2>What this proves, and what it does not</h2>
 *
 * It proves that the position the professional's device reported, moments ago and with an
 * accuracy it also reported, is inside the geofence. It does <b>not</b> prove the device was
 * telling the truth: browser geolocation originates on the client and can be spoofed. MS2 makes
 * no claim to be fraud-proof and builds no device attestation — see the {@code maps} README.
 */
@Service
public class ArrivalVerifier {

    private static final Logger log = LoggerFactory.getLogger(ArrivalVerifier.class);

    private final ProfessionalLocationService locationService;
    private final LocationProperties properties;

    public ArrivalVerifier(ProfessionalLocationService locationService, LocationProperties properties) {
        this.locationService = locationService;
        this.properties = properties;
    }

    /**
     * Verify one arrival claim, or throw.
     *
     * <p><b>Side effect, deliberately:</b> the submitted fix is recorded as the professional's
     * current position <em>before</em> any verification branch. It is fresh and well-formed
     * regardless of what happens next, and a rejected arrival still improves what the platform
     * knows about where they are — discarding it would be wasteful and would make a professional
     * who is genuinely two streets away look like one with no GPS at all.
     *
     * @param destination the order's or request's <b>immutable</b> destination snapshot. Never
     *                    the customer's current default address: an order is verified against the
     *                    place it was created for.
     * @return the measured distance in metres, for the caller to record as evidence
     * @throws ApiException {@code 400} on a malformed fix, {@code 422} on one that is too old,
     *                      too imprecise, or outside the geofence
     */
    public BigDecimal verify(Long professionalId, GeoCoordinates destination, BigDecimal latitude,
                              BigDecimal longitude, BigDecimal accuracyMeters, Instant capturedAt,
                              Instant now, String subject) {
        GeoCoordinates position = locationService.parseCoordinates(latitude, longitude);
        locationService.validateAccuracy(accuracyMeters);
        Instant effectiveCapturedAt = locationService.normalizeCapturedAt(capturedAt, now);

        locationService.record(professionalId, position.latitude(), position.longitude(),
                accuracyMeters, effectiveCapturedAt, now);

        Duration age = Duration.between(effectiveCapturedAt, now);
        if (age.compareTo(properties.getArrivalMaxAge()) > 0) {
            log.info("arrival.rejected subject={} professionalId={} reason=STALE ageSeconds={}",
                    subject, professionalId, age.toSeconds());
            throw new ApiException(ErrorCode.LOCATION_QUALITY_INSUFFICIENT,
                    "The location reading is too old to verify arrival. Take a fresh reading and try again.");
        }
        if (accuracyMeters.doubleValue() > properties.getArrivalMaxAccuracyMeters()) {
            log.info("arrival.rejected subject={} professionalId={} reason=INACCURATE accuracyMeters={}",
                    subject, professionalId, accuracyMeters);
            throw new ApiException(ErrorCode.LOCATION_QUALITY_INSUFFICIENT,
                    "The location reading is not precise enough to verify arrival. Enable precise location "
                            + "and try again.");
        }

        if (destination == null) {
            log.info("arrival.rejected subject={} professionalId={} reason=DESTINATION_UNKNOWN",
                    subject, professionalId);
            throw new ApiException(ErrorCode.ORDER_DESTINATION_UNKNOWN,
                    "This job has no verified destination coordinates, so arrival cannot be confirmed "
                            + "automatically.");
        }

        double distanceMeters = GeoDistance.meters(position, destination);
        if (distanceMeters > properties.getArrivalRadiusMeters()) {
            // The measured distance is logged -- operations needs it -- but deliberately NOT
            // returned: a refusal that reports how far off you are is a refusal that can be used
            // to search for the customer's address.
            log.info("arrival.rejected subject={} professionalId={} reason=OUT_OF_RANGE distanceMeters={} "
                            + "radiusMeters={}",
                    subject, professionalId, Math.round(distanceMeters), properties.getArrivalRadiusMeters());
            throw new ApiException(ErrorCode.ARRIVAL_OUT_OF_RANGE,
                    "Your location does not appear to be at the customer's address.");
        }

        log.info("arrival.verified subject={} professionalId={} distanceMeters={}",
                subject, professionalId, Math.round(distanceMeters));
        return BigDecimal.valueOf(distanceMeters).setScale(2, RoundingMode.HALF_UP);
    }

    /** The verified position, for callers that record it as evidence. */
    public GeoCoordinates positionOf(BigDecimal latitude, BigDecimal longitude) {
        return locationService.parseCoordinates(latitude, longitude);
    }
}
