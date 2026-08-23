package com.pronto.professionals.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.favorites.repository.FavoriteRepository;
import com.pronto.professionals.dto.MySubServicesResponse;
import com.pronto.professionals.dto.ProfessionalProfileResponse;
import com.pronto.professionals.dto.ProfileImageUploadResponse;
import com.pronto.professionals.dto.UpdateProfessionalProfileRequest;
import com.pronto.professionals.dto.UpdateSubServicesRequest;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.entity.ProfessionalSubService;
import com.pronto.professionals.entity.ProfessionalSubServiceId;
import com.pronto.professionals.repository.ProfessionalRatingAggregate;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ProfessionalSubServiceRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.storage.ImageContentType;
import com.pronto.storage.client.StoredObject;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code GET}/{@code PUT /api/professionals/me}, {@code POST
 * /api/professionals/me/profile-image}, {@code GET /api/professionals/{professionalId}}, and,
 * as of MS11 (Services &amp; Sub-services), {@code GET}/{@code PUT
 * /api/professionals/me/sub-services}. Route-level role gating ({@code PROFESSIONAL}-only on
 * the {@code /me} routes) happens in {@code professionals.config.ProfessionalsWebConfig}; the
 * {@code {professionalId}} detail route is either-role and has no route-level gate. See
 * {@code docs/architecture/product-ms11-sub-services-design.md} §3.2 for the sub-services
 * endpoints' full validation/update-semantics spec.
 */
@Service
public class ProfessionalsService {

    private final ProfessionalRepository professionalRepository;
    private final UserRepository userRepository;
    private final ReviewAggregateRepository reviewAggregateRepository;
    private final FavoriteRepository favoriteRepository;
    private final StorageService storageService;
    private final SubServiceSelectionValidator subServiceSelectionValidator;
    private final ProfessionalSubServiceRepository professionalSubServiceRepository;

    public ProfessionalsService(ProfessionalRepository professionalRepository,
                                 UserRepository userRepository,
                                 ReviewAggregateRepository reviewAggregateRepository,
                                 FavoriteRepository favoriteRepository,
                                 StorageService storageService,
                                 SubServiceSelectionValidator subServiceSelectionValidator,
                                 ProfessionalSubServiceRepository professionalSubServiceRepository) {
        this.professionalRepository = professionalRepository;
        this.userRepository = userRepository;
        this.reviewAggregateRepository = reviewAggregateRepository;
        this.favoriteRepository = favoriteRepository;
        this.storageService = storageService;
        this.subServiceSelectionValidator = subServiceSelectionValidator;
        this.professionalSubServiceRepository = professionalSubServiceRepository;
    }

    /** PROFESSIONAL only. {@code favorited} is always {@code null} on this self-view. */
    @Transactional(readOnly = true)
    public ProfessionalProfileResponse getMyProfile(AuthenticatedUser caller) {
        Professional professional = resolveOwnProfessional(caller.id());
        User user = loadUser(professional.getUserId());
        return toResponse(professional, user, null, caller.id());
    }

    /**
     * PROFESSIONAL only. Loads the caller's own {@code Professional}/{@code User} rows,
     * mutates both in memory, plain {@code save()} on each within this one
     * {@code @Transactional} method — matches {@code users.service.UsersService#deleteMe}'s
     * load-mutate-save precedent for a single-owner, non-concurrency-contended write (not the
     * guarded-atomic-UPDATE pattern reserved for concurrency-contended state machines like
     * orders/slots).
     */
    @Transactional
    public ProfessionalProfileResponse updateMyProfile(AuthenticatedUser caller,
                                                         UpdateProfessionalProfileRequest request) {
        Professional professional = resolveOwnProfessional(caller.id());
        User user = loadUser(professional.getUserId());

        user.setFullName(request.fullName());
        userRepository.save(user);

        professional.setServiceArea(request.serviceArea());
        professional.setCity(request.city());
        professional.setBio(request.bio());
        professional.setBasePrice(request.basePrice());
        professional = professionalRepository.save(professional);

        return toResponse(professional, user, null, caller.id());
    }

    /**
     * Either role. {@code 404} if no such professional exists. {@code favorited} is populated
     * only when the caller's role is {@code CUSTOMER} (a {@code PROFESSIONAL} caller, or the
     * professional viewing their own card by id, always gets {@code null}).
     */
    @Transactional(readOnly = true)
    public ProfessionalProfileResponse getProfile(Long professionalId, AuthenticatedUser caller) {
        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Professional " + professionalId + " not found."));
        User user = loadUser(professional.getUserId());

        Boolean favorited = null;
        if (UserRole.CUSTOMER.name().equals(caller.role())) {
            favorited = favoriteRepository.existsByCustomerIdAndProfessionalId(caller.id(), professionalId);
        }
        return toResponse(professional, user, favorited, caller.id());
    }

