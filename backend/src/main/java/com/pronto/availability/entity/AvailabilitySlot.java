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
 * JPA entity for the {@code availability_slots} table. {@code professionalId} is a plain FK
 * column (not a {@code @ManyToOne} association), matching the convention already used by
 * {@code issues.entity.Issue}/{@code professionals.entity.Professional}. Mapping matches the
 * already-applied {@code V5__create_availability_slots.sql} migration exactly
 * ({@code ddl-auto: validate}). See {@code docs/architecture/data-model.md} §2.5.
 *
 * <p>Minimal, read-focused entity per the Milestone 3 slice approved in
 * {@code docs/architecture/api-contract-bookings.md} §6 item 2 — only the fields needed for
 * §2.10/§2.11 (create + self-list) and for {@code bookings}' slot-claim/release mechanism
 * (§3.4 of that doc). No edit/delete/toggle behavior is modeled here.
 */
@Entity
@Table(name = "availability_slots")
public class AvailabilitySlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "professional_id", nullable = false)
    private Long professionalId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "is_available", nullable = false)
    private boolean isAvailable;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AvailabilitySlot() {
        // JPA
    }

    /** Always starts {@code isAvailable = true} — every newly created slot starts bookable. */
    public AvailabilitySlot(Long professionalId, Instant startTime, Instant endTime) {
        this.professionalId = professionalId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.isAvailable = true;
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

    public Long getProfessionalId() {
        return professionalId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
