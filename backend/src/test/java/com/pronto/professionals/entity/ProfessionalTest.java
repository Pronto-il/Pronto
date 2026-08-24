package com.pronto.professionals.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The MS1 approval state machine, at the level that owns it. {@link Professional} had no setter
 * for {@code approvalStatus} at all before this milestone — the column was immutable from row
 * creation — so these are the first tests of a transition path that did not previously exist.
 */
class ProfessionalTest {

    /** MS4: `professionals` no longer stores a category or free-text place -- see the entity. */
    private static final long SERVICE_REGION_ID = 4L;
    private static final long BASE_CITY_ID = 40L;

    private static final Instant REVIEWED_AT = Instant.parse("2026-08-22T10:15:30Z");
    private static final Long OPERATOR_ID = 7L;

    private Professional newProfessional() {
        return new Professional(1L, SERVICE_REGION_ID, BASE_CITY_ID, new BigDecimal("250.00"));
    }

    @Test
    void newProfessionalStartsPending_withNoReviewRecorded() {
        Professional professional = newProfessional();

        assertThat(professional.getApprovalStatus()).isEqualTo(Professional.STATUS_PENDING);
        assertThat(professional.getApprovalReviewedAt()).isNull();
        assertThat(professional.getApprovalReviewedBy()).isNull();
        assertThat(professional.getApprovalRejectionReason()).isNull();
    }

    @Test
    void approveFromPending_recordsWhoAndWhen() {
        Professional professional = newProfessional();

        professional.approve(OPERATOR_ID, REVIEWED_AT);

        assertThat(professional.getApprovalStatus()).isEqualTo(Professional.STATUS_APPROVED);
        assertThat(professional.getApprovalReviewedBy()).isEqualTo(OPERATOR_ID);
        assertThat(professional.getApprovalReviewedAt()).isEqualTo(REVIEWED_AT);
    }

    @Test
    void rejectFromPending_recordsWhoWhenAndWhy() {
        Professional professional = newProfessional();

        professional.reject(OPERATOR_ID, REVIEWED_AT, "Verification document is illegible.");

        assertThat(professional.getApprovalStatus()).isEqualTo(Professional.STATUS_REJECTED);
        assertThat(professional.getApprovalReviewedBy()).isEqualTo(OPERATOR_ID);
        assertThat(professional.getApprovalReviewedAt()).isEqualTo(REVIEWED_AT);
        assertThat(professional.getApprovalRejectionReason()).isEqualTo("Verification document is illegible.");
    }

    @Test
    void rejectedCanBeApprovedLater_andTheRejectionReasonIsCleared() {
        // A professional who fixed whatever was wrong and came back. The reason must not survive:
        // ck_professionals_rejection_reason forbids it on a non-REJECTED row, and an operator
        // reading a stale reason on an approved profile would be actively misled.
        Professional professional = newProfessional();
        professional.reject(OPERATOR_ID, REVIEWED_AT, "Missing licence number.");

        professional.approve(9L, REVIEWED_AT.plusSeconds(86_400));

        assertThat(professional.getApprovalStatus()).isEqualTo(Professional.STATUS_APPROVED);
        assertThat(professional.getApprovalRejectionReason()).isNull();
        assertThat(professional.getApprovalReviewedBy()).isEqualTo(9L);
    }

    @Test
    void approvingAnAlreadyApprovedProfessionalIsIllegal() {
        // The duplicate-decision case: an operator double-submitting must not silently re-stamp
        // the row with a new reviewer and timestamp.
        Professional professional = newProfessional();
        professional.approve(OPERATOR_ID, REVIEWED_AT);

        assertThat(professional.canApprove()).isFalse();
        assertThatThrownBy(() -> professional.approve(9L, REVIEWED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(professional.getApprovalReviewedBy()).isEqualTo(OPERATOR_ID);
    }

    @Test
    void rejectingAnApprovedProfessionalIsIllegal_thatIsSuspensionAndBelongsToMs7() {
        Professional professional = newProfessional();
        professional.approve(OPERATOR_ID, REVIEWED_AT);

        assertThat(professional.canReject()).isFalse();
        assertThatThrownBy(() -> professional.reject(OPERATOR_ID, REVIEWED_AT, "changed my mind"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(professional.getApprovalStatus()).isEqualTo(Professional.STATUS_APPROVED);
    }

    @Test
    void rejectingAnAlreadyRejectedProfessionalIsIllegal() {
        Professional professional = newProfessional();
        professional.reject(OPERATOR_ID, REVIEWED_AT, "Document expired.");

        assertThat(professional.canReject()).isFalse();
        assertThatThrownBy(() -> professional.reject(9L, REVIEWED_AT, "still expired"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(professional.getApprovalRejectionReason()).isEqualTo("Document expired.");
    }

    @Test
    void nothingInThisEntityCanProduceDisabled() {
        // V40 reserves the value for MS7; MS1 must not be able to reach it through any path.
        Professional professional = newProfessional();
        professional.approve(OPERATOR_ID, REVIEWED_AT);
        assertThat(professional.getApprovalStatus()).isNotEqualTo(Professional.STATUS_DISABLED);

        Professional rejected = newProfessional();
        rejected.reject(OPERATOR_ID, REVIEWED_AT, "no");
        assertThat(rejected.getApprovalStatus()).isNotEqualTo(Professional.STATUS_DISABLED);
    }
}
