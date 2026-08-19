package com.pronto.professionals.repository;

import com.pronto.professionals.entity.SubService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubServiceRepository extends JpaRepository<SubService, Long> {

    List<SubService> findAllByOrderByCategoryIdAscDisplayOrderAsc();

    List<SubService> findByCategoryIdOrderByDisplayOrderAsc(Long categoryId);
}
