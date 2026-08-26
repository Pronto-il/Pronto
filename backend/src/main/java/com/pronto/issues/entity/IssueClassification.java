package com.pronto.issues.entity;

import com.pronto.ai.dto.CategoryCandidate;
import com.pronto.issues.entity.converter.CategoryCandidateListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What the AI independently concluded about an issue's routing — telemetry, not authority.
 *
 * <p>{@code issues.category_id} stays the single source of truth for who is dispatched (the
 * customer confirms or overrides it). This row records the AI's own answer next to it, so
 * "how often does the model disagree with the customer's final choice, and on which
 * categories" is answerable in production. Accuracy itself is measured properly by the
 * labelled evaluation harness; this is drift monitoring, not a substitute for it.
 *
 * <p>Written once per issue by the post-creation job, keyed by {@code issue_id} — there is
 * exactly one final classification per issue.
 */
@Entity
@Table(name = "issue_classifications")
public class IssueClassification {

    @Id
    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    /** The category the AI would have routed to. Nullable: the pass may have failed. */
    @Column(name = "ai_category_code", length = 50)
    private String aiCategoryCode;

    @Column(name = "ai_confidence", precision = 4, scale = 3)
    private BigDecimal aiConfidence;

    @Convert(converter = CategoryCandidateListConverter.class)
    @Column(name = "candidates", nullable = false, columnDefinition = "TEXT")
    private List<CategoryCandidate> candidates = List.of();

    @Column(name = "ambiguity_reason", columnDefinition = "TEXT")
    private String ambiguityReason;

    @Column(name = "clarification_rounds", nullable = false)
    private short clarificationRounds;

    /**
     * True when Pronto committed to this category while recording that it was not fully
     * confident. The category is still a genuine prediction.
     */
    @Column(name = "low_confidence", nullable = false)
    private boolean lowConfidence;

    /**
     * True when routing could not separate two materially different categories (or validated
     * nothing) and deliberately used the {@code general_handyman} fallback — so
     * {@link #aiCategoryCode} is not a prediction at all.
     *
     * <p>Kept distinct from {@link #lowConfidence} because collapsing them would make routing
     * accuracy unreadable: a system quietly diverting every hard case to the fallback would
     * look like it was improving. Always implies {@code lowConfidence}; the reverse does not
     * hold.
     */
    @Column(name = "unresolved", nullable = false)
    private boolean unresolved;

    /**
     * Which prompt and which model produced this row. Nullable — rows written before MS3
     * genuinely do not know, and the mock provider has no model name.
     *
     * <p>These are what make the drift signal interpretable across a change: a shift in how
     * often the AI disagrees with the customer means nothing unless it can be attributed to
     * routing behaviour rather than to the prompt or model having been swapped underneath it.
     */
    @Column(name = "prompt_version", length = 40)
    private String promptVersion;

    @Column(name = "model", length = 80)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IssueClassification() {
        // JPA
    }

    public IssueClassification(Long issueId, int clarificationRounds) {
        this.issueId = issueId;
        this.clarificationRounds = (short) clarificationRounds;
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
     * @param promptVersion {@code ClassificationPromptBuilder.PROMPT_VERSION}
     * @param model         the configured OpenAI model, or {@code null} under the mock provider
     */
    public void recordAiOutcome(String categoryCode, Double confidence, List<CategoryCandidate> candidates,
                                 String ambiguityReason, boolean lowConfidence, boolean unresolved,
                                 String promptVersion, String model) {
        this.promptVersion = promptVersion;
        this.model = model;
        this.aiCategoryCode = categoryCode;
        this.aiConfidence = confidence == null ? null : BigDecimal.valueOf(confidence)
                .setScale(3, java.math.RoundingMode.HALF_UP);
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
        this.ambiguityReason = ambiguityReason;
        this.lowConfidence = lowConfidence;
        this.unresolved = unresolved;
    }

    public Long getIssueId() {
        return issueId;
    }

    public String getAiCategoryCode() {
        return aiCategoryCode;
    }

    public BigDecimal getAiConfidence() {
        return aiConfidence;
    }

    public List<CategoryCandidate> getCandidates() {
        return candidates;
    }

    public String getAmbiguityReason() {
        return ambiguityReason;
    }

    public short getClarificationRounds() {
        return clarificationRounds;
    }

    public boolean isLowConfidence() {
        return lowConfidence;
    }

    public boolean isUnresolved() {
        return unresolved;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getModel() {
        return model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
