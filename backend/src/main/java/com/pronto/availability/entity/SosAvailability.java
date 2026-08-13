package com.pronto.availability.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code sos_availability} table (see
 * {@code docs/architecture/data-model.md} §2.6). {@code professionalId} is the PK itself, not
 * a surrogate {@code id} — matches the migration exactly ({@code ddl-auto: validate}), since
 * this is a 1-row-per-professional live-status table, not an append-only one.
 *
 * <p>Row lifecycle: one row is expected to exist per professional from the moment their
 * profile is created (see {@code auth.service.AuthService#register}), defaulting to
 * {@code isAvailable = false}, so any future SOS-matching query can do a plain join with no
 * NULL-handling for professionals who have never toggled it.
 */
@Entity
@Table(name = "sos_availability")
public class SosAvailability {

    @Id
    @Column(name = "professional_id")
    private Long professionalId;

    @Column(name = "is_available", nullable = false)
    private boolean isAvailable;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SosAvailability() {
        // JPA
    }

    /** Always starts {@code isAvailable = false} — matches the column default. */
    public SosAvailability(Long professionalId) {
        this.professionalId = professionalId;
        this.isAvailable = false;
    }

    @PrePersist
    protected void onCreate() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
