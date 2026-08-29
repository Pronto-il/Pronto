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

    // --- house number: digits only (address-flow redesign, maps.HouseNumbers) -----------------

    @Test
    void houseNumberWithALetter_isRejected() {
        // "12א" is a real Israeli spelling and is refused anyway: the numeric part locates the
        // building, and the letter belongs in apartment/entrance, which exist for it. Enforced
        // here as well as in the browser because curl does not run the React app.
        DefaultAddressRequest address = new DefaultAddressRequest("Tel Aviv", "Dizengoff", "12א", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(violatedFields(address)).contains("houseNumber");
    }

    @Test
    void houseNumberWithASlash_isRejected() {
        DefaultAddressRequest address = new DefaultAddressRequest("Tel Aviv", "Dizengoff", "12/3", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(violatedFields(address)).contains("houseNumber");
    }

    @Test
    void houseNumberWithASpace_isRejected() {
        DefaultAddressRequest address = new DefaultAddressRequest("Tel Aviv", "Dizengoff", "12 3", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(violatedFields(address)).contains("houseNumber");
    }

    @Test
    void houseNumberOfDigits_isAccepted() {
        DefaultAddressRequest address = new DefaultAddressRequest("Tel Aviv", "Dizengoff", "12", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(violatedFields(address)).doesNotContain("houseNumber");
    }

    @Test
    void apartmentFloorEntranceAddressNotes_areOptional() {
        DefaultAddressRequest address = new DefaultAddressRequest("Tel Aviv", "Dizengoff", "100", null, null, null, null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"), new BigDecimal("34.7739"));
        assertThat(violatedFields(address)).doesNotContain("apartment", "floor", "entrance", "addressNotes");
    }

    // --- apartment / floor / entrance: optional, and now shaped (maps.AddressAccessFields) -----
    //
    // Optional and unconstrained are different things. These three used to be 20 characters of
    // anything, which is how a house number refused as "12א" reappears as an apartment, and how a
    // whole sentence lands in a field the professional's app renders as a two-character chip.
    // Enforced here as well as in the browser for the reason the house number is: curl does not
    // run the React app.

    private static DefaultAddressRequest withAccessFields(String apartment, String floor,
                                                           String entrance) {
        return new DefaultAddressRequest("Tel Aviv", "Dizengoff", "100", apartment, floor, entrance,
                null, "ChIJprontoTestPlaceId", "Test Address, Israel", new BigDecimal("32.0811"),
                new BigDecimal("34.7739"));
    }

    @Test
    void emptyAccessFields_areAccepted() {
        assertThat(violatedFields(withAccessFields("", "", "")))
                .doesNotContain("apartment", "floor", "entrance");
    }

    @Test
    void apartmentOfDigits_isAccepted() {
        assertThat(violatedFields(withAccessFields("12", null, null))).doesNotContain("apartment");
    }

    @Test
    void apartmentWithALetter_isRejected() {
        assertThat(violatedFields(withAccessFields("4א", null, null))).contains("apartment");
    }

    @Test
    void apartmentWithASymbol_isRejected() {
        assertThat(violatedFields(withAccessFields("4/2", null, null))).contains("apartment");
    }

    @Test
    void floorOfDigits_isAccepted() {
        assertThat(violatedFields(withAccessFields(null, "14", null))).doesNotContain("floor");
    }

    @Test
    void floorWithALetter_isRejected() {
        assertThat(violatedFields(withAccessFields(null, "2ב", null))).contains("floor");
    }

    @Test
    void negativeFloor_isRejected() {
        // A deliberate product decision rather than an oversight — nothing ever intentionally
        // supported a negative floor, and "digits only" is the rule. See maps.AddressAccessFields
        // for the reasoning and for where a basement is described instead (addressNotes).
        assertThat(violatedFields(withAccessFields(null, "-1", null))).contains("floor");
    }

    @Test
    void entranceOfOneLatinLetter_isAccepted() {
        assertThat(violatedFields(withAccessFields(null, null, "A"))).doesNotContain("entrance");
    }

    @Test
    void entranceOfOneHebrewLetter_isAccepted() {
        // The common case in Israel, and the reason the pattern is \p{L} rather than [A-Za-z].
        assertThat(violatedFields(withAccessFields(null, null, "ב"))).doesNotContain("entrance");
    }

    @Test
    void entranceOfTwoDigits_isAccepted() {
        assertThat(violatedFields(withAccessFields(null, null, "12"))).doesNotContain("entrance");
    }

    @Test
    void entranceOfALetterAndADigit_isAccepted() {
        assertThat(violatedFields(withAccessFields(null, null, "A1"))).doesNotContain("entrance");
        assertThat(violatedFields(withAccessFields(null, null, "ב2"))).doesNotContain("entrance");
    }

    @Test
    void entranceOfThreeCharacters_isRejected() {
        assertThat(violatedFields(withAccessFields(null, null, "ABC"))).contains("entrance");
        assertThat(violatedFields(withAccessFields(null, null, "123"))).contains("entrance");
    }

    @Test
    void entranceWithASymbol_isRejected() {
        assertThat(violatedFields(withAccessFields(null, null, "A-"))).contains("entrance");
        assertThat(violatedFields(withAccessFields(null, null, "@1"))).contains("entrance");
    }

    @Test
    void entranceWithASpace_isRejected() {
        assertThat(violatedFields(withAccessFields(null, null, "א "))).contains("entrance");
    }
}
