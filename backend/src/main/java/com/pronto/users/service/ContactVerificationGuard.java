package com.pronto.users.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.entity.User;
import com.pronto.users.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Has this account proved both of its contact channels?" — the backend gate that keeps pre-MS1
 * accounts, and half-finished registrations, out of the marketplace.
 *
 * <p><b>Why a gate instead of simply blocking login.</b> Every account created before Production
 * MS1 has {@code phone_verified = false}, and most have no phone number at all — the column was
 * only ever populated for {@code CUSTOMER} registrations. Refusing those users a session would lock
 * out the entire existing user base to enforce a rule none of them had the chance to satisfy.
 * Instead they authenticate normally (email + password + email OTP, which proves the one channel
 * they did verify) and are stopped here, at the specific operations that need a reachable phone
 * number: this platform sends a professional to a customer's home, and both sides have to be
 * contactable when something goes wrong on the way.
 *
 * <p><b>This is the enforcement, not the UI.</b> The frontend routes a
 * {@code PHONE_VERIFICATION_REQUIRED} response to the phone-capture screen, but that is a
 * convenience — the rule holds against a direct API call with a perfectly valid JWT, which is the
 * only test that counts (roadmap §1.5).
 *
 * <p><b>The professional side is enforced elsewhere, deliberately.</b> A professional's phone
 * verification is folded into {@code ProfessionalEligibility.ELIGIBLE_JPQL}, so an unverified
 * professional is simply not discoverable — no listing, no Standard match, no SOS offer. Putting it
 * there rather than calling this guard from six places is what makes it impossible to add a seventh
 * consumer that forgets.
 */
@Component
public class ContactVerificationGuard {

    private final UserRepository userRepository;

    public ContactVerificationGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @throws ApiException {@code PHONE_VERIFICATION_REQUIRED} if the account's phone number is
     *                      missing or unverified; {@code UNAUTHORIZED} if the account is gone
     */
    @Transactional(readOnly = true)
    public void requireVerifiedContactChannels(Long userId) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED,
                        "User no longer exists or has been deleted."));

        if (!user.isFullyVerified()) {
            throw new ApiException(ErrorCode.PHONE_VERIFICATION_REQUIRED,
                    "Verify your phone number before continuing.");
        }
    }
}
