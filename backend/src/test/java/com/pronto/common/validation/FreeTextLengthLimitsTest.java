package com.pronto.common.validation;

import com.pronto.bookings.dto.CreateOrderRequest;
import com.pronto.issues.dto.ClarificationAnswerRequest;
import com.pronto.issues.dto.ClassifyRequest;
import com.pronto.issues.dto.CreateIssueRequest;
import com.pronto.issues.dto.IssueText;
import com.pronto.issues.entity.IssueUrgencyType;
import com.pronto.reviews.dto.CreateReviewRequest;
import com.pronto.reviews.dto.ReviewText;
import com.pronto.reviews.dto.UpdateReviewRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every user-editable free-text field has a length the server enforces.
 *
 * <p><b>The point of testing it here.</b> The frontend caps the same fields with {@code maxLength}
 * and shows a counter, but that is a courtesy to the person typing, not a control: it is absent
 * for any caller that is not the browser, and a paste, an autofill or a direct {@code curl} goes
 * straight past it. These tests build the request objects the controllers bind — no client
 * involved, which is exactly the "frontend bypassed" case — and assert both edges of each bound:
 * a value <em>at</em> the limit is accepted, and one character more is refused.
 *
 * <p>Refused, never trimmed. Nothing in this codebase truncates a request field to make it fit;
 * an over-long value is reported as a validation error, which the existing
 * {@code GlobalExceptionHandler} renders in the standard {@code VALIDATION_ERROR} envelope with
 * the offending field named (covered by {@code GlobalExceptionHandlerTest}).
 *
 * <p>The numbers live in {@link IssueText} / {@link ReviewText} / the annotations themselves and
 * are mirrored on the client by {@code shared/api/fieldLimits.ts}. This suite reads them from the
 * constants rather than repeating literals, so it pins the <em>behaviour</em> at whatever the
 * limit is; the two ends agreeing on the number is asserted on the client side.
 */
class FreeTextLengthLimitsTest {

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

    private static String text(int length) {
        return "a".repeat(length);
    }

    private <T> Set<String> violatedFields(T request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    // ---- Issue description (300) ----

    private static ClassifyRequest classifyWith(String description) {
        return new ClassifyRequest(description, List.of(), null, List.of());
    }

    private static CreateIssueRequest createIssueWith(String description) {
        return new CreateIssueRequest(1L, description, IssueUrgencyType.STANDARD, List.of(), List.of());
    }

    @Test
    void issueDescription_atTheLimit_isAccepted() {
        assertThat(validator.validate(classifyWith(text(IssueText.DESCRIPTION_MAX_LENGTH)))).isEmpty();
        assertThat(validator.validate(createIssueWith(text(IssueText.DESCRIPTION_MAX_LENGTH)))).isEmpty();
    }

    @Test
    void issueDescription_oneCharacterOver_isRejected() {
        String tooLong = text(IssueText.DESCRIPTION_MAX_LENGTH + 1);
        assertThat(violatedFields(classifyWith(tooLong))).contains("description");
        assertThat(violatedFields(createIssueWith(tooLong))).contains("description");
    }

    @Test
    void issueDescription_keepsItsExistingMinimum() {
        // The pre-existing lower bound is untouched by this change.
        assertThat(violatedFields(classifyWith(text(IssueText.DESCRIPTION_MIN_LENGTH - 1))))
                .contains("description");
        assertThat(validator.validate(classifyWith(text(IssueText.DESCRIPTION_MIN_LENGTH)))).isEmpty();
    }

    @Test
    void bothIssueEntryPoints_agreeOnTheLimit() {
        // The description travels through /classify and then through the commit. A bound on one
        // and not the other would refuse at booking time something already classified.
        String justOver = text(IssueText.DESCRIPTION_MAX_LENGTH + 1);
        assertThat(violatedFields(classifyWith(justOver)))
                .isEqualTo(violatedFields(createIssueWith(justOver)));
    }

    // ---- Clarification answer (200) / question (500) ----

    private static ClassifyRequest classifyAnswering(String question, String answer) {
        return new ClassifyRequest(text(50), List.of(), null,
                List.of(new ClarificationAnswerRequest(question, answer)));
    }

    @Test
    void clarificationAnswer_atTheLimit_isAccepted() {
        assertThat(validator.validate(
                classifyAnswering("Where is the leak?", text(ClarificationAnswerRequest.ANSWER_MAX_LENGTH))))
                .isEmpty();
    }

    @Test
    void clarificationAnswer_oneCharacterOver_isRejected() {
        // Cascaded through @Valid on the list, so the path names the element and the field.
        assertThat(violatedFields(
                classifyAnswering("Where is the leak?", text(ClarificationAnswerRequest.ANSWER_MAX_LENGTH + 1))))
                .anyMatch(path -> path.endsWith("answer"));
    }

    @Test
    void clarificationQuestion_isBoundedToo() {
        assertThat(validator.validate(
                classifyAnswering(text(ClarificationAnswerRequest.QUESTION_MAX_LENGTH), "Under the sink")))
                .isEmpty();
        assertThat(violatedFields(
                classifyAnswering(text(ClarificationAnswerRequest.QUESTION_MAX_LENGTH + 1), "Under the sink")))
                .anyMatch(path -> path.endsWith("question"));
    }

    // ---- Review comment (500) ----

    @Test
    void reviewComment_atTheLimit_isAccepted() {
        String atLimit = text(ReviewText.COMMENT_MAX_LENGTH);
        assertThat(validator.validate(new CreateReviewRequest(1L, 5, atLimit))).isEmpty();
        assertThat(validator.validate(new UpdateReviewRequest(5, atLimit))).isEmpty();
    }

    @Test
    void reviewComment_oneCharacterOver_isRejected() {
        String tooLong = text(ReviewText.COMMENT_MAX_LENGTH + 1);
        assertThat(violatedFields(new CreateReviewRequest(1L, 5, tooLong))).contains("comment");
        assertThat(violatedFields(new UpdateReviewRequest(5, tooLong))).contains("comment");
    }

    @Test
    void reviewComment_staysOptional() {
        // Bounding the field must not have made it required — most reviews are stars only.
        assertThat(validator.validate(new CreateReviewRequest(1L, 5, null))).isEmpty();
    }

    // ---- Order service address: the three strings that had no bound at all ----

    private static CreateOrderRequest orderWith(String city, String street, String notes) {
        return new CreateOrderRequest(1L, 2L, Instant.parse("2026-09-10T07:00:00Z"), city, street, "10",
                "4", "2", "A", notes, "ChIJprontoTestPlaceId", "Test Address, Israel",
                new BigDecimal("32.0811"), new BigDecimal("34.7739"));
    }

    @Test
    void orderServiceAddress_atTheLimits_isAccepted() {
        assertThat(validator.validate(orderWith(text(100), text(150), text(500)))).isEmpty();
    }

    @Test
    void orderServiceAddress_overTheLimits_isRejected() {
        assertThat(violatedFields(orderWith(text(101), text(150), text(500)))).contains("serviceCity");
        assertThat(violatedFields(orderWith(text(100), text(151), text(500)))).contains("serviceStreet");
        assertThat(violatedFields(orderWith(text(100), text(150), text(501))))
                .contains("serviceAddressNotes");
    }
}
