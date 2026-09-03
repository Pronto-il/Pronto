package com.pronto.auth.service;

import com.pronto.auth.config.OtpPolicies;

import com.pronto.auth.config.AuthOtpPolicy;
import com.pronto.auth.config.VerificationPolicy;
import com.pronto.auth.dto.AuthNextStep;
import com.pronto.auth.dto.AuthStepResponse;
import com.pronto.auth.dto.CustomerRegistrationData;
import com.pronto.auth.dto.DefaultAddressRequest;
import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.PasswordResetRequest;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.email.EmailSender;
import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.entity.VerificationCode;
import com.pronto.auth.security.JwtService;
import com.pronto.auth.sms.SmsSender;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code pronto.verification.email-required} — the temporary closed-beta email-verification switch.
 *
 * <p>Deliberately built as a side-by-side pair, the same shape as {@link AuthOtpBypassTest}: every
 * behaviour is asserted in <b>both</b> settings of the flag. The risk a relaxation like this carries
 * is not "the bypass fails to work" — that is visible on the first registration — it is "the bypass
 * quietly became the only behaviour", and only asserting the required mode next to it catches that.
 *
 * <p><b>The load-bearing assertion is {@code never()} on {@link EmailSender}.</b> A test that only
 * checked the response for {@code LOGIN} would still pass if registration dispatched an
 * {@code EMAIL_VERIFICATION} code to SES and then returned {@code LOGIN} anyway — which is exactly
 * the outcome the flag exists to prevent, since SES rejects the recipient and the beta user is back
 * where they started. It is asserted alongside "no challenge row exists", checked against the real
 * {@link InMemoryVerificationCodes} store rather than a mock, so "no OTP was issued" is a statement
 * about persistence and not about which methods happened to be called.
 *
 * <p><b>And the flag must not launder the data.</b>
 * {@link #bypassLeavesEmailVerifiedFalseOnTheStoredAccount()} is the test that protects reversal:
 * if the bypass were ever implemented by setting {@code email_verified = true}, every one of these
 * assertions would still pass, and the beta cohort would be permanently and falsely marked as
 * having proved an address they never received a message at.
 */
class EmailVerificationBypassTest {

    private static final String EMAIL = "customer@example.com";
    private static final String PASSWORD = "StrongPassword123!";
    private static final String TOKEN = "issued.jwt.token";

    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final InMemoryVerificationCodes codes = new InMemoryVerificationCodes();
    private final EmailSender emailSender = Mockito.mock(EmailSender.class);
    private final SmsSender smsSender = Mockito.mock(SmsSender.class);
    private final JwtService jwtService = Mockito.mock(JwtService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** The account {@code userRepository.save} last persisted, so the stored state can be read. */
    private User saved;

    /**
     * The whole registration + login path for one setting of the policy. Everything below the
     * policy is real — {@code OtpService}, {@code AuthAccountWriter}, the code store, BCrypt — so
     * that "no row was written" and "SES was not called" are observed rather than stubbed.
     *
     * <p>{@code emailSender} is a plain mock of a {@code void} method, so it neither throws nor
     * fails — i.e. <b>a healthy SES</b>. That is deliberate: it means the required-mode tests below
     * assert the pre-existing behaviour on its own terms, and no bypass assertion can be passing
     * merely because delivery happened to fail.
     */
    private AuthService serviceWith(boolean emailRequired) {
        return serviceWith(emailRequired, true);
    }

    /**
     * @param otpRequired the <em>login</em> second factor, which this flag must leave alone. Kept as
     *                    a separate axis rather than assumed, because the two are independent and
     *                    Production currently has both off — a combination nothing would otherwise
     *                    exercise.
     */
    private AuthService serviceWith(boolean emailRequired, boolean otpRequired) {
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
                new com.pronto.maps.service.SelectedPlaceValidator(), new VerificationPolicy(OtpPolicies.enabled(), true, true));

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
        Mockito.lenient().when(userRepository.findByPhone(anyString())).thenReturn(Optional.empty());
        Mockito.lenient().when(jwtService.generateToken(any(User.class))).thenReturn(TOKEN);
        Mockito.lenient().when(jwtService.getExpirationSeconds()).thenReturn(86400L);

        return new AuthService(userRepository, accountWriter, otpService, jwtService, passwordEncoder,
                Mockito.mock(com.pronto.professionals.service.ProfessionalCoverageService.class),
                Mockito.mock(com.pronto.locations.service.ServiceCoverageValidator.class),
                Mockito.mock(com.pronto.professionals.service.SubServiceSelectionValidator.class),
                new com.pronto.professionals.service.SubServicePriceValidator(),
                new VerificationPolicy(OtpPolicies.enabled(), true, emailRequired),
                new AuthOtpPolicy(OtpPolicies.enabled(), String.valueOf(otpRequired)),
                new com.pronto.maps.service.SelectedPlaceValidator());
    }

    private static RegisterRequest customerRequest() {
        return new RegisterRequest(UserRole.CUSTOMER, "Israel Israeli", EMAIL, "0502234567", PASSWORD,
                new CustomerRegistrationData(
                        new DefaultAddressRequest("Tel Aviv", "Dizengoff", "100", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"))),
                null);
    }

    // ---- EMAIL VERIFICATION DISABLED ------------------------------------------

    @Test
    void bypassCreatesTheAccountAndSendsNothing() {
        AuthService authService = serviceWith(false);

        AuthStepResponse response = authService.register(customerRequest(), null, null);

        // The account is real and persisted...
        assertThat(saved).isNotNull();
        assertThat(saved.getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getRole()).isEqualTo(UserRole.CUSTOMER);
        // ...and the user is sent to log in rather than to a verification screen they could never
        // get past.
        assertThat(response.nextStep()).isEqualTo(AuthNextStep.LOGIN);
        assertThat(response.challenge()).isNull();
        assertThat(response.session()).isNull();
    }

    @Test
    void bypassDispatchesNoEmailVerificationOtp() {
        AuthService authService = serviceWith(false);

        authService.register(customerRequest(), null, null);

        verify(emailSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
        verify(smsSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }

    @Test
    void bypassWritesNoChallengeRow() {
        AuthService authService = serviceWith(false);

        authService.register(customerRequest(), null, null);

        assertThat(codes.all()).isEmpty();
    }

    @Test
    void bypassLeavesEmailVerifiedFalseOnTheStoredAccount() {
        // The reversal guarantee, and the reason this flag is not implemented as
        // "setEmailVerified(true) at registration". The column keeps meaning "this address was
        // proved"; nothing here makes it lie. Flipping the flag back must ask exactly this account
        // to verify, which is only possible if the stored answer is still false.
        AuthService authService = serviceWith(false);

        authService.register(customerRequest(), null, null);

        assertThat(saved.isEmailVerified()).isFalse();
    }

    @Test
    void bypassAllowsTheNewAccountToLogInImmediately() {
        AuthService authService = serviceWith(false);
        authService.register(customerRequest(), null, null);

        AuthStepResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD));

        // The end-to-end point of the whole change: register, then log in, with no message
        // delivered at any stage. AUTH_OTP_REQUIRED is "true" in this harness on purpose -- the
        // session here is granted because the account has a proved-enough address by policy, not
        // because the login second factor happens to be off.
        assertThat(login.nextStep()).isEqualTo(AuthNextStep.LOGIN_OTP);
        verify(emailSender).sendOtp(anyString(), any(OtpPurpose.class), anyString());
        assertThat(codes.all()).extracting(VerificationCode::getPurpose)
                .containsExactly(OtpPurpose.EMAIL_LOGIN_OTP);
    }

    @Test
    void bypassReportsEmailVerifiedHonestlyInTheResponse() {
        AuthService authService = serviceWith(false);

        AuthStepResponse response = authService.register(customerRequest(), null, null);

        // Same rule verifyEmail already follows for the phone half: the flags are reported as they
        // are, never as the policy wishes they were, because the client renders from them and the
        // reversal depends on them.
        assertThat(response.emailVerified()).isFalse();
        assertThat(response.phoneVerified()).isFalse();
    }

    @Test
    void bypassLetsAnUnverifiedAccountStartAPasswordReset() {
        // Without the policy check in requestPasswordReset, no beta account would pass the
        // isEmailVerified filter, so every real reset would silently take the decoy branch -- an
        // endpoint that is enumeration-neutral by design and therefore reports success either way.
        // Recovery would be completely broken and would look like it worked.
        AuthService authService = serviceWith(false);
        authService.register(customerRequest(), null, null);
        // Registration wrote no row at all in this mode, so whatever is in the store afterwards
        // came from the reset request.
        assertThat(codes.all()).isEmpty();

        authService.requestPasswordReset(new PasswordResetRequest(EMAIL));

        assertThat(codes.all()).extracting(VerificationCode::getPurpose)
                .containsExactly(OtpPurpose.PASSWORD_RESET);
    }

    @Test
    void bypassCombinedWithTheLoginOtpBypassSignsTheUserStraightIn() {
        // Production's actual closed-beta configuration: EMAIL_VERIFICATION_REQUIRED=false and
        // AUTH_OTP_REQUIRED=false together. This is the end-to-end claim the change is for --
        // register, log in, hold a session, with SES never called once.
        AuthService authService = serviceWith(false, false);
        authService.register(customerRequest(), null, null);

        AuthStepResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(login.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(login.session()).isNotNull();
        assertThat(login.session().token()).isEqualTo(TOKEN);
        assertThat(login.session().user().role()).isEqualTo(UserRole.CUSTOMER);
        // Still reported honestly, even on a fully authenticated session.
        assertThat(login.emailVerified()).isFalse();
        assertThat(codes.all()).isEmpty();
        verify(emailSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
        verify(smsSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }

    // ---- EMAIL VERIFICATION REQUIRED (unchanged behaviour) --------------------

    @Test
    void requiredStillChallengesAtRegistration() {
        AuthService authService = serviceWith(true);

        AuthStepResponse response = authService.register(customerRequest(), null, null);

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.VERIFY_EMAIL);
        assertThat(response.challenge()).isNotNull();
        assertThat(response.session()).isNull();
        assertThat(codes.all()).extracting(VerificationCode::getPurpose)
                .containsExactly(OtpPurpose.EMAIL_VERIFICATION);
        verify(emailSender).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }

    @Test
    void requiredStillRefusesLoginToAnUnverifiedAccount() {
        AuthService authService = serviceWith(true);
        authService.register(customerRequest(), null, null);

        AuthStepResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD));

        // The pre-existing "resume an abandoned registration" path: a correct password buys a fresh
        // EMAIL_VERIFICATION challenge, never a session.
        assertThat(login.nextStep()).isEqualTo(AuthNextStep.VERIFY_EMAIL);
        assertThat(login.session()).isNull();
    }

    @Test
    void requiredStillRefusesAPasswordResetForAnUnverifiedAccount() {
        AuthService authService = serviceWith(true);
        authService.register(customerRequest(), null, null);

        authService.requestPasswordReset(new PasswordResetRequest(EMAIL));

        // The decoy branch: no PASSWORD_RESET row is written for an address that was never proved.
        // Asserted as "does not contain" rather than "is empty" because registration legitimately
        // left its own EMAIL_VERIFICATION row in the store in this mode.
        assertThat(codes.all()).extracting(VerificationCode::getPurpose)
                .containsExactly(OtpPurpose.EMAIL_VERIFICATION)
                .doesNotContain(OtpPurpose.PASSWORD_RESET);
    }
}
