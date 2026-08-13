package com.pronto.issues.entity;

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

import java.time.Instant;

/**
 * JPA entity for the {@code issues} table. {@code customerId}/{@code categoryId} are plain
 * FK columns (not {@code @ManyToOne} associations), matching the convention already used by
 * {@code professionals.entity.Professional}/{@code auth.entity.VerificationCode} — this
 * package never needs to navigate the related {@code User}/{@code Category} object graph,
 * just the id. Mapping matches the already-applied {@code V6__create_issues.sql} migration
 * exactly ({@code ddl-auto: validate}). See {@code docs/architecture/data-model.md} §2.6.
 */
@Entity
@Table(name = "issues")
public class Issue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency_type", nullable = false, length = 20)
    private IssueUrgencyType urgencyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IssueStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Issue() {
        // JPA
    }

    /** Always starts {@code OPEN} — see {@link IssueStatus}'s Javadoc. */
    public Issue(Long customerId, Long categoryId, String description, IssueUrgencyType urgencyType) {
        this.customerId = customerId;
        this.categoryId = categoryId;
        this.description = description;
        this.urgencyType = urgencyType;
        this.status = IssueStatus.OPEN;
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

    public Long getCustomerId() {
        return customerId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getDescription() {
        return description;
    }

    public IssueUrgencyType getUrgencyType() {
        return urgencyType;
    }

    public IssueStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
