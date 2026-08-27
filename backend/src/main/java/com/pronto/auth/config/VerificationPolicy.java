package com.pronto.auth.config;

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
 * <p><b>What it deliberately does NOT relax.</b> Email verification. Under either setting an
 * account is unverified until it has redeemed an emailed code, because that is the only proof the
 * address behind the account belongs to the person using it. See
 * {@code users.service.ContactVerificationGuard}, which requires email unconditionally and consults
 * this policy only for the phone half.
 *
 * <p>Three places read this, and they are the whole surface:
 * <ul>
 *   <li>{@code ContactVerificationGuard} -- the customer-side gate on issues, bookings and SOS</li>
 *   <li>{@code ProfessionalEligibility.PHONE_VERIFIED_JPQL} -- the professional-side discoverability
 *       rule, which reads this bean through SpEL so no repository signature has to change</li>
 *   <li>{@code AuthService#verifyEmail} -- whether to issue a phone challenge after the email one</li>
 * </ul>
 */
@Component("verificationPolicy")
public class VerificationPolicy {

    private final boolean smsVerificationRequired;

    public VerificationPolicy(
            @Value("${pronto.verification.sms-required:true}") boolean smsVerificationRequired) {
        this.smsVerificationRequired = smsVerificationRequired;
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
}
