package com.pronto.professionals.repository;

import com.pronto.professionals.entity.ProfessionalSubService;
import com.pronto.professionals.entity.ProfessionalSubServiceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProfessionalSubServiceRepository
        extends JpaRepository<ProfessionalSubService, ProfessionalSubServiceId> {

    List<ProfessionalSubService> findByProfessionalId(Long professionalId);
}
