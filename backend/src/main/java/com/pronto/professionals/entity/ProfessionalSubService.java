package com.pronto.professionals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for the {@code professional_sub_services} table -- a genuinely composite-key
 * row, no surrogate {@code id}. Mirrors {@code favorites.entity.Favorite}'s exact existing
 * pattern (see {@link ProfessionalSubServiceId} for why {@code @IdClass}, not {@code
 * @EmbeddedId}). {@code professionalId}/{@code subServiceId} are plain FK columns (not
 * {@code @ManyToOne} associations), matching this codebase's universal convention. Mapping
 * matches {@code V30__create_professional_sub_services.sql} exactly ({@code ddl-auto:
 * validate}). See {@code docs/architecture/product-ms11-sub-services-design.md} §2.2.
 */
@Entity
@Table(name = "professional_sub_services")
@IdClass(ProfessionalSubServiceId.class)
public class ProfessionalSubService {

    @Id
    @Column(name = "professional_id")
    private Long professionalId;

    @Id
    @Column(name = "sub_service_id")
    private Long subServiceId;

    /**
     * What this professional charges for <em>this</em> sub-service, or {@code null} for "they have
     * not said".
     *
     * <p><b>Nullable is a real state, not a migration artefact.</b> Every row that existed before
     * {@code V57} has no price, and pricing stays optional at the API afterwards — see that
     * migration's header for why {@code professionals.base_price} was not backfilled into it. A
     * {@code null} here must be rendered as an absence and never as {@code 0}: the second would
     * advertise a free visit the professional never offered.
     *
     * <p>Distinct from, and never mixed with, the SOS surcharge
     * ({@code pronto.sos.visit-surcharge}). That is the platform's fee for urgency and is added on
     * top at dispatch; this is the professional's own price for the work.
     */
    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Bumped on every price edit. Deliberately separate from {@link #createdAt}, which records when
     * the professional first said they offer this service — repricing is not re-selecting, and
     * collapsing the two would erase the distinction the update-semantics rely on (see
     * {@code ProfessionalsService#updateMySubServices}, which preserves {@code createdAt} across an
     * edit precisely so a long-standing service does not look newly added).
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfessionalSubService() {
        // JPA
    }

    /** Selection with no price stated — the pre-{@code V57} shape, still a legal one. */
    public ProfessionalSubService(Long professionalId, Long subServiceId) {
        this(professionalId, subServiceId, null);
    }

    public ProfessionalSubService(Long professionalId, Long subServiceId, BigDecimal price) {
        this.professionalId = professionalId;
        this.subServiceId = subServiceId;
        this.price = price;
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

    /**
     * Repricing an already-selected sub-service. The only mutator on this entity: the two id
     * columns are the primary key and changing either would be a different row, not an edit.
     *
     * <p>{@code null} is accepted and means "withdraw the price", which is a state a professional
     * may legitimately want to return to. Validation of the value itself (non-negative, at most two
     * decimals) belongs to the caller — {@code ProfessionalsService} reports it as a field error —
     * with {@code ck_professional_sub_services_price} as the backstop for every other writer.
     */
    public void reprice(BigDecimal price) {
        this.price = price;
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public Long getSubServiceId() {
        return subServiceId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
