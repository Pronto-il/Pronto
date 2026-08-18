package com.pronto.issues.service;

import com.pronto.ai.client.ClarificationAnswer;
import com.pronto.ai.dto.ClassificationSuggestion;
import com.pronto.ai.service.ClassificationService;
import com.pronto.bookings.entity.Order;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.dto.ClassifyRequest;
import com.pronto.issues.dto.ClassifyResponse;
import com.pronto.issues.dto.CreateIssueRequest;
import com.pronto.issues.dto.IssueDetailResponse;
import com.pronto.issues.dto.IssueImageResponse;
import com.pronto.issues.dto.IssueResponse;
import com.pronto.issues.dto.LatestOrderSummary;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueImage;
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
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code POST /api/issues/classify} and {@code POST /api/issues}, per
 * {@code docs/architecture/api-contract-issues.md} §2.1-2.2. Role check
 * ({@code 403 FORBIDDEN} for a non-{@code CUSTOMER} caller) happens in the controller via
 * {@code common.security.RoleGuard}, before either method here is invoked.
 */
@Service
public class IssuesService {

    private final IssueRepository issueRepository;
    private final IssueImageRepository issueImageRepository;
    private final CategoryRepository categoryRepository;
    private final StorageClient storageClient;
    private final StorageService storageService;
    private final ClassificationService classificationService;
    private final ProfessionalRepository professionalRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public IssuesService(IssueRepository issueRepository,
                          IssueImageRepository issueImageRepository,
                          CategoryRepository categoryRepository,
                          StorageClient storageClient,
                          StorageService storageService,
                          ClassificationService classificationService,
                          ProfessionalRepository professionalRepository,
                          OrderRepository orderRepository,
                          UserRepository userRepository) {
        this.issueRepository = issueRepository;
        this.issueImageRepository = issueImageRepository;
        this.categoryRepository = categoryRepository;
        this.storageClient = storageClient;
        this.storageService = storageService;
        this.classificationService = classificationService;
        this.professionalRepository = professionalRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    /**
     * §2.1. Stateless — no DB write, may be called repeatedly with no side effects. When
     * {@code request.clarificationAnswers()} is present (the customer answered a prior
     * {@code QUESTIONS} response), performs the single allowed clarification round instead of
     * a fresh initial classification — see the clarification-question extension in §2.1.
     */
    public ClassifyResponse classify(Long callerId, ClassifyRequest request) {
        List<String> imageKeys = validateImageKeys(callerId, request.imageKeys());

        ClassificationSuggestion suggestion;
        if (request.clarificationAnswers() != null && !request.clarificationAnswers().isEmpty()) {
            List<ClarificationAnswer> answers = request.clarificationAnswers().stream()
                    .map(a -> new ClarificationAnswer(a.question(), a.answer()))
                    .toList();
            suggestion = classificationService.classifyWithClarification(request.description(), imageKeys, answers);
        } else {
            suggestion = classificationService.classify(request.description(), imageKeys);
        }

        return new ClassifyResponse(suggestion.status(), suggestion.categoryId(), suggestion.categoryCode(),
                suggestion.confidence(), suggestion.explanation(), suggestion.questions());
    }

    /** §2.2. First (and only) DB write in this milestone's request flow. */
    @Transactional
    public IssueResponse create(Long callerId, CreateIssueRequest request) {
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

        return toResponse(callerId, issue, images);
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

        LatestOrderSummary latestOrder = orderRepository.findFirstByIssueIdOrderByCreatedAtDesc(issueId)
                .map(this::toLatestOrderSummary)
                .orElse(null);

        return new IssueDetailResponse(issue.getId(), issue.getCustomerId(), issue.getCategoryId(), categoryCode,
                issue.getDescription(), issue.getUrgencyType(), issue.getStatus(), images, latestOrder,
                issue.getCreatedAt(), issue.getUpdatedAt());
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
