package com.pronto.auth.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * <b>The master switch for one-time passwords.</b> {@code OTP_VERIFICATION_ENABLED=false} turns off
 * every OTP the platform issues — registration's email code, registration's phone code, and login's
 * second factor — with one variable.
 *
 * <h2>Why a fourth flag, when three already existed</h2>
 *
 * <p>{@link VerificationPolicy} (email + SMS) and {@link AuthOtpPolicy} (login second factor) were
 * each added for a different provider outage, and each is still the right granularity for the
 * problem it was added for: SES sandboxed but SNS fine, or the reverse. What none of them expresses
 * is the <em>product</em> decision this class exists for — "for the current feedback phase we are
 * not doing OTP at all" — which today requires setting three variables correctly and produces a
 * silently half-verified deployment if an operator sets two.
 *
 * <p>So this does not replace them; it <b>gates</b> them. Both existing policies now report their
 * requirement as {@code masterEnabled && ownFlag}, which means:
 *
 * <ul>
 *   <li>every existing consumer keeps reading exactly the policy it already read — no call site had
 *       to learn about this class, and none of the fine-grained reasoning was duplicated;</li>
 *   <li>turning OTP off is one variable, and turning it back on restores whatever the three
 *       sub-flags say (all of which default to {@code true});</li>
 *   <li>the sub-flags remain independently useful when the master is on.</li>
 * </ul>
 *
 * <h2>What {@code false} does and does not do</h2>
 *
 * <p><b>Does:</b> registration creates the account and returns a session immediately; no
 * {@code verification_codes} row is written, no code is generated, and neither SES nor SNS is called
 * on any auth path; login completes on the password; the marketplace gates
 * ({@code ContactVerificationGuard}, {@code ProfessionalEligibility}) stop asking for proof nobody
 * was given a chance to provide.
 *
 * <p><b>Does not:</b> remove or weaken anything else. Email format, phone format and reachability
 * ({@code PhoneNumberNormalizer}), duplicate email, duplicate phone, password length, every required
 * field, BCrypt verification, the account lockout counter, the per-IP rate limiters, JWT issuance
 * and validation, and every role and route guard are untouched. It is not anonymous access, and it
 * is not a way into an account whose password you do not have. No OTP infrastructure is deleted:
 * {@code OtpService}, {@code verification_codes}, both {@code SmsSender}s, {@code SesEmailSender},
 * {@code POST /api/auth/verify-email}/{@code verify-phone}/{@code login/otp}/{@code otp/resend} and
 * every OTP test remain exactly as they are and resume working the moment this is {@code true}.
 *
 * <p><b>It writes nothing to the database.</b> Accounts created while OTP is off keep
 * {@code email_verified = false} and {@code phone_verified = false} — the columns continue to mean
 * "this channel was proved", and nothing here makes them lie. Whether an unproved channel
 * <em>blocks</em> is what this decides, and that is a policy question with a policy answer. Writing
 * {@code true} into those columns instead would be the one change in this whole feature that
 * outlives the flag: it would permanently mark unproved addresses as proved, and re-enabling would
 * silently grandfather the entire beta cohort rather than asking them to verify.
 *
 * <h2>Deliberately usable in production</h2>
 *
 * <p>There is no startup guard forbidding {@code false} in a production-like environment, and that
 * is intentional rather than an omission: the current beta runs with OTP off <em>in production</em>,
 * by product decision. What the guards do instead is make the state impossible to miss — this class
 * logs it at {@code WARN} on every boot, and {@link VerificationPolicy}/{@link AuthOtpPolicy} log
 * their own resolved state alongside it.
 *
 * <p>Not derived from {@code pronto.environment}, for the reason {@link AuthOtpPolicy} states and
 * this class inherits: an environment-derived second factor turns "which environment am I?" — a
 * question whose answer has quietly changed before — into "is authentication complete?". Only an
 * operator who typed {@code OTP_VERIFICATION_ENABLED=false} reaches it.
 */
@Component
public class OtpVerificationPolicy {

    private static final Logger log = LoggerFactory.getLogger(OtpVerificationPolicy.class);

    private final boolean otpVerificationEnabled;

    public OtpVerificationPolicy(
            @Value("${pronto.auth.otp-verification-enabled:true}") String otpVerificationEnabled) {
        this.otpVerificationEnabled = parse(otpVerificationEnabled);
    }

    /**
     * {@code true} (the default) when this deployment issues one-time passwords at all.
     *
     * <p>When {@code false}, {@link VerificationPolicy#isEmailVerificationRequired()},
     * {@link VerificationPolicy#isSmsVerificationRequired()} and
     * {@link AuthOtpPolicy#isOtpRequired()} all report {@code false} regardless of their own
     * settings — so no consumer needs to consult this bean directly, and none does except those
     * three and {@code ProviderModeStartupGuard}.
     */
    public boolean isOtpVerificationEnabled() {
        return otpVerificationEnabled;
    }

    /**
     * Announced at every boot, and at {@code WARN} when off.
     *
     * <p>An operator must never have to infer the state of verification from whether a message
     * arrived, because that inference cannot distinguish "verification is off" from "verification is
     * on and delivery is broken" — and those have opposite fixes. That reasoning is why
     * {@link AuthOtpPolicy} and {@link VerificationPolicy} announce themselves, and it applies with
     * more force to a switch that turns off all three at once.
     */
    @PostConstruct
    void announce() {
        log.info("OTP verification: {}", otpVerificationEnabled ? "ENABLED" : "DISABLED");
        if (!otpVerificationEnabled) {
            log.warn("OTP verification is DISABLED for the whole platform "
                    + "(pronto.auth.otp-verification-enabled=false, OTP_VERIFICATION_ENABLED). "
                    + "Registration completes immediately and issues a session; login completes on "
                    + "the password; no email or SMS code is generated or sent on any auth path. "
                    + "Accounts still record email_verified=false and phone_verified=false, so "
                    + "re-enabling asks exactly the right people to verify. Email/phone format "
                    + "checks, duplicate detection, password rules, lockout, rate limiting and every "
                    + "route guard remain in force. Set OTP_VERIFICATION_ENABLED=true to restore "
                    + "verification.");
        }
    }

    /**
     * Only {@code true} and {@code false} are accepted, and anything else refuses the boot — the
     * same strictness, for the same reason, as {@link AuthOtpPolicy}'s own parser. Spring's relaxed
     * binding would silently accept {@code yes}/{@code no}/{@code on}/{@code off}/{@code 1}/{@code 0},
     * and for the one property that decides whether this platform verifies anybody, "which spellings
     * mean off?" is not a question worth leaving open. Defaulting a malformed value to {@code true}
     * would be no safer: it would hide a broken deployment behind behaviour that merely looks
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
                "Refusing to start: pronto.auth.otp-verification-enabled (OTP_VERIFICATION_ENABLED) "
                        + "is '" + raw + "', which is not a recognized value. Expected exactly 'true' "
                        + "or 'false'.");
    }
}
