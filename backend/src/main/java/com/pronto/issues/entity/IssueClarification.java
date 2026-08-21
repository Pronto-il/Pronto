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
 * One clarification question and the customer's answer, in the order they were asked.
 *
 * <p>Kept because it is the highest-signal context the whole flow produces and it used to be
 * thrown away: it is replayed into the Professional Brief prompt, shown to the professional
 * alongside the customer's description, and is what makes "how many rounds did this issue
 * take" answerable.
 *
 * <p>Plain FK column rather than a {@code @ManyToOne} association, matching this package's
 * existing convention ({@link Issue}, {@link IssueImage}). Immutable once written — a
 * conversation that already happened is not edited.
 */
@Entity
@Table(name = "issue_clarifications")
public class IssueClarification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    /** Zero-based order of the question within this issue's conversation. */
    @Column(name = "position", nullable = false)
    private short position;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "answer", nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IssueClarification() {
        // JPA
    }

    public IssueClarification(Long issueId, int position, String question, String answer) {
        this.issueId = issueId;
        this.position = (short) position;
        this.question = question;
        this.answer = answer;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getIssueId() {
        return issueId;
    }

    public short getPosition() {
        return position;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
