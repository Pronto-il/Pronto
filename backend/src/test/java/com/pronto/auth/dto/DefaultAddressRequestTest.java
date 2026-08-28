package com.pronto.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation rules on {@link DefaultAddressRequest} — city/street/houseNumber
 * required, apartment/floor/entrance/addressNotes optional (backend registration flow
 * separation task §4/§6/§23). Exercised directly against a {@link Validator} rather
 * than through {@code AuthService} (which deliberately leaves these field-level checks
 * to {@code @Valid} cascading at the controller layer — see {@code RegisterRequest}'s
 * Javadoc) or a full {@code MockMvc}/{@code @WebMvcTest} slice, neither of which this
 * codebase's existing test suite uses elsewhere.
 */
class DefaultAddressRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static DefaultAddressRequest fullAddress() {
        return new DefaultAddressRequest("Tel Aviv", "Dizengoff", "100", "4", "2", "A", "Back entrance", "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
    }

    private Set<String> violatedFields(DefaultAddressRequest address) {
        Set<ConstraintViolation<DefaultAddressRequest>> violations = validator.validate(address);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void fullyPopulatedAddress_hasNoViolations() {
        assertThat(validator.validate(fullAddress())).isEmpty();
    }

    @Test
    void minimalAddress_requiredFieldsOnly_hasNoViolations() {
        DefaultAddressRequest minimal = new DefaultAddressRequest("Haifa", "Herzl", "5", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(validator.validate(minimal)).isEmpty();
    }

    @Test
    void blankCity_isRejected() {
        DefaultAddressRequest address = new DefaultAddressRequest(" ", "Dizengoff", "100", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(violatedFields(address)).contains("city");
    }

    @Test
    void nullCity_isRejected() {
        DefaultAddressRequest address = new DefaultAddressRequest(null, "Dizengoff", "100", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(violatedFields(address)).contains("city");
    }

    @Test
    void blankStreet_isRejected() {
        DefaultAddressRequest address = new DefaultAddressRequest("Tel Aviv", "", "100", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(violatedFields(address)).contains("street");
    }

    @Test
    void blankHouseNumber_isRejected() {
        DefaultAddressRequest address = new DefaultAddressRequest("Tel Aviv", "Dizengoff", "", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(violatedFields(address)).contains("houseNumber");
    }

    @Test
    void apartmentFloorEntranceAddressNotes_areOptional() {
        DefaultAddressRequest address = new DefaultAddressRequest("Tel Aviv", "Dizengoff", "100", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(violatedFields(address)).doesNotContain("apartment", "floor", "entrance", "addressNotes");
    }
}
