package com.pronto.locations.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Read-only reference entity for the fixed {@code service_regions} table seeded by
 * {@code V43__create_service_regions_and_cities.sql}. The application never inserts or updates
 * rows here — only migrations do, exactly like {@code professionals.entity.Category}.
 *
 * <p>That "only a migration writes it" property is what makes {@link #id} safe to persist on
 * {@code professionals.service_region_id}: the id of a region can never be reassigned by
 * anything a user does, so a stored id keeps meaning the same place forever, which is the whole
 * point of MS4 Part A replacing free-text service areas.
 */
@Entity
@Table(name = "service_regions")
public class ServiceRegion {

    @Id
    private Long id;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name_he", nullable = false, length = 100)
    private String nameHe;

    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ServiceRegion() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getNameHe() {
        return nameHe;
    }

    public String getNameEn() {
        return nameEn;
    }

    public short getDisplayOrder() {
        return displayOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
