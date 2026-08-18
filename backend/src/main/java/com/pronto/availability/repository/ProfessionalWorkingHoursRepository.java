package com.pronto.availability.repository;

import com.pronto.availability.entity.ProfessionalWorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * See {@code docs/architecture/professional-weekly-calendar-design.md} §3. {@code
 * findByProfessionalId} is the only access pattern needed -- "give me this professional's
 * whole (up to 7-row) week" -- used by {@code AvailabilityService#getWorkingHours}/
 * {@code #updateWorkingHours}'s upsert loop and by
 * {@code AvailabilityDerivationService#deriveCalendar}. Upsert-on-{@code PUT} is handled at
 * the service layer (load existing rows, update in place or insert missing weekdays, inside
 * one {@code @Transactional} method) -- a plain JPA save-per-weekday loop is sufficient at
 * this row count (at most 7), no bulk-SQL needed.
 */
public interface ProfessionalWorkingHoursRepository extends JpaRepository<ProfessionalWorkingHours, Long> {

    List<ProfessionalWorkingHours> findByProfessionalId(Long professionalId);
}
