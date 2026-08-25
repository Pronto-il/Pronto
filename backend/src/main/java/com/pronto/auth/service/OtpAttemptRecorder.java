package com.pronto.auth.service;

import com.pronto.auth.repository.VerificationCodeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a failed-OTP-attempt increment on its own transaction.
 *
 * <p><b>Without this the attempt cap does not exist.</b> Every OTP redemption runs inside a
 * {@code @Transactional} service method, and every wrong-code path ends by throwing an
 * {@code ApiException}. Spring's default rollback-on-unchecked-exception policy would roll that
 * method's transaction back — including the {@code attempts = attempts + 1} it just performed — so
 * the counter would return to its previous value on the way out and an attacker could guess a
 * 6-digit code indefinitely while the column stayed at zero.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction, runs the increment in a fresh one and
 * commits it before returning, so the write survives the throw that follows. This is the same
 * problem, and the same solution, as {@link LoginAttemptRecorder} — kept as a separate small bean
 * for the same reason: Spring's proxy-based {@code @Transactional} does not intercept a
 * self-invocation, so a private method on {@code OtpService} would silently run on the caller's
 * transaction and quietly reintroduce the bug.
 */
@Component
public class OtpAttemptRecorder {

    private final VerificationCodeRepository verificationCodeRepository;

    public OtpAttemptRecorder(VerificationCodeRepository verificationCodeRepository) {
        this.verificationCodeRepository = verificationCodeRepository;
    }

    /**
     * @return {@code true} if this guess was counted and the challenge is still alive;
     *         {@code false} if the challenge had already reached its ceiling or been consumed,
     *         which means the caller must treat it as dead rather than as "wrong code"
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailedAttempt(Long verificationCodeId, short maxAttempts) {
        return verificationCodeRepository.registerFailedAttempt(verificationCodeId, maxAttempts) == 1;
    }
}
