package com.pronto.maps.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The product rules about <b>positions</b>, as configuration — {@code pronto.location.*}.
 *
 * <p>Separate from {@link MapsProperties} on purpose: that one is about a vendor (which API, which
 * key, which timeout), this one is about what Pronto considers a trustworthy fix and how close
 * counts as "arrived". The vendor can be swapped without revisiting a single value here, and these
 * will be tuned against real field data without touching the vendor integration.
 *
 * <p><b>Why none of this is hardcoded in a service.</b> Every number below is a judgement call
 * about physical reality — urban GPS accuracy, how long somebody stays put, how far a parking
 * space is from a front door — and every one of them will turn out to be slightly wrong the first
 * time a real professional in a real Tel Aviv parking garage uses it. Values that will need
 * adjusting from operational evidence belong in configuration, not buried three call frames deep.
 */
@Component
@ConfigurationProperties(prefix = "pronto.location")
public class LocationProperties {

    /**
     * How recent a professional's position must be to be used as a <b>routing origin</b>.
     *
     * <p>10 minutes. A professional actively working moves continuously, and a position from an
     * hour ago describes a place they have left — routing from it produces a confident ETA to the
     * wrong journey, which is worse than no ETA. Ten minutes is roughly how far a car travels in
     * city traffic before the error exceeds what a customer would forgive, and it comfortably
     * exceeds the client's own refresh cadence so an ordinary active session never goes stale.
     *
     * <p>Evaluated against <b>both</b> {@code captured_at} (device clock) and {@code updated_at}
     * (server clock), taking the stricter — see {@code ProfessionalLocationService}. A client with
     * a wrong or dishonest clock therefore cannot make an old fix look new.
     */
    private Duration professionalFreshness = Duration.ofMinutes(10);

    /**
     * The worst device-reported accuracy still usable as a routing origin, in metres.
     *
     * <p>500 m — deliberately loose. For routing, being half a kilometre off changes a 20-minute
     * ETA by a minute or two; the figure is still substantially true. This exists to reject the
     * genuinely useless (a coarse IP/wifi geolocation reporting a several-kilometre radius, which
     * browsers do return when GPS is unavailable), not to demand precision that routing does not
     * need. Arrival verification uses a far stricter bar — see {@link #arrivalMaxAccuracyMeters}.
     */
    private double maxAccuracyMeters = 500;

    /**
     * How close to the order's destination counts as arrived, in metres.
     *
     * <p>150 m. Chosen from what actually happens rather than from what would be tidy: a
     * professional parks where they can, which in a dense Israeli city is routinely a street or
     * two away; the destination coordinate is a geocoder's idea of a building, itself good to
     * tens of metres; and a phone in a built-up street reports a fix with tens of metres of error
     * of its own. A 25 m radius would reject honest arrivals constantly, and a professional who
     * cannot complete their job because the platform disbelieves them will stop using the
     * platform. 150 m is loose enough to accept every genuine arrival and tight enough that
     * pressing the button from home, or from the previous job, does not pass.
     */
    private double arrivalRadiusMeters = 150;

    /**
     * The worst device-reported accuracy accepted for arrival verification, in metres.
     *
     * <p>100 m, much stricter than {@link #maxAccuracyMeters}, because the question is different.
     * Routing asks "roughly where are you"; arrival asks "are you at this door", and a fix whose
     * own error circle is wider than the geofence cannot answer that — accepting one would mean
     * verifying arrival for somebody who might be anywhere in a 1 km radius, which is a
     * verification in name only.
     */
    private double arrivalMaxAccuracyMeters = 100;

    /**
     * How recent the GPS fix submitted with an arrival claim must be.
     *
     * <p>Two minutes, far tighter than {@link #professionalFreshness}, and for a different reason:
     * this fix is not being used to estimate something, it is the entire evidence for a claim
     * about where a person is right now. The client is expected to take a fresh reading at the
     * moment the button is pressed, which takes seconds — two minutes is generous for that, while
     * being far too short to replay a fix captured at the address earlier in the day.
     */
    private Duration arrivalMaxAge = Duration.ofMinutes(2);

    /**
     * How far in the future a client-supplied {@code capturedAt} may be before the fix is
     * rejected, absorbing ordinary client-clock skew.
     *
     * <p>Without this, a device whose clock runs 30 seconds fast submits a fix "from the future"
     * and every freshness check has to decide what that means. With it, the rule is explicit:
     * modest skew is tolerated and clamped; a wildly future timestamp is rejected as malformed
     * rather than treated as maximally fresh, which is the direction an attacker would push.
     */
    private Duration maxClockSkew = Duration.ofMinutes(2);

    @PostConstruct
    void validate() {
        requirePositive("professional-freshness", professionalFreshness);
        requirePositive("arrival-max-age", arrivalMaxAge);
        requirePositive("max-clock-skew", maxClockSkew);
        requirePositive("max-accuracy-meters", maxAccuracyMeters);
        requirePositive("arrival-radius-meters", arrivalRadiusMeters);
        requirePositive("arrival-max-accuracy-meters", arrivalMaxAccuracyMeters);

        // Not a taste rule: an arrival tolerance looser than the geofence means a fix whose own
        // error exceeds the radius could still verify an arrival, which makes the geofence
        // decorative. Caught at startup rather than discovered from a dispute.
        if (arrivalMaxAccuracyMeters > arrivalRadiusMeters) {
            throw new IllegalStateException("Refusing to start: pronto.location.arrival-max-accuracy-meters ("
                    + arrivalMaxAccuracyMeters + ") exceeds pronto.location.arrival-radius-meters ("
                    + arrivalRadiusMeters + "). A fix less precise than the geofence itself cannot verify "
                    + "presence inside it.");
        }
    }

    private static void requirePositive(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("Refusing to start: pronto.location." + property
                    + " must be a positive duration, but was " + value + ".");
        }
    }

    private static void requirePositive(String property, double value) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalStateException("Refusing to start: pronto.location." + property
                    + " must be a positive number, but was " + value + ".");
        }
    }

    public Duration getProfessionalFreshness() {
        return professionalFreshness;
    }

    public void setProfessionalFreshness(Duration professionalFreshness) {
        this.professionalFreshness = professionalFreshness;
    }

    public double getMaxAccuracyMeters() {
        return maxAccuracyMeters;
    }

    public void setMaxAccuracyMeters(double maxAccuracyMeters) {
        this.maxAccuracyMeters = maxAccuracyMeters;
    }

    public double getArrivalRadiusMeters() {
        return arrivalRadiusMeters;
    }

    public void setArrivalRadiusMeters(double arrivalRadiusMeters) {
        this.arrivalRadiusMeters = arrivalRadiusMeters;
    }

    public double getArrivalMaxAccuracyMeters() {
        return arrivalMaxAccuracyMeters;
    }

    public void setArrivalMaxAccuracyMeters(double arrivalMaxAccuracyMeters) {
        this.arrivalMaxAccuracyMeters = arrivalMaxAccuracyMeters;
    }

    public Duration getArrivalMaxAge() {
        return arrivalMaxAge;
    }

    public void setArrivalMaxAge(Duration arrivalMaxAge) {
        this.arrivalMaxAge = arrivalMaxAge;
    }

    public Duration getMaxClockSkew() {
        return maxClockSkew;
    }

    public void setMaxClockSkew(Duration maxClockSkew) {
        this.maxClockSkew = maxClockSkew;
    }
}
