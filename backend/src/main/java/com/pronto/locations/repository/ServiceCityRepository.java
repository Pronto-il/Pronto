package com.pronto.locations.repository;

import com.pronto.locations.entity.ServiceCity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceCityRepository extends JpaRepository<ServiceCity, Long> {

    /** {@code GET /api/service-areas} — every city, grouped into its region by the caller. */
    List<ServiceCity> findAllByOrderByRegionIdAscDisplayOrderAsc();

    /** The region → city filter, index-anchored on {@code idx_service_cities_region}. */
    List<ServiceCity> findByRegionIdOrderByDisplayOrderAsc(Long regionId);
}
