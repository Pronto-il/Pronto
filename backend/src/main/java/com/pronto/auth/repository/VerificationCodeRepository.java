package com.pronto.auth.repository;

import com.pronto.auth.entity.VerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

    /**
     * Most recent matching code for {@code (userId, purpose, code)}, per
     * {@code docs/architecture/api-contract.md} §2.2 step 3.
     */
    Optional<VerificationCode> findFirstByUserIdAndPurposeAndCodeOrderByCreatedAtDesc(
            Long userId, String purpose, String code);
}
