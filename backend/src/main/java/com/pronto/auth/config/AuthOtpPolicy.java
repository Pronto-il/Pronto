package com.pronto.auth.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Whether logging in requires a one-time password in addition to the password itself.
 *
 * <p><b>Why this exists.</b> Pronto's rule is that a password alone never yields a session — see
 * {@code AuthService}'s class Javadoc, where that is stated as the structural guarantee of the whole
 * auth package. That rule is correct and is still the default. This property exists because the
 * platform is currently pre-user, AWS End User Messaging is still in the SMS sandbox with an
 * exhausted monthly spend quota, and a second factor that cannot be delivered is not a second
 * factor — it is a locked door with the key on the wrong side.
 *
 * <p><b>This is configuration, not a redesign.</b> Nothing is removed: {@code OtpService}, both
 * {@code SmsSender} implementations, {@code SesEmailSender}, the {@code verification_codes} table,
 * {@code POST /api/auth/login/otp}, {@code /otp/resend}, the rate limits and every OTP test stay
 * exactly as they are and keep working. One property decides whether {@code POST /api/auth/login}
 * issues a login challenge or a session, and setting it back to {@code true} restores the original
 * behaviour with no code change and no data migration.
 *
 * <p><b>Deliberately not derived from {@code pronto.environment}.</b> Every other switch in this
 * package keys off {@link com.pronto.common.config.ProntoEnvironment}, and this one does not, on
 * purpose. An environment-derived second factor would turn "which environment am I?" — a question
 * whose answer has quietly changed before — into "is authentication complete?". Requiring the
 * operator to name the property means the bypass can only ever be reached by someone who typed
 * {@code AUTH_OTP_REQUIRED=false} and meant it.
 *
 * <p><b>What it deliberately does NOT relax.</b> Everything else on the login path: password
 * verification, account lookup, the lockout counter, the login rate limiter, JWT issuance and
 * validation, role authorization and every route guard. This removes one step from one flow. It is
 * not anonymous access, and it is not a way into an account whose password you do not have. Nor
 * does it touch registration's email verification — see {@code AuthService#login}, which still
 * refuses a session to an account that never proved its email address.
 */
@Component
public class AuthOtpPolicy {

    private static final Logger log = LoggerFactory.getLogger(AuthOtpPolicy.class);

    private final boolean otpRequired;

    /**
     * {@code AND}ed with {@link OtpVerificationPolicy}, the platform-wide master switch, so that
     * {@code OTP_VERIFICATION_ENABLED=false} removes the login second factor along with
     * registration's two codes — one variable rather than three, and no deployment in which login
     * still demands a code nobody can be sent. Resolved here rather than in the getter so
     * {@link #announce()} reports what callers will actually see.
     */
    public AuthOtpPolicy(OtpVerificationPolicy otpVerificationPolicy,
                          @Value("${pronto.auth.otp-required:true}") String otpRequired) {
        // Parsed BEFORE the master is consulted, so the strict true/false check below is not
        // short-circuited away while OTP happens to be off. A deployment carrying
        // AUTH_OTP_REQUIRED=flase has a broken configuration either way, and the worst time to
        // discover it is the day the master switch goes back on and the typo silently decides the
        // second factor.
        boolean requiredBySetting = parse(otpRequired);
        this.otpRequired = otpVerificationPolicy.isOtpVerificationEnabled() && requiredBySetting;
    }

    /**
     * Announced at boot, at INFO, because the alternative is an operator inferring the state of the
     * second factor from whether a code arrived — which is exactly the reasoning that cannot
     * distinguish "OTP is off" from "OTP is on and delivery is broken", and those two have very
     * different fixes.
     */
    @PostConstruct
    void announce() {
        log.info("Authentication OTP requirement: {}", otpRequired ? "ENABLED" : "DISABLED");
        if (!otpRequired) {
            log.warn("Login is completing on password alone (pronto.auth.otp-required=false, "
                    + "AUTH_OTP_REQUIRED). No login OTP is issued and no SMS or email is sent for "
                    + "login. Password checks, account lockout, rate limiting and every route guard "
                    + "remain in force. Set AUTH_OTP_REQUIRED=true to restore the second factor.");
        }
    }

    /**
     * {@code true} (the default) when {@code POST /api/auth/login} must answer with a
     * {@code LOGIN_OTP} challenge rather than a session.
     */
    public boolean isOtpRequired() {
        return otpRequired;
    }

    /**
     * Only {@code true} and {@code false} are accepted, and anything else refuses the boot.
     *
     * <p>Spring's own relaxed binding would be adequate for a typo like {@code "flase"} — its
     * converter throws — but it also silently accepts {@code yes}/{@code no}/{@code on}/{@code off}
     * /{@code 1}/{@code 0}, and for a property whose {@code false} value removes an authentication
     * step, "which spellings mean off?" is not a question worth leaving open. Parsing the raw string
     * here makes the answer exactly two words, and makes a malformed value a startup failure rather
     * than a silent fallback to either setting. Failing closed by defaulting to {@code true} would
     * be no better: it would hide a broken deployment config behind behaviour that merely looks
     * correct.
     */
    private static boolean parse(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalStateException(
                "Refusing to start: pronto.auth.otp-required (AUTH_OTP_REQUIRED) is '" + raw
                        + "', which is not a recognized value. Expected exactly 'true' or 'false'.");
    }
}
