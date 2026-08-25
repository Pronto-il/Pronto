package com.pronto.professionals.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.maps.config.LocationProperties;
import com.pronto.professionals.entity.ProfessionalLocation;
import com.pronto.professionals.repository.ProfessionalLocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>The single authority on whether a professional's position may be used for anything.</b>
 *
 * <p>Every consumer — the marketplace listing, {@code Fastest} sorting, SOS candidate discovery,
 * the {@code ON_THE_WAY} arrival estimate, the {@code ARRIVED} geofence — asks this service, and
 * none of them re-implements the freshness or accuracy rule. That centralisation is the point:
 * the pre-MS2 codebase had the same distance question answered by a string comparison in one
 * place and a config constant in another, and they drifted. There is exactly one place here where
 * "is this fix good enough" is decided, and the answer it gives is a
 * {@link RouteUnavailableReason} — a reason code the caller must handle, never a silent
 * substitution.
 *
 * <h2>The rule</h2>
 *
 * A stored position is usable as a routing origin when <em>all</em> of:
 * <ul>
 *   <li>it exists at all;</li>
 *   <li>its age — the stricter of device-capture age and server-receive age, see
 *       {@link ProfessionalLocation#age} — is within
 *       {@code pronto.location.professional-freshness};</li>
 *   <li>its reported accuracy is within {@code pronto.location.max-accuracy-meters}.</li>
 * </ul>
 *
 * Anything else yields a reason, and the caller decides what that means in its flow. The two
 * flows deliberately differ: a normal listing still shows the professional, with no ETA, because
 * being unroutable is not a reason to hide someone a customer could still book for next Tuesday.
 * SOS excludes them outright, because SOS is a promise about arrival speed and there is nothing
 * to promise.
 */
@Service
public class ProfessionalLocationService {

    private static final Logger log = LoggerFactory.getLogger(ProfessionalLocationService.class);

    /**
     * The absolute ceiling on a reported accuracy figure, matching
     * {@code ck_professional_locations_accuracy}. A value beyond this is a malformed client, not
     * a poor fix, and is refused as a validation error rather than accepted and then judged
     * unusable — the difference matters, because the second would silently store nonsense.
     */
    private static final BigDecimal MAX_PLAUSIBLE_ACCURACY_METERS = new BigDecimal("100000");

    private final ProfessionalLocationRepository repository;
    private final LocationProperties properties;

    public ProfessionalLocationService(ProfessionalLocationRepository repository, LocationProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    // ---------------------------------------------------------------------------------------
    // Write
    // ---------------------------------------------------------------------------------------

    /**
     * Record a new reading for this professional, replacing whatever was there.
     *
     * <p>Validation is <b>rejection, not correction</b>: a malformed coordinate or an implausible
     * accuracy is a {@code 400}, never a clamped-and-stored value. Storing a corrected version of
     * a bad reading would mean the row no longer describes what the device actually reported,
     * which is exactly the property arrival verification later depends on.
     *
     * @param capturedAt device-reported capture time. A modestly future timestamp (within
     *                   {@code pronto.location.max-clock-skew}) is clamped to now — ordinary
     *                   client clock drift, not worth failing a professional's shift over. A
     *                   wildly future one is refused, because accepting it would hand a client a
     *                   way to manufacture permanent freshness.
     */
    @Transactional
    public ProfessionalLocation record(Long professionalId, BigDecimal latitude, BigDecimal longitude,
                                        BigDecimal accuracyMeters, Instant capturedAt, Instant now) {
        GeoCoordinates coordinates = parseCoordinates(latitude, longitude);
        validateAccuracy(accuracyMeters);
        Instant effectiveCapturedAt = normalizeCapturedAt(capturedAt, now);

        ProfessionalLocation location = repository.findById(professionalId).orElse(null);
        if (location == null) {
            location = new ProfessionalLocation(professionalId, coordinates, accuracyMeters, effectiveCapturedAt, now);
        } else {
            location.apply(coordinates, accuracyMeters, effectiveCapturedAt, now);
        }
        ProfessionalLocation saved = repository.save(location);

        // Reason codes and ids, never coordinates -- roadmap §40. accuracyMeters is safe: it is a
        // quality figure, not a position.
        log.info("location.recorded professionalId={} accuracyMeters={} ageSeconds={}",
                professionalId, accuracyMeters, Duration.between(effectiveCapturedAt, now).toSeconds());
        return saved;
    }

    // ---------------------------------------------------------------------------------------
    // Read / policy
    // ---------------------------------------------------------------------------------------

    /**
     * The usable routing origins for these professionals, plus a reason for every one that has
     * none.
     *
     * <p>One query for the whole set. Callers get back a result that accounts for <b>every</b>
     * id they asked about, so there is no way to iterate the usable map and quietly forget that
     * some candidates were dropped — which is how a professional with no GPS ends up silently
     * treated as being at distance zero.
     */
    @Transactional(readOnly = true)
    public LocationLookup lookup(Collection<Long> professionalIds, Instant now) {
        Map<Long, GeoCoordinates> usable = new LinkedHashMap<>();
        Map<Long, RouteUnavailableReason> rejected = new LinkedHashMap<>();
        if (professionalIds == null || professionalIds.isEmpty()) {
            return new LocationLookup(usable, rejected);
        }

        List<ProfessionalLocation> stored = repository.findByProfessionalIdIn(professionalIds);
        Map<Long, ProfessionalLocation> byId = new LinkedHashMap<>();
        stored.forEach(location -> byId.put(location.getProfessionalId(), location));

        for (Long professionalId : professionalIds) {
            ProfessionalLocation location = byId.get(professionalId);
            RouteUnavailableReason reason = evaluate(location, now);
            if (reason == null) {
                usable.put(professionalId, location.coordinates());
            } else {
                rejected.put(professionalId, reason);
            }
        }

        if (!rejected.isEmpty()) {
            long missing = rejected.values().stream()
                    .filter(r -> r == RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING).count();
            long stale = rejected.values().stream()
                    .filter(r -> r == RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE).count();
            long inaccurate = rejected.values().stream()
                    .filter(r -> r == RouteUnavailableReason.PROFESSIONAL_LOCATION_INACCURATE).count();
            log.info("location.lookup requested={} usable={} missing={} stale={} inaccurate={}",
                    professionalIds.size(), usable.size(), missing, stale, inaccurate);
        }
        return new LocationLookup(usable, rejected);
    }

    /** Single-professional form of {@link #lookup}. */
    @Transactional(readOnly = true)
    public Optional<GeoCoordinates> usableOrigin(Long professionalId, Instant now) {
        return Optional.ofNullable(lookup(List.of(professionalId), now).usable().get(professionalId));
    }

    /** The professional's own view of their position's state — their data, and only theirs. */
    @Transactional(readOnly = true)
    public LocationStatus status(Long professionalId, Instant now) {
        ProfessionalLocation location = repository.findById(professionalId).orElse(null);
        if (location == null) {
            return new LocationStatus(false, null, null, RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING);
        }
        RouteUnavailableReason reason = evaluate(location, now);
        return new LocationStatus(reason == null, location.getUpdatedAt(), location.getAccuracyMeters(), reason);
    }

    /**
     * The freshness/accuracy rule itself. {@code null} means usable.
     *
     * <p>Order matters and is deliberate: absence, then staleness, then accuracy. A professional
     * whose fix is both stale and imprecise is reported as stale, because that is the one they
     * can fix by opening the app.
     */
    RouteUnavailableReason evaluate(ProfessionalLocation location, Instant now) {
        if (location == null) {
            return RouteUnavailableReason.PROFESSIONAL_LOCATION_MISSING;
        }
        if (location.age(now).compareTo(properties.getProfessionalFreshness()) > 0) {
            return RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE;
        }
        if (location.getAccuracyMeters().doubleValue() > properties.getMaxAccuracyMeters()) {
            return RouteUnavailableReason.PROFESSIONAL_LOCATION_INACCURATE;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Validation helpers, shared with the arrival flow
    // ---------------------------------------------------------------------------------------

    /**
     * {@code 400} on a coordinate outside the legal WGS-84 range or a missing half of the pair.
     * Shared with arrival verification so both endpoints refuse the same inputs the same way.
     */
    public GeoCoordinates parseCoordinates(BigDecimal latitude, BigDecimal longitude) {
        try {
            return new GeoCoordinates(latitude, longitude);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("latitude", "latitude and longitude must be a valid coordinate pair")));
        }
    }

    /** {@code 400} on a non-positive or implausibly large accuracy. */
    public void validateAccuracy(BigDecimal accuracyMeters) {
        if (accuracyMeters == null || accuracyMeters.signum() <= 0
                || accuracyMeters.compareTo(MAX_PLAUSIBLE_ACCURACY_METERS) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("accuracyMeters",
                            "must be a positive number of metres no greater than "
                                    + MAX_PLAUSIBLE_ACCURACY_METERS.toPlainString())));
        }
    }

    /**
     * Clamp modest future skew, refuse the rest. See {@link #record}'s parameter documentation
     * for why this is not simply "trust the client" or simply "always use server time".
     */
    public Instant normalizeCapturedAt(Instant capturedAt, Instant now) {
        if (capturedAt == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("capturedAt", "is required")));
        }
        if (capturedAt.isAfter(now)) {
            if (Duration.between(now, capturedAt).compareTo(properties.getMaxClockSkew()) > 0) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                        List.of(new FieldError("capturedAt", "must not be in the future")));
            }
            return now;
        }
        return capturedAt;
    }

    /**
     * Every id asked about is accounted for exactly once, in one map or the other.
     *
     * @param usable   professionals whose current position may be routed from
     * @param rejected professionals whose position may not be used, and why — always a reason,
     *                 never an empty absence
     */
    public record LocationLookup(Map<Long, GeoCoordinates> usable, Map<Long, RouteUnavailableReason> rejected) {

        /** The reason this id has no origin, or {@code null} if it does. */
        public RouteUnavailableReason reasonFor(Long professionalId) {
            return rejected.get(professionalId);
        }
    }

    /**
     * What a professional may be told about their own location state.
     *
     * <p><b>Carries no coordinates</b>, even though the caller owns them. The professional's
     * client already knows where it is — it just sent the reading — so returning the position
     * would add nothing while creating a response shape that a future careless change could
     * expose more widely. What is genuinely useful is whether the platform currently considers
     * them routable, and if not, why.
     */
    public record LocationStatus(boolean usable, Instant updatedAt, BigDecimal accuracyMeters,
                                  RouteUnavailableReason reason) {
    }
}
