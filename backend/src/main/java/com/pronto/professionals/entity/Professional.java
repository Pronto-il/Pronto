package com.pronto.professionals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for the {@code professionals} table — a 1:1 extension of a {@code users} row
 * with {@code role = PROFESSIONAL}. {@code userId}/{@code categoryId} are stored as plain
 * FK columns rather than {@code @ManyToOne}/{@code @OneToOne} associations, deliberately —
 * this package doesn't need to navigate/lazy-load the related {@code User} object graph,
 * just the id. Mapping matches the already-applied {@code V4__create_professionals.sql}
 * migration exactly. See {@code docs/architecture/data-model.md} §2.4.
 *
 * <p>Does not model {@code sos_availability} — that table doesn't exist yet (known,
 * flagged gap; see {@code docs/architecture/api-contract.md} §4).
 */
@Entity
@Table(name = "professionals")
public class Professional {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "service_area", nullable = false, length = 150)
    private String serviceArea;

    @Column(name = "approval_status", nullable = false, length = 20)
    private String approvalStatus;

    @Column(name = "reliability_score", precision = 3, scale = 2)
    private BigDecimal reliabilityScore;

    @Column(name = "base_price", precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Professional() {
        // JPA
    }

    /**
     * {@code approvalStatus} always starts {@code 'APPROVED'} — v1.0 has no approval
     * workflow (per {@code docs/architecture/overview.md}), the column is kept but
     * functionally inert.
     */
    public Professional(Long userId, Long categoryId, String serviceArea, BigDecimal basePrice) {
        this.userId = userId;
        this.categoryId = categoryId;
        this.serviceArea = serviceArea;
        this.basePrice = basePrice;
        this.approvalStatus = "APPROVED";
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

    public Long getUserId() {
        return userId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getServiceArea() {
        return serviceArea;
    }

    public void setServiceArea(String serviceArea) {
        this.serviceArea = serviceArea;
    }

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public BigDecimal getReliabilityScore() {
        return reliabilityScore;
    }

    public void setReliabilityScore(BigDecimal reliabilityScore) {
        this.reliabilityScore = reliabilityScore;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
