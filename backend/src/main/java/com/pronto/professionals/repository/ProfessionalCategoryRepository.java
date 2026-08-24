package com.pronto.professionals.repository;

import com.pronto.professionals.entity.ProfessionalCategory;
import com.pronto.professionals.entity.ProfessionalCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProfessionalCategoryRepository
        extends JpaRepository<ProfessionalCategory, ProfessionalCategoryId> {

    List<ProfessionalCategory> findByProfessionalId(Long professionalId);

    /**
     * A professional's categories in the service catalogue's own display order — the order every
     * UI surface renders them in, so "primary category" means the same thing on the card, in the
     * profile modal and on the dashboard without any of them storing a primary flag.
     */
    @Query("SELECT pc.categoryId FROM ProfessionalCategory pc, Category c "
            + "WHERE c.id = pc.categoryId AND pc.professionalId = :professionalId "
            + "ORDER BY c.displayOrder ASC, c.id ASC")
    List<Long> findCategoryIdsInDisplayOrder(@Param("professionalId") Long professionalId);

    /**
     * The same ordered lookup for a batch of professionals, so a listing of N cards costs one
     * query instead of N. Returns {@code [professionalId, categoryId]} pairs, ordered so the
     * caller can group them without re-sorting.
     */
    @Query("SELECT pc.professionalId, pc.categoryId FROM ProfessionalCategory pc, Category c "
            + "WHERE c.id = pc.categoryId AND pc.professionalId IN :professionalIds "
            + "ORDER BY pc.professionalId ASC, c.displayOrder ASC, c.id ASC")
    List<Object[]> findCategoryIdsInDisplayOrder(@Param("professionalIds") List<Long> professionalIds);
}
