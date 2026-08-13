package com.pronto.professionals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Read-only reference entity for the fixed 8-row {@code categories} table (see
 * {@code docs/architecture/data-model.md} §2.1). The application never inserts/updates
 * rows here — only {@code V10__seed_categories.sql} does.
 *
 * <p><b>Placement note (flagged, not a hard decision):</b> mapped inside {@code
 * professionals} rather than a dedicated {@code categories} package (none exists yet)
 * purely so professional registration ({@code POST /api/auth/register}) can validate
 * {@code categoryId} against a real FK target. Milestone 2's {@code issues} package will
 * also need {@code category_id} — if a shared home for this table is warranted at that
 * point, this class should move there rather than being duplicated. Raised to
 * {@code pronto-lead} as a judgment call worth revisiting, not raised as a blocker.
 */
@Entity
@Table(name = "categories")
public class Category {

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

    protected Category() {
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
