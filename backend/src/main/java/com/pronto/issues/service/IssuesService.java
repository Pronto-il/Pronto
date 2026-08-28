package com.pronto.issues.service;

import com.pronto.ai.dto.ClarificationExchange;
import com.pronto.ai.dto.ClarificationQuestion;
import com.pronto.ai.dto.ClassificationStatus;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.service.ClassificationService;
import com.pronto.bookings.entity.Order;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.dto.ClarificationAnswerRequest;
import com.pronto.issues.dto.ClarificationEntryResponse;
import com.pronto.issues.dto.ClarifyQuestionResponse;
import com.pronto.issues.dto.ClassifyRequest;
import com.pronto.issues.dto.ClassifyResponse;
import com.pronto.issues.dto.CreateIssueRequest;
import com.pronto.issues.dto.IssueDetailResponse;
import com.pronto.issues.dto.IssueImageResponse;
import com.pronto.issues.dto.IssueResponse;
import com.pronto.issues.dto.LatestOrderSummary;
import com.pronto.issues.dto.ProntoAnalysisResponse;
import com.pronto.issues.dto.UpdateIssueCategoryRequest;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueBrief;
import com.pronto.issues.entity.IssueClarification;
import com.pronto.issues.entity.IssueClassification;
import com.pronto.issues.entity.IssueImage;
import com.pronto.issues.entity.IssueStatus;
import com.pronto.issues.event.IssueCategoryChangedEvent;
import com.pronto.issues.event.IssueCreatedEvent;
import com.pronto.issues.repository.IssueBriefRepository;
import com.pronto.issues.repository.IssueClarificationRepository;
import com.pronto.issues.repository.IssueClassificationRepository;
import com.pronto.issues.repository.IssueImageRepository;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.professionals.entity.Category;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.storage.ImageKeyUtils;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.client.StorageException;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.service.ContactVerificationGuard;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code POST /api/issues/classify}, {@code POST /api/issues} and {@code GET /api/issues/{id}},
 * per {@code docs/architecture/api-contract-issues.md} §2.1-2.2 and
 * {@code api-contract-bookings.md} §2.1. Role checks happen in the controller layer via
 * {@code common.security.RoleGuard}, before any method here is invoked.
 */
@Service
public class IssuesService {

    private final IssueRepository issueRepository;
    private final IssueImageRepository issueImageRepository;
    private final IssueClarificationRepository issueClarificationRepository;
    private final IssueClassificationRepository issueClassificationRepository;
    private final IssueBriefRepository issueBriefRepository;
    private final CategoryRepository categoryRepository;
    private final StorageClient storageClient;
    private final StorageService storageService;
    private final ClassificationService classificationService;
    private final ProfessionalRepository professionalRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ContactVerificationGuard contactVerificationGuard;
    private final ApplicationEventPublisher eventPublisher;

    public IssuesService(IssueRepository issueRepository,
                          IssueImageRepository issueImageRepository,
                          IssueClarificationRepository issueClarificationRepository,
                          IssueClassificationRepository issueClassificationRepository,
                          IssueBriefRepository issueBriefRepository,
                          CategoryRepository categoryRepository,
                          StorageClient storageClient,
                          StorageService storageService,
                          ClassificationService classificationService,
                          ProfessionalRepository professionalRepository,
                          OrderRepository orderRepository,
                          UserRepository userRepository,
                          ContactVerificationGuard contactVerificationGuard,
                          ApplicationEventPublisher eventPublisher) {
        this.issueRepository = issueRepository;
        this.issueImageRepository = issueImageRepository;
        this.issueClarificationRepository = issueClarificationRepository;
        this.issueClassificationRepository = issueClassificationRepository;
        this.issueBriefRepository = issueBriefRepository;
        this.categoryRepository = categoryRepository;
        this.storageClient = storageClient;
        this.storageService = storageService;
        this.classificationService = classificationService;
        this.professionalRepository = professionalRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.contactVerificationGuard = contactVerificationGuard;
        this.eventPublisher = eventPublisher;
    }

