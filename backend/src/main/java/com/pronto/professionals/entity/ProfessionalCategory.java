package com.pronto.professionals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for {@code professional_categories} — the MS4 replacement for the single
 * {@code professionals.category_id} column, letting one professional serve Plumbing
 * <em>and</em> Handyman.
 *
 * <p>Structurally identical to {@link ProfessionalSubService} (composite key, plain FK columns,
 * {@code @IdClass}) because it is the same kind of thing: a pure many-to-many with no meaning
 * beyond the relationship. Mapping matches {@code V45__create_professional_categories.sql}
 * exactly ({@code ddl-auto: validate}).
 *
 * <p>Note what is <b>not</b> here: no "primary" flag. The brief's display guidance ("primary
 * category + additional count") is satisfied by ordering on the catalogue's own
 * {@code categories.display_order}, which every surface already has — a stored primary flag
 * would be a second thing to keep correct on every edit, and nothing in matching or SOS treats
 * one of a professional's categories differently from another.
 */
@Entity
@Table(name = "professional_categories")
@IdClass(ProfessionalCategoryId.class)
public class ProfessionalCategory {

    @Id
    @Column(name = "professional_id")
    private Long professionalId;

    @Id
    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProfessionalCategory() {
        // JPA
    }

    public ProfessionalCategory(Long professionalId, Long categoryId) {
        this.professionalId = professionalId;
        this.categoryId = categoryId;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
