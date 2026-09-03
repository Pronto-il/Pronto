package com.pronto.auth.service;

import com.pronto.auth.config.OtpPolicies;

import com.pronto.auth.config.AuthOtpPolicy;
import com.pronto.auth.config.VerificationPolicy;
import com.pronto.auth.dto.AuthNextStep;
import com.pronto.auth.dto.AuthStepResponse;
import com.pronto.auth.dto.CapturePhoneRequest;
import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.OtpChallengeResponse;
import com.pronto.auth.dto.OtpSubmissionRequest;
import com.pronto.auth.dto.PasswordResetConfirmRequest;
import com.pronto.auth.dto.PasswordResetRequest;
import com.pronto.auth.dto.ResendOtpRequest;
import com.pronto.auth.email.EmailSender;
import com.pronto.auth.entity.OtpChannel;
import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.security.JwtService;
import com.pronto.auth.sms.SmsSender;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Production MS1 flows end to end at the service layer: registration verification sequence,
 * dual-identifier login, the second factor, password recovery, and the legacy-account path.
 *
 * <p>The assertion this suite exists for, and repeats deliberately at every step, is
 * <b>{@code session() == null}</b>. MS1's headline rule is that a password alone never yields a
 * token; the only way to keep that true as these flows change is to state it at each point a token
 * could plausibly have leaked into a response.
 *
 * <p>A <b>real BCrypt encoder</b> is used rather than a mock, because "the password check actually
 * rejects the wrong password" is precisely what a stubbed {@code matches()} would stop testing.
 */
class AuthFlowTest {

    private static final String EMAIL = "customer@example.com";
    private static final String RAW_PHONE = "050-223-4567";
    private static final String CANONICAL_PHONE = "+972502234567";
    private static final String PASSWORD = "StrongPassword123!";
    private static final String TOKEN = "issued.jwt.token";

