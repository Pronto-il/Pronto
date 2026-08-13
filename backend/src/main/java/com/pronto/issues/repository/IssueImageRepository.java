package com.pronto.issues.repository;

import com.pronto.issues.entity.IssueImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueImageRepository extends JpaRepository<IssueImage, Long> {
}
