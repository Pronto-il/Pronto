package com.pronto.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One issued one-time password — an OTP <em>challenge</em>, addressed by an opaque
 * {@link #challengeId} rather than by the address it was sent to.
 *
 * <p>{@code userId} stays a plain FK column (no {@code @ManyToOne}): this package only ever needs
 * the id. Mapping matches {@code V3} as amended by {@code V47}. See
 * {@code docs/architecture/data-model.md} §2.3.
 *
 * <p><b>The plaintext code is not here, and is nowhere else either.</b> Only
 * {@link #codeHash} — SHA-256 hex — is persisted. The six digits exist in memory for as long as it
 * takes to hand them to the Email/SMS provider and are then dropped; they are never written to a
 * column, never returned in a response body, and never logged outside a {@code local} environment.
 * A database disclosure therefore yields no usable credential.
 *
 * <p><b>Mutation happens in SQL, not here.</b> {@link #attempts} and {@link #consumedAt} both have
 * getters and no setters, on purpose: both are the subject of a race that a read-modify-write
 * through JPA loses. Two concurrent guesses that each load this entity, each see
 * {@code attempts = 4}, and each write {@code 5} have collectively spent one attempt against a cap
 * of five. They are advanced instead by the two conditional UPDATE statements on
 * {@code VerificationCodeRepository}, where the row lock and the WHERE clause make the cap and the
 * single-use rule hold under concurrency.
 */
@Entity
@Table(name = "verification_codes")
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 hex of the dispatched code. 64 characters, always. */
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    /** Opaque public handle. Unique ({@code ux_verification_codes_challenge}). */
    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private OtpPurpose purpose;

    @Column(name = "attempts", nullable = false)
    private short attempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * When the provider accepted this code ({@code V54}). {@code NULL} means it was never sent —
     * the dispatch failed and {@code OtpChallengeWriter#abandon} killed the challenge.
     *
     * <p>Both OTP rate rules read this rather than {@link #createdAt}, which is written before the
     * provider call and therefore counts attempts rather than messages. Charging a customer's 60s
     * cooldown and their hourly ceiling for a send that reached nobody is what turned a transient
     * provider failure into an account that could not verify its phone number at all.
     *
     * <p>No setter, for the reason the class Javadoc gives: it is advanced by a conditional UPDATE.
     */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected VerificationCode() {
        // JPA
    }

    /**
     * {@code challengeId} is supplied rather than generated here, because the keyed hash in
     * {@code codeHash} is computed <em>over</em> it ({@code auth.service.OtpPepper}) — so the id has
     * to exist before the hash does. It stays {@code updatable = false}: minted once, never changed.
     */
    public VerificationCode(Long userId, OtpPurpose purpose, UUID challengeId, String codeHash,
                             Instant expiresAt) {
        this.userId = userId;
        this.purpose = purpose;
        this.challengeId = challengeId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.attempts = 0;
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

    public String getCodeHash() {
        return codeHash;
    }

    public UUID getChallengeId() {
        return challengeId;
    }

    public OtpPurpose getPurpose() {
        return purpose;
    }

    public short getAttempts() {
        return attempts;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    /** True while this challenge is still redeemable: never consumed and not yet expired. */
    public boolean isActiveAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }
}
