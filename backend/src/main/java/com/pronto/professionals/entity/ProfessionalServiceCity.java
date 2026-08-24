package com.pronto.professionals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for {@code professional_service_cities} — the cities a professional is willing to
 * travel to, as canonical {@code service_cities} ids rather than the free text
 * {@code professionals.city} used to hold.
 *
 * <p>Same composite-key/plain-FK shape as {@link ProfessionalSubService} and
 * {@link ProfessionalCategory}; mapping matches
 * {@code V44__alter_professionals_service_coverage.sql} exactly.
 *
 * <p>Distinct from {@code Professional#baseCityId}, which is the one city the professional
 * operates out of and the one {@code matching.ApproximateDistanceEtaStrategy} measures travel
 * from. The base city is always a member of this set — enforced by
 * {@code locations.service.ServiceCoverageValidator} on every write path.
 */
@Entity
@Table(name = "professional_service_cities")
@IdClass(ProfessionalServiceCityId.class)
public class ProfessionalServiceCity {

    @Id
    @Column(name = "professional_id")
    private Long professionalId;

    @Id
    @Column(name = "city_id")
    private Long cityId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProfessionalServiceCity() {
        // JPA
    }

    public ProfessionalServiceCity(Long professionalId, Long cityId) {
        this.professionalId = professionalId;
        this.cityId = cityId;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public Long getCityId() {
        return cityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
