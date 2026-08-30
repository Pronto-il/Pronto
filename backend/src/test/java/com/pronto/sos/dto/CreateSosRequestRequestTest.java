package com.pronto.sos.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>{@code issueId} is required, and this test exists so it stays that way.</b>
 *
 * <p>Production reported {@code VALIDATION_ERROR / issueId: must not be null} on
 * {@code POST /api/sos/requests} for signed-in customers. The cause was entirely client-side —
 * {@code features/sos/ProntoSosEntryPage} read the id from a route segment that deferred
 * authentication had removed, so {@code Number(undefined)} produced {@code NaN} and
 * {@code JSON.stringify} sent it as {@code null}. <b>This DTO was correct and was not changed.</b>
 *
 * <p>The tempting "fix" was to drop {@code @NotNull} and make the 400 go away. That would have
 * turned a loud client bug into a silent server one, because {@code SosService.create} cannot
 * function without a real issue: it loads the row, refuses one owned by anybody else, requires
 * {@code urgencyType = SOS} and {@code status = OPEN}, reads {@code categoryId} to match on, writes
 * it to {@code sos_requests.issue_id}, and relies on {@code ux_sos_requests_active_issue} — keyed
 * on that id — to stop one problem fanning out two competing dispatch waves. A null id has no
 * meaning at any of those points.
 *
 * <p>So these cases pin the contract rather than the bug: the shape the frontend must satisfy
 * before it is allowed to make a professional's phone ring.
 */
class CreateSosRequestRequestTest {

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

    private CreateSosRequestRequest request(Long issueId) {
        return new CreateSosRequestRequest(issueId, null, null, "תל אביב-יפו", "הרצל", "10",
                null, null, null, null, null, null, "place-abc", "הרצל 10, תל אביב-יפו",
                new BigDecimal("32.06"), new BigDecimal("34.77"));
    }

    private Set<String> violatedFields(CreateSosRequestRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void aNullIssueIdIsRejected() {
        // The exact Production payload: what a NaN becomes once serialised.
        assertThat(violatedFields(request(null))).contains("issueId");
    }

    @ParameterizedTest(name = "issueId = {0} is rejected")
    @ValueSource(longs = { 0L, -1L })
    void aNonPositiveIssueIdIsRejected(long issueId) {
        // @Positive as well as @NotNull: a synthesised placeholder id must not slip through either.
        assertThat(violatedFields(request(issueId))).contains("issueId");
    }

    @Test
    void aRealIssueIdPasses() {
        assertThat(violatedFields(request(777L))).doesNotContain("issueId");
    }

    @Test
    void theRequestCarriesNoDescriptionOrCategoryToFallBackOn() {
        // Stated structurally, because it is the reason "just make issueId optional" is not a
        // repair: there is no alternative data on this DTO that could identify what to dispatch
        // for. Both facts come from the anchoring issue, by design (see the record's Javadoc), so
        // relaxing issueId would leave the server with an SOS request about nothing.
        assertThat(CreateSosRequestRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("description", "categoryId", "imageKeys");
    }
}
