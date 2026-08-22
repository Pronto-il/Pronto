package com.pronto.sos.service;

import com.pronto.notifications.service.SosRequestIssueResolver;
import com.pronto.sos.repository.SosRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This package's implementation of {@link SosRequestIssueResolver} — the whole of it.
 *
 * <p>See that interface for why the dependency is inverted. In short: {@code sos} already depends
 * on {@code notifications}, so {@code notifications} declares the one fact it needs about an SOS
 * request and this class supplies it, keeping the arrow pointing one way.
 *
 * <p>Ids only, and nothing else. It is not a window onto SOS state: it cannot report a status, an
 * address, a candidate or a professional, which is exactly the point — a notification row needs to
 * know where to send the reader and nothing more, and a wider seam here would be an invitation to
 * duplicate SOS state into a package that has no business holding it.
 */
@Component
public class SosRequestIssueLookup implements SosRequestIssueResolver {

    private final SosRequestRepository sosRequestRepository;

    public SosRequestIssueLookup(SosRequestRepository sosRequestRepository) {
        this.sosRequestRepository = sosRequestRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> issueIdsBySosRequestId(Collection<Long> sosRequestIds) {
        if (sosRequestIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> issueIds = new HashMap<>();
        for (Object[] row : sosRequestRepository.findIssueIdsByIds(List.copyOf(sosRequestIds))) {
            issueIds.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return issueIds;
    }
}
