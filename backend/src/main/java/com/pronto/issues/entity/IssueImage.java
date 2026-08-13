package com.pronto.issues.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code issue_images} table. {@code issueId} is a plain FK column, not
 * a {@code @ManyToOne} association — see {@code Issue}'s Javadoc for the same rationale.
 * Mapping matches the already-applied {@code V7__create_issue_images.sql} migration
 * exactly. See {@code docs/architecture/data-model.md} §2.7.
 *
 * <p>{@code imageUrl} is the URL {@code storage.StorageClient.resolveUrl} returns for the
 * same key the object was originally uploaded to — the underlying object is never
 * moved/renamed on issue confirmation (§2.2 step 6 / §4).
 */
@Entity
@Table(name = "issue_images")
public class IssueImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected IssueImage() {
        // JPA
    }

    public IssueImage(Long issueId, String imageUrl) {
        this.issueId = issueId;
        this.imageUrl = imageUrl;
    }

    @PrePersist
    protected void onCreate() {
        this.uploadedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getIssueId() {
        return issueId;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
