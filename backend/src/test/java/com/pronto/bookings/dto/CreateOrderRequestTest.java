package com.pronto.bookings.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service-address shape rules on {@link CreateOrderRequest} — the order's own address
 * snapshot, and the third of the three write paths that accept an address.
 *
 * <p><b>Why this matters here specifically.</b> The other two paths save an address to a profile,
 * where a bad value is a cosmetic problem until somebody books against it. This one is the value a
 * professional reads on their way to the job, and it is written once and never re-derived. So the
 * check that {@code maps.HouseNumbers} and {@code maps.AddressAccessFields} are actually applied to
 * the {@code service*} fields — and not only to the two records that happen to share their field
 * names — is worth asserting rather than assuming.
 *
 * <p>Exercised directly against a {@link Validator}, matching
 * {@code auth.dto.DefaultAddressRequestTest} and {@code users.dto.CustomerAddressRequestTest};
 * there is no {@code MockMvc}/{@code @WebMvcTest} slice anywhere in this suite.
 */
class CreateOrderRequestTest {

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

    private static CreateOrderRequest order(String houseNumber, String apartment, String floor,
                                             String entrance) {
        return new CreateOrderRequest(1L, 2L, Instant.parse("2030-01-01T09:00:00Z"),
                "תל אביב-יפו", "דיזנגוף", houseNumber, apartment, floor, entrance, null,
                "ChIJprontoTestPlaceId", "דיזנגוף 100, תל אביב-יפו",
                new BigDecimal("32.0811"), new BigDecimal("34.7739"));
    }

    private Set<String> violatedFields(CreateOrderRequest request) {
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void aWellFormedOrder_hasNoViolations() {
        assertThat(validator.validate(order("100", "12", "3", "ב"))).isEmpty();
    }

    @Test
    void accessFields_remainOptional() {
        assertThat(validator.validate(order("100", null, null, null))).isEmpty();
        assertThat(validator.validate(order("100", "", "", ""))).isEmpty();
    }

    @Test
    void serviceHouseNumberWithALetter_isRejected() {
        assertThat(violatedFields(order("12א", null, null, null))).contains("serviceHouseNumber");
    }

    @Test
    void serviceApartmentMustBeDigitsOnly() {
        assertThat(violatedFields(order("100", "4א", null, null))).contains("serviceApartment");
        assertThat(violatedFields(order("100", "4", null, null))).doesNotContain("serviceApartment");
    }

    @Test
    void serviceFloorMustBeDigitsOnly() {
        assertThat(violatedFields(order("100", null, "-1", null))).contains("serviceFloor");
        assertThat(violatedFields(order("100", null, "2", null))).doesNotContain("serviceFloor");
    }

    @Test
    void serviceEntranceIsAtMostTwoAlphanumerics() {
        assertThat(violatedFields(order("100", null, null, "A1"))).doesNotContain("serviceEntrance");
        assertThat(violatedFields(order("100", null, null, "ABC"))).contains("serviceEntrance");
        assertThat(violatedFields(order("100", null, null, "א ב"))).contains("serviceEntrance");
        assertThat(violatedFields(order("100", null, null, "@1"))).contains("serviceEntrance");
    }
}
