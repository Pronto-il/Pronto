package com.pronto.auth.service;

import com.pronto.auth.config.VerificationPolicy;
import com.pronto.auth.dto.LoginRequest;
import com.pronto.auth.dto.PasswordResetRequest;
import com.pronto.auth.email.EmailSender;
import com.pronto.auth.security.JwtService;
import com.pronto.auth.sms.SmsSender;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Timing-based account enumeration on the two unauthenticated endpoints that behave differently for
 * a known and an unknown identifier.
 *
 * <p><b>What the MS1 pre-DONE audit found.</b> {@code login} threw {@code INVALID_CREDENTIALS} for an
 * unknown identifier <em>before</em> doing any key derivation, while a known one paid ~100 ms of
 * BCrypt. Response bodies were identical and the status code was identical, but the wall-clock
 * difference was an order of magnitude — a perfectly reliable "does this account exist" oracle, and
 * one that scales: an attacker can test a list of addresses as fast as the network allows.
 *
 * <p><b>Why these tests count invocations rather than measure milliseconds.</b> A timing assertion on
 * a shared CI runner is a flaky test, and a flaky security test gets deleted. What is deterministic,
 * and what actually produces the timing symmetry, is that both branches perform one BCrypt
 * verification — so that is what is asserted. The encoder is a mock precisely so the call can be
 * counted.
 */
class AuthTimingTest {

    private static final String KNOWN_EMAIL = "customer@example.com";
    private static final String UNKNOWN_EMAIL = "nobody@example.com";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;
    private AuthAccountWriter accountWriter;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        passwordEncoder = Mockito.mock(PasswordEncoder.class);
        InMemoryVerificationCodes codes = new InMemoryVerificationCodes();

        // encode() is called once in the writer's constructor to mint the dummy hash.
        Mockito.lenient().when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$dummy");
        Mockito.lenient().when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        OtpService otpService = new OtpService(codes.repository(),
                new OtpChallengeWriter(codes.repository()),
                new OtpAttemptRecorder(codes.repository()), OtpServiceTest.TEST_PEPPER,
                Mockito.mock(EmailSender.class), Mockito.mock(SmsSender.class));

        accountWriter = new AuthAccountWriter(userRepository,
                Mockito.mock(com.pronto.professionals.repository.ProfessionalRepository.class),
                Mockito.mock(com.pronto.availability.repository.SosAvailabilityRepository.class),
                Mockito.mock(com.pronto.professionals.repository.ProfessionalSubServiceRepository.class),
                Mockito.mock(com.pronto.professionals.repository.ProfessionalCategoryRepository.class),
                Mockito.mock(com.pronto.professionals.repository.ProfessionalServiceCityRepository.class),
                Mockito.mock(com.pronto.availability.repository.ProfessionalWorkingHoursRepository.class),
                Mockito.mock(com.pronto.storage.service.StorageService.class),
                passwordEncoder, new PhoneNumberNormalizer("IL"),
                Mockito.mock(LoginAttemptRecorder.class), otpService, Mockito.mock(com.pronto.maps.service.ServiceAddressGeocoder.class));

        authService = new AuthService(userRepository, accountWriter, otpService,
                Mockito.mock(JwtService.class), passwordEncoder,
                Mockito.mock(com.pronto.professionals.service.ProfessionalCoverageService.class),
                Mockito.mock(com.pronto.locations.service.ServiceCoverageValidator.class),
                Mockito.mock(com.pronto.professionals.service.SubServiceSelectionValidator.class),
                new VerificationPolicy(true));

        User account = new User("Israel Israeli", KNOWN_EMAIL, "$2a$10$storedhash", UserRole.CUSTOMER);
        InMemoryVerificationCodes.setField(account, "id", 42L);
        account.setEmailVerified(true);
        Mockito.lenient().when(userRepository.findByEmail(anyString())).thenAnswer(inv ->
                KNOWN_EMAIL.equals(inv.getArgument(0)) ? Optional.of(account) : Optional.empty());
        Mockito.lenient().when(userRepository.findById(42L)).thenReturn(Optional.of(account));
        Mockito.lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void login_withAnUnknownIdentifier_stillPerformsOnePasswordVerification() {
        assertThatThrownBy(() -> authService.login(new LoginRequest(UNKNOWN_EMAIL, "whatever")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void login_withAKnownIdentifierAndWrongPassword_performsTheSameOneVerification() {
        assertThatThrownBy(() -> authService.login(new LoginRequest(KNOWN_EMAIL, "wrong")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        // Same count as the unknown-identifier branch above. That equality is the property.
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void login_neverComparesAgainstTheStoredHashOfAnAccountThatDoesNotExist() {
        // The dummy work must not reach into the database or fabricate a user; it compares against a
        // constant hash minted at startup.
        authFailsQuietly(UNKNOWN_EMAIL);

        verify(userRepository).findByEmail(UNKNOWN_EMAIL);
        Mockito.verify(passwordEncoder, Mockito.never()).matches(anyString(), Mockito.eq("$2a$10$storedhash"));
    }

    @Test
    void login_withAnUnknownIdentifier_issuesNoChallenge() {
        authFailsQuietly(UNKNOWN_EMAIL);

        // The equalising work is a hash comparison and nothing else — no code is generated, nothing
        // is persisted, and no message is sent to an address nobody registered.
        Mockito.verify(userRepository, Mockito.never()).save(any(User.class));
    }

    @Test
    void passwordResetRequest_forAnUnknownAccount_alsoPerformsOnePasswordVerification() {
        // Before the fix the decoy branch returned instantly while a real account paid for a keyed
        // hash, an insert and a provider round trip.
        authService.requestPasswordReset(new PasswordResetRequest(UNKNOWN_EMAIL));

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    @Test
    void passwordResetRequest_forAnUnknownAccount_stillReturnsAWellFormedChallenge() {
        var response = authService.requestPasswordReset(new PasswordResetRequest(UNKNOWN_EMAIL));

        assertThat(response.challengeId()).isNotNull();
        assertThat(response.delivered()).isTrue();
        assertThat(response.destinationMasked()).isEqualTo("n***@example.com");
    }

    @Test
    void burnEquivalentPasswordWork_isAvailableAndDoesNotThrow() {
        accountWriter.burnEquivalentPasswordWork();

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    private void authFailsQuietly(String identifier) {
        try {
            authService.login(new LoginRequest(identifier, "whatever"));
        } catch (ApiException expected) {
            // The point of these tests is what happened on the way, not the exception.
        }
    }
}
