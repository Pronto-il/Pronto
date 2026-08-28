package com.pronto.users.service;

import com.pronto.auth.config.VerificationPolicy;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The backend half of MS1's legacy-account policy: an account with an unproved phone number
 * authenticates normally but cannot perform the operations that end with a stranger arriving at
 * somebody's home.
 */
class ContactVerificationGuardTest {

    private static final Long USER_ID = 42L;

    private UserRepository userRepository;
    private ContactVerificationGuard guard;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        // Default to the STRICT policy, so every pre-existing expectation below still describes the
        // intended long-term rule rather than the temporary MS5 relaxation.
        guard = guardWithSmsRequired(true);
    }

    /** The guard wired to a given value of {@code pronto.verification.sms-required}. */
    private ContactVerificationGuard guardWithSmsRequired(boolean smsRequired) {
        return new ContactVerificationGuard(userRepository, new VerificationPolicy(smsRequired, true));
    }

    /** The guard wired to a given value of {@code pronto.verification.email-required}. */
    private ContactVerificationGuard guardWithEmailRequired(boolean emailRequired) {
        return new ContactVerificationGuard(userRepository, new VerificationPolicy(true, emailRequired));
    }

    private User account(boolean emailVerified, boolean phoneVerified) {
        User user = new User("Israel Israeli", "customer@example.com", "hash", UserRole.CUSTOMER);
        try {
            Field id = User.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(user, USER_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        user.setEmailVerified(emailVerified);
        user.setPhoneVerified(phoneVerified);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void bothChannelsVerified_passes() {
        account(true, true);

        assertThatCode(() -> guard.requireVerifiedContactChannels(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    void anUnverifiedPhone_isRefusedWithItsOwnActionableCode() {
        account(true, false);

        assertThatThrownBy(() -> guard.requireVerifiedContactChannels(USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        // Not a generic FORBIDDEN: the caller MAY do this, as soon as they finish a
                        // step that takes thirty seconds, and the client needs to be able to tell
                        // those apart to route them there instead of showing a dead end.
                        .isEqualTo(ErrorCode.PHONE_VERIFICATION_REQUIRED));
    }

    @Test
    void anUnverifiedEmail_isAlsoRefused() {
        account(false, true);

        assertThatThrownBy(() -> guard.requireVerifiedContactChannels(USER_ID))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aLegacyAccountWithNoPhoneAtAll_isRefused() {
        User user = account(true, false);
        user.setPhone(null);

        assertThatThrownBy(() -> guard.requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.PHONE_VERIFICATION_REQUIRED));
    }

    @Test
    void aSoftDeletedAccountIsUnauthorized_notMerelyUnverified() {
        User user = account(true, true);
        user.setDeletedAt(Instant.now());

        assertThatThrownBy(() -> guard.requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void aVanishedAccountIsUnauthorized() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void isFullyVerified_isTheSingleQuestionTheGateAsks() {
        assertThat(account(true, true).isFullyVerified()).isTrue();
        assertThat(account(true, false).isFullyVerified()).isFalse();
        assertThat(account(false, true).isFullyVerified()).isFalse();
        assertThat(account(false, false).isFullyVerified()).isFalse();
    }

    // ---- Production MS5: temporary email-only verification -------------------------------------
    //
    // AWS production SMS access is not approved, so pronto.verification.sms-required is false in
    // production. These tests pin BOTH halves of that decision: the phone requirement stops
    // blocking, and email verification does not quietly relax with it.

    @Test
    void smsNotRequired_verifiedEmailAlone_passes() {
        account(true, false);

        assertThatCode(() -> guardWithSmsRequired(false).requireVerifiedContactChannels(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void smsNotRequired_accountWithNoPhoneAtAll_passes() {
        // The case that would otherwise strand every account created while SMS is undeliverable.
        User user = account(true, false);
        user.setPhone(null);

        assertThatCode(() -> guardWithSmsRequired(false).requireVerifiedContactChannels(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void smsNotRequired_unverifiedEmail_isSTILLrefused() {
        // The security line. Relaxing the phone requirement must not relax the email one: this is
        // what stops "SMS is temporarily optional" from becoming "nothing is verified".
        account(false, false);

        assertThatThrownBy(() -> guardWithSmsRequired(false).requireVerifiedContactChannels(USER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED));
    }

    @Test
    void smsNotRequired_softDeletedAccount_isStillUnauthorized() {
        User user = account(true, true);
        user.setDeletedAt(Instant.now());

        assertThatThrownBy(() -> guardWithSmsRequired(false).requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void smsRequired_isTheDefaultWhenThePropertyIsAbsent() {
        // Guards against the relaxation silently becoming permanent: if the property is ever
        // dropped from configuration, the strict rule must be what comes back.
        assertThat(new VerificationPolicy(true, true).isSmsVerificationRequired()).isTrue();

        account(true, false);
        assertThatThrownBy(() -> guardWithSmsRequired(true).requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.PHONE_VERIFICATION_REQUIRED));
    }

    // ---- email-required, the closed-beta relaxation ---------------------------

    @Test
    void emailNotRequired_anUnverifiedEmail_reachesTheMarketplace() {
        // The whole point of the beta flag at this gate: a registered user who never received an
        // SES message can still create an issue, book and raise SOS. Note phoneVerified is true
        // here so that only the email rule is under test.
        account(false, true);

        assertThatCode(() -> guardWithEmailRequired(false).requireVerifiedContactChannels(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void emailNotRequired_doesNotAlsoRelaxThePhoneRule() {
        // The two halves are independent. Checking them through separate policy reads rather than
        // User#isFullyVerified() is what keeps this true; if either ever routes through the other,
        // this fails.
        account(false, false);

        assertThatThrownBy(() -> new ContactVerificationGuard(userRepository,
                new VerificationPolicy(true, false)).requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.PHONE_VERIFICATION_REQUIRED));
    }

    @Test
    void emailNotRequired_softDeletedAccount_isStillUnauthorized() {
        // The relaxation is about verification state only. A deleted account is not a verification
        // question and must stay refused under every setting.
        User user = account(false, true);
        user.setDeletedAt(Instant.now());

        assertThatThrownBy(() -> guardWithEmailRequired(false).requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void emailRequired_isTheDefaultWhenThePropertyIsAbsent() {
        // The same guard-against-permanence as the SMS case above.
        assertThat(new VerificationPolicy(true, true).isEmailVerificationRequired()).isTrue();

        account(false, true);
        assertThatThrownBy(() -> guardWithEmailRequired(true).requireVerifiedContactChannels(USER_ID))
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED));
    }
}
