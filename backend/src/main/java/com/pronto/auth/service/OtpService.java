package com.pronto.auth.service;

import com.pronto.auth.email.EmailSender;
import com.pronto.auth.entity.OtpChannel;
import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.entity.VerificationCode;
import com.pronto.auth.repository.VerificationCodeRepository;
import com.pronto.auth.sms.SmsSender;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, dispatches, resends and redeems one-time passwords. The whole OTP lifecycle lives here so
 * that {@code AuthService} expresses flows ("registration needs the email proved") rather than
 * mechanics, and so that every one of MS1's OTP rules is enforced in exactly one place regardless
 * of which of the nine endpoints is calling.
 *
 * <p>The rules, and why each is where it is:
 * <ul>
 *   <li><b>Six digits from {@link SecureRandom}.</b> Uniform over {@code 000000}-{@code 999999};
 *       {@code String.format("%06d", …)} keeps leading zeros, which a naive {@code toString} would
 *       drop and thereby shrink the keyspace tenfold for one in ten codes.</li>
 *   <li><b>Keyed hash at rest.</b> {@code HMAC-SHA256} under a server-side pepper, over
 *       {@code challengeId:purpose:code} — see {@link OtpPepper} for why a plain digest, salted or
 *       not, is worthless for a secret with only 10^6 possible values.</li>
 *   <li><b>Single use, and only while valid.</b> Enforced by a conditional UPDATE whose WHERE clause
 *       carries both {@code consumed_at IS NULL} and {@code expires_at > now}, so neither
 *       double-redemption nor an expiry race is decided in Java.</li>
 *   <li><b>{@value #MAX_ATTEMPTS} attempts per challenge.</b> Enforced by a conditional UPDATE
 *       committed on its own transaction ({@link OtpAttemptRecorder}) — a read-modify-write, or one
 *       that rolled back with the exception it accompanies, would not hold.</li>
 *   <li><b>Resend replaces, but only once the replacement has actually been delivered.</b> See
 *       {@link #issue} and {@link OtpChallengeWriter}.</li>
 *   <li><b>{@value #RESEND_COOLDOWN_SECONDS}s cooldown and {@value #MAX_ISSUES_PER_HOUR} codes per
 *       purpose per hour.</b> The cooldown spaces requests; the hourly ceiling is what actually
 *       bounds them, since a cooldown alone permits one message a minute forever — which is both an
 *       SMS bill and a way to harass whoever owns that handset.</li>
 * </ul>
 *
 * <p><b>The plaintext code never leaves this class except towards a provider.</b> It is generated
 * locally, hashed, handed to the transport, and dropped. It is not returned to any caller, not
 * stored, and not logged (the logging transports print it only where they are permitted to run at
 * all — see {@code LoggingEmailSender}).
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Guesses allowed against one issued code before the challenge is dead. */
    public static final short MAX_ATTEMPTS = 5;

    /** Minimum spacing between two deliberate resends of the same purpose. */
    public static final long RESEND_COOLDOWN_SECONDS = 60;

    /** Codes of one purpose one user may be issued per rolling hour, resends included. */
    public static final int MAX_ISSUES_PER_HOUR = 5;

    private final VerificationCodeRepository verificationCodeRepository;
    private final OtpChallengeWriter challengeWriter;
    private final OtpAttemptRecorder attemptRecorder;
    private final OtpPepper pepper;
    private final EmailSender emailSender;
    private final SmsSender smsSender;

    public OtpService(VerificationCodeRepository verificationCodeRepository,
                       OtpChallengeWriter challengeWriter,
                       OtpAttemptRecorder attemptRecorder,
                       OtpPepper pepper,
                       EmailSender emailSender,
                       SmsSender smsSender) {
        this.verificationCodeRepository = verificationCodeRepository;
        this.challengeWriter = challengeWriter;
        this.attemptRecorder = attemptRecorder;
        this.pepper = pepper;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
    }

    /**
     * What a caller may tell the client about a freshly issued challenge.
     *
     * <p>Note what is absent: the code, and the unmasked destination. A client needs to know where
     * to type the code, roughly where it went, and how long it has — nothing more.
     *
     * @param delivered whether the provider accepted the message. {@code false} means the challenge
     *                  has already been abandoned and whatever the user held before is still their
     *                  live code; callers turn this into {@code OTP_DELIVERY_FAILED} or into a
     *                  "tap resend" hint — see {@code AuthService}.
     */
    public record IssuedChallenge(UUID challengeId, OtpChannel channel, String destinationMasked,
                                   long expiresInSeconds, boolean delivered) {
    }

    /**
     * Issues a code for {@code purpose} and dispatches it.
     *
     * <p><b>Deliberately not {@code @Transactional}.</b> This method makes a network call to SES or
     * SNS, and holding a database connection across it is how a slow provider becomes an application
     * outage. The database work is three short transactions on {@link OtpChallengeWriter}, and the
     * dispatch happens between them with no connection held.
     *
     * <p><b>Ordering matters more than it looks.</b> The previous code is superseded <em>after</em> a
     * successful delivery, never before it. Invalidating first would mean that a provider failure
     * destroys a code the user could still have used and replaces it with one that never arrived.
     * Here a failed delivery abandons the new challenge instead, so the net effect is nothing at all
     * and the user's existing code keeps working.
     *
     * @param enforceCooldown {@code true} for a user-initiated resend, where the
     *                        {@value #RESEND_COOLDOWN_SECONDS}s spacing rule applies. {@code false}
     *                        for a code issued as the natural next step of a flow the user just
     *                        completed (registration, a successful password check), where there is
     *                        nothing to space out and refusing would strand them.
     * @throws ApiException {@code RATE_LIMITED} if the cooldown or the hourly ceiling is hit
     */
    public IssuedChallenge issue(User user, OtpPurpose purpose, boolean enforceCooldown) {
        // The id exists before the row does, because the hash is computed over it.
        UUID challengeId = UUID.randomUUID();
        String code = generateCode();

        VerificationCode challenge = challengeWriter.create(
                user.getId(), purpose, challengeId, pepper.hash(challengeId, purpose, code), enforceCooldown);

        boolean delivered = dispatch(user, purpose, code);

        if (delivered) {
            challengeWriter.supersedePrevious(user.getId(), purpose, challenge.getId());
        } else {
            challengeWriter.abandon(challenge.getId());
        }

        return new IssuedChallenge(challengeId, purpose.channel(),
                maskDestination(user, purpose), purpose.timeToLive().getSeconds(), delivered);
    }

    /**
     * Redeems {@code code} against {@code challengeId}.
     *
     * <p>Every rejection reason that could distinguish "this account exists" from "it does not"
     * collapses to {@code INVALID_CODE}: an unknown challenge id and a wrong code are the same
     * answer, and so is a challenge that exists but was issued for a different purpose. The three
     * codes that <em>are</em> distinguished — expired, already used, out of attempts — are only
     * reachable by a caller who already holds a real challenge id, so they tell an attacker nothing
     * they did not already know and tell a legitimate user exactly what went wrong.
     *
     * @return the user id the redeemed challenge belongs to
     * @throws ApiException {@code INVALID_CODE} / {@code CODE_EXPIRED} /
     *                      {@code CODE_ALREADY_CONSUMED} / {@code OTP_ATTEMPTS_EXCEEDED}
     */
    @Transactional
    public Long redeem(UUID challengeId, String code, OtpPurpose expectedPurpose) {
        VerificationCode challenge = verificationCodeRepository.findByChallengeId(challengeId)
                .filter(c -> c.getPurpose() == expectedPurpose)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CODE, "Invalid or expired code."));

        Instant now = Instant.now();

        // These three reads produce a precise error for a legitimate user. None of them is the
        // authority: consumeIfValid below re-decides consumption and expiry at the moment of the
        // write, and registerFailedAttempt re-decides the cap at the moment of its own.
        if (challenge.getConsumedAt() != null) {
            throw new ApiException(ErrorCode.CODE_ALREADY_CONSUMED, "This code has already been used.");
        }
        if (!challenge.getExpiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.CODE_EXPIRED, "This code has expired. Request a new one.");
        }
        if (challenge.getAttempts() >= MAX_ATTEMPTS) {
            throw attemptsExceeded();
        }

        String expected = pepper.hash(challengeId, expectedPurpose, code);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                challenge.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            // Committed independently of the exception below — see OtpAttemptRecorder.
            boolean stillAlive = attemptRecorder.recordFailedAttempt(challenge.getId(), MAX_ATTEMPTS);
            if (!stillAlive) {
                throw attemptsExceeded();
            }
            throw new ApiException(ErrorCode.INVALID_CODE, "Invalid or expired code.");
        }

        if (verificationCodeRepository.consumeIfValid(challenge.getId(), now) != 1) {
            // Either a concurrent request carrying the same correct code won the row lock first, or
            // the code expired between the read above and this write. Exactly one caller ever gets
            // past this line.
            throw new ApiException(ErrorCode.CODE_ALREADY_CONSUMED, "This code has already been used.");
        }

        return challenge.getUserId();
    }

    /** Resolves a challenge without redeeming it — used to re-derive its owner on a resend. */
    @Transactional(readOnly = true)
    public Optional<VerificationCode> findChallenge(UUID challengeId) {
        return verificationCodeRepository.findByChallengeId(challengeId);
    }

    /**
     * Kills every outstanding challenge of {@code purpose} for {@code userId}. Called after a
     * password reset so that a login challenge started with the old password cannot still be
     * completed.
     */
    @Transactional
    public void invalidateAll(Long userId, OtpPurpose purpose) {
        verificationCodeRepository.invalidateOpenChallenges(userId, purpose, Instant.now());
    }

    /**
     * @return whether the provider accepted the message. Provider failure is caught rather than
     *         propagated because the right response differs per flow — a failed dispatch during
     *         registration should surface as a 502, while a failed dispatch of the phone code after a
     *         successful email verification must not undo the email verification. Callers decide;
     *         this method only reports.
     */
    private boolean dispatch(User user, OtpPurpose purpose, String code) {
        try {
            if (purpose.channel() == OtpChannel.EMAIL) {
                emailSender.sendOtp(user.getEmail(), purpose, code);
            } else {
                smsSender.sendOtp(user.getPhone(), purpose, code);
            }
            return true;
        } catch (RuntimeException e) {
            // User id, not address: enough to correlate a support call, without writing contact
            // details into every log aggregator this application ever ships to. The provider's own
            // sanitized detail was already logged by the sender.
            log.error("OTP dispatch failed for user {} purpose {} channel {}: {}",
                    user.getId(), purpose, purpose.channel(), e.getClass().getSimpleName());
            return false;
        }
    }

    private String maskDestination(User user, OtpPurpose purpose) {
        return purpose.channel() == OtpChannel.EMAIL
                ? EmailNormalizer.mask(user.getEmail())
                : PhoneNumberNormalizer.mask(user.getPhone());
    }

    static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static ApiException attemptsExceeded() {
        return new ApiException(ErrorCode.OTP_ATTEMPTS_EXCEEDED,
                "Too many incorrect attempts. Request a new code.");
    }
}
