package com.pronto.availability.repository;

import com.pronto.availability.entity.SosAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * See {@code docs/architecture/data-model.md} §2.6. No finder/toggle methods yet — the only
 * current caller is {@code auth.service.AuthService#register}, which inserts the initial
 * {@code isAvailable = false} row for a newly registered professional. Milestone 4 (SOS
 * booking flow) will add the toggle/listing query methods this table exists for.
 */
public interface SosAvailabilityRepository extends JpaRepository<SosAvailability, Long> {
}
