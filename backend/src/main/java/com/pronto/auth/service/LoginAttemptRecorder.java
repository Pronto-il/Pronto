package com.pronto.auth.service;

import com.pronto.users.entity.User;
import com.pronto.users.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists {@code users.failed_login_attempts}/{@code users.locked_until} in their own,
 * independently-committed transaction.
 *
 * <p>{@link AuthService#login} is {@code @Transactional} and every failed-login branch
 * (wrong password, lockout-threshold-reached, email-not-verified) needs its lockout-state
 * write to survive even though the method then throws an {@link com.pronto.common.exception.ApiException}.
 * Spring's default rollback-on-unchecked-exception policy rolls back <em>any</em> write
 * made on that method's own transaction once the exception propagates out of it — so
 * those writes must happen on a transaction that commits before {@code login()} throws,
 * not on {@code login()}'s own transaction.
 *
 * <p>{@code REQUIRES_NEW} on this bean's method achieves that: it suspends the caller's
 * transaction, runs in a brand-new one, and commits that new transaction as soon as the
 * method returns — before control returns to {@code login()} and the throw happens.
 *
 * <p>This has to live on a <em>separate</em> Spring bean from {@link AuthService}, not a
 * private/self-invoked method on {@code AuthService} itself: Spring's proxy-based
 * {@code @Transactional} only intercepts calls that arrive through the bean's proxy, and
 * a self-invocation from within {@code AuthService} bypasses the proxy entirely, silently
 * running with whatever transaction (or lack thereof) the caller already has.
 */
@Component
public class LoginAttemptRecorder {

    private final UserRepository userRepository;

    public LoginAttemptRecorder(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Writes the given failed-attempt counter/lockout timestamp for {@code userId} and
     * commits immediately, independent of whatever the caller's own transaction does
     * afterwards.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistLockoutState(Long userId, short failedLoginAttempts, Instant lockedUntil) {
        // getReferenceById rather than findById: this transaction only ever writes these
        // two columns (never reads them to decide anything), so a lazy proxy is enough —
        // no need for a round-trip SELECT first.
        User user = userRepository.getReferenceById(userId);
        user.setFailedLoginAttempts(failedLoginAttempts);
        user.setLockedUntil(lockedUntil);
        userRepository.save(user);
    }
}