    /**
     * §2.1. Stateless — no DB write, may be called repeatedly with no side effects.
     *
     * <p>There is no longer an "initial pass" branch and a "clarification round" branch: every
     * call runs the same classification over the same complete evidence, and the accumulated
     * {@code clarificationAnswers} simply shrink the server-side question budget. A response
     * can therefore come back {@code QUESTIONS} more than once — Pronto asks one question at a
     * time and re-evaluates — until the budget is spent, after which a final decision is
     * guaranteed rather than enforced by throwing.
     */
    public ClassifyResponse classify(Long callerId, ClassifyRequest request) {
        // Production MS1 (pre-DONE audit): classification is the first step of the marketplace
        // issue flow and every call spends an OpenAI request. Gating only POST /api/issues left an
        // unverified account able to drive the model indefinitely. The route is already
        // authenticated and CUSTOMER-only (SecurityConfig + IssuesWebConfig) -- there is no
        // anonymous classification flow this can break.
        contactVerificationGuard.requireVerifiedContactChannels(callerId);

        List<String> imageKeys = validateImageKeys(callerId, request.imageKeys());
        List<ClarificationExchange> answers = toExchanges(request.clarificationAnswers());

        ClassificationSuggestion suggestion = classificationService.classify(
                request.description(), imageKeys, request.selectedCategoryId(), answers);

        List<ClarifyQuestionResponse> questions = suggestion.questions().stream()
                .map(this::toQuestionResponse)
                .toList();

        return new ClassifyResponse(suggestion.status(), suggestion.detectedProfession(),
                suggestion.categoryId(), suggestion.categoryCode(), questions);
    }

    /**
     * §2.2. The one DB write in the customer's issue-creation flow — now also persisting the
     * clarification conversation and seeding the classification/brief rows.
     *
     * <p>The two AI-artefact rows are created here, empty, rather than only by the async job:
     * that guarantees the clarification-round count is recorded even if the AI is completely
     * unavailable, and gives the professional's screen a {@code PENDING} state to read instead
     * of a missing object it has to interpret.
     *
     * <p>The event is published inside the transaction but consumed after commit (see
     * {@code IssueBriefService}), so no model call can affect whether this issue is saved.
     */
    @Transactional
    public IssueResponse create(Long callerId, CreateIssueRequest request) {
        // Production MS1: reporting an issue is the first step towards a professional arriving at
        // this person's address, so it is the first step that requires a reachable phone number.
        contactVerificationGuard.requireVerifiedContactChannels(callerId);

        if (!categoryRepository.existsById(request.categoryId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("categoryId", "must reference an existing category")));
        }

        List<String> imageKeys = validateImageKeys(callerId, request.imageKeys());

        Issue issue = new Issue(callerId, request.categoryId(), request.description(), request.urgencyType());
        issue = issueRepository.save(issue);

        List<IssueImage> images = new ArrayList<>();
        for (String key : imageKeys) {
            images.add(issueImageRepository.save(new IssueImage(issue.getId(), key)));
        }

        List<ClarificationAnswerRequest> answers = request.clarificationAnswers() == null
                ? List.of() : request.clarificationAnswers();
        for (int position = 0; position < answers.size(); position++) {
            ClarificationAnswerRequest answer = answers.get(position);
            issueClarificationRepository.save(
                    new IssueClarification(issue.getId(), position, answer.question(), answer.answer()));
        }

        issueClassificationRepository.save(new IssueClassification(issue.getId(), answers.size()));
        issueBriefRepository.save(new IssueBrief(issue.getId()));

        eventPublisher.publishEvent(new IssueCreatedEvent(issue.getId()));

