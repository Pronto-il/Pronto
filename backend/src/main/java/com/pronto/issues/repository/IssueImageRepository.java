package com.pronto.issues.repository;

import com.pronto.issues.entity.IssueImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueImageRepository extends JpaRepository<IssueImage, Long> {

    /** §2.1 step 4 (GET /api/issues/{id}): all images attached to one issue. */
    List<IssueImage> findByIssueId(Long issueId);
}
