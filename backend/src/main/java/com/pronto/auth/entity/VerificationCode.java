package com.pronto.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code verification_codes} table. {@code userId} is a plain FK
 * column (no {@code @ManyToOne}) — this package only ever needs the id, never the related
 * {@code User} object graph. Mapping matches the already-applied
 * {@code V3__create_verification_codes.sql} migration exactly. See
 * {@code docs/architecture/data-model.md} §2.3.
 */
@Entity
@Table(name = "verification_codes")
public class VerificationCode {

    /** Only value used in v1.0 — see {@code V3}'s {@code ck_verification_codes_purpose}. */
    public static final String PURPOSE_EMAIL_VERIFICATION = "EMAIL_VERIFICATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "purpose", nullable = false, length = 30)
    private String purpose;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected VerificationCode() {
        // JPA
    }

    public VerificationCode(Long userId, String code, Instant expiresAt) {
        this.userId = userId;
        this.code = code;
        this.purpose = PURPOSE_EMAIL_VERIFICATION;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCode() {
        return code;
    }

    public String getPurpose() {
        return purpose;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
