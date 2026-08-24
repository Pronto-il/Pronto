package com.pronto.professionals.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.SubService;
import com.pronto.professionals.repository.SubServiceRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "Every one of these sub-service ids exists, and belongs to one of those categories" — extracted
 * in MS1 so registration and the later self-service edit enforce the identical rule.
 *
 * <p><b>MS4</b> turns "that category" into "one of those categories": a professional now holds a
 * set. The rule is otherwise untouched, and for a single-category professional the outcome is
 * bit-for-bit what it was — the set is a singleton and the membership test reduces to the
 * equality check it replaced.
 *
 * <p>The rule and both its error codes are unchanged from where it was written inline in
 * {@code ProfessionalsService#updateMySubServices}: an unknown id is a
 * {@code 400 VALIDATION_ERROR} with a field error, a cross-category id is a
 * {@code 400 CATEGORY_MISMATCH}. It moved here rather than being copied into
 * {@code auth.service.AuthService} because MS1 makes sub-services a registration requirement
 * (D4/D7), and two copies of a cross-tenant-ish authorization rule is exactly how the backend
 * ends up enforcing it in one place and not the other.
 *
 * <p>Its own {@code @Service} rather than a public method on {@code ProfessionalsService}: that
 * class carries storage, favorites and review-aggregate collaborators that registration has no
 * business depending on.
 */
@Service
public class SubServiceSelectionValidator {

    private final SubServiceRepository subServiceRepository;

    public SubServiceSelectionValidator(SubServiceRepository subServiceRepository) {
        this.subServiceRepository = subServiceRepository;
    }

    /**
     * @param categoryIds the professional's own categories — a sub-service under any one of them
     *                    is legal
     * @param fieldPath the field name to report an unknown id against — {@code "subServiceIds"}
     *                  for the edit endpoint's body, {@code "professional.subServiceIds"} for
     *                  registration's nested payload
     * @throws ApiException {@code 400 VALIDATION_ERROR} for an id no {@code sub_services} row
     *         has; {@code 400 CATEGORY_MISMATCH} for an id belonging to a category the
     *         professional does not serve
     */
    public void validate(Collection<Long> categoryIds, Collection<Long> subServiceIds, String fieldPath) {
        if (subServiceIds.isEmpty()) {
            return;
        }
        Set<Long> ownCategoryIds = new HashSet<>(categoryIds);
        Map<Long, SubService> subServicesById = subServiceRepository.findAllById(subServiceIds).stream()
                .collect(Collectors.toMap(SubService::getId, s -> s));

        for (Long id : subServiceIds) {
            SubService subService = subServicesById.get(id);
            if (subService == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                        List.of(new FieldError(fieldPath, "unknown sub-service id " + id)));
            }
            if (!ownCategoryIds.contains(subService.getCategoryId())) {
                throw new ApiException(ErrorCode.CATEGORY_MISMATCH,
                        "Sub-service " + id + " does not belong to any of the caller's own categories.");
            }
        }
    }
}
