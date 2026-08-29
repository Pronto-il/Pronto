package com.pronto.users.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation rules on {@link CustomerAddressRequest} — the body of
 * {@code PUT /api/users/me/default-address} and the nested {@code defaultAddress} of
 * {@code PUT /api/users/me}.
 *
 * <p>Exercised directly against a {@link Validator} rather than through {@code UsersService}
 * (which leaves field-level checks to {@code @Valid} at the controller layer) or a
 * {@code MockMvc}/{@code @WebMvcTest} slice, neither of which this codebase's test suite uses —
 * same convention as {@code auth.dto.DefaultAddressRequestTest}, whose rules this record
 * deliberately mirrors.
 */
class CustomerAddressRequestTest {

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

    private static CustomerAddressRequest address(String houseNumber) {
        return new CustomerAddressRequest("תל אביב-יפו", "דיזנגוף", houseNumber, null, null, null,
                null, "ChIJprontoTestPlaceId", "דיזנגוף 100, תל אביב-יפו",
                new BigDecimal("32.0811"), new BigDecimal("34.7739"));
    }

    private Set<String> violatedFields(CustomerAddressRequest request) {
        Set<ConstraintViolation<CustomerAddressRequest>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void aFullySelectedAddress_hasNoViolations() {
        assertThat(validator.validate(address("100"))).isEmpty();
    }

    @Test
    void blankCity_isRejected() {
        assertThat(violatedFields(new CustomerAddressRequest(" ", "דיזנגוף", "100", null, null, null,
                null, "ChIJprontoTestPlaceId", null, new BigDecimal("32.0811"),
                new BigDecimal("34.7739")))).contains("city");
    }

    @Test
    void blankStreet_isRejected() {
        assertThat(violatedFields(new CustomerAddressRequest("תל אביב-יפו", "", "100", null, null,
                null, null, "ChIJprontoTestPlaceId", null, new BigDecimal("32.0811"),
                new BigDecimal("34.7739")))).contains("street");
    }

    @Test
    void blankHouseNumber_isRejected() {
        assertThat(violatedFields(address(""))).contains("houseNumber");
    }

    @Test
    void houseNumberOfDigits_isAccepted() {
        assertThat(violatedFields(address("7"))).doesNotContain("houseNumber");
    }

    @Test
    void houseNumberWithALetter_isRejected() {
        assertThat(violatedFields(address("12א"))).contains("houseNumber");
    }

    @Test
    void houseNumberWithASymbol_isRejected() {
        assertThat(violatedFields(address("12/3"))).contains("houseNumber");
    }

    @Test
    void houseNumberWithASpace_isRejected() {
        assertThat(violatedFields(address("12 3"))).contains("houseNumber");
    }

    @Test
    void accessFields_areOptional() {
        assertThat(violatedFields(address("100")))
                .doesNotContain("apartment", "floor", "entrance", "addressNotes");
    }

    // --- apartment / floor / entrance: optional, but shaped (maps.AddressAccessFields) ---------
    //
    // The same rules DefaultAddressRequestTest asserts, restated here because the two records are
    // deliberately independent declarations (see this record's Javadoc) and a rule added to one
    // and not the other is exactly the drift that independence risks.

    private static CustomerAddressRequest accessFields(String apartment, String floor,
                                                        String entrance) {
        return new CustomerAddressRequest("תל אביב-יפו", "דיזנגוף", "100", apartment, floor,
                entrance, null, "ChIJprontoTestPlaceId", "דיזנגוף 100, תל אביב-יפו",
                new BigDecimal("32.0811"), new BigDecimal("34.7739"));
    }

    @Test
    void emptyAccessFields_areAccepted() {
        assertThat(violatedFields(accessFields("", "", "")))
                .doesNotContain("apartment", "floor", "entrance");
    }

    @Test
    void digitsOnlyApartmentAndFloor_areAccepted() {
        assertThat(violatedFields(accessFields("12", "3", "ב")))
                .doesNotContain("apartment", "floor", "entrance");
    }

    @Test
    void apartmentWithALetter_isRejected() {
        assertThat(violatedFields(accessFields("4א", null, null))).contains("apartment");
    }

    @Test
    void negativeFloor_isRejected() {
        // Decided, not overlooked: see maps.AddressAccessFields. A basement goes in addressNotes.
        assertThat(violatedFields(accessFields(null, "-1", null))).contains("floor");
    }

    @Test
    void entranceOfTwoAlphanumerics_isAccepted() {
        assertThat(violatedFields(accessFields(null, null, "ב2"))).doesNotContain("entrance");
    }

    @Test
    void entranceLongerThanTwoCharacters_isRejected() {
        assertThat(violatedFields(accessFields(null, null, "ABC"))).contains("entrance");
    }

    @Test
    void entranceWithASymbol_isRejected() {
        assertThat(violatedFields(accessFields(null, null, "A-"))).contains("entrance");
    }

    @Test
    void entranceWithASpace_isRejected() {
        assertThat(violatedFields(accessFields(null, null, "א "))).contains("entrance");
    }
}
