package com.pronto.professionals.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.favorites.repository.FavoriteRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.professionals.dto.MySubServiceItem;
import com.pronto.professionals.dto.MySubServicesResponse;
import com.pronto.professionals.dto.ProfessionalProfileResponse;
import com.pronto.professionals.dto.ProfileImageUploadResponse;
import com.pronto.professionals.dto.SubServicePriceSelection;
import com.pronto.professionals.dto.UpdateProfessionalProfileRequest;
import com.pronto.professionals.dto.UpdateSubServicesRequest;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.entity.ProfessionalSubService;
import com.pronto.professionals.entity.ProfessionalSubServiceId;
import com.pronto.professionals.entity.SubService;
import com.pronto.professionals.repository.ProfessionalRatingAggregate;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ProfessionalSubServiceRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.professionals.repository.SubServiceRepository;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final ProfessionalCoverageService professionalCoverageService;
    private final SubServicePriceValidator subServicePriceValidator;
    /** Catalogue lookup for labelling the caller's own selection. Read-only reference data. */
    private final SubServiceRepository subServiceRepository;

    public ProfessionalsService(ProfessionalRepository professionalRepository,
                                 UserRepository userRepository,
                                 ReviewAggregateRepository reviewAggregateRepository,
                                 FavoriteRepository favoriteRepository,
                                 StorageService storageService,
                                 SubServiceSelectionValidator subServiceSelectionValidator,
                                 ProfessionalSubServiceRepository professionalSubServiceRepository,
                                 ProfessionalCoverageService professionalCoverageService,
                                 SubServicePriceValidator subServicePriceValidator,
                                 SubServiceRepository subServiceRepository) {
        this.subServicePriceValidator = subServicePriceValidator;
        this.subServiceRepository = subServiceRepository;
        this.professionalRepository = professionalRepository;
        this.userRepository = userRepository;
        this.reviewAggregateRepository = reviewAggregateRepository;
        this.favoriteRepository = favoriteRepository;
        this.storageService = storageService;
        this.subServiceSelectionValidator = subServiceSelectionValidator;
        this.professionalSubServiceRepository = professionalSubServiceRepository;
        this.professionalCoverageService = professionalCoverageService;
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

        // MS4 §18: coverage and categories are editable here, not only at registration, and both
        // go through the same validators registration uses -- an unknown city, a city outside the
        // chosen region, a base city the professional does not serve, or an unknown category is a
        // 400 naming the field, never a constraint violation. `fieldPrefix` is "" because this
        // request body is flat, unlike registration's nested `professional` object.
        professionalCoverageService.replaceCoverage(professional, request.serviceRegionId(),
                request.serviceCityIds(), request.baseCityId(), "");
        professionalCoverageService.replaceCategories(professional.getId(), request.categoryIds(), "categoryIds");

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
        return getProfile(professionalId, caller, null);
    }

    /**
     * As above, plus <b>this professional's own price for one specific sub-service</b> when the
     * caller names one.
     *
     * <p>This is the customer-facing half of sub-service pricing: once a problem has been
     * classified to a concrete service, the price the customer is shown should be the professional's
     * price for <em>that</em> service rather than a generic figure covering their whole trade.
     * Looked up by primary key on the row that already models the relationship
     * ({@code professional_sub_services}), so there is nothing to reconcile and nothing to cache.
     *
     * <p><b>Nothing is invented when there is no match.</b> A professional who has not priced this
     * service, or who does not offer it at all, yields {@code subServicePrice = null}, and
     * {@code basePrice} is <em>not</em> substituted — see
     * {@link ProfessionalProfileResponse#subServicePrice} for why that substitution would be a
     * misquote rather than a fallback.
     *
     * @param subServiceId the classified service, or {@code null} to ask nothing about pricing.
     *                     An unknown id is not an error: it simply matches no row and answers
     *                     {@code null}, the same as a known id the professional does not offer.
     *                     Refusing it would leak which sub-service ids exist to an unauthenticated
     *                     caller, and this endpoint is reachable by guests.
     */
    @Transactional(readOnly = true)
    public ProfessionalProfileResponse getProfile(Long professionalId, AuthenticatedUser caller,
                                                    Long subServiceId) {
        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Professional " + professionalId + " not found."));
        User user = loadUser(professional.getUserId());

        // Deferred authentication: `caller` is null for a guest reading a listing card's profile.
        // `favorited` stays null in that case -- the same value a PROFESSIONAL caller already got,
        // and the honest one, since "have you favourited this person" has no answer for somebody
        // with no account rather than the answer "no".
        Boolean favorited = null;
        if (caller != null && UserRole.CUSTOMER.name().equals(caller.role())) {
            favorited = favoriteRepository.existsByCustomerIdAndProfessionalId(caller.id(), professionalId);
        }
        return toResponse(professional, user, favorited, caller == null ? null : caller.id(),
                subServiceId, exactSubServicePrice(professionalId, subServiceId));
    }

    /**
     * This professional's price for exactly this sub-service, or {@code null}.
     *
     * <p>One primary-key lookup on {@code professional_sub_services}. {@code null} covers three
     * genuinely different situations — the caller asked about nothing, the professional does not
     * offer this service, and the professional offers it but has not priced it — and collapsing them
     * is correct here precisely because the customer-facing answer is the same in all three: there
     * is no exact price to show. The {@code subServiceId} echoed alongside is what lets a client
     * distinguish the first from the other two when it matters.
     */
    private BigDecimal exactSubServicePrice(Long professionalId, Long subServiceId) {
        if (subServiceId == null) {
            return null;
        }
        return professionalSubServiceRepository
                .findById(new ProfessionalSubServiceId(professionalId, subServiceId))
                .map(ProfessionalSubService::getPrice)
                .orElse(null);
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

    /**
     * MS11 §3.2. PROFESSIONAL only. Now also returns each selection's price and Hebrew label — see
     * {@link MySubServicesResponse}, whose original ids-only field is unchanged alongside it.
     *
     * <p>Ordered by the catalogue's own {@code display_order} (category, then sub-service) rather
     * than by selection time or id, so the professional's own list reads in the same order as the
     * picker they chose it from. Two queries, both by primary key, for a set capped at 34 rows.
     */
    @Transactional(readOnly = true)
    public MySubServicesResponse getMySubServices(AuthenticatedUser caller) {
        Professional professional = resolveOwnProfessional(caller.id());
        return new MySubServicesResponse(
                readSelection(professional.getId()).stream().map(MySubServiceItem::subServiceId).toList(),
                readSelection(professional.getId()));
    }

    /**
     * The caller's selection joined to the catalogue, in catalogue display order.
     *
     * <p>A selected row whose {@code sub_services} row has vanished is skipped rather than rendered
     * with a null label. That cannot happen today — {@code sub_services} is schema-owned seed data
     * and {@code fk_professional_sub_services_sub_service} cascades — but rendering half a row to a
     * professional would be a confusing way to discover it if it ever did.
     */
    private List<MySubServiceItem> readSelection(Long professionalId) {
        List<ProfessionalSubService> selected =
                professionalSubServiceRepository.findByProfessionalId(professionalId);
        if (selected.isEmpty()) {
            return List.of();
        }
        Map<Long, SubService> catalogue = subServiceRepository
                .findAllById(selected.stream().map(ProfessionalSubService::getSubServiceId).toList())
                .stream()
                .collect(Collectors.toMap(SubService::getId, s -> s));

        return selected.stream()
                .filter(row -> catalogue.containsKey(row.getSubServiceId()))
                .sorted(Comparator
                        .comparing((ProfessionalSubService row) -> catalogue.get(row.getSubServiceId()).getCategoryId())
                        .thenComparing(row -> catalogue.get(row.getSubServiceId()).getDisplayOrder())
                        .thenComparing(ProfessionalSubService::getSubServiceId))
                .map(row -> {
                    SubService subService = catalogue.get(row.getSubServiceId());
                    return new MySubServiceItem(subService.getId(), subService.getCategoryId(),
                            subService.getCode(), subService.getNameHe(), row.getPrice());
                })
                .toList();
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

        // Neither field supplied is a malformed request, not an instruction to delete everything.
        // Erasing a professional's whole service list -- which also makes them ineligible for the
        // marketplace, per ProfessionalEligibility -- must be something they asked for explicitly.
        if (request.isEmptyPayload()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("subServices", "is required (send an empty array to clear "
                            + "your selection)")));
        }

        List<SubServicePriceSelection> selections = request.selections();
        // Shape of the money, before anything is written. Reports every malformed entry at once.
        subServicePriceValidator.validate(selections, "subServices", request.pricesAreAuthoritative());

        // Server-side dedupe -- defensive, a checkbox-driven UI can't produce duplicates but
        // the endpoint shouldn't rely on that. (The priced form rejects duplicates outright above,
        // because two prices for one service have no honest resolution; the id-only form keeps its
        // original silently-deduplicating behaviour, which was never ambiguous.)
        Map<Long, BigDecimal> requestedPrices = new LinkedHashMap<>();
        for (SubServicePriceSelection selection : selections) {
            requestedPrices.put(selection.subServiceId(), selection.price());
        }
        Set<Long> requestedIds = requestedPrices.keySet();

        // MS1: the existence/cross-category rule moved verbatim into SubServiceSelectionValidator
        // so registration enforces the identical one. Behavior and both error codes unchanged.
        // MS4: "the caller's category" is now "any of the caller's categories".
        subServiceSelectionValidator.validate(professionalCoverageService.categoryIds(professional.getId()),
                requestedIds, "subServiceIds");

        Map<Long, ProfessionalSubService> existing = professionalSubServiceRepository
                .findByProfessionalId(professional.getId())
                .stream()
                .collect(Collectors.toMap(ProfessionalSubService::getSubServiceId, row -> row));

        Set<Long> toRemove = new HashSet<>(existing.keySet());
        toRemove.removeAll(requestedIds);
        toRemove.forEach(id -> professionalSubServiceRepository
                .deleteById(new ProfessionalSubServiceId(professional.getId(), id)));

        for (Long subServiceId : requestedIds) {
            BigDecimal price = requestedPrices.get(subServiceId);
            ProfessionalSubService current = existing.get(subServiceId);
            if (current == null) {
                professionalSubServiceRepository
                        .save(new ProfessionalSubService(professional.getId(), subServiceId, price));
                continue;
            }
            // Still selected. Diff-based on purpose (not delete-all-then-reinsert), so created_at
            // survives an edit and a long-standing service does not look newly added.
            //
            // An id-only payload cannot express prices, so it must not be read as "clear them all":
            // an older client saving the profile screen would otherwise wipe pricing it has no
            // knowledge of. Its prices are ignored; the priced form's are authoritative, including
            // an explicit null, which is how a professional withdraws a price.
            if (request.pricesAreAuthoritative()
                    && !Objects.equals(normalizeAmount(current.getPrice()), normalizeAmount(price))) {
                current.reprice(price);
                professionalSubServiceRepository.save(current);
            }
        }

        return getMySubServices(caller);
    }

    /**
     * Compares prices by value, not by representation: {@code 420} and {@code 420.00} are the same
     * price, and {@link BigDecimal#equals} says they are not. Without this, re-saving an unchanged
     * form would bump {@code updated_at} on every row every time — turning the price-edit timestamp
     * into a record of when the professional last opened the screen.
     */
    private static BigDecimal normalizeAmount(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros();
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
        // The self-views ask about no particular service, so both pricing fields are absent -- which
        // is exactly what a null subServiceId means on this response.
        return toResponse(professional, user, favorited, callerId, null, null);
    }

    private ProfessionalProfileResponse toResponse(Professional professional, User user, Boolean favorited,
                                                     Long callerId, Long subServiceId,
                                                     BigDecimal subServicePrice) {
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

        ProfessionalCoverageService.CoverageView coverage = professionalCoverageService.load(professional);

        return new ProfessionalProfileResponse(professional.getId(), coverage.categoryIds(),
                user.getFullName(), coverage.serviceRegionId(), coverage.serviceRegionNameHe(),
                coverage.baseCityId(), coverage.baseCityNameHe(), coverage.serviceCityIds(),
                coverage.serviceCityNamesHe(), professional.getBio(),
                professional.getBasePrice(), profileImageUrl, averageRating, reviewCount,
                approvalStatus, bookable, favorited, subServiceId, subServicePrice,
                professional.getCreatedAt(), professional.getUpdatedAt());
    }
}
