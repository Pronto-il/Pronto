package com.pronto.professionals.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for the {@code professionals} table — a 1:1 extension of a {@code users} row
 * with {@code role = PROFESSIONAL}. {@code userId}/{@code categoryId} are stored as plain
 * FK columns rather than {@code @ManyToOne}/{@code @OneToOne} associations, deliberately —
 * this package doesn't need to navigate/lazy-load the related {@code User} object graph,
 * just the id. Mapping matches the already-applied {@code V4__create_professionals.sql}
 * migration exactly. See {@code docs/architecture/data-model.md} §2.4.
 *
 * <p>Does not model {@code sos_availability} — that's a separate 1:1 table owned by the
 * {@code availability} package ({@code availability.entity.SosAvailability}), not this
 * entity, matching the decided design in {@code docs/architecture/data-model.md} §2.6.
 *
 * <p><b>MS1 — approval lifecycle.</b> {@link #approvalStatus} used to be set once, to
 * {@code APPROVED}, and never changed by anything: there was no setter and no update path
 * anywhere in the backend. It is now a real state machine
 * ({@code PENDING -> APPROVED | REJECTED}, {@code REJECTED -> APPROVED}) driven by
 * {@link #approve}/{@link #reject}, whose transition rules live here rather than in the service
 * so no future caller can invent an illegal one. {@link #STATUS_DISABLED} is reserved for MS7 and
 * cannot be reached from this class. Note that {@code APPROVED} is <b>not</b> the same thing as
 * bookable — see {@link com.pronto.professionals.ProfessionalEligibility}.
 */
@Entity
@Table(name = "professionals")
public class Professional {

    /** Registered, onboarding submitted, awaiting an operator decision. The starting state. */
    public static final String STATUS_PENDING = "PENDING";

    /**
     * An operator has approved this professional. <b>Not, on its own, "bookable"</b> —
     * marketplace eligibility is this status <em>and</em> completed onboarding, evaluated per
     * query by {@link com.pronto.professionals.ProfessionalEligibility}. Approving someone whose
     * onboarding is incomplete leaves them non-bookable; the system does not, and must not,
     * invent the missing data.
     */
    public static final String STATUS_APPROVED = "APPROVED";

    /** An operator refused this professional. Reversible — a later {@link #approve} is legal. */
    public static final String STATUS_REJECTED = "REJECTED";

    /**
     * <b>Reserved for MS7's suspend capability. Unreachable in MS1.</b> {@code V40} adds it to
     * {@code ck_professionals_approval_status} so MS7 does not need a second lifecycle migration
     * against a live column, but nothing here can write it: {@link #approve} and {@link #reject}
     * are the only writers of {@link #approvalStatus} and neither targets it, and there is no
     * suspend endpoint. Because eligibility is a positive test against {@link #STATUS_APPROVED},
     * this value is already ineligible everywhere the moment MS7 makes it reachable.
     */
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "service_area", nullable = false, length = 150)
    private String serviceArea;

    @Column(name = "approval_status", nullable = false, length = 20)
    private String approvalStatus;

    @Column(name = "reliability_score", precision = 3, scale = 2)
    private BigDecimal reliabilityScore;

    @Column(name = "base_price", precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "profile_image_key", length = 500)
    private String profileImageKey;

    /**
     * Object-storage key for the required verification document uploaded at
     * registration (backend registration flow separation task §12) — never the raw
     * document bytes, same pattern as {@link #profileImageKey}. Populated by
     * {@code auth.service.AuthService#register} as part of the same registration
     * transaction, immediately after this row is first saved (needs {@link #id} for the
     * upload key).
     */
    @Column(name = "verification_document_key", length = 500)
    private String verificationDocumentKey;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * When an operator last decided this professional's approval, and who they were. Both
     * {@code null} until a first review happens — including for every row that predates
     * {@code V40}, which is deliberate: nobody reviewed those, and naming a reviewer for them
     * would fabricate the very record this trail exists to make trustworthy.
     */
    @Column(name = "approval_reviewed_at")
    private Instant approvalReviewedAt;

    @Column(name = "approval_reviewed_by")
    private Long approvalReviewedBy;

    /**
     * Why the professional was rejected. Constrained by {@code ck_professionals_rejection_reason}
     * to be non-{@code null} only while {@link #approvalStatus} is {@link #STATUS_REJECTED} —
     * {@link #approve} clears it, so an operator can never read a superseded rejection as though
     * it were current.
     */
    @Column(name = "approval_rejection_reason", length = 500)
    private String approvalRejectionReason;

    protected Professional() {
        // JPA
    }

    /**
     * A new professional always starts {@link #STATUS_PENDING} — MS1 replaces v1.0's
     * auto-approval outright (Playbook MS1 / D7). Registration is the only caller, and there is
     * no client-supplied field anywhere in {@code RegisterRequest} that can influence this.
     *
     * <p>{@code PENDING} is not a limbo the registrant is stuck in: they can log in, edit their
     * profile, their sub-services and their working hours, and toggle SOS availability. What they
     * cannot do until an operator approves them is appear to customers or receive work.
     */
    public Professional(Long userId, Long categoryId, String serviceArea, BigDecimal basePrice) {
        this.userId = userId;
        this.categoryId = categoryId;
        this.serviceArea = serviceArea;
        this.basePrice = basePrice;
        this.approvalStatus = STATUS_PENDING;
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

    public Long getUserId() {
        return userId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getServiceArea() {
        return serviceArea;
    }

    public void setServiceArea(String serviceArea) {
        this.serviceArea = serviceArea;
    }

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public Instant getApprovalReviewedAt() {
        return approvalReviewedAt;
    }

    public Long getApprovalReviewedBy() {
        return approvalReviewedBy;
    }

    public String getApprovalRejectionReason() {
        return approvalRejectionReason;
    }

    /**
     * Operator approval. Legal from {@link #STATUS_PENDING} (the ordinary review) and from
     * {@link #STATUS_REJECTED} (a professional who fixed whatever was wrong and was re-reviewed);
     * illegal from {@link #STATUS_APPROVED}, which is what turns a double-submitted approval into
     * a reported conflict instead of a silent second write of the same value under a new
     * reviewer's name.
     *
     * <p>Clears {@link #approvalRejectionReason} — required by
     * {@code ck_professionals_rejection_reason}, and correct on its own terms: these columns
     * record the decision currently in force, not a history, so leaving a stale reason on an
     * approved row would be actively misleading to the next operator who reads it.
     *
     * @throws IllegalStateException if the transition is not legal. The caller
     *         ({@code ProfessionalApprovalService}) checks {@link #canApprove} first and reports a
     *         proper {@code 409}; reaching this throw means two operators decided the same
     *         professional between one's read and their write, and it is deliberately loud rather
     *         than a silent overwrite.
     */
    public void approve(Long reviewerUserId, Instant reviewedAt) {
        if (!canApprove()) {
            throw new IllegalStateException(
                    "Cannot approve professional " + id + " from status " + approvalStatus + ".");
        }
        this.approvalStatus = STATUS_APPROVED;
        this.approvalReviewedBy = reviewerUserId;
        this.approvalReviewedAt = reviewedAt;
        this.approvalRejectionReason = null;
    }

    /**
     * Operator rejection. Legal only from {@link #STATUS_PENDING}: re-rejecting an already
     * rejected professional decides nothing, and rejecting an approved one is a suspension, which
     * is MS7's {@link #STATUS_DISABLED} and not something this method may quietly stand in for.
     *
     * @param reason why — required, and stored so the professional can eventually be told
     *               something actionable rather than "no"
     * @throws IllegalStateException on an illegal transition; see {@link #approve} for why this
     *         is a throw rather than a no-op
     */
    public void reject(Long reviewerUserId, Instant reviewedAt, String reason) {
        if (!canReject()) {
            throw new IllegalStateException(
                    "Cannot reject professional " + id + " from status " + approvalStatus + ".");
        }
        this.approvalStatus = STATUS_REJECTED;
        this.approvalReviewedBy = reviewerUserId;
        this.approvalReviewedAt = reviewedAt;
        this.approvalRejectionReason = reason;
    }

    /** {@code PENDING -> APPROVED} and {@code REJECTED -> APPROVED} are the legal approvals. */
    public boolean canApprove() {
        return STATUS_PENDING.equals(approvalStatus) || STATUS_REJECTED.equals(approvalStatus);
    }

    /** {@code PENDING -> REJECTED} is the only legal rejection. */
    public boolean canReject() {
        return STATUS_PENDING.equals(approvalStatus);
    }

    public BigDecimal getReliabilityScore() {
        return reliabilityScore;
    }

    public void setReliabilityScore(BigDecimal reliabilityScore) {
        this.reliabilityScore = reliabilityScore;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getProfileImageKey() {
        return profileImageKey;
    }

    public void setProfileImageKey(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }

    public String getVerificationDocumentKey() {
        return verificationDocumentKey;
    }

    public void setVerificationDocumentKey(String verificationDocumentKey) {
        this.verificationDocumentKey = verificationDocumentKey;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
