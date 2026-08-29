package com.pronto.auth.service;

import com.pronto.auth.config.AuthOtpPolicy;
import com.pronto.auth.config.OtpPolicies;
import com.pronto.auth.config.OtpVerificationPolicy;
import com.pronto.auth.config.VerificationPolicy;
import com.pronto.auth.dto.AuthNextStep;
import com.pronto.auth.dto.AuthStepResponse;
import com.pronto.auth.dto.CapturePhoneRequest;
import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.email.EmailSender;
import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.security.JwtService;
import com.pronto.auth.sms.SmsSender;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * <b>{@code OTP_VERIFICATION_ENABLED=false} — the current feedback/beta phase, end to end.</b>
 *
 * <p>{@code RegistrationAutoLoginTest} already covers what happens when the two fine-grained
 * verification flags are off. This file covers the thing that is actually deployed: <b>one master
 * switch</b>, set while every sub-flag is left at its {@code true} default, and the properties that
 * have to hold for a beta user to be able to use the product.
 *
 * <p>Every construction below passes {@code true, true} to {@link VerificationPolicy} and
 * {@code "true"} to {@link AuthOtpPolicy} on purpose. If the master gate ever stopped working, the
 * sub-flags would take over and every test here would fail — which is the point. A fixture that
 * also set the sub-flags to {@code false} would pass whether or not the master switch did anything
 * at all.
 *
 * <p>The mirrored {@code otpEnabled}-side assertions are kept beside the disabled ones deliberately.
 * The risk worth testing for is not "the bypass fails to work" — that is visible on the first
 * registration — it is "the bypass became the only behaviour", and only a side-by-side pair catches
 * that.
 */
class OtpVerificationDisabledTest {

    private static final String EMAIL = "beta@example.com";
    private static final String PHONE = "0502234567";
    private static final String PHONE_E164 = "+972502234567";
    private static final String PASSWORD = "StrongPassword123!";
    private static final String TOKEN = "issued.jwt.token";

    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final InMemoryVerificationCodes codes = new InMemoryVerificationCodes();
    private final EmailSender emailSender = Mockito.mock(EmailSender.class);
    private final SmsSender smsSender = Mockito.mock(SmsSender.class);
    private final JwtService jwtService = Mockito.mock(JwtService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private User saved;

    /**
     * The whole auth stack, built from the master switch alone.
     *
     * @param otpEnabled the value of {@code OTP_VERIFICATION_ENABLED}. Every other verification
     *                   setting is left at its secure default, so anything that changes below is
     *                   attributable to this one variable.
     */
    private AuthService serviceWith(boolean otpEnabled) {
        OtpVerificationPolicy master = otpEnabled ? OtpPolicies.enabled() : OtpPolicies.disabled();
        VerificationPolicy verificationPolicy = new VerificationPolicy(master, true, true);
        AuthOtpPolicy otpPolicy = new AuthOtpPolicy(master, "true");

        OtpService otpService = new OtpService(codes.repository(),
                new OtpChallengeWriter(codes.repository()),
                new OtpAttemptRecorder(codes.repository()), OtpServiceTest.TEST_PEPPER,
                emailSender, smsSender);

        AuthAccountWriter accountWriter = new AuthAccountWriter(userRepository,
                Mockito.mock(com.pronto.professionals.repository.ProfessionalRepository.class),
                Mockito.mock(com.pronto.availability.repository.SosAvailabilityRepository.class),
                Mockito.mock(com.pronto.professionals.repository.ProfessionalSubServiceRepository.class),
                Mockito.mock(com.pronto.professionals.repository.ProfessionalCategoryRepository.class),
                Mockito.mock(com.pronto.professionals.repository.ProfessionalServiceCityRepository.class),
                Mockito.mock(com.pronto.availability.repository.ProfessionalWorkingHoursRepository.class),
                Mockito.mock(com.pronto.storage.service.StorageService.class),
                passwordEncoder, new PhoneNumberNormalizer("IL"),
                Mockito.mock(LoginAttemptRecorder.class), otpService,
                Mockito.mock(com.pronto.maps.service.ServiceAddressGeocoder.class),
                new com.pronto.maps.service.SelectedPlaceValidator(), verificationPolicy);

        Mockito.lenient().when(userRepository.existsByEmail(anyString())).thenReturn(false);
        Mockito.lenient().when(userRepository.existsByPhone(anyString())).thenReturn(false);
        Mockito.lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            if (user.getId() == null) {
                InMemoryVerificationCodes.setField(user, "id", 100L);
            }
            saved = user;
            return user;
        });
        Mockito.lenient().when(userRepository.findById(100L)).thenAnswer(inv -> Optional.ofNullable(saved));
        Mockito.lenient().when(userRepository.findByEmail(anyString())).thenAnswer(inv ->
                EMAIL.equals(inv.getArgument(0)) ? Optional.ofNullable(saved) : Optional.empty());
        Mockito.lenient().when(userRepository.findByPhone(anyString())).thenAnswer(inv ->
                PHONE_E164.equals(inv.getArgument(0)) ? Optional.ofNullable(saved) : Optional.empty());
        Mockito.lenient().when(jwtService.generateToken(any(User.class))).thenReturn(TOKEN);
        Mockito.lenient().when(jwtService.getExpirationSeconds()).thenReturn(86400L);

