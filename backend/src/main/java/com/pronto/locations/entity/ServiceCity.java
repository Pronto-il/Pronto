package com.pronto.locations.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Read-only reference entity for the fixed {@code service_cities} table seeded by
 * {@code V43__create_service_regions_and_cities.sql}. Same "migrations are the only writer"
 * contract as {@link ServiceRegion}.
 *
 * <p>{@link #regionId} is the whole of the region → city filtering rule (MS4 Part A §3): the
 * cities offered for a region are the rows carrying its id, resolved by one indexed query in
 * {@code locations.repository.ServiceCityRepository} rather than by a region→city map written
 * into a form component.
 */
@Entity
@Table(name = "service_cities")
public class ServiceCity {

    @Id
    private Long id;

    @Column(name = "region_id", nullable = false)
    private Long regionId;

    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "name_he", nullable = false, length = 100)
    private String nameHe;

    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ServiceCity() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public Long getRegionId() {
        return regionId;
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
