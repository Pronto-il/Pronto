package com.pronto.availability.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code professional_availability_blocks} table. {@code professionalId}
 * is a plain FK column, matching the convention already used by
 * {@link AvailabilitySlot}/{@link SosAvailability}/{@link ProfessionalWorkingHours}. Mapping
 * matches {@code V26__create_professional_availability_blocks.sql} exactly
 * ({@code ddl-auto: validate}). See
 * {@code docs/architecture/professional-weekly-calendar-design.md} §2.2/§3.
 *
 * <p>A manual, temporary exception (personal appointment, lunch, vacation, etc.) -- never
 * auto-generated, never represents a booking. {@code startAt}/{@code endAt} are real points
 * in time ({@code Instant}), unlike {@link ProfessionalWorkingHours}' wall-clock
 * {@code LocalTime} fields -- a block is a one-off dated exception, not a recurring rule.
 *
 * <p>The table's {@code ck_blocks_no_overlap} exclusion constraint (requires
 * {@code btree_gist}) is the DB-level authoritative guard against two of the same
 * professional's own blocks overlapping -- see {@code AvailabilityService#createBlock}/
 * {@code #updateBlock} for how a Postgres {@code 23P01} (exclusion-violation) SQLState on
 * insert/update is caught and mapped to {@code 409 BLOCK_OVERLAPS_EXISTING_BLOCK}. No setter
 * per individual field -- {@link #update} is the sole full-replace mutation path (mirrors
 * {@link ProfessionalWorkingHours#update}'s reasoning: no concurrency-guard concept applies
 * to a professional editing their own block, the exclusion constraint above is what actually
 * guards correctness, not an atomic-affected-row-count pattern).
 */
@Entity
@Table(name = "professional_availability_blocks")
public class ProfessionalAvailabilityBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "professional_id", nullable = false)
    private Long professionalId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfessionalAvailabilityBlock() {
        // JPA
    }

    public ProfessionalAvailabilityBlock(Long professionalId, Instant startAt, Instant endAt, String reason) {
        this.professionalId = professionalId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.reason = reason;
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

    /** Full replace of the mutable fields -- see class Javadoc for why no per-field setter exists. */
    public void update(Instant startAt, Instant endAt, String reason) {
        this.startAt = startAt;
        this.endAt = endAt;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
