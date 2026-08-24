package com.pronto.sos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for the {@code sos_offers} table — one dispatched opportunity, for one
 * professional, on one SOS request. Mapping matches {@code V34__create_sos.sql} exactly
 * ({@code ddl-auto: validate}).
 *
 * <p>Same no-setter-for-{@link #status} rule as {@code SosRequest}: every status change goes
 * through {@code sos.repository.SosOfferRepository}'s atomic guarded updates.
 *
 * <p>The three money columns are snapshots taken at dispatch time, never recomputed. A
 * professional accepts a specific number; an edit to {@code professionals.base_price} or to
 * the configured commission rate while an offer is in flight must not silently change what
 * either party agreed to. {@link #platformCommission} is derived from {@link #visitFee} +
 * {@link #sosFee} only — never from the value of the repair work itself, which Pronto takes
 * no cut of.
 */
@Entity
@Table(name = "sos_offers")
public class SosOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sos_request_id", nullable = false)
    private Long sosRequestId;

    @Column(name = "professional_id", nullable = false)
    private Long professionalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SosOfferStatus status;

    /** 1-based position in the ranked candidate list this offer came from. */
    @Column(name = "match_rank", nullable = false)
    private Short matchRank;

    @Column(name = "match_score", nullable = false, precision = 6, scale = 3)
    private BigDecimal matchScore;

    @Column(name = "distance_km", precision = 7, scale = 2)
    private BigDecimal distanceKm;

    /**
     * The platform's ETA estimate at dispatch, replaced by the professional's own figure when
     * they accept — they know their current job and traffic better than
     * {@code matching.ApproximateDistanceEtaStrategy} does.
     *
     * <p><b>Immutable from acceptance onward</b> (MS3): the professional commits to a number the
     * customer then chooses on, so nothing may revise it afterwards — see
     * {@code SosOfferService#updateEta}, which refuses, and note that
     * {@code SosOfferRepository} no longer contains any statement that writes this column
     * outside {@code accept}.
     */
    @Column(name = "estimated_arrival_minutes")
    private Short estimatedArrivalMinutes;

    /**
     * The ETA the professional promised at the moment they accepted, and the moment they
     * accepted it ({@code V41}).
     *
     * <p>Write-once, by the {@code accept} statement only. They duplicate
     * {@link #estimatedArrivalMinutes}/{@link #respondedAt} today <em>because</em> the ETA is
     * locked — that is the point: {@code responded_at} is also stamped by a rejection, and these
     * two columns are the audit record that stays true even if some future code touches the live
     * ETA. "What was promised, and when" is what a reliability or dispute review reads.
     */
    @Column(name = "promised_eta_minutes")
    private Short promisedEtaMinutes;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "visit_fee", precision = 10, scale = 2)
    private BigDecimal visitFee;

    @Column(name = "sos_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal sosFee;

    @Column(name = "platform_commission", nullable = false, precision = 10, scale = 2)
    private BigDecimal platformCommission;

    @Column(name = "offered_at", nullable = false)
    private Instant offeredAt;

    @Column(name = "viewed_at")
    private Instant viewedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SosOffer() {
        // JPA
    }

    /** Always starts {@link SosOfferStatus#OFFERED}. */
    public SosOffer(Long sosRequestId, Long professionalId, int matchRank, BigDecimal matchScore,
                     BigDecimal distanceKm, Integer estimatedArrivalMinutes, BigDecimal visitFee,
                     BigDecimal sosFee, BigDecimal platformCommission, Instant offeredAt, Instant expiresAt) {
        this.sosRequestId = sosRequestId;
        this.professionalId = professionalId;
        this.status = SosOfferStatus.OFFERED;
        this.matchRank = (short) matchRank;
        this.matchScore = matchScore;
        this.distanceKm = distanceKm;
        this.estimatedArrivalMinutes = estimatedArrivalMinutes == null ? null : estimatedArrivalMinutes.shortValue();
        this.visitFee = visitFee;
        this.sosFee = sosFee;
        this.platformCommission = platformCommission;
        this.offeredAt = offeredAt;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSosRequestId() {
        return sosRequestId;
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public SosOfferStatus getStatus() {
        return status;
    }

    public Short getMatchRank() {
        return matchRank;
    }

    public BigDecimal getMatchScore() {
        return matchScore;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public Short getEstimatedArrivalMinutes() {
        return estimatedArrivalMinutes;
    }

    public BigDecimal getVisitFee() {
        return visitFee;
    }

    public BigDecimal getSosFee() {
        return sosFee;
    }

    public BigDecimal getPlatformCommission() {
        return platformCommission;
    }

    /**
     * What the professional actually keeps: {@code visitFee + sosFee - platformCommission}.
     * Derived on read rather than stored — it is fully determined by three snapshotted columns
     * and a fourth column would be one more thing that could disagree with them.
     */
    public BigDecimal getProfessionalNet() {
        BigDecimal gross = (visitFee == null ? BigDecimal.ZERO : visitFee).add(sosFee);
        return gross.subtract(platformCommission);
    }

    public Instant getOfferedAt() {
        return offeredAt;
    }

    public Instant getViewedAt() {
        return viewedAt;
    }

    /** The immutable promise: what was committed at acceptance. See the field's Javadoc. */
    public Short getPromisedEtaMinutes() {
        return promisedEtaMinutes;
    }

    /** When the professional accepted — never set by a rejection or an expiry. */
    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
