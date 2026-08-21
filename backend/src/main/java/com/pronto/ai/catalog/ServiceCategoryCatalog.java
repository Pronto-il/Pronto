package com.pronto.ai.catalog;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.professionals.entity.Category;
import com.pronto.professionals.repository.CategoryRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The single source of truth for "which categories may a classification resolve to".
 *
 * <p>Reads the real {@code categories} table (via the existing
 * {@code professionals.repository.CategoryRepository} — no duplicated taxonomy, no hardcoded
 * enum) and joins each row onto its authored {@link CategoryRoutingProfile}. Everything that
 * needs a category — prompt building, structured-output schema enums, AI response validation,
 * the controlled fallback — goes through here, so a category added to the database
 * automatically participates in routing.
 *
 * <p>Category codes coming back from OpenAI are matched case-insensitively and trimmed
 * ({@link #findByCode}); anything that does not match a real row is rejected by the caller,
 * never coerced.
 */
@Component
public class ServiceCategoryCatalog {

    /**
     * Pronto's controlled fallback. Deliberately a real seeded category rather than a
     * synthetic "OTHER" — it is a category a professional can actually be registered under,
     * and the customer can still override it on the review screen.
     */
    public static final String FALLBACK_CATEGORY_CODE = CategoryRoutingProfiles.CODE_GENERAL_HANDYMAN;

    private final CategoryRepository categoryRepository;

    public ServiceCategoryCatalog(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Every routable category, in {@code display_order}.
     *
     * @throws ApiException {@code AI_SERVICE_ERROR} when the table is empty — classification
     *                      is impossible without a taxonomy, and silently proceeding would
     *                      produce a schema with an empty enum.
     */
    public List<ServiceCategory> categories() {
        List<ServiceCategory> categories = categoryRepository.findAll().stream()
                .sorted(Comparator.comparingInt(Category::getDisplayOrder))
                .map(row -> new ServiceCategory(row.getId(), row.getCode(), row.getNameHe(), row.getNameEn(),
                        CategoryRoutingProfiles.find(row.getCode())))
                .toList();

        if (categories.isEmpty()) {
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, "No service categories are configured.");
        }
        return categories;
    }

    /** Category codes only, same order as {@link #categories()} — the schema enum's contents. */
    public List<String> codes() {
        return categories().stream().map(ServiceCategory::code).toList();
    }

    /** Case-insensitive, whitespace-tolerant lookup. Empty when the code is not a real category. */
    public Optional<ServiceCategory> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return categories().stream()
                .filter(category -> category.code().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }

    /** Same as {@link #findByCode} but against an already-loaded list — avoids re-querying in loops. */
    public static Optional<ServiceCategory> findByCode(List<ServiceCategory> categories, String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return categories.stream()
                .filter(category -> category.code().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }

    public Optional<ServiceCategory> findById(Long categoryId) {
        if (categoryId == null) {
            return Optional.empty();
        }
        return categories().stream().filter(category -> categoryId.equals(category.id())).findFirst();
    }

    /**
     * The controlled fallback row. Throws {@link IllegalStateException} rather than inventing
     * a category if the seeded fallback is somehow missing — that is a broken deployment, not
     * a runtime condition to paper over.
     */
    public ServiceCategory fallback() {
        return findByCode(FALLBACK_CATEGORY_CODE)
                .orElseThrow(() -> new IllegalStateException("Seeded category '" + FALLBACK_CATEGORY_CODE
                        + "' is missing from the categories table."));
    }
}
