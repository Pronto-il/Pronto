package com.pronto.favorites.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code favorites} table — a genuinely composite-key row, no surrogate
 * {@code id}. See {@link FavoriteId} for why {@code @IdClass} (not {@code @EmbeddedId}) was
 * chosen. {@code customerId}/{@code professionalId} are plain FK columns (not
 * {@code @ManyToOne} associations), matching this codebase's universal convention. Mapping
 * matches {@code V17__create_favorites.sql} exactly ({@code ddl-auto: validate}).
 */
@Entity
@Table(name = "favorites")
@IdClass(FavoriteId.class)
public class Favorite {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Id
    @Column(name = "professional_id")
    private Long professionalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Favorite() {
        // JPA
    }

    public Favorite(Long customerId, Long professionalId) {
        this.customerId = customerId;
        this.professionalId = professionalId;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
