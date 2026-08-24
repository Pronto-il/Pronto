package com.pronto.locations.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.locations.entity.ServiceCity;
import com.pronto.locations.repository.ServiceCityRepository;
import com.pronto.locations.repository.ServiceRegionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "This region exists, these cities exist, they all sit inside that region, and the base city is
 * one of them."
 *
 * <p>The single enforcement point for MS4 Part A, shared verbatim by professional registration
 * ({@code auth.service.AuthService}) and by the later self-service profile edit
 * ({@code professionals.service.ProfessionalsService}) — written once here for exactly the reason
 * {@code professionals.service.SubServiceSelectionValidator} was extracted in MS1: two copies of
 * a controlled-vocabulary rule is how a backend ends up enforcing it on one surface and not the
 * other, and the un-enforced surface is always the one that lets a junk value reach the database.
 *
 * <p>The database is the backstop, not the rule: {@code fk_professionals_service_region},
 * {@code fk_professional_service_cities_city} and {@code ux_service_cities_name_he} already make
 * an uncontrolled city physically unstorable. This class exists so the caller gets a {@code 400}
 * naming the offending field instead of a constraint-violation {@code 500}, and so the
 * cross-field rules the schema cannot express (city-inside-region, base-city-inside-selection)
 * are checked at all.
 *
 * <p>Its own {@code @Service} rather than a method on a domain service, for the same reason
 * {@code SubServiceSelectionValidator} is: registration must be able to depend on it without
 * dragging in storage, favorites and review-aggregate collaborators it has no business knowing
 * about.
 */
@Service
public class ServiceCoverageValidator {

    private final ServiceRegionRepository serviceRegionRepository;
    private final ServiceCityRepository serviceCityRepository;

    public ServiceCoverageValidator(ServiceRegionRepository serviceRegionRepository,
                                     ServiceCityRepository serviceCityRepository) {
        this.serviceRegionRepository = serviceRegionRepository;
        this.serviceCityRepository = serviceCityRepository;
    }

    /**
     * Validates a complete service-coverage selection and returns it de-duplicated, in the
     * catalogue's own display order.
     *
     * @param regionId      the chosen region; must exist
     * @param cityIds       at least one city, every one of them inside {@code regionId}
     * @param baseCityId    where the professional is based; must be one of {@code cityIds}, so the
     *                      city {@code matching.ApproximateDistanceEtaStrategy} measures from is
     *                      always a city they actually serve
     * @param fieldPrefix   {@code ""} for a flat request body, {@code "professional."} for
     *                      registration's nested payload — so the reported field path matches the
     *                      JSON the client actually sent
     * @return the validated city ids, ordered by {@code service_cities.display_order}
     * @throws ApiException {@code 400 VALIDATION_ERROR} with a field error naming what was wrong
     */
    public List<Long> validate(Long regionId, Collection<Long> cityIds, Long baseCityId, String fieldPrefix) {
        List<FieldError> errors = new ArrayList<>();

        if (regionId == null) {
            errors.add(new FieldError(fieldPrefix + "serviceRegionId", "is required"));
        } else if (!serviceRegionRepository.existsById(regionId)) {
            errors.add(new FieldError(fieldPrefix + "serviceRegionId",
                    "must reference a supported service region"));
        }

        Set<Long> requested = new LinkedHashSet<>(cityIds == null ? List.of() : cityIds);
        if (requested.contains(null)) {
            errors.add(new FieldError(fieldPrefix + "serviceCityIds", "must not contain a null city id"));
            requested.remove(null);
        }
        if (requested.isEmpty()) {
            errors.add(new FieldError(fieldPrefix + "serviceCityIds", "at least one service city is required"));
        }

        // One lookup for the whole selection. Unknown ids simply do not come back, which is how
        // "does it exist" and "which region is it in" are answered by the same query.
        Map<Long, ServiceCity> byId = serviceCityRepository.findAllById(requested).stream()
                .collect(Collectors.toMap(ServiceCity::getId, Function.identity()));

        for (Long cityId : requested) {
            ServiceCity city = byId.get(cityId);
            if (city == null) {
                errors.add(new FieldError(fieldPrefix + "serviceCityIds",
                        "unknown service city id " + cityId));
            } else if (regionId != null && !city.getRegionId().equals(regionId)) {
                errors.add(new FieldError(fieldPrefix + "serviceCityIds",
                        "city " + cityId + " does not belong to the selected service region"));
            }
        }

        if (baseCityId == null) {
            errors.add(new FieldError(fieldPrefix + "baseCityId", "is required"));
        } else if (!requested.contains(baseCityId)) {
            errors.add(new FieldError(fieldPrefix + "baseCityId",
                    "must be one of the selected service cities"));
        }

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.", errors);
        }

        // Catalogue order, not request order: the professional's own city list then renders the
        // same way everywhere it is shown, regardless of the order the checkboxes were ticked in.
        return requested.stream()
                .sorted(java.util.Comparator.comparing((Long id) -> byId.get(id).getDisplayOrder())
                        .thenComparing(id -> id))
                .toList();
    }
}
