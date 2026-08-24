package com.pronto.locations.repository;

import com.pronto.locations.entity.ServiceRegion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceRegionRepository extends JpaRepository<ServiceRegion, Long> {

    /** {@code GET /api/service-areas} — the closed region list, in product order. */
    List<ServiceRegion> findAllByOrderByDisplayOrderAsc();
}
