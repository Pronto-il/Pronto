package com.pronto.professionals.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.professionals.dto.ProfessionalApprovalListResponse;
import com.pronto.professionals.dto.ProfessionalApprovalSummary;
import com.pronto.professionals.dto.ProfessionalReviewDetailResponse;
import com.pronto.professionals.dto.RejectProfessionalRequest;
import com.pronto.professionals.dto.VerificationDocumentUrlResponse;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.entity.ProfessionalSubService;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ProfessionalSubServiceRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * {@code /api/admin/professionals/**} — <b>the minimum operator capability MS1 needs and nothing
 * more</b>: see who is waiting, read what they submitted, look at their verification document,
 * approve, reject. The full admin portal is MS7's; this is the five actions without which the
 * approval lifecycle introduced in MS1 would have no one able to drive it, leaving every new
 * professional stuck at {@code PENDING} forever.
 *
 * <p><b>Authorization is at the route.</b> Every path here is gated on {@code ADMIN} by
 * {@code professionals.config.ProfessionalsWebConfig}'s {@code RoleRequiredInterceptor}, which
 * runs in {@code preHandle} — before Spring resolves any {@code @Valid} body — so a customer or a
 * professional gets {@code 403 FORBIDDEN} rather than a validation error that would confirm the
 * endpoint's shape. This class deliberately does not re-check the role: a second, divergent copy
 * of the gate is how one of them ends up wrong.
 *
 * <p><b>Concurrency.</b> Load-mutate-save on a single row, the same pattern
 * {@code ProfessionalsService#updateMyProfile} and {@code UsersService#deleteMe} use, rather than
 * the guarded atomic {@code UPDATE ... WHERE <state>} reserved for genuinely contended state
 * machines (orders, SOS requests, slots). Approval is a human queue worked by a handful of
 * operators, not a race between a customer and a professional with a deadline between them. The
 * transition rules live in {@link Professional#approve}/{@link Professional#reject} and throw on
 * an illegal transition, so the honest limit of this design is stated rather than hidden: two
 * operators who genuinely decide the same professional at the same instant both write, and the
 * later commit wins. That is a lost audit attribution on one row, not a corrupted state — both
 * writers were making the same legal transition from the same status.
 */
@Service
public class ProfessionalApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ProfessionalApprovalService.class);

    private final ProfessionalRepository professionalRepository;
    private final ProfessionalSubServiceRepository professionalSubServiceRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;

    public ProfessionalApprovalService(ProfessionalRepository professionalRepository,
                                        ProfessionalSubServiceRepository professionalSubServiceRepository,
                                        UserRepository userRepository,
                                        StorageService storageService) {
        this.professionalRepository = professionalRepository;
        this.professionalSubServiceRepository = professionalSubServiceRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
    }

    /**
     * {@code GET /api/admin/professionals[?approvalStatus=PENDING]} — the queue, oldest first, so
     * the person who has been waiting longest is at the top.
     *
     * <p>An unrecognized {@code approvalStatus} is a {@code 400}, not an empty list: silently
     * returning nothing for a typo would read as "nobody is waiting", which is the one wrong
     * answer this screen can give. Missing/blank means no filter, matching
     * {@code BookingsService#parseStatus}'s convention for the same kind of parameter.
     */
    @Transactional(readOnly = true)
    public ProfessionalApprovalListResponse list(String approvalStatusParam) {
        String approvalStatus = parseApprovalStatus(approvalStatusParam);
        List<Professional> professionals = approvalStatus == null
                ? professionalRepository.findAllByOrderByCreatedAtAsc()
                : professionalRepository.findByApprovalStatusOrderByCreatedAtAsc(approvalStatus);

        return new ProfessionalApprovalListResponse(professionals.stream()
                .map(this::toSummary)
                .toList());
    }

    /** {@code GET /api/admin/professionals/{professionalId}} — everything needed to decide. */
    @Transactional(readOnly = true)
    public ProfessionalReviewDetailResponse getReviewDetail(Long professionalId) {
        Professional professional = load(professionalId);
        User user = loadUser(professional.getUserId());

        List<Long> subServiceIds = professionalSubServiceRepository.findByProfessionalId(professionalId).stream()
                .map(ProfessionalSubService::getSubServiceId)
                .toList();

        return new ProfessionalReviewDetailResponse(professional.getId(), professional.getUserId(),
                user.getFullName(), user.getEmail(), professional.getCategoryId(), professional.getServiceArea(),
                professional.getCity(), professional.getBio(), professional.getBasePrice(),
                professional.getApprovalStatus(), professionalRepository.existsEligibleById(professionalId),
                professional.getVerificationDocumentKey() != null, subServiceIds,
                professionalRepository.hasCompleteOnboarding(professionalId), professional.getCreatedAt(),
                professional.getApprovalReviewedAt(), professional.getApprovalReviewedBy(),
                professional.getApprovalRejectionReason());
    }

    /**
     * {@code GET /api/admin/professionals/{professionalId}/verification-document} — mints a
     * short-lived URL for the one private document this operator is entitled to see.
     *
     * <p>The key comes off the row, never off the request, and is handed to
     * {@code StorageService#getVerificationDocumentUrlForOperator} — the deliberately narrow,
     * prefix-locked operator path, rather than a widening of the general ownership rule. See that
     * method's Javadoc for the full justification.
     *
     * <p><b>Nothing here logs the key or the URL.</b> Both are secrets: the URL is a bearer
     * capability for the document's lifetime, and the key is the durable half of it. The audit
     * line below records that an operator viewed a professional's document — who and whose, which
     * is the accountable fact — and nothing that would let a log reader fetch it.
     */
    @Transactional(readOnly = true)
    public VerificationDocumentUrlResponse getVerificationDocumentUrl(AuthenticatedUser caller, Long professionalId) {
        Professional professional = load(professionalId);
        String key = professional.getVerificationDocumentKey();
        if (key == null) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                    "Professional " + professionalId + " has no verification document.");
        }

        String url = storageService.getVerificationDocumentUrlForOperator(key);
        log.info("professional.verification-document.viewed professionalId={} operatorUserId={}",
                professionalId, caller.id());
        return new VerificationDocumentUrlResponse(professionalId, url,
                storageService.getPresignedUrlTtlSeconds());
    }

    /**
     * {@code POST /api/admin/professionals/{professionalId}/approve}.
     *
     * <p><b>Approval does not make anyone bookable on its own</b> (D4). If onboarding is
     * incomplete the professional stays invisible to customers until they finish it, and this
     * method will not fabricate the missing sub-services or working hours to close the gap — it
     * records the decision and logs that the decision landed on someone not yet ready, so the
     * outcome is visible rather than mysterious.
     *
     * <p>Approval is deliberately <em>not</em> refused for incomplete onboarding. The two
     * judgments are different: "is this a real, verified tradesperson" is the operator's, and
     * "have they finished setting up their calendar" is the professional's own, self-healing the
     * moment they do it. Coupling them would either block a legitimate approval or, worse, invite
     * an operator to fill in someone else's availability to unblock it.
     */
    @Transactional
    public ProfessionalReviewDetailResponse approve(AuthenticatedUser caller, Long professionalId) {
        Professional professional = load(professionalId);
        if (!professional.canApprove()) {
            throw invalidTransition(professionalId, professional.getApprovalStatus(), "approved");
        }

        professional.approve(caller.id(), Instant.now());
        professionalRepository.save(professional);

        boolean onboardingComplete = professionalRepository.hasCompleteOnboarding(professionalId);
        log.info("professional.approved professionalId={} operatorUserId={} onboardingComplete={}",
                professionalId, caller.id(), onboardingComplete);
        return getReviewDetail(professionalId);
    }

    /**
     * {@code POST /api/admin/professionals/{professionalId}/reject}. Legal only from
     * {@code PENDING} — rejecting an already-approved professional is a suspension, which is MS7's
     * {@code DISABLED} and not something this endpoint may quietly stand in for.
     *
     * <p>The reason is stored, not logged: it is a judgment about a named person and belongs in
     * the row an operator can see, not in an application log stream.
     */
    @Transactional
    public ProfessionalReviewDetailResponse reject(AuthenticatedUser caller, Long professionalId,
                                                     RejectProfessionalRequest request) {
        Professional professional = load(professionalId);
        if (!professional.canReject()) {
            throw invalidTransition(professionalId, professional.getApprovalStatus(), "rejected");
        }

        professional.reject(caller.id(), Instant.now(), request.reason().trim());
        professionalRepository.save(professional);

        log.info("professional.rejected professionalId={} operatorUserId={}", professionalId, caller.id());
        return getReviewDetail(professionalId);
    }

    private ProfessionalApprovalSummary toSummary(Professional professional) {
        User user = userRepository.findById(professional.getUserId()).orElse(null);
        return new ProfessionalApprovalSummary(professional.getId(), professional.getUserId(),
                user == null ? null : user.getFullName(), user == null ? null : user.getEmail(),
                professional.getCategoryId(), professional.getServiceArea(), professional.getCity(),
                professional.getApprovalStatus(),
                professionalRepository.hasCompleteOnboarding(professional.getId()),
                professional.getCreatedAt(), professional.getApprovalReviewedAt());
    }

    private Professional load(Long professionalId) {
        return professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Professional " + professionalId + " not found."));
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User " + userId + " not found."));
    }

    /**
     * Accepts only the statuses an operator can meaningfully filter on. {@code DISABLED} is
     * accepted as a filter value even though MS1 can never produce it — filtering for an empty set
     * is harmless, whereas rejecting a value the database's own CHECK constraint permits would be
     * a second, narrower definition of the same enumeration.
     */
    private String parseApprovalStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<String> allowed = Set.of(Professional.STATUS_PENDING, Professional.STATUS_APPROVED,
                Professional.STATUS_REJECTED, Professional.STATUS_DISABLED);
        if (!allowed.contains(raw)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request query parameters failed validation.",
                    List.of(new com.pronto.common.dto.FieldError("approvalStatus",
                            "must be one of PENDING, APPROVED, REJECTED, DISABLED")));
        }
        return raw;
    }

    private ApiException invalidTransition(Long professionalId, String currentStatus, String action) {
        return new ApiException(ErrorCode.PROFESSIONAL_APPROVAL_INVALID_TRANSITION,
                "Professional " + professionalId + " is " + currentStatus + " and cannot be " + action + ".");
    }
}
