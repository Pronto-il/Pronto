package com.pronto.users.repository;

import com.pronto.users.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Exact-match email lookup.
     *
     * <p>Production MS1 replaced {@code findByEmailIgnoreCase} with this. Every stored address is
     * canonical as of {@code V48} (lowercase, trimmed — see {@code auth.service.EmailNormalizer}),
     * so callers normalize before calling and an exact match is the correct comparison. It is also
     * the only one that can use {@code ux_users_email}: the {@code IgnoreCase} derivation rendered
     * {@code upper(email) = upper(?)}, which no index covered, so login, registration's duplicate
     * check and email verification each sequentially scanned {@code users}.
     *
     * <p>Deliberately does NOT filter {@code deleted_at} — callers each decide how to treat
     * soft-deleted rows, per {@code docs/architecture/api-contract.md} §2.1/§2.3.
     */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Exact-match phone lookup, against canonical E.164 ({@code ux_users_phone}, V46). The phone
     * half of MS1's dual-identifier login: callers normalize through
     * {@code auth.service.PhoneNumberNormalizer} first, so the three accepted input spellings all
     * arrive here as the one stored value.
     */
    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);
}
