package com.pronto.professionals.repository;

import com.pronto.professionals.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * {@code GET /api/categories} (MS11, {@code professionals.controller.CategoriesController}).
     * See {@code docs/architecture/product-ms11-sub-services-design.md} §3.1.
     */
    List<Category> findAllByOrderByDisplayOrderAsc();
}
