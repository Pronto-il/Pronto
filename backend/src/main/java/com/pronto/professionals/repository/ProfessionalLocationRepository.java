package com.pronto.professionals.repository;

import com.pronto.professionals.entity.ProfessionalLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * {@code professional_locations} ({@code V49}) — one current row per professional.
 *
 * <p>Deliberately tiny. There is no "find positions between these times", no history query and no
 * bounding-box search, because MS2 stores no history and does no spatial querying in SQL (the
 * geographic filter is Java-side, against real routing results — see {@code maps} README). Adding
 * query surface for capabilities that do not exist is how a table that was meant to hold one row
 * per person quietly becomes a tracking log.
 */
public interface ProfessionalLocationRepository extends JpaRepository<ProfessionalLocation, Long> {

    /**
     * Every stored position for the given professionals, in one query.
     *
     * <p>The batched form is the one matching and listing actually use: a listing evaluates tens
     * of candidates at once, and doing that one {@code findById} at a time would be an N+1 against
     * the database to feed a call whose entire purpose is to avoid an N+1 against the provider.
     *
     * <p>Returns only rows that exist — a professional who has never sent a position is simply
     * absent, which callers must already handle, since "absent" and "stale" lead to the same
     * outcome by different reason codes.
     */
    List<ProfessionalLocation> findByProfessionalIdIn(Collection<Long> professionalIds);
}
