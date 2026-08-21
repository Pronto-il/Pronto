package com.pronto.issues.repository;

import com.pronto.issues.entity.IssueClarification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueClarificationRepository extends JpaRepository<IssueClarification, Long> {

    /**
     * The clarification conversation in the order it happened. Ordering is explicit rather
     * than incidental — the brief prompt and the professional-facing response both replay
     * this list, and a shuffled conversation reads as a different one.
     */
    List<IssueClarification> findByIssueIdOrderByPositionAsc(Long issueId);
}