    /**
     * PROFESSIONAL only. Builds its own key template
     * {@code professionals/{professionalId}/profile/{uuid}.{ext}} and calls
     * {@code storage.service.StorageService#uploadWithKey} directly (rather than the
     * {@code customers/...}-keyed {@link StorageService#upload}, which is issue-image-specific
     * and CUSTOMER-scoped).
     */
    @Transactional
    public ProfileImageUploadResponse uploadProfileImage(AuthenticatedUser caller, MultipartFile file) {
        Professional professional = resolveOwnProfessional(caller.id());

        ImageContentType type = ImageContentType.fromContentType(file == null ? null : file.getContentType())
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_IMAGE_TYPE,
                        "Unsupported image content type: " + (file == null ? null : file.getContentType())));
        String key = "professionals/" + professional.getId() + "/profile/" + UUID.randomUUID() + "." + type.extension();

        StoredObject stored = storageService.uploadWithKey(key, file);

        professional.setProfileImageKey(stored.key());
        professionalRepository.save(professional);

        return new ProfileImageUploadResponse(stored.key(), stored.url(), stored.contentType(), stored.sizeBytes());
    }

    /** MS11 §3.2. PROFESSIONAL only. */
    @Transactional(readOnly = true)
    public MySubServicesResponse getMySubServices(AuthenticatedUser caller) {
        Professional professional = resolveOwnProfessional(caller.id());
        List<Long> subServiceIds = professionalSubServiceRepository.findByProfessionalId(professional.getId())
                .stream()
                .map(ProfessionalSubService::getSubServiceId)
                .toList();
        return new MySubServicesResponse(subServiceIds);
    }

    /**
     * MS11 §3.2. PROFESSIONAL only. Full-replace of the caller's sub-service selection,
     * reusing {@code availability.service.AvailabilityService#updateWorkingHours}'s shape
     * precedent. Validation, in order: (1) every requested id must exist in {@code
     * sub_services} -- unknown id -&gt; {@code 400 VALIDATION_ERROR}; (2) every id's {@code
     * category_id} must equal the caller's own {@code professionals.category_id} -- mismatch
     * -&gt; {@code 400 CATEGORY_MISMATCH} (reuses the existing error code, see {@code
     * bookings.service.BookingsService#categoryMismatch}). Update semantics are diff-based
     * (not delete-all-then-reinsert): only removed rows are deleted and only newly-added rows
     * are inserted, so {@code created_at} is preserved for sub-services that stay selected
     * across an edit.
     */
    @Transactional
    public MySubServicesResponse updateMySubServices(AuthenticatedUser caller, UpdateSubServicesRequest request) {
        Professional professional = resolveOwnProfessional(caller.id());

        // Server-side dedupe -- defensive, a checkbox-driven UI can't produce duplicates but
        // the endpoint shouldn't rely on that.
        Set<Long> requestedIds = new HashSet<>(request.subServiceIds());

        // MS1: the existence/cross-category rule moved verbatim into SubServiceSelectionValidator
        // so registration enforces the identical one. Behavior and both error codes unchanged.
        subServiceSelectionValidator.validate(professional.getCategoryId(), requestedIds, "subServiceIds");

        Set<Long> existingIds = professionalSubServiceRepository.findByProfessionalId(professional.getId())
                .stream()
                .map(ProfessionalSubService::getSubServiceId)
                .collect(Collectors.toSet());

        Set<Long> toRemove = new HashSet<>(existingIds);
        toRemove.removeAll(requestedIds);
        Set<Long> toAdd = new HashSet<>(requestedIds);
        toAdd.removeAll(existingIds);

        toRemove.forEach(id -> professionalSubServiceRepository
                .deleteById(new ProfessionalSubServiceId(professional.getId(), id)));
        toAdd.forEach(id -> professionalSubServiceRepository
                .save(new ProfessionalSubService(professional.getId(), id)));

        return getMySubServices(caller);
    }

    private Professional resolveOwnProfessional(Long callerId) {
        return professionalRepository.findByUserId(callerId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN,
                        "No professional profile found for this account."));
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User " + userId + " not found."));
    }

    /**
     * <b>MS1 (D-G): {@code approvalStatus} is disclosed on the self-view only.</b> Whether the
     * caller <em>is</em> this professional is decided here, from the loaded row's own
     * {@code userId} rather than from which endpoint was called — so the professional gets the
     * same honest answer whether they open {@code /me} or their own public card by id, and a
     * customer gets {@code null} from either. Everyone, including the professional, gets the
     * neutral {@code bookable} flag; see {@link ProfessionalProfileResponse}.
     */
    private ProfessionalProfileResponse toResponse(Professional professional, User user, Boolean favorited,
                                                     Long callerId) {
        String profileImageUrl = professional.getProfileImageKey() == null
                ? null
                : storageService.getPresignedUrl(callerId, professional.getProfileImageKey());

        ProfessionalRatingAggregate aggregate = reviewAggregateRepository.getRatingAggregate(professional.getId());
        BigDecimal averageRating = aggregate.averageRating() == null
                ? null
                : BigDecimal.valueOf(aggregate.averageRating()).setScale(2, RoundingMode.HALF_UP);
        long reviewCount = aggregate.reviewCount() == null ? 0 : aggregate.reviewCount();

        boolean selfView = professional.getUserId().equals(callerId);
        String approvalStatus = selfView ? professional.getApprovalStatus() : null;
        boolean bookable = professionalRepository.existsEligibleById(professional.getId());

        return new ProfessionalProfileResponse(professional.getId(), professional.getCategoryId(),
                user.getFullName(), professional.getServiceArea(), professional.getCity(), professional.getBio(),
                professional.getBasePrice(), profileImageUrl, averageRating, reviewCount,
                approvalStatus, bookable, favorited, professional.getCreatedAt(), professional.getUpdatedAt());
    }
}
