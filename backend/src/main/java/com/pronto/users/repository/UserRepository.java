package com.pronto.users.repository;

import com.pronto.users.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Case-insensitive email lookup, matching {@code ux_users_email_lower}. Note this
     * intentionally does NOT filter by {@code deleted_at} — callers (login, registration
     * duplicate-check) each decide how to treat soft-deleted rows per
     * {@code docs/architecture/api-contract.md} §2.1/§2.3.
     */
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
