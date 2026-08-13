package com.pronto.issues.service;

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
    private final ClassificationService classificationService;
    private final ProfessionalRepository professionalRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public IssuesService(IssueRepository issueRepository,
                          IssueImageRepository issueImageRepository,
                          CategoryRepository categoryRepository,
                          StorageClient storageClient,
                          ClassificationService classificationService,
                          ProfessionalRepository professionalRepository,
                          OrderRepository orderRepository,
                          UserRepository userRepository) {
        this.issueRepository = issueRepository;
        this.issueImageRepository = issueImageRepository;
        this.categoryRepository = categoryRepository;
        this.storageClient = storageClient;
        this.classificationService = classificationService;
        this.professionalRepository = professionalRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    /** §2.1. Stateless — no DB write, may be called repeatedly with no side effects. */
    public ClassifyResponse classify(Long callerId, ClassifyRequest request) {
        List<String> imageKeys = validateImageKeys(callerId, request.imageKeys());
        ClassificationSuggestion suggestion = classificationService.classify(request.description(), imageKeys);
        return new ClassifyResponse(suggestion.categoryId(), suggestion.categoryCode(),
                suggestion.confidence(), suggestion.explanation());
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
            String url = storageClient.resolveUrl(key);
            images.add(issueImageRepository.save(new IssueImage(issue.getId(), url)));
        }

        return toResponse(issue, images);
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

        List<IssueImageResponse> images = issueImageRepository.findByIssueId(issueId).stream()
                .map(img -> new IssueImageResponse(img.getId(), img.getImageUrl(), img.getUploadedAt()))
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

    private IssueResponse toResponse(Issue issue, List<IssueImage> images) {
        List<IssueImageResponse> imageResponses = images.stream()
                .map(img -> new IssueImageResponse(img.getId(), img.getImageUrl(), img.getUploadedAt()))
                .toList();
        return new IssueResponse(issue.getId(), issue.getCustomerId(), issue.getCategoryId(),
                issue.getDescription(), issue.getUrgencyType(), issue.getStatus(), imageResponses, issue.getCreatedAt());
    }
}
