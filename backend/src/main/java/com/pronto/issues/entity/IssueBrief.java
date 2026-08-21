package com.pronto.issues.entity;

import com.pronto.ai.dto.LikelyIssue;
import com.pronto.ai.dto.ProfessionalBriefResponse;
import com.pronto.issues.entity.converter.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Pronto's stored preparation brief for the professional.
 *
 * <p>Held in its own table, never merged into {@code issues}: this is Pronto's <b>analysis</b>,
 * and the customer's own report ({@code issues.description}) must remain untouched and
 * separately identifiable at every layer — storage, API and UI. Nothing here ever overwrites
 * what the customer wrote.
 *
 * <p>One row per issue, created {@link IssueBriefStatus#PENDING} at issue creation and
 * completed asynchronously.
 */
@Entity
@Table(name = "issue_briefs")
public class IssueBrief {

    @Id
    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IssueBriefStatus status;

    @Column(name = "customer_problem_summary", columnDefinition = "TEXT")
    private String customerProblemSummary;

    @Column(name = "clarification_summary", columnDefinition = "TEXT")
    private String clarificationSummary;

    @Convert(converter = StringListConverter.class)
    @Column(name = "image_observations", nullable = false, columnDefinition = "TEXT")
    private List<String> imageObservations = List.of();

    @Column(name = "likely_issue_description", columnDefinition = "TEXT")
    private String likelyIssueDescription;

    @Column(name = "likely_issue_confidence", precision = 4, scale = 3)
    private BigDecimal likelyIssueConfidence;

    @Convert(converter = StringListConverter.class)
    @Column(name = "likely_issue_evidence", nullable = false, columnDefinition = "TEXT")
    private List<String> likelyIssueEvidence = List.of();

    @Convert(converter = StringListConverter.class)
    @Column(name = "possible_causes", nullable = false, columnDefinition = "TEXT")
    private List<String> possibleCauses = List.of();

    @Convert(converter = StringListConverter.class)
    @Column(name = "recommended_tools", nullable = false, columnDefinition = "TEXT")
    private List<String> recommendedTools = List.of();

    @Convert(converter = StringListConverter.class)
    @Column(name = "recommended_parts", nullable = false, columnDefinition = "TEXT")
    private List<String> recommendedParts = List.of();

    @Convert(converter = StringListConverter.class)
    @Column(name = "safety_notes", nullable = false, columnDefinition = "TEXT")
    private List<String> safetyNotes = List.of();

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IssueBrief() {
        // JPA
    }

    /** A brief always starts {@code PENDING} — the row is created with the issue itself. */
    public IssueBrief(Long issueId) {
        this.issueId = issueId;
        this.status = IssueBriefStatus.PENDING;
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

    /**
     * Copies a generated brief in and flips the row to {@link IssueBriefStatus#READY}. A
     * hypothesis that was stripped during validation (no supporting evidence) arrives here as
     * {@code null} and is stored as {@code null} — an unexplained guess is not persisted.
     */
    public void markReady(ProfessionalBriefResponse brief) {
        this.customerProblemSummary = brief.customerProblemSummary();
        this.clarificationSummary = brief.clarificationSummary();
        this.imageObservations = brief.imageObservations();
        this.possibleCauses = brief.possibleCauses();
        this.recommendedTools = brief.recommendedTools();
        this.recommendedParts = brief.recommendedParts();
        this.safetyNotes = brief.safetyNotes();

        LikelyIssue likelyIssue = brief.likelyIssue();
        if (likelyIssue == null) {
            this.likelyIssueDescription = null;
            this.likelyIssueConfidence = null;
            this.likelyIssueEvidence = List.of();
        } else {
            this.likelyIssueDescription = likelyIssue.description();
            this.likelyIssueConfidence =
                    BigDecimal.valueOf(likelyIssue.confidence()).setScale(3, RoundingMode.HALF_UP);
            this.likelyIssueEvidence = likelyIssue.evidence();
        }

        this.status = IssueBriefStatus.READY;
        this.generatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = IssueBriefStatus.FAILED;
    }

    public Long getIssueId() {
        return issueId;
    }

    public IssueBriefStatus getStatus() {
        return status;
    }

    public String getCustomerProblemSummary() {
        return customerProblemSummary;
    }

    public String getClarificationSummary() {
        return clarificationSummary;
    }

    public List<String> getImageObservations() {
        return imageObservations;
    }

    public String getLikelyIssueDescription() {
        return likelyIssueDescription;
    }

    public BigDecimal getLikelyIssueConfidence() {
        return likelyIssueConfidence;
    }

    public List<String> getLikelyIssueEvidence() {
        return likelyIssueEvidence;
    }

    public List<String> getPossibleCauses() {
        return possibleCauses;
    }

    public List<String> getRecommendedTools() {
        return recommendedTools;
    }

    public List<String> getRecommendedParts() {
        return recommendedParts;
    }

    public List<String> getSafetyNotes() {
        return safetyNotes;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
