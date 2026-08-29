package com.pronto.auth.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which contact channels an account must prove before it counts as verified.
 *
 * <p><b>Why this exists.</b> Pronto's design rule is that both channels are proved: email, because
 * it is the account identity, and phone, because this platform sends a professional to a stranger's
 * home and both sides must be reachable when something goes wrong on the way. That rule is correct
 * and is still the default.
 *
 * <p>It is temporarily unsatisfiable in production. AWS End User Messaging production SMS access
 * has not been approved for this account, so no verification code can reach an Israeli handset.
 * With the rule enforced, every new account would stall at the phone step: registration would
 * complete, login would work, and then creating an issue, booking or SOS request would be refused
 * forever. An unreachable requirement is worse than a relaxed one, because it fails silently and
 * looks like a bug.
 *
 * <p><b>This is configuration, not a redesign.</b> Nothing about the SMS implementation is removed
 * or bypassed: {@code auth.sms}, {@code OtpPurpose.PHONE_VERIFICATION}, the phone column, the
 * capture screen and every test remain exactly as they were. One property decides whether an
 * unproved phone number BLOCKS, and flipping it back to {@code true} restores the original
 * behaviour with no code change and no data migration -- accounts verified while it was
 * {@code false} keep {@code phone_verified = false} and are then correctly asked to prove it.
 *
 * <p>Three places read the phone half, and they are its whole surface:
 * <ul>
 *   <li>{@code ContactVerificationGuard} -- the customer-side gate on issues, bookings and SOS</li>
 *   <li>{@code ProfessionalEligibility.PHONE_VERIFIED_JPQL} -- the professional-side discoverability
 *       rule, which reads this bean through SpEL so no repository signature has to change</li>
 *   <li>{@code AuthService#verifyEmail} -- whether to issue a phone challenge after the email one</li>
 * </ul>
 *
 * <h2>The email half</h2>
 *
 * <p>Email verification was, until the closed beta, the one channel this class did not make
 * conditional — the paragraph here used to say so explicitly. {@link #isEmailVerificationRequired()}
 * is the same shape of temporary, operator-named relaxation as the phone half, for the same kind of
 * reason: <b>AWS SES is still in the sandbox and Production Access has not been approved</b>, so SES
 * rejects every recipient address that has not itself been individually verified in the console. A
 * verification code that the provider refuses to send is not a weaker verification step; it is a
 * registration that always ends in {@code OTP_DELIVERY_FAILED}, for every real user, with the
 * account already created and no way forward.
 *
 * <p><b>Both halves default to {@code true} and neither is derived from
 * {@code pronto.environment}.</b> The bypass is reachable only by an operator who set
 * {@code EMAIL_VERIFICATION_REQUIRED=false} and meant it.
 *
 * <p>Four places read the email half, and they are its whole surface — verified by walking every
 * {@code isEmailVerified()} call site in {@code src/main}:
 * <ul>
 *   <li>{@code AuthService#register} -- whether to dispatch the {@code EMAIL_VERIFICATION} code</li>
 *   <li>{@code AuthService#login} -- whether an unproved address is challenged instead of signed in</li>
 *   <li>{@code ContactVerificationGuard} -- the gate on issues, bookings and SOS</li>
 *   <li>{@code AuthService#requestPasswordReset} -- which accounts may start a reset at all</li>
 * </ul>
 * Gating only the first would produce a beta in which everybody registers successfully and is then
 * unable to book <em>or</em> to recover a password, which is a worse failure than the one being
 * fixed because it looks like the product working.
 */
@Component("verificationPolicy")
public class VerificationPolicy {

    private static final Logger log = LoggerFactory.getLogger(VerificationPolicy.class);

    private final boolean smsVerificationRequired;
    private final boolean emailVerificationRequired;

    /**
     * Both halves are {@code AND}ed with {@link OtpVerificationPolicy} at construction rather than
     * at each getter, so the resolved values are what {@link #announce()} prints and what every
     * consumer reads — there is no state in which the log says one thing and a call site sees
     * another. {@code OTP_VERIFICATION_ENABLED=false} therefore reports both channels as not
     * required no matter what the two fine-grained variables say, and turning the master back on
     * restores exactly whatever they say.
     */
    public VerificationPolicy(
            OtpVerificationPolicy otpVerificationPolicy,
            @Value("${pronto.verification.sms-required:true}") boolean smsVerificationRequired,
            @Value("${pronto.verification.email-required:true}") boolean emailVerificationRequired) {
        boolean otpEnabled = otpVerificationPolicy.isOtpVerificationEnabled();
        this.smsVerificationRequired = otpEnabled && smsVerificationRequired;
        this.emailVerificationRequired = otpEnabled && emailVerificationRequired;
    }

    /**
     * {@code true} (the default) when an account must prove its phone number as well as its email
     * address. {@code false} while production SMS access is unavailable, which makes a verified
     * email sufficient.
     *
     * <p>Read from JPQL as {@code :#{@verificationPolicy.smsVerificationRequired}} -- the getter
     * name is therefore part of the contract, not an implementation detail.
     */
    public boolean isSmsVerificationRequired() {
        return smsVerificationRequired;
    }

    /**
     * {@code true} (the default) when a new account must redeem an emailed code before it is
     * treated as verified. {@code false} for the closed beta, while SES is sandboxed.
     *
     * <p><b>{@code false} does not write {@code email_verified = true} for anybody.</b> Accounts
     * created while it is off keep {@code email_verified = false}, exactly as the phone half keeps
     * {@code phone_verified = false} — the column continues to mean "this address was proved", and
     * nothing here makes it lie. That is what makes reversal a one-variable change: flip it back and
     * those accounts are asked to verify at their next login, through the {@code VERIFY_EMAIL}
     * branch that already exists for an abandoned registration. No migration, no backfill.
     */
    public boolean isEmailVerificationRequired() {
        return emailVerificationRequired;
    }

    /**
     * Announced at boot for the same reason {@link AuthOtpPolicy} announces itself: an operator
     * must not have to infer the state of a verification requirement from whether a message
     * arrived, because that inference cannot tell "the requirement is off" from "the requirement is
     * on and delivery is broken" — and here those two states are one property apart.
     */
    @PostConstruct
    void announce() {
        log.info("Contact verification requirements: email={} sms={}",
                emailVerificationRequired ? "REQUIRED" : "NOT REQUIRED",
                smsVerificationRequired ? "REQUIRED" : "NOT REQUIRED");
        if (!emailVerificationRequired) {
            log.warn("Email verification is DISABLED (pronto.verification.email-required=false, "
                    + "EMAIL_VERIFICATION_REQUIRED). Registration creates the account without "
                    + "dispatching an EMAIL_VERIFICATION code, and an unproved address no longer "
                    + "blocks login, booking or password reset. Accounts still record "
                    + "email_verified=false. Password checks, lockout, rate limiting and every route "
                    + "guard remain in force. Set EMAIL_VERIFICATION_REQUIRED=true once SES "
                    + "Production Access is approved.");
        }
    }
}
