package com.pronto.issues.repository;

import com.pronto.issues.entity.IssueClassification;
import org.springframework.data.jpa.repository.JpaRepository;

/** Keyed by {@code issue_id} — exactly one final classification record per issue. */
public interface IssueClassificationRepository extends JpaRepository<IssueClassification, Long> {
}
