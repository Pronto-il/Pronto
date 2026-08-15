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
 * <p>Does not model {@code sos_availability} — that's a separate 1:1 table owned by the
 * {@code availability} package ({@code availability.entity.SosAvailability}), not this
 * entity, matching the decided design in {@code docs/architecture/data-model.md} §2.6.
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

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "profile_image_key", length = 500)
    private String profileImageKey;

    /**
     * Object-storage key for the required verification document uploaded at
     * registration (backend registration flow separation task §12) — never the raw
     * document bytes, same pattern as {@link #profileImageKey}. Populated by
     * {@code auth.service.AuthService#register} as part of the same registration
     * transaction, immediately after this row is first saved (needs {@link #id} for the
     * upload key).
     */
    @Column(name = "verification_document_key", length = 500)
    private String verificationDocumentKey;

    @Column(name = "city", length = 100)
    private String city;

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

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getProfileImageKey() {
        return profileImageKey;
    }

    public void setProfileImageKey(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }

    public String getVerificationDocumentKey() {
        return verificationDocumentKey;
    }

    public void setVerificationDocumentKey(String verificationDocumentKey) {
        this.verificationDocumentKey = verificationDocumentKey;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
