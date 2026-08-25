package com.pronto.auth.repository;

import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.entity.VerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Production MS1. Four of the six methods below are conditional UPDATE statements rather than entity
 * mutations, and that is the whole point of this interface: the attempt cap, the single-use rule and
 * the expiry are the properties a 6-digit secret's safety actually rests on, and all three are lost
 * to a read-modify-write race if expressed as "load the entity, change a field, save".
 */
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

    /** Resolve a challenge by its opaque public handle. The only lookup the auth flows use. */
    Optional<VerificationCode> findByChallengeId(UUID challengeId);

    /**
     * The newest challenge of this purpose for this user, whatever its state. Backs the resend
     * cooldown (how long ago was the last one issued).
     */
    Optional<VerificationCode> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(Long userId, OtpPurpose purpose);

    /**
     * How many challenges of this purpose this user has been issued since {@code since}. Backs the
     * bounded resend volume ceiling — the cooldown alone only spaces requests out, it does not cap
     * them, so without this a caller could sit on the resend button all day at one per minute.
     */
    @Query("""
            SELECT count(c) FROM VerificationCode c
            WHERE c.userId = :userId AND c.purpose = :purpose AND c.createdAt >= :since""")
    long countIssuedSince(@Param("userId") Long userId, @Param("purpose") OtpPurpose purpose,
                           @Param("since") Instant since);

    /**
     * Burns one failed guess against this challenge, atomically.
     *
     * <p>Returns {@code 1} when the attempt was recorded and the caller may report an ordinary
     * "wrong code"; {@code 0} when the row was already consumed or already at the ceiling, which is
     * the caller's signal to kill the challenge instead. The {@code attempts < :maxAttempts}
     * predicate is evaluated by PostgreSQL under the row lock this UPDATE takes, so N concurrent
     * guesses against one challenge produce at most {@code maxAttempts} successful increments no
     * matter how they interleave — the property a {@code load → attempts+1 → save} cannot provide.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE VerificationCode c SET c.attempts = c.attempts + 1
            WHERE c.id = :id AND c.attempts < :maxAttempts AND c.consumedAt IS NULL""")
    int registerFailedAttempt(@Param("id") Long id, @Param("maxAttempts") short maxAttempts);

    /**
     * Marks this challenge used, atomically, <b>and only while it is still valid</b>.
     *
     * <p>Returns {@code 1} for the single caller that won and {@code 0} for every other, which is
     * what makes an OTP genuinely single-use: two requests carrying the same correct code at the
     * same moment cannot both be told they succeeded, so a redeemed code can never authenticate
     * twice.
     *
     * <p>{@code expiresAt > :now} is in the WHERE clause deliberately, and not only in the service's
     * own check. Without it the expiry is a time-of-check/time-of-use gap: the service reads the row,
     * finds it unexpired, and then issues this UPDATE — and between those two moments the code can
     * expire, most plausibly while this statement is queued behind another transaction's row lock.
     * With the predicate here the database makes the decision at the instant of the write.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE VerificationCode c SET c.consumedAt = :now
            WHERE c.id = :id AND c.consumedAt IS NULL AND c.expiresAt > :now""")
    int consumeIfValid(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Unconditional consume, used only to abandon a challenge whose delivery failed. Separate from
     * {@link #consumeIfValid} because killing a code nobody received must succeed regardless of
     * whether it has since expired.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE VerificationCode c SET c.consumedAt = :now WHERE c.id = :id AND c.consumedAt IS NULL")
    int consume(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Supersedes every still-open challenge of this purpose for this user <em>except</em> the one
     * just delivered.
     *
     * <p>Called after a successful dispatch, never before it — see {@code OtpChallengeWriter} for
     * why invalidating first is the bug this ordering exists to avoid.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE VerificationCode c SET c.consumedAt = :now
            WHERE c.userId = :userId AND c.purpose = :purpose AND c.consumedAt IS NULL
              AND c.id <> :keepId""")
    int supersedeOtherOpenChallenges(@Param("userId") Long userId, @Param("purpose") OtpPurpose purpose,
                                      @Param("keepId") Long keepId, @Param("now") Instant now);

    /**
     * Invalidates every still-open challenge of this purpose for this user.
     *
     * <p>Used after a password reset, so that completing one kills any login challenge an attacker
     * had already started with the old password.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE VerificationCode c SET c.consumedAt = :now
            WHERE c.userId = :userId AND c.purpose = :purpose AND c.consumedAt IS NULL""")
    int invalidateOpenChallenges(@Param("userId") Long userId, @Param("purpose") OtpPurpose purpose,
                                  @Param("now") Instant now);
}
