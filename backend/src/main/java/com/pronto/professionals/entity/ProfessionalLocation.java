package com.pronto.professionals.entity;

import com.pronto.maps.GeoCoordinates;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for {@code professional_locations} ({@code V49}) — one professional's <b>current
 * device position</b>, and the origin every real driving distance and ETA on this platform is
 * measured from.
 *
 * <p><b>One row per professional, replaced in place.</b> {@link #professionalId} is the primary
 * key, so there is no such thing as "the professional's second-most-recent position": an update
 * overwrites. MS2 deliberately does not build GPS history or route replay — see the {@code maps}
 * package README.
 *
 * <p><b>Not the professional's base city.</b> {@code Professional#baseCityId} is business
 * coverage data: which region they will travel to, which city their profile card names. It is
 * emphatically not where they are right now — a professional is usually coming from another job,
 * not from home — and MS2's central product decision is that a base city may never stand in for a
 * live position when a precise ETA is being produced.
 *
 * <p><b>Private operational data.</b> No customer-facing DTO reads this entity. Customers
 * receive derived figures only ({@code distanceKm}, {@code etaMinutes}); see the {@code maps}
 * README's privacy section and {@code CustomerLocationPrivacyTest}.
 */
@Entity
@Table(name = "professional_locations")
public class ProfessionalLocation {

    @Id
    @Column(name = "professional_id", nullable = false)
    private Long professionalId;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "accuracy_meters", nullable = false, precision = 8, scale = 2)
    private BigDecimal accuracyMeters;

    /** Device clock — what the browser reported. Never trusted on its own. */
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    /**
     * Server clock — when this instance accepted the reading. Set here, never from the request
     * body, which is the whole point: a client cannot make a stale fix look fresh, because the
     * value freshness is ultimately bounded by is one it does not control.
     *
     * <p>Written explicitly rather than through {@code @PreUpdate}, because this entity is
     * written through an upsert statement as well as through the persistence context, and two
     * mechanisms disagreeing about the timestamp is worse than one explicit assignment.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfessionalLocation() {
        // JPA
    }

    public ProfessionalLocation(Long professionalId, GeoCoordinates coordinates, BigDecimal accuracyMeters,
                                 Instant capturedAt, Instant receivedAt) {
        this.professionalId = professionalId;
        apply(coordinates, accuracyMeters, capturedAt, receivedAt);
    }

    /** Replace semantics — the same row, new reading. */
    public void apply(GeoCoordinates coordinates, BigDecimal accuracyMeters, Instant capturedAt, Instant receivedAt) {
        this.latitude = coordinates.latitude();
        this.longitude = coordinates.longitude();
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
        this.updatedAt = receivedAt;
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public GeoCoordinates coordinates() {
        return new GeoCoordinates(latitude, longitude);
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public BigDecimal getAccuracyMeters() {
        return accuracyMeters;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * The age of this reading, taking the <b>stricter</b> of the device clock and the server
     * clock.
     *
     * <p>Both are needed, and neither alone is enough. {@code capturedAt} alone is client-
     * controlled: a device with a fast clock (or a client choosing to lie) could keep a
     * half-hour-old fix looking new forever. {@code updatedAt} alone would treat a client that
     * uploads a fix it captured twenty minutes ago as if the position were current. Taking the
     * larger age is the conservative reading of both.
     */
    public java.time.Duration age(Instant now) {
        java.time.Duration sinceCapture = java.time.Duration.between(capturedAt, now);
        java.time.Duration sinceReceived = java.time.Duration.between(updatedAt, now);
        // A negative duration means the timestamp is in the future (clock skew); treat it as zero
        // age here and let the caller's skew rule decide whether to accept it at all.
        if (sinceCapture.isNegative()) {
            sinceCapture = java.time.Duration.ZERO;
        }
        if (sinceReceived.isNegative()) {
            sinceReceived = java.time.Duration.ZERO;
        }
        return sinceCapture.compareTo(sinceReceived) >= 0 ? sinceCapture : sinceReceived;
    }
}
