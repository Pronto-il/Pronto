package com.pronto.professionals.repository;

import com.pronto.professionals.entity.ProfessionalServiceCity;
import com.pronto.professionals.entity.ProfessionalServiceCityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProfessionalServiceCityRepository
        extends JpaRepository<ProfessionalServiceCity, ProfessionalServiceCityId> {

    List<ProfessionalServiceCity> findByProfessionalId(Long professionalId);

    /** A professional's service cities in catalogue order, ready to display. */
    @Query("SELECT psc.cityId FROM ProfessionalServiceCity psc, "
            + "com.pronto.locations.entity.ServiceCity sc "
            + "WHERE sc.id = psc.cityId AND psc.professionalId = :professionalId "
            + "ORDER BY sc.displayOrder ASC, sc.id ASC")
    List<Long> findCityIdsInDisplayOrder(@Param("professionalId") Long professionalId);
}
