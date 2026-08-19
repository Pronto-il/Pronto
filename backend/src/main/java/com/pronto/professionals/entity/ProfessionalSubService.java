package com.pronto.professionals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProfessionalSubService() {
        // JPA
    }

    public ProfessionalSubService(Long professionalId, Long subServiceId) {
        this.professionalId = professionalId;
        this.subServiceId = subServiceId;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public Long getSubServiceId() {
        return subServiceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
