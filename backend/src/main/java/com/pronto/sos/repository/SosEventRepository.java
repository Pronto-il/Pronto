package com.pronto.sos.repository;

import com.pronto.sos.entity.SosEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Insert-and-read only. There is deliberately no update or delete path anywhere in this
 * package for {@code sos_events} — an audit trail that can be rewritten is not one.
 */
public interface SosEventRepository extends JpaRepository<SosEvent, Long> {

    /**
     * The whole timeline for one request, oldest first. Ordered by {@code createdAt} then
     * {@code id} because two events written inside the same transaction can share a timestamp
     * to the microsecond, and the id then breaks the tie deterministically — without it the
     * timeline could render two same-instant events in a different order on each read. Backed
     * by {@code idx_sos_events_request_created}, whose column list matches exactly.
     */
    List<SosEvent> findBySosRequestIdOrderByCreatedAtAscIdAsc(Long sosRequestId);
}
