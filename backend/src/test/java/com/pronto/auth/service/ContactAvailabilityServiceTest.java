package com.pronto.auth.service;

import com.pronto.auth.dto.AvailabilityRequest;
import com.pronto.auth.dto.AvailabilityResponse;
import com.pronto.auth.dto.ContactField;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.users.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/auth/availability} — the pre-submit "is this email/phone already registered?"
 * check.
 *
 * <p><b>What these tests are really pinning</b> is that this endpoint answers the <em>same</em>
 * question {@code AuthAccountWriter#createAccount} asks, through the same normalizers, and gives
 * away nothing beyond the boolean. An availability answer that disagreed with registration would
 * be worse than no endpoint at all: it would send a customer confidently on to a 409, or stop them
 * registering an address that was in fact free.
 */
class ContactAvailabilityServiceTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    private UserRepository userRepository;
    private ContactAvailabilityService service;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        service = new ContactAvailabilityService(userRepository, new PhoneNumberNormalizer("IL"),
                validator);
    }

    private AvailabilityResponse check(ContactField field, String value) {
        return service.check(new AvailabilityRequest(field, value));
    }

    // --- email ---------------------------------------------------------------------------------

    @Test
    void unregisteredEmail_isAvailable() {
        when(userRepository.existsByEmail("free@example.com")).thenReturn(false);

        AvailabilityResponse response = check(ContactField.EMAIL, "free@example.com");

        assertThat(response.available()).isTrue();
        assertThat(response.field()).isEqualTo(ContactField.EMAIL);
    }

    @Test
    void registeredEmail_isNotAvailable() {
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThat(check(ContactField.EMAIL, "taken@example.com").available()).isFalse();
    }

    @Test
    void emailIsNormalisedExactlyAsRegistrationNormalisesIt() {
        // createAccount looks up EmailNormalizer.normalize(request.email()), so this must too --
        // otherwise "Taken@Example.com " reports available and then loses to ux_users_email.
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThat(check(ContactField.EMAIL, "  Taken@Example.COM ").available()).isFalse();
        verify(userRepository).existsByEmail("taken@example.com");
    }

    @Test
    void malformedEmail_isRejectedRatherThanReportedAvailable() {
        // Answering "available" for something that is not an address at all would march the
        // customer on to a registration that is already doomed.
        Throwable thrown = catchThrowable(() -> check(ContactField.EMAIL, "not-an-email"));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(userRepository, never()).existsByEmail(anyString());
    }

    // --- phone ---------------------------------------------------------------------------------

    @Test
    void unregisteredPhone_isAvailable() {
        when(userRepository.existsByPhone("+972502234567")).thenReturn(false);

        assertThat(check(ContactField.PHONE, "050-223-4567").available()).isTrue();
    }

    @Test
    void registeredPhone_isNotAvailable() {
        when(userRepository.existsByPhone("+972502234567")).thenReturn(true);

        assertThat(check(ContactField.PHONE, "0502234567").available()).isFalse();
    }

    @Test
    void everySpellingOfTheSameNumberGetsTheSameAnswer() {
        // The point of routing through PhoneNumberNormalizer: 0502234567, 050-223-4567 and
        // +972502234567 are one subscriber, and registration stores exactly one of them.
        when(userRepository.existsByPhone("+972502234567")).thenReturn(true);

        assertThat(check(ContactField.PHONE, "0502234567").available()).isFalse();
        assertThat(check(ContactField.PHONE, "050-223-4567").available()).isFalse();
        assertThat(check(ContactField.PHONE, "+972 50 223 4567").available()).isFalse();
        assertThat(check(ContactField.PHONE, "00972502234567").available()).isFalse();
    }

    @Test
    void unparseablePhone_isRejectedRatherThanReportedAvailable() {
        Throwable thrown = catchThrowable(() -> check(ContactField.PHONE, "12345"));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(userRepository, never()).existsByPhone(anyString());
    }

    @Test
    void aLandline_isRejectedWithTheSameRuleRegistrationApplies() {
        // 03-xxxxxxx parses and is a real number, and registration still refuses it because it
        // cannot receive an SMS. This endpoint is where the customer finds that out -- on the
        // field, on blur, rather than on the confirmation screen.
        Throwable thrown = catchThrowable(() -> check(ContactField.PHONE, "03-6231234"));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // --- disclosure ----------------------------------------------------------------------------

    @Test
    void theAnswerCarriesNothingButTheFieldAndTheBoolean() {
        // The whole anti-enumeration position rests on this response being incapable of saying
        // anything about WHO holds the address. AvailabilityResponse is a two-component record;
        // if a third ever appears, this fails and the reviewer has to justify it.
        assertThat(AvailabilityResponse.class.getRecordComponents()).hasSize(2);
    }

    @Test
    void aTakenValueDoesNotRevealWhyItIsTaken() {
        // Soft-deleted, never-verified and fully active accounts are indistinguishable here,
        // because the question asked is exactly createAccount's existsBy* -- no deleted_at filter,
        // no verified filter, nothing to read a status off.
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        AvailabilityResponse response = check(ContactField.EMAIL, "taken@example.com");

        assertThat(response).isEqualTo(new AvailabilityResponse(ContactField.EMAIL, false));
    }

    @Test
    void validationErrorsNameTheRequestField_notTheStoredColumn() {
        Throwable thrown = catchThrowable(() -> check(ContactField.EMAIL, "nope"));

        @SuppressWarnings("unchecked")
        List<FieldError> details = (List<FieldError>) ((ApiException) thrown).getDetails();
        assertThat(details).extracting(FieldError::field).containsExactly("value");
    }
}
