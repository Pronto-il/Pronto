package com.pronto.professionals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Read-only reference entity for the {@code sub_services} table (34 seed rows across the 8
 * fixed categories, see {@code V29__create_sub_services.sql}) -- a child, one level down from
 * {@link Category}. The application never inserts/updates rows here -- only
 * {@code V29__create_sub_services.sql} does. See {@code
 * docs/architecture/product-ms11-sub-services-design.md} §2.1.
 *
 * <p>{@code categoryId} is a plain FK column (not a {@code @ManyToOne} association), matching
 * this codebase's universal convention for FK fields on entities in this package.
 */
@Entity
@Table(name = "sub_services")
public class SubService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

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

    protected SubService() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
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
