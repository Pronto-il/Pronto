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
import java.time.LocalTime;

/**
 * JPA entity for the {@code professional_working_hours} table. {@code professionalId} is a
 * plain FK column (not a {@code @ManyToOne} association), matching the convention already
 * used by {@link AvailabilitySlot}/{@link SosAvailability}. Mapping matches
 * {@code V25__create_professional_working_hours.sql} exactly ({@code ddl-auto: validate}).
 * See {@code docs/architecture/professional-weekly-calendar-design.md} §2.1/§3.
 *
 * <p>One row per professional per weekday ({@code 0 = Sunday ... 6 = Saturday}), enforced by
 * the table's own {@code UNIQUE(professional_id, weekday)} constraint. {@code startTime}/
 * {@code endTime} are wall-clock local times in the app's fixed business timezone
 * ({@link com.pronto.availability.service.AvailabilityDerivationService#BUSINESS_TIMEZONE}),
 * not points in time -- this is a recurring weekly rule, hence {@code LocalTime}, not
 * {@code Instant}.
 *
 * <p>No setters for individual fields -- {@link #update} is the sole mutation path, used by
 * {@code AvailabilityService#updateWorkingHours}'s update-in-place upsert loop (design §3/
 * §4.2). Unlike {@link AvailabilitySlot}'s atomic guarded {@code UPDATE ... WHERE
 * <state-guard>} pattern, no concurrency guard is needed here: a professional's own
 * working-hours write has no "wrong state to update from" concept, the exact same
 * single-writer reasoning {@link SosAvailability}'s plain unconditional toggle already
 * relies on.
 */
@Entity
@Table(name = "professional_working_hours")
public class ProfessionalWorkingHours {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "professional_id", nullable = false)
    private Long professionalId;

    @Column(name = "weekday", nullable = false)
    private short weekday;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfessionalWorkingHours() {
        // JPA
    }

    /**
     * {@code startTime}/{@code endTime} must be {@code null} when {@code enabled = false}.
     * {@code weekday} is accepted as {@code int} (matching the design doc's own type and this
     * class's callers, which all work with plain {@code int}s from DTOs/JSON) and narrowed to
     * {@code short} for storage -- the {@code weekday} column is {@code SMALLINT} (design
     * §2.1), and this codebase's established convention for a {@code SMALLINT} column is a
     * narrow Java numeric type ({@code reviews.entity.Review#rating} uses {@code Short} for
     * the same reason), not {@code int}/{@code INTEGER} (Hibernate's {@code ddl-auto: validate}
     * rejects the {@code int}/{@code INTEGER} mapping against an actual {@code SMALLINT}
     * column at startup).
     */
    public ProfessionalWorkingHours(Long professionalId, int weekday, boolean enabled,
                                     LocalTime startTime, LocalTime endTime) {
        this.professionalId = professionalId;
        this.weekday = (short) weekday;
        this.enabled = enabled;
        this.startTime = startTime;
        this.endTime = endTime;
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
    public void update(boolean enabled, LocalTime startTime, LocalTime endTime) {
        this.enabled = enabled;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public Long getId() {
        return id;
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public short getWeekday() {
        return weekday;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
