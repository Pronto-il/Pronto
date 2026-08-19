package com.pronto.professionals.controller;

import com.pronto.professionals.dto.CategoryWithSubServicesResponse;
import com.pronto.professionals.dto.SubServiceResponse;
import com.pronto.professionals.entity.Category;
import com.pronto.professionals.entity.SubService;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.professionals.repository.SubServiceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code GET /api/categories} -- new, public/unauthenticated (MS11, Services &amp;
 * Sub-services). The concrete mechanism satisfying the brief's "support future
 * service/sub-service changes without hardcoding the entire structure into the UI"
 * requirement. See {@code docs/architecture/product-ms11-sub-services-design.md} §3.1.
 *
 * <p>Kept in the {@code professionals} package rather than a new dedicated package,
 * following the existing, already-accepted precedent that {@code Category}/{@code
 * CategoryRepository} live here and are consumed cross-package by {@code issues} directly.
 * One query per table, joined against each other in memory -- 8 categories x ~4
 * sub-services each is trivial data volume, no need for a JPA {@code @OneToMany} graph.
 */
@RestController
@RequestMapping("/api/categories")
public class CategoriesController {

    private final CategoryRepository categoryRepository;
    private final SubServiceRepository subServiceRepository;

    public CategoriesController(CategoryRepository categoryRepository, SubServiceRepository subServiceRepository) {
        this.categoryRepository = categoryRepository;
        this.subServiceRepository = subServiceRepository;
    }

    @GetMapping
    public ResponseEntity<List<CategoryWithSubServicesResponse>> getCategories() {
        List<Category> categories = categoryRepository.findAllByOrderByDisplayOrderAsc();
        List<SubService> subServices = subServiceRepository.findAllByOrderByCategoryIdAscDisplayOrderAsc();

        // Map order is irrelevant -- the response is built by iterating the already-ordered
        // `categories` list below and looking each category's sub-services up by id.
        Map<Long, List<SubServiceResponse>> subServicesByCategory = subServices.stream()
                .collect(Collectors.groupingBy(SubService::getCategoryId,
                        Collectors.mapping(CategoriesController::toSubServiceResponse, Collectors.toList())));

        List<CategoryWithSubServicesResponse> response = categories.stream()
                .map(category -> new CategoryWithSubServicesResponse(category.getId(), category.getCode(),
                        category.getNameHe(), category.getNameEn(), category.getDisplayOrder(),
                        subServicesByCategory.getOrDefault(category.getId(), List.of())))
                .toList();

        return ResponseEntity.ok(response);
    }

    private static SubServiceResponse toSubServiceResponse(SubService subService) {
        return new SubServiceResponse(subService.getId(), subService.getCode(), subService.getNameHe(),
                subService.getNameEn(), subService.getDisplayOrder());
    }
}
