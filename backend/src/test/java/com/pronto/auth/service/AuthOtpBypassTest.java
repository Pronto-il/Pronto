package com.pronto.auth.service;

import com.pronto.auth.config.AuthOtpPolicy;
import com.pronto.auth.config.VerificationPolicy;
import com.pronto.auth.dto.AuthNextStep;
import com.pronto.auth.dto.AuthStepResponse;
import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.OtpSubmissionRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code pronto.auth.otp-required} — the temporary login second-factor switch.
 *
 * <p>Two properties are worth more than the rest of this file put together, and both are asserted
 * against the collaborators rather than the response:
 *
 * <ul>
 *   <li><b>Nothing is sent.</b> {@code SmsSender} and {@code EmailSender} are Mockito mocks and the
 *       bypass tests assert {@code never()} on both. A test that only checked for
 *       {@code AUTHENTICATED} would still pass if login issued a challenge, dispatched a real SMS,
 *       and then signed the user in anyway — which is the one outcome that would quietly keep
 *       spending an exhausted SMS budget.</li>
 *   <li><b>No challenge row exists.</b> Asserted against the real {@link InMemoryVerificationCodes}
 *       store, not a mock, so "no OTP DB row is created" is a statement about persistence rather
 *       than about which methods happened to be called.</li>
 * </ul>
 *
 * <p>The enabled-mode cases are deliberately kept here alongside the disabled ones. The risk this
 * change carries is not "the bypass fails to work" — that shows up immediately — it is "the bypass
 * silently became the only behaviour", and only a side-by-side pair catches that.
 */
class AuthOtpBypassTest {

    private static final String EMAIL = "customer@example.com";
    private static final String CANONICAL_PHONE = "+972502234567";
    private static final String PASSWORD = "StrongPassword123!";
    private static final String TOKEN = "issued.jwt.token";

    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final InMemoryVerificationCodes codes = new InMemoryVerificationCodes();
    private final EmailSender emailSender = Mockito.mock(EmailSender.class);
    private final SmsSender smsSender = Mockito.mock(SmsSender.class);
    private final JwtService jwtService = Mockito.mock(JwtService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final LoginAttemptRecorder loginAttemptRecorder = Mockito.mock(LoginAttemptRecorder.class);

    private User account;

    /**
     * Builds the whole login path for one setting of the policy. Everything below the policy is
     * real — {@code OtpService}, {@code OtpChallengeWriter}, the code store, BCrypt — so that
     * "no row was written" and "no code was generated" are observed rather than stubbed.
     */
    private AuthService serviceWith(boolean otpRequired, UserRole role) {
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
                loginAttemptRecorder, otpService,
                Mockito.mock(com.pronto.maps.service.ServiceAddressGeocoder.class),
                new com.pronto.maps.service.SelectedPlaceValidator());

        account = new User("Israel Israeli", EMAIL, passwordEncoder.encode(PASSWORD), role);
        InMemoryVerificationCodes.setField(account, "id", 42L);
        account.setPhone(CANONICAL_PHONE);
        account.setEmailVerified(true);
        account.setPhoneVerified(true);

        Mockito.lenient().when(userRepository.findById(42L)).thenReturn(Optional.of(account));
        Mockito.lenient().when(userRepository.findByEmail(anyString())).thenAnswer(inv ->
                EMAIL.equals(inv.getArgument(0)) ? Optional.of(account) : Optional.empty());
        Mockito.lenient().when(userRepository.findByPhone(anyString())).thenAnswer(inv ->
                CANONICAL_PHONE.equals(inv.getArgument(0)) ? Optional.of(account) : Optional.empty());
        Mockito.lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.lenient().when(jwtService.generateToken(any(User.class))).thenReturn(TOKEN);
        Mockito.lenient().when(jwtService.getExpirationSeconds()).thenReturn(86400L);

        return new AuthService(userRepository, accountWriter, otpService, jwtService, passwordEncoder,
                Mockito.mock(com.pronto.professionals.service.ProfessionalCoverageService.class),
                Mockito.mock(com.pronto.locations.service.ServiceCoverageValidator.class),
                Mockito.mock(com.pronto.professionals.service.SubServiceSelectionValidator.class),
                new VerificationPolicy(true, true), new AuthOtpPolicy(String.valueOf(otpRequired)),
                new com.pronto.maps.service.SelectedPlaceValidator());
    }

    // ---- OTP DISABLED ---------------------------------------------------------

    @ParameterizedTest(name = "otp-required=false signs a {0} in on the password alone")
    @EnumSource(value = UserRole.class, names = {"CUSTOMER", "PROFESSIONAL"})
    void bypassIssuesASessionImmediately(UserRole role) {
        AuthService authService = serviceWith(false, role);

        AuthStepResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(response.challenge()).isNull();
        assertThat(response.session()).isNotNull();
        assertThat(response.session().token()).isEqualTo(TOKEN);
        // The role must survive the bypass: a session that authenticated a professional as a
        // customer would pass every assertion about "a session was returned".
        assertThat(response.session().user().role()).isEqualTo(role);
        assertThat(response.emailVerified()).isTrue();
    }

