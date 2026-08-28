package com.pronto.auth.service;

import com.pronto.auth.config.AuthOtpPolicy;
import com.pronto.auth.config.VerificationPolicy;
import com.pronto.auth.dto.AuthNextStep;
import com.pronto.auth.dto.AuthStepResponse;
import com.pronto.auth.dto.CustomerRegistrationData;
import com.pronto.auth.dto.DefaultAddressRequest;
import com.pronto.auth.dto.RegisterRequest;
import com.pronto.auth.email.EmailSender;
import com.pronto.auth.entity.OtpPurpose;
import com.pronto.auth.security.JwtService;
import com.pronto.auth.sms.SmsSender;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Registration hands back a session the moment no verification step remains — and never lies about
 * what was verified.
 *
 * <p>Two properties are being pinned, and they pull in opposite directions, which is exactly why
 * both need tests:
 *
 * <ul>
 *   <li><b>Convenience:</b> when nothing is required, do not ask the person who just chose a
 *       password to type it again. That is the auto-login half.</li>
 *   <li><b>Honesty:</b> {@code email_verified}/{@code phone_verified} stay {@code false} for a
 *       channel nobody proved. The flags decide whether an unproved channel BLOCKS; writing
 *       {@code true} to make the blocking go away would destroy the only record of who still owes
 *       what, and every reversal path depends on that record.</li>
 * </ul>
 *
 * <p>The requirement-still-on cases are kept here beside the relaxed ones on purpose. The risk is
 * not "auto-login fails to work" — that is visible on the first registration — it is "auto-login
 * became the only behaviour", and only a side-by-side pair catches that.
 */
class RegistrationAutoLoginTest {

    private static final String EMAIL = "customer@example.com";
    private static final String PASSWORD = "StrongPassword123!";
    private static final String TOKEN = "issued.jwt.token";

    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final InMemoryVerificationCodes codes = new InMemoryVerificationCodes();
    private final EmailSender emailSender = Mockito.mock(EmailSender.class);
    private final SmsSender smsSender = Mockito.mock(SmsSender.class);
    private final JwtService jwtService = Mockito.mock(JwtService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private User saved;

    private AuthService serviceWith(boolean emailRequired, boolean smsRequired) {
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
                new com.pronto.maps.service.SelectedPlaceValidator());

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
                new VerificationPolicy(smsRequired, emailRequired), new AuthOtpPolicy("false"),
                new com.pronto.maps.service.SelectedPlaceValidator());
    }

    private static RegisterRequest customerRequest() {
        return new RegisterRequest(UserRole.CUSTOMER, "Israel Israeli", EMAIL, "0502234567", PASSWORD,
                new CustomerRegistrationData(new DefaultAddressRequest("תל אביב-יפו", "דיזנגוף", "100",
                        null, null, null, null, "ChIJplace", "דיזנגוף 100",
                        new BigDecimal("32.0811"), new BigDecimal("34.7739"))),
                null);
    }

    // ---- 9, 10, 11. No verification required -> AUTHENTICATED, flags still false ----

    @Test
    void withNoVerificationRequiredRegistrationReturnsAnAuthenticatedSession() {
        AuthStepResponse response = serviceWith(false, false).register(customerRequest(), null, null);

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(response.session()).isNotNull();
        assertThat(response.session().token()).isEqualTo(TOKEN);
        assertThat(response.session().user().role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(response.challenge()).isNull();
    }

    @Test
    void theSessionIsIssuedWithoutMarkingEitherChannelVerified() {
        // The load-bearing assertion of this file. Every other test here would still pass if the
        // implementation had taken the easy route and written email_verified = true to make the
        // blocking go away -- and the beta cohort would then be permanently recorded as having
        // proved an address nobody ever sent a message to.
        AuthStepResponse response = serviceWith(false, false).register(customerRequest(), null, null);

        assertThat(response.emailVerified()).isFalse();
        assertThat(response.phoneVerified()).isFalse();
        assertThat(saved.isEmailVerified()).isFalse();
        assertThat(saved.isPhoneVerified()).isFalse();
    }

    @Test
    void noVerificationCodeIsDispatchedOnTheAutoLoginPath() {
        serviceWith(false, false).register(customerRequest(), null, null);

        verify(emailSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
        verify(smsSender, never()).sendOtp(anyString(), any(OtpPurpose.class), anyString());
        assertThat(codes.all()).isEmpty();
    }

    // ---- 12. Verification required -> the secure flow is untouched ----

    @ParameterizedTest(name = "email={0} sms={1} still withholds the session")
    @CsvSource({
            "true,  true",   // both required: the original flow
            "true,  false",  // email only
            "false, true",   // sms only -- email waived, phone still owed
    })
    void anyOutstandingVerificationStepStillWithholdsTheSession(boolean emailRequired, boolean smsRequired) {
        AuthStepResponse response = serviceWith(emailRequired, smsRequired).register(customerRequest(), null, null);

        assertThat(response.nextStep()).isNotEqualTo(AuthNextStep.AUTHENTICATED);
        assertThat(response.session()).isNull();
    }

    @Test
    void withEmailVerificationRequiredRegistrationStillChallenges() {
        AuthStepResponse response = serviceWith(true, true).register(customerRequest(), null, null);

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.VERIFY_EMAIL);
        assertThat(response.challenge()).isNotNull();
        assertThat(codes.all()).hasSize(1);
        verify(emailSender).sendOtp(anyString(), any(OtpPurpose.class), anyString());
    }

    @Test
    void withOnlyThePhoneStillOwedRegistrationSendsTheCustomerToLogIn() {
        // email waived, sms required, phone unproved -> registration is NOT complete, so no
        // session. The customer signs in and picks the phone step up from there, which is the
        // pre-existing PHONE_VERIFICATION_REQUIRED path and is deliberately unchanged.
        AuthStepResponse response = serviceWith(false, true).register(customerRequest(), null, null);

        assertThat(response.nextStep()).isEqualTo(AuthNextStep.LOGIN);
        assertThat(response.session()).isNull();
        assertThat(saved.isPhoneVerified()).isFalse();
    }
}
