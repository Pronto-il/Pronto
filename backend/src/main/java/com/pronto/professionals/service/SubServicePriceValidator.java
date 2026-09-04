package com.pronto.professionals.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.dto.SubServicePriceSelection;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Are these sub-service prices well-formed?" — extracted for the same reason
 * {@link SubServiceSelectionValidator} was: registration and the later self-service edit must
 * enforce the identical rule, and two copies of a validation rule is how a backend ends up
 * enforcing it on one path and not the other.
 *
 * <p>Deliberately separate from {@link SubServiceSelectionValidator} rather than folded into it.
 * That one answers "does this sub-service exist and may this professional offer it?", which is an
 * authorization-shaped question about the taxonomy; this answers "is this number a price?", which is
 * a shape question about a value. They fail with different error codes ({@code CATEGORY_MISMATCH} vs
 * {@code VALIDATION_ERROR}), they are wanted independently, and the id-only request form needs the
 * first without the second.
 *
 * <p><b>Every problem is reported, not just the first.</b> A professional pricing eight
 * sub-services who typed two of them wrong should be told about both at once — one round trip, one
 * corrected form. The field paths are indexed ({@code subServices[3].price}) so the client can put
 * each message on the input it belongs to.
 */
@Service
public class SubServicePriceValidator {

    /**
     * The maximum scale a money value may carry. Two decimal places is what
     * {@code professional_sub_services.price} stores ({@code NUMERIC(10,2)}), so a third would be
     * silently rounded on write — the professional would see a different number afterwards than the
     * one they typed, which is worse than being told to fix it.
     */
    private static final int MAX_PRICE_SCALE = 2;

    /**
     * Fat-finger ceiling, matching {@code ck_professional_sub_services_price}. Not a business rule
     * about what a trade may charge: it exists so that a mistyped {@code 42000000} is refused with a
     * message instead of being stored and shown to customers.
     */
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000");

    /**
     * Validates a selection that came from the <b>id-only</b> request form, where a repeated id is
     * unambiguous and has always been silently deduplicated.
     *
     * @see #validate(List, String, boolean)
     */
    public void validate(List<SubServicePriceSelection> selections, String fieldPrefix) {
        validate(selections, fieldPrefix, false);
    }

    /**
     * @param selections the requested selection, in the client's own order — the index in a field
     *                   path refers to this list, so it must be the list the client sent
     * @param fieldPrefix {@code "subServices"} for the edit endpoint's body,
     *                    {@code "professional.subServices"} for registration's nested payload —
     *                    same convention {@link SubServiceSelectionValidator} uses
     * @param rejectDuplicateIds whether a repeated {@code subServiceId} is an error.
     *
     *                   <p><b>{@code true} only for the priced request form</b>, and the asymmetry
     *                   is deliberate rather than an oversight. Two entries for the same
     *                   sub-service carrying two different prices have no honest resolution — any
     *                   rule for picking one silently stores a number the professional did not mean
     *                   — so the request is refused. In the id-only form there is nothing to
     *                   conflict: a repeated id says the same thing twice, and this codebase has
     *                   deduplicated it silently since registration first accepted sub-services.
     *                   Making that an error here would break existing clients over a payload whose
     *                   meaning was never in doubt.
     * @throws ApiException {@code 400 VALIDATION_ERROR} listing every malformed entry
     */
    public void validate(List<SubServicePriceSelection> selections, String fieldPrefix,
                          boolean rejectDuplicateIds) {
        List<FieldError> errors = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

        for (int i = 0; i < selections.size(); i++) {
            SubServicePriceSelection selection = selections.get(i);
            String path = fieldPrefix + "[" + i + "]";

            if (selection == null) {
                errors.add(new FieldError(path, "must not be null"));
                continue;
            }
            if (selection.subServiceId() == null) {
                errors.add(new FieldError(path + ".subServiceId", "is required"));
            } else if (!seenIds.add(selection.subServiceId()) && rejectDuplicateIds) {
                // A checkbox UI cannot produce this, but the endpoint must not rely on that: two
                // entries for one sub-service carry two prices and there is no honest way to pick
                // one. Refusing is the only answer that cannot silently store the wrong number.
                errors.add(new FieldError(path + ".subServiceId",
                        "is listed more than once (" + selection.subServiceId() + ")"));
            }

            BigDecimal price = selection.price();
            if (price == null) {
                continue;   // "not priced" is a legal state -- see SubServicePriceSelection
            }
            if (price.signum() < 0) {
                errors.add(new FieldError(path + ".price", "must not be negative"));
            } else if (price.compareTo(MAX_PRICE) > 0) {
                errors.add(new FieldError(path + ".price", "must not exceed " + MAX_PRICE.toPlainString()));
            }
            if (price.scale() > MAX_PRICE_SCALE) {
                errors.add(new FieldError(path + ".price", "must have at most 2 decimal places"));
            }
        }

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.", errors);
        }
    }
}
