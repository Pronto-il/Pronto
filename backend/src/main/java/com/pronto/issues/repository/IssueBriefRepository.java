package com.pronto.issues.repository;

import com.pronto.issues.entity.IssueBrief;
import org.springframework.data.jpa.repository.JpaRepository;

/** Keyed by {@code issue_id} — one Professional Brief per issue. */
public interface IssueBriefRepository extends JpaRepository<IssueBrief, Long> {
}