        return toResponse(callerId, issue, images);
    }

    /**
     * {@code PATCH /api/issues/{id}/category} — the customer overruling Pronto's classification on
     * an issue that already exists.
     *
     * <p><b>Why this endpoint exists.</b> The classification screen is reachable *backwards*: a
     * customer who has gone on to pick a service address can return to it and change the trade.
     * Before this, the only way to record that was to create a second issue, which left the first
     * one sitting {@code OPEN} — an orphan carrying the same description, the same photos and the
     * same clarification answers, indistinguishable from a real un-booked request. One reported
     * fault is one issue, for its whole life; this is how the category changes on it.
     *
     * <p><b>Rules, in the order they are checked.</b> The issue must exist ({@code 404}); the
     * caller must be its own customer ({@code 403} — not {@code 404}, matching
     * {@link #getById}'s treatment of the same question); the category must be a real row in
     * {@code categories} ({@code 400}, same check and same field error {@link #create} applies);
     * and the issue must still be {@code OPEN} ({@code 409 ISSUE_NOT_EDITABLE}), which is what
     * keeps booked, completed and cancelled issues immutable here. The state guard is applied
     * twice on purpose: once on the loaded row so the caller gets the specific error, and again
     * inside {@code updateCategoryIfOpen}'s {@code WHERE} clause, which is what actually makes it
     * safe against a booking landing in between.
     *
     * <p>A no-op change (same category re-confirmed) writes nothing at all and publishes nothing —
     * it just answers with the issue as it stands. That is the common path: the customer walked
     * back to look at the classification and agreed with it.
     */
    @Transactional
    public IssueResponse updateCategory(Long callerId, Long issueId, UpdateIssueCategoryRequest request) {
        Issue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Issue " + issueId + " not found."));

        if (!issue.getCustomerId().equals(callerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You are not authorized to modify this issue.");
        }
        if (!categoryRepository.existsById(request.categoryId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("categoryId", "must reference an existing category")));
        }
        if (issue.getStatus() != IssueStatus.OPEN) {
            throw notEditable(issueId);
        }

        if (issue.getCategoryId().equals(request.categoryId())) {
            return toResponse(callerId, issue, issueImageRepository.findByIssueId(issueId));
        }

        int affected = issueRepository.updateCategoryIfOpen(issueId, request.categoryId(), Instant.now());
        if (affected == 0) {
            // Booked by a concurrent request between the read above and this write.
            throw notEditable(issueId);
        }

        // The brief is written for a named trade, so the one generated for the old category is now
        // wrong rather than merely stale. Same after-commit, off-the-critical-path treatment as
        // creation: the correction is durable before any model call starts, and a failed
        // regeneration downgrades the brief, never the issue.
        eventPublisher.publishEvent(new IssueCategoryChangedEvent(issueId));

        // Re-read rather than mutating the detached entity: `updateCategoryIfOpen` is a bulk JPQL
        // update with `clearAutomatically`, so the version in the persistence context is stale by
        // definition.
        Issue updated = issueRepository.findById(issueId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Issue " + issueId + " not found."));
        return toResponse(callerId, updated, issueImageRepository.findByIssueId(issueId));
    }

    private ApiException notEditable(Long issueId) {
        return new ApiException(ErrorCode.ISSUE_NOT_EDITABLE,
                "Issue " + issueId + " can no longer be edited.");
    }

    /**
     * {@code GET /api/issues/{id}}, per
     * {@code docs/architecture/api-contract-bookings.md} §2.1. {@code callerRole} is the raw
     * JWT role string ({@code common.security.AuthenticatedUser.role()}) —
     * ownership/authorization is resolved here, not by a route-level role matcher (§0.1 of
     * that doc).
     */
    @Transactional(readOnly = true)
    public IssueDetailResponse getById(Long callerId, String callerRole, Long issueId) {
        Issue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Issue " + issueId + " not found."));

        boolean isProfessionalViewer = false;

        if (UserRole.CUSTOMER.name().equals(callerRole)) {
            if (!issue.getCustomerId().equals(callerId)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "You are not authorized to view this issue.");
            }
        } else if (UserRole.PROFESSIONAL.name().equals(callerRole)) {
            Long professionalId = professionalRepository.findByUserId(callerId).map(Professional::getId).orElse(null);
            boolean hasOrder = professionalId != null
                    && orderRepository.existsByIssueIdAndProfessionalId(issueId, professionalId);
            if (!hasOrder) {
                throw new ApiException(ErrorCode.FORBIDDEN, "You are not authorized to view this issue.");
            }
            isProfessionalViewer = true;
        } else {
            throw new ApiException(ErrorCode.FORBIDDEN, "You are not authorized to view this issue.");
        }

        String categoryCode = categoryRepository.findById(issue.getCategoryId()).map(Category::getCode).orElse(null);

        // Resolved fresh on every read, never persisted (backend MS9 §9.4.1) — and, per §9.4.2,
        // deliberately bypasses the general per-key ownership check: getById's own role-based
        // branch above already established (customer owns the issue OR professional has an
        // order on it) that the caller may view this issue and everything attached to it, a
        // strict superset of "may view every image this issue owns." Re-running the general
        // check here would wrongly reject the exact professional caller just approved above,
        // since a customers/{customerId}/... key never embeds a professional's own id.
        List<IssueImageResponse> images = issueImageRepository.findByIssueId(issueId).stream()
                .map(img -> new IssueImageResponse(img.getId(),
                        storageService.getPresignedUrlAssumingCallerAuthorized(img.getImageKey()), img.getUploadedAt()))
                .toList();

        List<ClarificationEntryResponse> clarifications = issueClarificationRepository
                .findByIssueIdOrderByPositionAsc(issueId).stream()
                .map(row -> new ClarificationEntryResponse(row.getQuestion(), row.getAnswer()))
                .toList();

        // Preparation material for whoever is going — not customer-facing content, so it is
        // resolved only for the professional branch above.
        ProntoAnalysisResponse prontoAnalysis = isProfessionalViewer
                ? issueBriefRepository.findById(issueId).map(this::toProntoAnalysis).orElse(null)
                : null;

        LatestOrderSummary latestOrder = orderRepository.findFirstByIssueIdOrderByCreatedAtDesc(issueId)
                .map(this::toLatestOrderSummary)
                .orElse(null);

        return new IssueDetailResponse(issue.getId(), issue.getCustomerId(), issue.getCategoryId(), categoryCode,
                issue.getDescription(), issue.getUrgencyType(), issue.getStatus(), images, clarifications,
                prontoAnalysis, latestOrder, issue.getCreatedAt(), issue.getUpdatedAt());
    }

    private ProntoAnalysisResponse toProntoAnalysis(IssueBrief brief) {
        ProntoAnalysisResponse.LikelyIssueResponse likelyIssue = brief.getLikelyIssueDescription() == null
                ? null
                : new ProntoAnalysisResponse.LikelyIssueResponse(
                        brief.getLikelyIssueDescription(),
                        toDouble(brief.getLikelyIssueConfidence()),
                        brief.getLikelyIssueEvidence());

        return new ProntoAnalysisResponse(brief.getStatus(), brief.getCustomerProblemSummary(),
                brief.getClarificationSummary(), brief.getImageObservations(), likelyIssue,
                brief.getPossibleCauses(), brief.getRecommendedTools(), brief.getRecommendedParts(),
                brief.getSafetyNotes());
    }

    private Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private ClarifyQuestionResponse toQuestionResponse(ClarificationQuestion question) {
        return new ClarifyQuestionResponse(question.id(), question.question(), question.options());
    }

    private List<ClarificationExchange> toExchanges(List<ClarificationAnswerRequest> answers) {
        if (answers == null || answers.isEmpty()) {
            return List.of();
        }
        return answers.stream()
                .map(answer -> new ClarificationExchange(answer.question(), answer.answer()))
                .toList();
    }

    private LatestOrderSummary toLatestOrderSummary(Order order) {
        String professionalName = professionalRepository.findById(order.getProfessionalId())
                .flatMap(p -> userRepository.findById(p.getUserId()))
                .map(User::getFullName)
                .orElse(null);
        return new LatestOrderSummary(order.getId(), order.getProfessionalId(), professionalName,
                order.getOrderStatus(), order.getBookedStart(), order.getBookedEnd(), order.getFinalPrice(),
                order.getCreatedAt());
    }

    /**
     * §2.1 step 3 / §2.2 step 4 / §3.3: every key must exist in storage and embed the
     * caller's own id as its owner segment. Fails the whole request on the first bad key —
     * no partial processing.
     */
    private List<String> validateImageKeys(Long callerId, List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) {
            return Collections.emptyList();
        }
        for (String key : imageKeys) {
            if (!ImageKeyUtils.belongsTo(key, callerId)) {
                throw new ApiException(ErrorCode.IMAGE_KEY_INVALID,
                        "One or more imageKeys were not found or do not belong to the caller.");
            }
            boolean exists;
            try {
                exists = storageClient.exists(key);
            } catch (StorageException e) {
                throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to verify an attached image.");
            }
            if (!exists) {
                throw new ApiException(ErrorCode.IMAGE_KEY_INVALID,
                        "One or more imageKeys were not found or do not belong to the caller.");
            }
        }
        return imageKeys;
    }

    private IssueResponse toResponse(Long callerId, Issue issue, List<IssueImage> images) {
        // Customer viewing their own image they just uploaded — the general, checked
        // getPresignedUrl path applies cleanly here (no exemption needed): every key in
        // `images` is guaranteed to already belong to `callerId`, since validateImageKeys
        // enforced that before any image was ever persisted (see #create above).
        List<IssueImageResponse> imageResponses = images.stream()
                .map(img -> new IssueImageResponse(img.getId(),
                        storageService.getPresignedUrl(callerId, img.getImageKey()), img.getUploadedAt()))
                .toList();
        return new IssueResponse(issue.getId(), issue.getCustomerId(), issue.getCategoryId(),
                issue.getDescription(), issue.getUrgencyType(), issue.getStatus(), imageResponses, issue.getCreatedAt());
    }
}
