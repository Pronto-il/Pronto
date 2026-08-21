package com.pronto.ai;

import com.pronto.professionals.entity.Category;
import com.pronto.professionals.repository.CategoryRepository;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The seven seeded categories as test doubles, matching {@code V10__seed_categories.sql} after
 * {@code V31__replace_carpentry_with_handyman.sql}.
 *
 * <p>{@code Category} is a read-only reference entity with no setters (only Flyway ever
 * writes those rows), so tests mock it — the same approach the pre-existing AI tests already
 * used, just centralised here so every test in this package agrees on one taxonomy instead of
 * each inventing its own subset.
 */
public final class TestCategories {

    /** code -> id, in display order, ids matching the seed data. */
    public static final Map<String, Long> IDS_BY_CODE = idsByCode();

    private TestCategories() {
    }

    private static Map<String, Long> idsByCode() {
        Map<String, Long> ids = new LinkedHashMap<>();
        ids.put("plumbing", 1L);
        ids.put("electrical", 2L);
        ids.put("ac_hvac", 3L);
        ids.put("appliance_repair", 4L);
        ids.put("locksmith", 5L);
        ids.put("painting", 7L);
        ids.put("general_handyman", 8L);
        return Map.copyOf(ids);
    }

    private static final Map<String, String> NAMES_HE = Map.of(
            "plumbing", "אינסטלציה",
            "electrical", "חשמל",
            "ac_hvac", "מיזוג אוויר",
            "appliance_repair", "תיקון מוצרי חשמל",
            "locksmith", "מנעולן",
            "painting", "צביעה",
            "general_handyman", "הנדימן");

    private static final Map<String, String> NAMES_EN = Map.of(
            "plumbing", "Plumbing",
            "electrical", "Electrical",
            "ac_hvac", "AC / HVAC",
            "appliance_repair", "Appliance Repair",
            "locksmith", "Locksmith",
            "painting", "Painting",
            "general_handyman", "Handyman");

    public static List<Category> all() {
        List<Category> categories = new java.util.ArrayList<>();
        short displayOrder = 1;
        for (Map.Entry<String, Long> entry : idsByCode().entrySet()) {
            categories.add(category(entry.getValue(), entry.getKey(), displayOrder++));
        }
        return List.copyOf(categories);
    }

    public static Category category(Long id, String code, short displayOrder) {
        Category category = Mockito.mock(Category.class);
        lenient().when(category.getId()).thenReturn(id);
        lenient().when(category.getCode()).thenReturn(code);
        lenient().when(category.getNameHe()).thenReturn(NAMES_HE.getOrDefault(code, code));
        lenient().when(category.getNameEn()).thenReturn(NAMES_EN.getOrDefault(code, code));
        lenient().when(category.getDisplayOrder()).thenReturn(displayOrder);
        return category;
    }

    /** A {@link CategoryRepository} stub backed by {@link #all()}. */
    public static CategoryRepository repository() {
        CategoryRepository repository = Mockito.mock(CategoryRepository.class);
        List<Category> categories = all();
        when(repository.findAll()).thenReturn(categories);
        lenient().when(repository.existsById(anyLong())).thenReturn(true);
        return repository;
    }
}
