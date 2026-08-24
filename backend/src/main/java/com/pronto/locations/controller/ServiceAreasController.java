package com.pronto.locations.controller;

import com.pronto.locations.dto.ServiceCityResponse;
import com.pronto.locations.dto.ServiceRegionResponse;
import com.pronto.locations.entity.ServiceCity;
import com.pronto.locations.entity.ServiceRegion;
import com.pronto.locations.repository.ServiceCityRepository;
import com.pronto.locations.repository.ServiceRegionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code GET /api/service-areas} — the whole closed region/city catalogue in one
 * unauthenticated read (MS4 Part A/F).
 *
 * <p><b>Why an endpoint rather than a static frontend module.</b> The brief allows either. This
 * is an endpoint for the same reason {@code GET /api/categories} is: the backend already has to
 * hold this list, because {@code professionals.service_region_id} and
 * {@code professional_service_cities} are foreign keys into it and registration validates
 * against it. A static TS mirror would therefore be a *second* copy of a list the server owns —
 * the exact duplication MS4 §17 asks to avoid, and the failure mode {@code
 * shared/api/categories.ts} already demonstrates (it is a hand-maintained mirror of
 * {@code V10}/{@code V31} that has to be edited whenever a migration touches categories).
 *
 * <p>Unauthenticated because professional registration needs it before an account exists — same
 * reasoning, and the same {@code permitAll} entry style, as {@code /api/categories}. Nothing
 * here is sensitive: it is a list of Israeli city names.
 *
 * <p>Two queries joined in memory: 7 regions × ~100 cities total is trivial volume, and it
 * avoids a JPA {@code @OneToMany} graph for a table nothing ever writes — the identical
 * trade-off {@code professionals.controller.CategoriesController} documents.
 */
@RestController
@RequestMapping("/api/service-areas")
public class ServiceAreasController {

    private final ServiceRegionRepository serviceRegionRepository;
    private final ServiceCityRepository serviceCityRepository;

    public ServiceAreasController(ServiceRegionRepository serviceRegionRepository,
                                   ServiceCityRepository serviceCityRepository) {
        this.serviceRegionRepository = serviceRegionRepository;
        this.serviceCityRepository = serviceCityRepository;
    }

    @GetMapping
    public ResponseEntity<List<ServiceRegionResponse>> getServiceAreas() {
        List<ServiceRegion> regions = serviceRegionRepository.findAllByOrderByDisplayOrderAsc();
        List<ServiceCity> cities = serviceCityRepository.findAllByOrderByRegionIdAscDisplayOrderAsc();

        Map<Long, List<ServiceCityResponse>> citiesByRegion = cities.stream()
                .collect(Collectors.groupingBy(ServiceCity::getRegionId,
                        Collectors.mapping(ServiceAreasController::toCityResponse, Collectors.toList())));

        List<ServiceRegionResponse> response = regions.stream()
                .map(region -> new ServiceRegionResponse(region.getId(), region.getCode(), region.getNameHe(),
                        region.getNameEn(), region.getDisplayOrder(),
                        citiesByRegion.getOrDefault(region.getId(), List.of())))
                .toList();

        return ResponseEntity.ok(response);
    }

    private static ServiceCityResponse toCityResponse(ServiceCity city) {
        return new ServiceCityResponse(city.getId(), city.getCode(), city.getNameHe(), city.getNameEn(),
                city.getDisplayOrder());
    }
}