    private UserRepository userRepository;
    private InMemoryVerificationCodes codes;
    private EmailSender emailSender;
    private SmsSender smsSender;
    private JwtService jwtService;
    private AuthService authService;
    private User account;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        codes = new InMemoryVerificationCodes();
        emailSender = Mockito.mock(EmailSender.class);
        smsSender = Mockito.mock(SmsSender.class);
        jwtService = Mockito.mock(JwtService.class);
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

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
                Mockito.mock(LoginAttemptRecorder.class), otpService, Mockito.mock(com.pronto.maps.service.ServiceAddressGeocoder.class),
                new com.pronto.maps.service.SelectedPlaceValidator(), new VerificationPolicy(OtpPolicies.enabled(), true, true));

        authService = new AuthService(userRepository, accountWriter, otpService, jwtService,
                passwordEncoder,
                Mockito.mock(com.pronto.professionals.service.ProfessionalCoverageService.class),
                Mockito.mock(com.pronto.locations.service.ServiceCoverageValidator.class),
                Mockito.mock(com.pronto.professionals.service.SubServiceSelectionValidator.class),
                new com.pronto.professionals.service.SubServicePriceValidator(),
                new VerificationPolicy(OtpPolicies.enabled(), true, true), new AuthOtpPolicy(OtpPolicies.enabled(), "true"),
                new com.pronto.maps.service.SelectedPlaceValidator());

        account = new User("Israel Israeli", EMAIL, passwordEncoder.encode(PASSWORD), UserRole.CUSTOMER);
        InMemoryVerificationCodes.setField(account, "id", 42L);
        account.setPhone(CANONICAL_PHONE);
        account.setEmailVerified(true);
        account.setPhoneVerified(true);

        Mockito.lenient().when(userRepository.findById(42L)).thenReturn(Optional.of(account));
        Mockito.lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        Mockito.lenient().when(userRepository.findByEmail(anyString())).thenAnswer(inv ->
                EMAIL.equals(inv.getArgument(0)) ? Optional.of(account) : Optional.empty());
        Mockito.lenient().when(userRepository.findByPhone(anyString())).thenAnswer(inv ->
                CANONICAL_PHONE.equals(inv.getArgument(0)) ? Optional.of(account) : Optional.empty());
        Mockito.lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.lenient().when(jwtService.generateToken(any(User.class))).thenReturn(TOKEN);
        Mockito.lenient().when(jwtService.getExpirationSeconds()).thenReturn(86400L);
    }

    // ---- helpers --------------------------------------------------------------

    private String lastEmailCode() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(emailSender, Mockito.atLeastOnce()).sendOtp(anyString(), any(OtpPurpose.class), captor.capture());
        return captor.getValue();
    }

    private String lastSmsCode() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(smsSender, Mockito.atLeastOnce()).sendOtp(anyString(), any(OtpPurpose.class), captor.capture());
        return captor.getValue();
    }

    private static ApiException thrownBy(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        try {
            callable.call();
        } catch (Throwable t) {
            assertThat(t).isInstanceOf(ApiException.class);
            return (ApiException) t;
        }
        throw new AssertionError("expected an ApiException");
    }

    // ---- login: the second factor ---------------------------------------------

    @Test
    void login_withACorrectPassword_returnsAChallengeAndNoToken() {
        AuthStepResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.LOGIN_OTP);
        assertThat(response.session()).as("a password alone must never yield a session").isNull();
        assertThat(response.challenge()).isNotNull();
        assertThat(response.challenge().challengeId()).isNotNull();
        assertThat(response.challenge().channel()).isEqualTo(OtpChannel.EMAIL);
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void loginOtp_withTheCorrectCode_isTheOnlyThingThatIssuesAToken() {
        AuthStepResponse challenge = authService.login(new LoginRequest(EMAIL, PASSWORD));

        AuthStepResponse authenticated = authService.loginOtp(
                new OtpSubmissionRequest(challenge.challenge().challengeId(), lastEmailCode()));

        assertThat(authenticated.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(authenticated.session()).isNotNull();
        assertThat(authenticated.session().token()).isEqualTo(TOKEN);
        assertThat(authenticated.session().user().id()).isEqualTo(42L);
    }

    @Test
    void login_byPhone_sendsAnSmsCodeAndResolvesTheSameUserRow() {
        AuthStepResponse byEmail = authService.login(new LoginRequest(EMAIL, PASSWORD));
        AuthStepResponse authenticatedByEmail = authService.loginOtp(
                new OtpSubmissionRequest(byEmail.challenge().challengeId(), lastEmailCode()));

        AuthStepResponse byPhone = authService.login(new LoginRequest(RAW_PHONE, PASSWORD));
        assertThat(byPhone.challenge().channel()).isEqualTo(OtpChannel.SMS);
        assertThat(byPhone.session()).isNull();

        AuthStepResponse authenticatedByPhone = authService.loginOtp(
                new OtpSubmissionRequest(byPhone.challenge().challengeId(), lastSmsCode()));

        // The requirement in one assertion: two identifiers, one account.
        assertThat(authenticatedByPhone.session().user().id())
                .isEqualTo(authenticatedByEmail.session().user().id())
                .isEqualTo(42L);
    }

    @Test
    void login_acceptsEveryAcceptedPhoneSpelling_asTheSameIdentity() {
        for (String spelling : new String[]{"0502234567", "050-223-4567", "+972502234567", "00972502234567"}) {
            AuthStepResponse response = authService.login(new LoginRequest(spelling, PASSWORD));
            assertThat(response.nextStep()).as("spelling %s", spelling).isEqualTo(AuthNextStep.LOGIN_OTP);
            assertThat(response.challenge().channel()).isEqualTo(OtpChannel.SMS);
            // Each issue consumes the previous one, so this also demonstrates resend invalidation
            // across repeated logins.
            codes.all().forEach(c -> InMemoryVerificationCodes.backdate(c, 3601));
        }
    }

    @Test
    void login_wrongPassword_isRefusedWithoutIssuingAnyChallenge() {
        assertThat(thrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong-password"))).getCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(codes.all()).isEmpty();
    }

    @Test
    void login_unknownIdentifier_isIndistinguishableFromAWrongPassword() {
        assertThat(thrownBy(() -> authService.login(new LoginRequest("nobody@example.com", PASSWORD)))
                .getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void login_byAnUnverifiedPhone_isRefused_soAnUntestedNumberIsNeverACredential() {
        account.setPhoneVerified(false);

        assertThat(thrownBy(() -> authService.login(new LoginRequest(RAW_PHONE, PASSWORD))).getCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void login_whileLockedOut_isRefusedBeforeThePasswordIsEvenChecked() {
        account.setLockedUntil(java.time.Instant.now().plusSeconds(600));

        assertThat(thrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD))).getCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
        assertThat(codes.all()).isEmpty();
    }

    @Test
    void loginOtp_wrongCode_yieldsNoToken() {
        AuthStepResponse challenge = authService.login(new LoginRequest(EMAIL, PASSWORD));
        String correct = lastEmailCode();
        String wrong = correct.equals("000000") ? "111111" : "000000";

        assertThat(thrownBy(() -> authService.loginOtp(
                new OtpSubmissionRequest(challenge.challenge().challengeId(), wrong))).getCode())
                .isEqualTo(ErrorCode.INVALID_CODE);
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void loginOtp_aReusedCode_yieldsNoSecondToken() {
        AuthStepResponse challenge = authService.login(new LoginRequest(EMAIL, PASSWORD));
        String code = lastEmailCode();
        authService.loginOtp(new OtpSubmissionRequest(challenge.challenge().challengeId(), code));

        assertThat(thrownBy(() -> authService.loginOtp(
                new OtpSubmissionRequest(challenge.challenge().challengeId(), code))).getCode())
                .isEqualTo(ErrorCode.CODE_ALREADY_CONSUMED);
    }

    @Test
    void loginOtp_anExpiredCode_yieldsNoToken() {
        AuthStepResponse challenge = authService.login(new LoginRequest(EMAIL, PASSWORD));
        String code = lastEmailCode();
        InMemoryVerificationCodes.expire(codes.newest());

        assertThat(thrownBy(() -> authService.loginOtp(
                new OtpSubmissionRequest(challenge.challenge().challengeId(), code))).getCode())
                .isEqualTo(ErrorCode.CODE_EXPIRED);
    }

    @Test
    void loginOtp_refusesAContactVerificationChallenge_soTheRegistrationSequenceCannotBeSkipped() {
        account.setEmailVerified(false);
        AuthStepResponse resume = authService.login(new LoginRequest(EMAIL, PASSWORD));
        assertThat(resume.nextStep()).isEqualTo(AuthNextStep.VERIFY_EMAIL);

        assertThat(thrownBy(() -> authService.loginOtp(
                new OtpSubmissionRequest(resume.challenge().challengeId(), lastEmailCode()))).getCode())
                .isEqualTo(ErrorCode.INVALID_CODE);
        verify(jwtService, never()).generateToken(any());
    }

    // ---- registration verification sequence -----------------------------------

    @Test
    void verifyEmail_thenVerifyPhone_isTheOnlyOtherPathToAToken() {
        account.setEmailVerified(false);
        account.setPhoneVerified(false);

        AuthStepResponse afterLogin = authService.login(new LoginRequest(EMAIL, PASSWORD));
        AuthStepResponse afterEmail = authService.verifyEmail(
                new OtpSubmissionRequest(afterLogin.challenge().challengeId(), lastEmailCode()));

        assertThat(afterEmail.nextStep()).isEqualTo(AuthNextStep.VERIFY_PHONE);
        assertThat(afterEmail.emailVerified()).isTrue();
        assertThat(afterEmail.phoneVerified()).isFalse();
        assertThat(afterEmail.session()).as("one channel proved is not enough").isNull();

        AuthStepResponse afterPhone = authService.verifyPhone(
                new OtpSubmissionRequest(afterEmail.challenge().challengeId(), lastSmsCode()));

        assertThat(afterPhone.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(afterPhone.session().token()).isEqualTo(TOKEN);
        assertThat(account.isPhoneVerified()).isTrue();
    }

    @Test
    void verifyPhone_beforeTheEmailIsVerified_isRefused() {
        account.setEmailVerified(false);
        account.setPhoneVerified(false);
        OtpChallengeResponse phoneChallenge = authService.capturePhone(42L,
                new CapturePhoneRequest(RAW_PHONE));

        assertThat(thrownBy(() -> authService.verifyPhone(
                new OtpSubmissionRequest(phoneChallenge.challengeId(), lastSmsCode()))).getCode())
                .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void verifyEmail_onAnAccountWithNoPhoneOnFile_stopsAtLoginRatherThanStranding() {
        account.setEmailVerified(false);
        account.setPhoneVerified(false);
        account.setPhone(null);

        AuthStepResponse afterLogin = authService.login(new LoginRequest(EMAIL, PASSWORD));
        AuthStepResponse afterEmail = authService.verifyEmail(
                new OtpSubmissionRequest(afterLogin.challenge().challengeId(), lastEmailCode()));

        assertThat(afterEmail.nextStep()).isEqualTo(AuthNextStep.LOGIN);
        assertThat(afterEmail.challenge()).isNull();
        assertThat(afterEmail.session()).isNull();
        verify(smsSender, never()).sendOtp(anyString(), any(), anyString());
    }

    // ---- resend ---------------------------------------------------------------

    @Test
    void resend_issuesAFreshCodeAndKillsTheOldOne() {
        AuthStepResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD));
        String firstCode = lastEmailCode();
        InMemoryVerificationCodes.backdate(codes.newest(), OtpService.RESEND_COOLDOWN_SECONDS + 1);

        OtpChallengeResponse resent = authService.resend(
                new ResendOtpRequest(login.challenge().challengeId()));

        assertThat(resent.challengeId()).isNotEqualTo(login.challenge().challengeId());
        assertThat(thrownBy(() -> authService.loginOtp(
                new OtpSubmissionRequest(login.challenge().challengeId(), firstCode))).getCode())
                .isEqualTo(ErrorCode.CODE_ALREADY_CONSUMED);

        // The new code works.
        AuthStepResponse authenticated = authService.loginOtp(
                new OtpSubmissionRequest(resent.challengeId(), lastEmailCode()));
        assertThat(authenticated.session()).isNotNull();
    }

    @Test
    void resend_forAnAlreadyVerifiedEmail_isRefused_soAStaleTabCannotKeepSendingMail() {
        account.setEmailVerified(false);
        AuthStepResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD));
        authService.verifyEmail(new OtpSubmissionRequest(login.challenge().challengeId(), lastEmailCode()));

        assertThat(thrownBy(() -> authService.resend(
                new ResendOtpRequest(login.challenge().challengeId()))).getCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_VERIFIED);
    }

    @Test
    void resend_withinTheCooldown_isRateLimited() {
        AuthStepResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(thrownBy(() -> authService.resend(
                new ResendOtpRequest(login.challenge().challengeId()))).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    // ---- resend on the SMS half of the flow ------------------------------------
    //
    // Every resend test above this line rides an EMAIL challenge. The SMS half had none, which is
    // how a phone-verification resend defect could ship: nothing exercised a PHONE_VERIFICATION
    // challenge through authService.resend at all.

    /** Puts the account on the phone step of registration, the way a real registrant gets there. */
    private OtpChallengeResponse atThePhoneStep() {
        account.setEmailVerified(false);
        account.setPhoneVerified(false);
        AuthStepResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD));
        AuthStepResponse afterEmail = authService.verifyEmail(
                new OtpSubmissionRequest(login.challenge().challengeId(), lastEmailCode()));
        assertThat(afterEmail.nextStep()).isEqualTo(AuthNextStep.VERIFY_PHONE);
        return afterEmail.challenge();
    }

    @Test
    void resendOnThePhoneStep_sendsAnotherSmsAndTheNewCodeCompletesRegistration() {
        OtpChallengeResponse first = atThePhoneStep();
        verify(smsSender, Mockito.times(1)).sendOtp(anyString(), any(), anyString());
        InMemoryVerificationCodes.backdate(codes.newest(), OtpService.RESEND_COOLDOWN_SECONDS + 1);

        OtpChallengeResponse resent = authService.resend(new ResendOtpRequest(first.challengeId()));

        assertThat(resent.challengeId()).isNotEqualTo(first.challengeId());
        assertThat(resent.channel()).isEqualTo(OtpChannel.SMS);
        assertThat(resent.delivered()).isTrue();
        // A second real message, not merely a second 200.
        verify(smsSender, Mockito.times(2)).sendOtp(anyString(), any(), anyString());

        AuthStepResponse done = authService.verifyPhone(
                new OtpSubmissionRequest(resent.challengeId(), lastSmsCode()));
        assertThat(done.session()).isNotNull();
        assertThat(account.isPhoneVerified()).isTrue();
    }

    /** Both sends address the same canonical number — one normalizer, one stored spelling. */
    @Test
    void everySmsInTheFlowGoesToTheSameCanonicalE164Number() {
        OtpChallengeResponse first = atThePhoneStep();
        InMemoryVerificationCodes.backdate(codes.newest(), OtpService.RESEND_COOLDOWN_SECONDS + 1);
        authService.resend(new ResendOtpRequest(first.challengeId()));

        ArgumentCaptor<String> destinations = ArgumentCaptor.forClass(String.class);
        verify(smsSender, Mockito.times(2))
                .sendOtp(destinations.capture(), any(OtpPurpose.class), anyString());
        assertThat(destinations.getAllValues()).containsExactly(CANONICAL_PHONE, CANONICAL_PHONE);
    }

    @Test
    void resendOnThePhoneStep_withinTheCooldown_isRateLimitedAndSendsNothing() {
        OtpChallengeResponse first = atThePhoneStep();

        assertThat(thrownBy(() -> authService.resend(
                new ResendOtpRequest(first.challengeId()))).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        verify(smsSender, Mockito.times(1)).sendOtp(anyString(), any(), anyString());
    }

    @Test
    void resend_forAnAlreadyVerifiedPhone_isRefused_soAStaleTabCannotKeepSendingSms() {
        OtpChallengeResponse first = atThePhoneStep();
        authService.verifyPhone(new OtpSubmissionRequest(first.challengeId(), lastSmsCode()));
        Mockito.reset(smsSender);

        assertThat(thrownBy(() -> authService.resend(
                new ResendOtpRequest(first.challengeId()))).getCode())
                .isEqualTo(ErrorCode.PHONE_ALREADY_VERIFIED);
        verify(smsSender, never()).sendOtp(anyString(), any(), anyString());
    }

    /** A refused provider is reported as a failure, never as a successful resend. */
    @Test
    void resend_whenTheProviderRefuses_reportsDeliveryFailureAndLeavesTheOldCodeUsable() {
        OtpChallengeResponse first = atThePhoneStep();
        String firstCode = lastSmsCode();
        InMemoryVerificationCodes.backdate(codes.newest(), OtpService.RESEND_COOLDOWN_SECONDS + 1);

        Mockito.doThrow(new ApiException(ErrorCode.OTP_DELIVERY_FAILED, "provider refused"))
                .when(smsSender).sendOtp(anyString(), any(), anyString());
        assertThat(thrownBy(() -> authService.resend(
                new ResendOtpRequest(first.challengeId()))).getCode())
                .isEqualTo(ErrorCode.OTP_DELIVERY_FAILED);

        Mockito.reset(smsSender);
        assertThat(authService.verifyPhone(new OtpSubmissionRequest(first.challengeId(), firstCode))
                .session())
                .as("the code they still hold was not destroyed by the failed replacement")
                .isNotNull();
    }

    /**
     * The reported bug, end to end. The provider refuses one resend; the client shows "we could not
     * send it, try again"; the user taps again. That second tap must send an SMS, not be refused
     * for a message that never left the building.
     */
    @Test
    void resendAfterAFailedSmsDispatch_isNotBlockedByTheAttemptThatFailed() {
        OtpChallengeResponse first = atThePhoneStep();
        InMemoryVerificationCodes.backdate(codes.newest(), OtpService.RESEND_COOLDOWN_SECONDS + 1);

        Mockito.doThrow(new ApiException(ErrorCode.OTP_DELIVERY_FAILED, "provider refused"))
                .when(smsSender).sendOtp(anyString(), any(), anyString());
        assertThat(thrownBy(() -> authService.resend(
                new ResendOtpRequest(first.challengeId()))).getCode())
                .isEqualTo(ErrorCode.OTP_DELIVERY_FAILED);

        Mockito.reset(smsSender);
        OtpChallengeResponse retry = authService.resend(new ResendOtpRequest(first.challengeId()));

        assertThat(retry.delivered()).isTrue();
        verify(smsSender).sendOtp(eq(CANONICAL_PHONE), eq(OtpPurpose.PHONE_VERIFICATION), anyString());
        assertThat(authService.verifyPhone(new OtpSubmissionRequest(retry.challengeId(), lastSmsCode()))
                .session()).isNotNull();
    }

    // ---- legacy accounts / phone capture --------------------------------------

    @Test
    void capturePhone_normalizesStoresAndSendsACodeWithoutVerifyingAnything() {
        account.setPhone(null);
        account.setPhoneVerified(false);

        OtpChallengeResponse challenge = authService.capturePhone(42L, new CapturePhoneRequest(RAW_PHONE));

        assertThat(account.getPhone()).isEqualTo(CANONICAL_PHONE);
        assertThat(account.isPhoneVerified()).as("supplying is not proving").isFalse();
        assertThat(challenge.channel()).isEqualTo(OtpChannel.SMS);
    }

    @Test
    void capturePhone_onAnAlreadyVerifiedNumber_isRefused() {
        assertThat(thrownBy(() -> authService.capturePhone(42L, new CapturePhoneRequest("0522234567")))
                .getCode()).isEqualTo(ErrorCode.PHONE_ALREADY_VERIFIED);
    }

    @Test
    void capturePhone_aNumberBelongingToAnotherAccount_isRefused() {
        account.setPhone(null);
        account.setPhoneVerified(false);
        User other = new User("Other", "other@example.com", "hash", UserRole.CUSTOMER);
        InMemoryVerificationCodes.setField(other, "id", 99L);
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(other));

        assertThat(thrownBy(() -> authService.capturePhone(42L, new CapturePhoneRequest(RAW_PHONE)))
                .getCode()).isEqualTo(ErrorCode.DUPLICATE_PHONE);
    }

    @Test
    void capturePhone_rejectsAnInvalidNumberBeforeStoringIt() {
        account.setPhone(null);
        account.setPhoneVerified(false);

        assertThat(thrownBy(() -> authService.capturePhone(42L, new CapturePhoneRequest("not-a-number")))
                .getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(account.getPhone()).isNull();
    }

    // ---- password recovery ----------------------------------------------------

    @Test
    void passwordReset_happyPath_changesTheHashAndClearsTheLockout() {
        account.setFailedLoginAttempts((short) 3);
        OtpChallengeResponse challenge = authService.requestPasswordReset(new PasswordResetRequest(EMAIL));
        String oldHash = account.getPasswordHash();

        authService.confirmPasswordReset(new PasswordResetConfirmRequest(
                challenge.challengeId(), lastEmailCode(), "BrandNewPassword456!"));

        assertThat(account.getPasswordHash()).isNotEqualTo(oldHash);
        assertThat(account.getFailedLoginAttempts()).isZero();
        assertThat(account.getLockedUntil()).isNull();
        assertThat(new BCryptPasswordEncoder().matches("BrandNewPassword456!", account.getPasswordHash()))
                .isTrue();
    }

    @Test
    void passwordReset_forAnUnknownAccount_answersIdenticallyToAKnownOne() {
        OtpChallengeResponse known = authService.requestPasswordReset(new PasswordResetRequest(EMAIL));
        OtpChallengeResponse unknown = authService.requestPasswordReset(
                new PasswordResetRequest("nobody@example.com"));

        // Same shape, same channel, same claimed delivery, a well-formed challenge id in both cases.
        // The only difference is that one of them refers to something.
        assertThat(unknown.challengeId()).isNotNull();
        assertThat(unknown.channel()).isEqualTo(known.channel());
        assertThat(unknown.delivered()).isEqualTo(known.delivered()).isTrue();
        assertThat(unknown.expiresInSeconds()).isEqualTo(known.expiresInSeconds());
        assertThat(unknown.destinationMasked()).isEqualTo("n***@example.com");
        verify(emailSender, Mockito.times(1)).sendOtp(anyString(), any(), anyString());
    }

    @Test
    void passwordReset_confirmingADecoyChallenge_failsExactlyAsAWrongCodeDoes() {
        OtpChallengeResponse decoy = authService.requestPasswordReset(
                new PasswordResetRequest("nobody@example.com"));

        assertThat(thrownBy(() -> authService.confirmPasswordReset(new PasswordResetConfirmRequest(
                decoy.challengeId(), "123456", "BrandNewPassword456!"))).getCode())
                .isEqualTo(ErrorCode.INVALID_CODE);
    }

    @Test
    void passwordReset_whenRateLimited_stillAnswersNeutrally_ratherThanRevealingTheAccount() {
        for (int i = 0; i < OtpService.MAX_ISSUES_PER_HOUR; i++) {
            authService.requestPasswordReset(new PasswordResetRequest(EMAIL));
        }

        // The (n+1)-th request would throw RATE_LIMITED if the account's existence leaked through.
        OtpChallengeResponse throttled = authService.requestPasswordReset(new PasswordResetRequest(EMAIL));
        assertThat(throttled.challengeId()).isNotNull();
        assertThat(throttled.delivered()).isTrue();
    }

    @Test
    void passwordReset_invalidatesOutstandingLoginChallenges() {
        // The scenario: somebody who knew the old password has a live login challenge in flight.
        // Resetting the password must end it, or the reset has not actually ended their access.
        AuthStepResponse attackerChallenge = authService.login(new LoginRequest(EMAIL, PASSWORD));
        String attackerCode = lastEmailCode();

        OtpChallengeResponse reset = authService.requestPasswordReset(new PasswordResetRequest(EMAIL));
        authService.confirmPasswordReset(new PasswordResetConfirmRequest(
                reset.challengeId(), lastEmailCode(), "BrandNewPassword456!"));

        assertThat(thrownBy(() -> authService.loginOtp(new OtpSubmissionRequest(
                attackerChallenge.challenge().challengeId(), attackerCode))).getCode())
                .isEqualTo(ErrorCode.CODE_ALREADY_CONSUMED);
    }

    @Test
    void passwordReset_aReusedResetChallenge_isRefused() {
        OtpChallengeResponse challenge = authService.requestPasswordReset(new PasswordResetRequest(EMAIL));
        String code = lastEmailCode();
        authService.confirmPasswordReset(new PasswordResetConfirmRequest(
                challenge.challengeId(), code, "BrandNewPassword456!"));

        assertThat(thrownBy(() -> authService.confirmPasswordReset(new PasswordResetConfirmRequest(
                challenge.challengeId(), code, "YetAnotherPassword789!"))).getCode())
                .isEqualTo(ErrorCode.CODE_ALREADY_CONSUMED);
    }

    @Test
    void passwordReset_anExpiredResetChallenge_isRefused() {
        OtpChallengeResponse challenge = authService.requestPasswordReset(new PasswordResetRequest(EMAIL));
        String code = lastEmailCode();
        InMemoryVerificationCodes.expire(codes.newest());

        assertThat(thrownBy(() -> authService.confirmPasswordReset(new PasswordResetConfirmRequest(
                challenge.challengeId(), code, "BrandNewPassword456!"))).getCode())
                .isEqualTo(ErrorCode.CODE_EXPIRED);
    }

    @Test
    void passwordReset_forAnUnverifiedEmail_isTreatedAsUnknown() {
        // Recovery always goes to a PROVED email address. An account that never verified one has no
        // channel this platform can trust, and saying so would itself be an enumeration signal.
        account.setEmailVerified(false);

        OtpChallengeResponse response = authService.requestPasswordReset(new PasswordResetRequest(EMAIL));

        assertThat(response.challengeId()).isNotNull();
        verify(emailSender, never()).sendOtp(anyString(), any(), anyString());
    }

    @Test
    void passwordResetConfirm_neverReturnsASession() {
        // Deliberate: a reset proves control of an inbox, not of the account's second factor. The
        // user signs in afterwards through the normal two-step flow.
        OtpChallengeResponse challenge = authService.requestPasswordReset(new PasswordResetRequest(EMAIL));
        authService.confirmPasswordReset(new PasswordResetConfirmRequest(
                challenge.challengeId(), lastEmailCode(), "BrandNewPassword456!"));

        verify(jwtService, never()).generateToken(any());
        verify(userRepository, Mockito.atLeastOnce()).save(any(User.class));
        assertThat(codes.all()).allSatisfy(c -> assertThat(c.getConsumedAt()).isNotNull());
    }

    // ---- structural guarantee -------------------------------------------------

    @Test
    void noPreOtpResponseTypeCanEvenCarryAToken() {
        // The cheapest possible audit of "no JWT before OTP": OtpChallengeResponse — the return type
        // of resend, phone capture and password-reset request — has no component that could hold one.
        var names = java.util.Arrays.stream(OtpChallengeResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();

        assertThat(names).doesNotContainAnyElementsOf(
                java.util.Set.of("token", "accessToken", "jwt", "session", "tokenType"));
        assertThat(names).containsExactlyInAnyOrder(
                "challengeId", "channel", "destinationMasked", "expiresInSeconds", "delivered");
    }

    @Test
    void anUnknownChallengeId_neverAuthenticates() {
        for (UUID unknown : new UUID[]{UUID.randomUUID(), UUID.randomUUID()}) {
            assertThat(thrownBy(() -> authService.loginOtp(new OtpSubmissionRequest(unknown, "123456")))
                    .getCode()).isEqualTo(ErrorCode.INVALID_CODE);
        }
        verify(jwtService, never()).generateToken(any());
        verify(userRepository, never()).findById(anyLong());
    }
}
