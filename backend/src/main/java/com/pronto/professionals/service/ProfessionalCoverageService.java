package com.pronto.professionals.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.locations.entity.ServiceCity;
import com.pronto.locations.entity.ServiceRegion;
import com.pronto.locations.repository.ServiceCityRepository;
import com.pronto.locations.repository.ServiceRegionRepository;
import com.pronto.locations.service.ServiceCoverageValidator;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.entity.ProfessionalCategory;
import com.pronto.professionals.entity.ProfessionalCategoryId;
import com.pronto.professionals.entity.ProfessionalServiceCity;
import com.pronto.professionals.entity.ProfessionalServiceCityId;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.professionals.repository.ProfessionalCategoryRepository;
import com.pronto.professionals.repository.ProfessionalServiceCityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The one place that reads and writes a professional's categories and service coverage.</b>
 *
 * <p>MS4 split three columns off {@code professionals} into two join tables and two catalogue
 * FKs. Six different services need to read some part of that back — the profile endpoints, the
 * favourites summary, the operator review screen, {@code GET /api/users/me}, the SOS candidate
 * assembler and the booking listing — and two need to write it (registration and the profile
 * edit). Left to themselves, each would grow its own two-repository join and its own idea of
 * what order to render the categories in.
 *
 * <p>So the join lives here, once, and everything else asks. Two consequences worth naming:
 *
 * <ul>
 *   <li><b>Ordering is a property of the catalogue, not of the caller.</b> Categories come back
 *       in {@code categories.display_order} and cities in {@code service_cities.display_order},
 *       which is what lets every surface treat "the first one" as the primary without anybody
 *       storing a primary flag. See {@link ProfessionalCategory}.</li>
 *   <li><b>Writes are diff-based, never delete-all-then-reinsert</b> — the same semantics
 *       {@code ProfessionalsService#updateMySubServices} already established, so
 *       {@code created_at} survives an edit that happens to keep a row.</li>
 * </ul>
 *
 * <p>Validation is delegated, not re-implemented: coverage to
 * {@code locations.service.ServiceCoverageValidator}, categories to {@link #validateCategories}
 * below. Registration and the profile edit therefore enforce byte-identical rules.
 */
@Service
public class ProfessionalCoverageService {

    private final ProfessionalCategoryRepository professionalCategoryRepository;
    private final ProfessionalServiceCityRepository professionalServiceCityRepository;
    private final ServiceRegionRepository serviceRegionRepository;
    private final ServiceCityRepository serviceCityRepository;
    private final CategoryRepository categoryRepository;
    private final ServiceCoverageValidator serviceCoverageValidator;

    public ProfessionalCoverageService(ProfessionalCategoryRepository professionalCategoryRepository,
                                        ProfessionalServiceCityRepository professionalServiceCityRepository,
                                        ServiceRegionRepository serviceRegionRepository,
                                        ServiceCityRepository serviceCityRepository,
                                        CategoryRepository categoryRepository,
                                        ServiceCoverageValidator serviceCoverageValidator) {
        this.professionalCategoryRepository = professionalCategoryRepository;
        this.professionalServiceCityRepository = professionalServiceCityRepository;
        this.serviceRegionRepository = serviceRegionRepository;
        this.serviceCityRepository = serviceCityRepository;
        this.categoryRepository = categoryRepository;
        this.serviceCoverageValidator = serviceCoverageValidator;
    }

    /**
     * Everything a display surface needs about one professional's trades and reach, resolved to
     * both ids (what a client persists) and Hebrew labels (what it shows).
     *
     * <p>Every field is nullable/empty-tolerant on purpose: {@code V44} leaves
     * {@code serviceRegionId}/{@code baseCityId} null on pre-MS4 rows it could not canonicalise,
     * and a UI that renders "לא הוגדר" for those is telling the truth. Inventing a place would
     * not be.
     */
    public record CoverageView(
            Long serviceRegionId,
            String serviceRegionNameHe,
            Long baseCityId,
            String baseCityNameHe,
            List<Long> serviceCityIds,
            List<String> serviceCityNamesHe,
            List<Long> categoryIds
    ) {
    }

    /** {@link CoverageView} for one professional. */
    @Transactional(readOnly = true)
    public CoverageView load(Professional professional) {
        List<Long> cityIds = professionalServiceCityRepository
                .findCityIdsInDisplayOrder(professional.getId());
        Map<Long, ServiceCity> cities = new HashMap<>();
        serviceCityRepository.findAllById(cityIds).forEach(city -> cities.put(city.getId(), city));

        List<String> cityNames = cityIds.stream()
                .map(cities::get)
                .filter(java.util.Objects::nonNull)
                .map(ServiceCity::getNameHe)
                .toList();

        String regionName = professional.getServiceRegionId() == null ? null
                : serviceRegionRepository.findById(professional.getServiceRegionId())
                        .map(ServiceRegion::getNameHe).orElse(null);
        String baseCityName = baseCityName(professional);

        return new CoverageView(professional.getServiceRegionId(), regionName,
                professional.getBaseCityId(), baseCityName, cityIds, cityNames,
                categoryIds(professional.getId()));
    }

    /**
     * The city {@code matching.DistanceEtaStrategy} measures travel from — {@code null} for a
     * professional {@code V44} could not place, which that strategy already handles (an unknown
     * city is simply not the customer's city, so the different-city estimate applies).
     */
    @Transactional(readOnly = true)
    public String baseCityName(Professional professional) {
        if (professional.getBaseCityId() == null) {
            return null;
        }
        return serviceCityRepository.findById(professional.getBaseCityId())
                .map(ServiceCity::getNameHe).orElse(null);
    }

    /** A professional's categories, in catalogue display order. */
    @Transactional(readOnly = true)
    public List<Long> categoryIds(Long professionalId) {
        return professionalCategoryRepository.findCategoryIdsInDisplayOrder(professionalId);
    }

    /**
     * The same lookup for a whole listing page in one query — see
     * {@code bookings.dto.ProfessionalCard} for why the listing cannot get this from its own
     * {@code SELECT NEW} projection.
     *
     * @return every requested id is present as a key, mapping to an empty list if the
     *         professional has no categories at all, so callers never have to null-check
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> categoryIdsByProfessional(List<Long> professionalIds) {
        Map<Long, List<Long>> byProfessional = new HashMap<>();
        professionalIds.forEach(id -> byProfessional.put(id, new ArrayList<>()));
        if (professionalIds.isEmpty()) {
            return byProfessional;
        }
        for (Object[] row : professionalCategoryRepository.findCategoryIdsInDisplayOrder(professionalIds)) {
            byProfessional.computeIfAbsent((Long) row[0], key -> new ArrayList<>()).add((Long) row[1]);
        }
        return byProfessional;
    }

    /**
     * "Does this professional serve that category?" — the Java-side single-row form of
     * {@link com.pronto.professionals.ProfessionalCategoryMatch#SERVES_CATEGORY_JPQL}, for the
     * two {@code bookings} guards that hold a loaded {@code Professional} rather than a query.
     */
    @Transactional(readOnly = true)
    public boolean servesCategory(Long professionalId, Long categoryId) {
        return professionalCategoryRepository
                .existsById(new ProfessionalCategoryId(professionalId, categoryId));
    }

    // ------------------------------------------------------------------ writes

    /**
     * Validates and applies a complete coverage selection: region and base city are set on
     * {@code professional} (the caller saves it, inside its own transaction), and
     * {@code professional_service_cities} is diffed to match {@code cityIds}.
     *
     * @param fieldPrefix passed through to {@code ServiceCoverageValidator} so the reported field
     *                    path matches the request body the client actually sent
     */
    @Transactional
    public void replaceCoverage(Professional professional, Long serviceRegionId, Collection<Long> cityIds,
                                 Long baseCityId, String fieldPrefix) {
        List<Long> validated = serviceCoverageValidator.validate(serviceRegionId, cityIds, baseCityId, fieldPrefix);

        professional.setServiceRegionId(serviceRegionId);
        professional.setBaseCityId(baseCityId);

        Set<Long> desired = new LinkedHashSet<>(validated);
        Set<Long> existing = new HashSet<>(
                professionalServiceCityRepository.findByProfessionalId(professional.getId()).stream()
                        .map(ProfessionalServiceCity::getCityId)
                        .toList());

        existing.stream()
                .filter(id -> !desired.contains(id))
                .forEach(id -> professionalServiceCityRepository
                        .deleteById(new ProfessionalServiceCityId(professional.getId(), id)));
        desired.stream()
                .filter(id -> !existing.contains(id))
                .forEach(id -> professionalServiceCityRepository
                        .save(new ProfessionalServiceCity(professional.getId(), id)));
    }

    /**
     * Validates and applies a complete category selection, diff-based.
     *
     * <p>Removing a category the professional still has sub-services under is deliberately
     * <em>allowed</em> and deliberately <em>not</em> cascaded: the orphaned
     * {@code professional_sub_services} rows stop counting towards
     * {@code ProfessionalEligibility} (which requires a sub-service under a category the
     * professional actually holds), so the effect is visible where it matters, and re-adding the
     * category restores the selection the professional made rather than silently discarding it.
     * Callers that want the stricter behaviour should re-validate sub-services afterwards; the
     * profile editor does exactly that by re-saving the checklist.
     */
    @Transactional
    public void replaceCategories(Long professionalId, Collection<Long> categoryIds, String fieldPath) {
        Set<Long> desired = validateCategories(categoryIds, fieldPath);

        Set<Long> existing = new HashSet<>(professionalCategoryRepository.findByProfessionalId(professionalId)
                .stream()
                .map(ProfessionalCategory::getCategoryId)
                .toList());

        existing.stream()
                .filter(id -> !desired.contains(id))
                .forEach(id -> professionalCategoryRepository
                        .deleteById(new ProfessionalCategoryId(professionalId, id)));
        desired.stream()
                .filter(id -> !existing.contains(id))
                .forEach(id -> professionalCategoryRepository
                        .save(new ProfessionalCategory(professionalId, id)));
    }

    /**
     * "At least one category, every one of them a real one." Shared by registration and the
     * profile edit — an unknown id is a {@code 400 VALIDATION_ERROR} naming the field, never a
     * foreign-key violation surfacing as a {@code 500}.
     *
     * @return the requested ids, de-duplicated
     */
    public Set<Long> validateCategories(Collection<Long> categoryIds, String fieldPath) {
        Set<Long> requested = new LinkedHashSet<>(categoryIds == null ? List.of() : categoryIds);
        List<FieldError> errors = new ArrayList<>();

        if (requested.remove(null)) {
            errors.add(new FieldError(fieldPath, "must not contain a null category id"));
        }
        if (requested.isEmpty()) {
            errors.add(new FieldError(fieldPath, "at least one service category is required"));
        }

        Set<Long> known = new HashSet<>(categoryRepository.findAllById(requested).stream()
                .map(com.pronto.professionals.entity.Category::getId)
                .toList());
        requested.stream()
                .filter(id -> !known.contains(id))
                .forEach(id -> errors.add(new FieldError(fieldPath, "unknown category id " + id)));

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.", errors);
        }
        return requested;
    }
}