        return new AuthService(userRepository, accountWriter, otpService, jwtService, passwordEncoder,
                Mockito.mock(com.pronto.professionals.service.ProfessionalCoverageService.class),
                Mockito.mock(com.pronto.locations.service.ServiceCoverageValidator.class),
                Mockito.mock(com.pronto.professionals.service.SubServiceSelectionValidator.class),
                verificationPolicy, otpPolicy,
                new com.pronto.maps.service.SelectedPlaceValidator());
    }

    private static RegisterRequest customerRequest() {
        // customer == null: registration collects no address (address-flow redesign).
        return new RegisterRequest(UserRole.CUSTOMER, "Israel Israeli", EMAIL, PHONE, PASSWORD,
                null, null);
    }

    // ---- registration completes and the user can continue -------------------------------------

    @Test
    void registrationReturnsAnAuthenticatedSessionImmediately() {
        AuthStepResponse response = serviceWith(false).register(customerRequest(), null, null);

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(response.session()).isNotNull();
        assertThat(response.session().token()).isEqualTo(TOKEN);
        assertThat(response.challenge()).isNull();
    }

    @Test
    void oneMasterSwitchOverridesAllThreeFineGrainedFlags() {
        // The property the master switch exists for. Every sub-flag above is left at `true`, so a
        // gate that had been wired to only one of them -- or to none -- fails right here.
        OtpVerificationPolicy master = OtpPolicies.disabled();

        assertThat(new VerificationPolicy(master, true, true).isEmailVerificationRequired()).isFalse();
        assertThat(new VerificationPolicy(master, true, true).isSmsVerificationRequired()).isFalse();
        assertThat(new AuthOtpPolicy(master, "true").isOtpRequired()).isFalse();
    }

    @Test
    void neitherSesNorSnsIsCalledAndNoChallengeRowIsWritten() {
        serviceWith(false).register(customerRequest(), null, null);

        verify(emailSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
        verify(smsSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
        assertThat(codes.all()).isEmpty();
    }

    @Test
    void registrationNeverFailsWithOtpDeliveryFailed() {
        // The failure this whole switch exists to end. Under the sandbox, a registration that
        // dispatched a code created the account and then answered 502, with no way forward.
        Mockito.doThrow(new RuntimeException("SES sandbox: recipient not verified"))
                .when(emailSender).sendOtp(anyString(), any(OtpPurpose.class), anyString());

        AuthStepResponse response = serviceWith(false).register(customerRequest(), null, null);

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
    }

    @Test
    void theAccountIsNotLeftPendingOrUnusable() {
        serviceWith(false).register(customerRequest(), null, null);

        assertThat(saved).isNotNull();
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getPhone()).isEqualTo(PHONE_E164);
    }

    // ---- and the columns still tell the truth --------------------------------------------------

    @Test
    void noChannelIsMarkedVerifiedInTheDatabase() {
        // The load-bearing assertion. Writing `true` here is the easy way to stop every gate
        // complaining, and it is the one change in this feature that would outlive the flag: the
        // beta cohort would be permanently recorded as having proved an address nobody ever
        // messaged, and re-enabling verification would silently grandfather all of them.
        AuthStepResponse response = serviceWith(false).register(customerRequest(), null, null);

        assertThat(saved.isEmailVerified()).isFalse();
        assertThat(saved.isPhoneVerified()).isFalse();
        assertThat(response.emailVerified()).isFalse();
        assertThat(response.phoneVerified()).isFalse();
    }

    // ---- the rest of the product treats the account as usable ----------------------------------

    @Test
    void loginCompletesOnThePasswordAlone() {
        AuthService service = serviceWith(false);
        service.register(customerRequest(), null, null);

        AuthStepResponse response = service.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(response.session()).isNotNull();
    }

    @Test
    void theRegisteredPhoneNumberStillWorksAsALoginIdentifier() {
        // The contradiction this had to fix. `resolveIdentifier` filters on phone_verified, which
        // no account can ever reach while verification is off -- so without a policy-conditional
        // filter, EVERY beta user is silently unable to sign in with the number they registered
        // with, and is told "invalid credentials" for a correct password.
        AuthService service = serviceWith(false);
        service.register(customerRequest(), null, null);

        AuthStepResponse response = service.login(new LoginRequest(PHONE, PASSWORD));

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(response.session()).isNotNull();
    }

    @Test
    void aWrongPasswordIsStillRefusedOnBothIdentifiers() {
        // Relaxing the phone filter must not relax the credential check behind it.
        AuthService service = serviceWith(false);
        service.register(customerRequest(), null, null);

        assertThat(catchThrowable(() -> service.login(new LoginRequest(PHONE, "wrong-password"))))
                .isInstanceOf(ApiException.class);
        assertThat(catchThrowable(() -> service.login(new LoginRequest(EMAIL, "wrong-password"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void phoneCaptureIsRefusedRatherThanSendingACodeNothingWillRedeem() {
        AuthService service = serviceWith(false);
        service.register(customerRequest(), null, null);

        Throwable thrown = catchThrowable(() ->
                service.capturePhone(100L, new CapturePhoneRequest("0503334444")));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(smsSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }

    // ---- duplicate and format validation is untouched ------------------------------------------

    @Test
    void duplicateEmailIsStillRefused() {
        AuthService service = serviceWith(false);
        Mockito.when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        Throwable thrown = catchThrowable(() -> service.register(customerRequest(), null, null));

        assertThat(((ApiException) thrown).getCode()).isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    void duplicatePhoneIsStillRefused() {
        AuthService service = serviceWith(false);
        Mockito.when(userRepository.existsByPhone(PHONE_E164)).thenReturn(true);

        Throwable thrown = catchThrowable(() -> service.register(customerRequest(), null, null));

        assertThat(((ApiException) thrown).getCode()).isEqualTo(ErrorCode.DUPLICATE_PHONE);
    }

    @Test
    void anUnreachablePhoneNumberIsStillRefused() {
        // A landline cannot receive an SMS. It is refused even though no SMS is being sent: the
        // number is stored as the account's second identity and has to be a real mobile line for
        // the day verification comes back on.
        AuthService service = serviceWith(false);
        RegisterRequest landline = new RegisterRequest(UserRole.CUSTOMER, "Israel Israeli", EMAIL,
                "036231234", PASSWORD, null, null);

        Throwable thrown = catchThrowable(() -> service.register(landline, null, null));

        assertThat(((ApiException) thrown).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ---- turning it back on restores the real flow ---------------------------------------------

    @Test
    void withOtpEnabledRegistrationChallengesAndDispatchesAsBefore() {
        AuthStepResponse response = serviceWith(true).register(customerRequest(), null, null);

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.VERIFY_EMAIL);
        assertThat(response.challenge()).isNotNull();
        assertThat(response.session()).isNull();
        assertThat(codes.all()).hasSize(1);
        verify(emailSender).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }

    @Test
    void withOtpEnabledAnUnverifiedPhoneIsAgainRefusedAsALoginIdentifier() {
        // The strict rule returns with no migration: the same account that could sign in by phone
        // above cannot once the switch is back on, because it never proved the number.
        AuthService disabled = serviceWith(false);
        disabled.register(customerRequest(), null, null);
        assertThat(saved.isPhoneVerified()).isFalse();

        Throwable thrown = catchThrowable(() ->
                serviceWith(true).login(new LoginRequest(PHONE, PASSWORD)));

        assertThat(((ApiException) thrown).getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void withOtpEnabledPhoneCaptureWorksAgain() {
        AuthService service = serviceWith(true);
        service.register(customerRequest(), null, null);

        service.capturePhone(100L, new CapturePhoneRequest("0503334444"));

        verify(smsSender).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }
}
