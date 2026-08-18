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
 * Mapping matches the {@code V7__create_issue_images.sql} migration plus
 * {@code V24__rename_issue_images_image_url_to_image_key.sql} (backend MS9). See
 * {@code docs/architecture/data-model.md} §2.7.
 *
 * <p>{@code imageKey} is the raw storage key the image was originally uploaded to — never a
 * resolved URL. Resolved to a presigned URL only at read time (never persisted resolved), by
 * {@code storage.service.StorageService#getPresignedUrl}/
 * {@code #getPresignedUrlAssumingCallerAuthorized}, called from
 * {@code issues.service.IssuesService#create}/{@code #getById} respectively. This column used
 * to store a resolved, non-expiring proxy URL directly — reversed in backend MS9
 * ({@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §9.4.1) once presigned
 * URLs became time-limited (a URL saved at creation time would be long expired by the time a
 * later {@code getById} read it back verbatim). Now matches
 * {@code professionals.profile_image_key}'s existing key-not-URL pattern. The underlying
 * storage object is never moved/renamed on issue confirmation (§2.2 step 6 / §4).
 */
@Entity
@Table(name = "issue_images")
public class IssueImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    @Column(name = "image_key", nullable = false, length = 500)
    private String imageKey;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected IssueImage() {
        // JPA
    }

    public IssueImage(Long issueId, String imageKey) {
        this.issueId = issueId;
        this.imageKey = imageKey;
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

    public String getImageKey() {
        return imageKey;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
