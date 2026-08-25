package com.pronto.auth.service;

import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.entity.VerificationCode;
import com.pronto.auth.repository.VerificationCodeRepository;
import com.pronto.common.dto.RateLimitDetails;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The three short database transactions an OTP issue is made of.
 *
 * <p><b>Why this class exists.</b> {@code OtpService.issue} used to be a single
 * {@code @Transactional} method that wrote the challenge row and then called SES or SNS <em>while
 * still holding the database connection</em>. With a 10-second provider timeout and HikariCP's
 * default pool of ten, ten concurrent registrations against a slow provider exhaust the pool and
 * stall every other request in the application — a provider hiccup becomes a full outage. Splitting
 * the work into discrete transactions lets {@code OtpService} commit, release the connection, and
 * only then talk to the network.
 *
 * <p><b>The ordering is what makes a failed resend harmless.</b> The obvious split — invalidate the
 * previous code and insert the new one in one transaction, then dispatch — is wrong: if the
 * dispatch fails, the user's previous, still-usable code has been destroyed and nothing arrived to
 * replace it. So {@link #create} deliberately does <em>not</em> invalidate anything. Superseding
 * happens afterwards, and only on success:
 *
 * <pre>
 *   create()            -> commit   (previous code still valid)
 *   dispatch()                      (no connection held)
 *   supersedePrevious() -> commit   on success: the new code is now the only one
 *   abandon()           -> commit   on failure: the new code is killed, previous survives
 * </pre>
 *
 * <p>Between {@code create} and {@code supersedePrevious} two codes are briefly redeemable. That
 * window is the duration of one provider call, and during it the older code is the one the user
 * actually has — which is the point. "Resend invalidates the previous code" holds once the operation
 * completes, which is the property that matters.
 *
 * <p><b>Plain {@code @Transactional}, not {@code REQUIRES_NEW}.</b> Every caller of these methods is
 * a non-transactional orchestrator, so {@code REQUIRED} already gives each method its own
 * transaction. {@code REQUIRES_NEW} would additionally be unsafe on the registration path: the
 * {@code verification_codes.user_id} foreign key would have to see a {@code users} row that a
 * suspended outer transaction had not yet committed, and the inner insert would block on it while
 * the outer waited for the inner to return. Deadlock. Keeping the callers non-transactional avoids
 * the problem rather than working around it.
 */
@Component
public class OtpChallengeWriter {

    private static final Duration ISSUE_WINDOW = Duration.ofHours(1);

    private final VerificationCodeRepository verificationCodeRepository;

    public OtpChallengeWriter(VerificationCodeRepository verificationCodeRepository) {
        this.verificationCodeRepository = verificationCodeRepository;
    }

    /**
     * Applies the issue-rate rules and inserts the new challenge. Commits before any provider call.
     *
     * <p>Does not touch existing challenges — see this class's Javadoc.
     *
     * @throws ApiException {@code RATE_LIMITED} if the resend cooldown or the hourly ceiling is hit
     */
    @Transactional
    public VerificationCode create(Long userId, OtpPurpose purpose, UUID challengeId, String codeHash,
                                    boolean enforceCooldown) {
        Instant now = Instant.now();

        if (enforceCooldown) {
            verificationCodeRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose)
                    .map(VerificationCode::getCreatedAt)
                    .map(createdAt -> OtpService.RESEND_COOLDOWN_SECONDS
                            - Duration.between(createdAt, now).getSeconds())
                    .filter(remaining -> remaining > 0)
                    .ifPresent(remaining -> {
                        throw rateLimited(remaining,
                                "A code was just sent. Please wait before requesting another.");
                    });
        }

        long issuedThisHour = verificationCodeRepository.countIssuedSince(
                userId, purpose, now.minus(ISSUE_WINDOW));
        if (issuedThisHour >= OtpService.MAX_ISSUES_PER_HOUR) {
            throw rateLimited(ISSUE_WINDOW.getSeconds(),
                    "Too many codes requested. Please try again later.");
        }

        return verificationCodeRepository.save(new VerificationCode(
                userId, purpose, challengeId, codeHash, now.plus(purpose.timeToLive())));
    }

    /**
     * The delivery succeeded: every other open challenge of this purpose for this user is now
     * superseded and stops working.
     */
    @Transactional
    public void supersedePrevious(Long userId, OtpPurpose purpose, Long keepChallengeRowId) {
        verificationCodeRepository.supersedeOtherOpenChallenges(
                userId, purpose, keepChallengeRowId, Instant.now());
    }

    /**
     * The delivery failed: kill the challenge nobody received, leaving whatever the user already had
     * untouched. The net effect of a failed issue is therefore no change at all.
     */
    @Transactional
    public void abandon(Long challengeRowId) {
        verificationCodeRepository.consume(challengeRowId, Instant.now());
    }

    private static ApiException rateLimited(long retryAfterSeconds, String message) {
        return new ApiException(ErrorCode.RATE_LIMITED, message, new RateLimitDetails(retryAfterSeconds));
    }
}