    @Test
    void bypassCallsNeitherProvider() {
        AuthService authService = serviceWith(false, UserRole.CUSTOMER);

        authService.login(new LoginRequest(EMAIL, PASSWORD));

        verify(smsSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
        verify(emailSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }

    @Test
    void bypassWritesNoChallengeRow() {
        AuthService authService = serviceWith(false, UserRole.CUSTOMER);

        authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(codes.all()).isEmpty();
    }

    @ParameterizedTest(name = "otp-required=false bypasses for the {0} identifier too")
    @ValueSource(strings = {EMAIL, CANONICAL_PHONE})
    void bypassAppliesToBothLoginIdentifiers(String identifier) {
        // The identifier decides which login purpose would have been chosen — PHONE_LOGIN_OTP for a
        // phone, EMAIL_LOGIN_OTP for an address. Neither may survive the bypass.
        AuthService authService = serviceWith(false, UserRole.CUSTOMER);

        AuthStepResponse response = authService.login(new LoginRequest(identifier, PASSWORD));

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(codes.all()).isEmpty();
        verify(smsSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }

    @Test
    void bypassStillRejectsAWrongPassword() {
        AuthService authService = serviceWith(false, UserRole.CUSTOMER);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "WrongPassword123!")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        assertThat(codes.all()).isEmpty();
    }

    @Test
    void bypassStillCountsFailedAttemptsTowardsLockout() {
        // The lockout counter is persisted by LoginAttemptRecorder from AuthAccountWriter, on the
        // password check itself. If the bypass had been implemented by short-circuiting earlier in
        // the flow, brute-force protection would have gone with it.
        AuthService authService = serviceWith(false, UserRole.CUSTOMER);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "WrongPassword123!")))
                .isInstanceOf(ApiException.class);

        verify(loginAttemptRecorder).persistLockoutState(eq(42L), eq((short) 1), isNull());
    }

    @Test
    void bypassStillLocksTheAccountAfterRepeatedFailures() {
        AuthService authService = serviceWith(false, UserRole.CUSTOMER);
        account.setFailedLoginAttempts((short) (AuthAccountWriter.MAX_FAILED_LOGIN_ATTEMPTS - 1));

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "WrongPassword123!")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        // And the lock is then honoured even for the CORRECT password — the bypass must not be a
        // way around a locked account.
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    void bypassStillRefusesAnAccountThatNeverVerifiedItsEmail() {
        AuthService authService = serviceWith(false, UserRole.CUSTOMER);
        account.setEmailVerified(false);

        AuthStepResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));

        // Not a session: turning the login second factor off does not turn an unproved email
        // address into a proved one.
        assertThat(response.nextStep()).isEqualTo(AuthNextStep.VERIFY_EMAIL);
        assertThat(response.session()).isNull();
        assertThat(response.challenge()).isNotNull();
        verify(emailSender).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }

    // ---- OTP ENABLED (unchanged behaviour) ------------------------------------

    @Test
    void enabledStillChallengesAndWithholdsTheSession() {
        AuthService authService = serviceWith(true, UserRole.CUSTOMER);

        AuthStepResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.LOGIN_OTP);
        assertThat(response.session()).isNull();
        assertThat(response.challenge()).isNotNull();
        assertThat(codes.all()).hasSize(1);
        verify(emailSender).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }

    @Test
    void enabledStillCompletesTheFullTwoStepLogin() {
        AuthService authService = serviceWith(true, UserRole.CUSTOMER);

        AuthStepResponse challenge = authService.login(new LoginRequest(EMAIL, PASSWORD));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendOtp(anyString(), any(OtpPurpose.class), code.capture());

        AuthStepResponse authenticated = authService.loginOtp(
                new OtpSubmissionRequest(challenge.challenge().challengeId(), code.getValue()));

        assertThat(authenticated.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(authenticated.session().token()).isEqualTo(TOKEN);
    }

    @Test
    void enabledAndDisabledIssueTheSameSessionShape() {
        // The point of AuthService#authenticatedSession. If the bypass ever grows its own
        // AuthStepResponse construction, this is what notices.
        AuthStepResponse viaBypass = serviceWith(false, UserRole.CUSTOMER)
                .login(new LoginRequest(EMAIL, PASSWORD));

        AuthService enabled = serviceWith(true, UserRole.CUSTOMER);
        AuthStepResponse challenge = enabled.login(new LoginRequest(EMAIL, PASSWORD));
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendOtp(anyString(), any(OtpPurpose.class), code.capture());
        AuthStepResponse viaOtp = enabled.loginOtp(
                new OtpSubmissionRequest(challenge.challenge().challengeId(), code.getValue()));

        assertThat(viaBypass.nextStep()).isEqualTo(viaOtp.nextStep());
        assertThat(viaBypass.emailVerified()).isEqualTo(viaOtp.emailVerified());
        assertThat(viaBypass.phoneVerified()).isEqualTo(viaOtp.phoneVerified());
        assertThat(viaBypass.session().tokenType()).isEqualTo(viaOtp.session().tokenType());
        assertThat(viaBypass.session().expiresIn()).isEqualTo(viaOtp.session().expiresIn());
        assertThat(viaBypass.session().user()).isEqualTo(viaOtp.session().user());
    }
}
