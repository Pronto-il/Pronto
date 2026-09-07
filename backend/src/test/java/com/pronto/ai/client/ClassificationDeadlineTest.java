package com.pronto.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.Deadline;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The total classification deadline, and the rule that a spent budget is never an answer.
 *
 * <p><b>What went wrong before this existed.</b> The path was bounded only by per-socket
 * timeouts: 30 seconds each, three attempts, plus backoff. Every individual step was inside its
 * own limit and the total was not bounded at all. The measured baseline against live OpenAI —
 * 106 labelled cases, 128 calls — had a p50 of 10.4s, a p95 of 88.2s and a maximum of 92.0s, and
 * <b>not one call finished inside five seconds</b>. These tests hold the shape that fixes it.
 *
 * <p>No network and no wall-clock waiting: {@link MockRestServiceServer} answers, and the
 * budget is manipulated directly. A test that genuinely slept for four seconds would be slow and
 * would still not prove what it waited for.
 */
class ClassificationDeadlineTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("primaryCategoryCode", Map.of("type", "string")),
            "required", List.of("primaryCategoryCode"),
            "additionalProperties", false);

    private static final String OK_BODY = """
            {"choices":[{"message":{"content":"{\\"primaryCategoryCode\\":\\"PLUMBING\\"}"}}]}""";

    private static final String URL = "https://api.openai.com/v1/chat/completions";

    private static final class RecordingSleeper implements OpenAiChatClient.Sleeper {
        private final List<Long> delays = new ArrayList<>();

        @Override
        public boolean sleep(long millis) {
            delays.add(millis);
            return true;
        }
    }

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RecordingSleeper sleeper;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        sleeper = new RecordingSleeper();
    }

    private OpenAiChatClient client(OpenAiCallPolicy policy) {
        return new OpenAiChatClient(builder.build(), policy, new ObjectMapper(), sleeper);
    }

    private static OpenAiCallPolicy policy(int maxAttempts) {
        return new OpenAiCallPolicy("gpt-5-mini", 10_000, maxAttempts, "minimal");
    }

    // ---- the budget is checked before the provider is called ------------------------------------

    @Test
    void anExhaustedBudgetFailsAsATimeoutWithoutCallingTheProviderAtAll() {
        // No server.expect(...) at all: if a request were sent, MockRestServiceServer fails the
        // test. Spending an OpenAI call that cannot finish in time helps nobody and still bills.
        assertThatThrownBy(() -> client(policy(1)).requestStructured(
                "system", "evidence", List.of(), "pronto_issue_routing", SCHEMA,
                Deadline.inMillis(0)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .isEqualTo(ErrorCode.AI_TIMEOUT));

        server.verify();
    }

    /**
     * The boundary itself. Anything under the minimum-useful budget is treated as already out of
     * time rather than as a very short attempt, because a request that cannot plausibly complete
     * costs the customer their remaining patience and then fails anyway.
     */
    @Test
    void aBudgetTooSmallToBeUsefulIsTreatedAsAlreadyExpired() {
        assertThat(Deadline.inMillis(10).hasUsefulBudget()).isFalse();
        assertThat(Deadline.inMillis(5_000).hasUsefulBudget()).isTrue();
        assertThat(Deadline.unbounded().hasUsefulBudget()).isTrue();
    }

    // ---- a timeout is NOT a classification ------------------------------------------------------

    /**
     * The single most important assertion in this file.
     *
     * <p>A timeout must surface as {@link ErrorCode#AI_TIMEOUT} — a distinct 504 — and never as a
     * result. Nothing downstream may turn it into a {@code CLASSIFIED} status, a low-confidence
     * answer, or a fall back to {@code general_handyman}. "We ran out of time" and "we decided"
     * are different facts, and a system that blurs them dispatches a professional on the strength
     * of a network delay.
     */
    @Test
    void aTimeoutIsDistinctFromAProviderErrorAndCarriesNoCategory() {
        ApiException timeout = timeoutFrom(Deadline.inMillis(0));

        assertThat(timeout.getCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
        assertThat(timeout.getCode()).isNotEqualTo(ErrorCode.AI_SERVICE_ERROR);
        assertThat(ErrorCode.AI_TIMEOUT.getHttpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        // Nothing category-shaped rides on the failure.
        assertThat(timeout.getMessage()).doesNotContain("general_handyman", "plumbing", "CLASSIFIED");
    }

    private ApiException timeoutFrom(Deadline deadline) {
        try {
            client(policy(1)).requestStructured("system", "evidence", List.of(),
                    "pronto_issue_routing", SCHEMA, deadline);
            throw new AssertionError("expected the call to fail");
        } catch (ApiException e) {
            return e;
        }
    }

    // ---- one attempt by default on the interactive path -----------------------------------------

    @Test
    void theInteractivePolicyMakesExactlyOneAttemptAndDoesNotRetry() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client(policy(1)).requestStructured(
                "system", "evidence", List.of(), "pronto_issue_routing", SCHEMA,
                Deadline.inMillis(4_000)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .isEqualTo(ErrorCode.AI_SERVICE_ERROR));

        // A retryable 503 that was NOT retried, because the policy allows one attempt.
        server.verify();
        assertThat(sleeper.delays).as("no backoff was waited, because no retry was attempted").isEmpty();
    }

    /**
     * Retries are still possible — the policy is configuration, not a hardcoded 1. Raising
     * {@code max-attempts} must genuinely restore retrying, otherwise the knob is a lie.
     */
    @Test
    void aRetryStillHappensWhenThePolicyAllowsOneAndTheBudgetAffordsIt() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var payload = client(policy(2)).requestStructured("system", "evidence", List.of(),
                "pronto_issue_routing", SCHEMA, Deadline.in(Duration.ofSeconds(30)));

        assertThat(payload.path("primaryCategoryCode").asText()).isEqualTo("PLUMBING");
        server.verify();
        assertThat(sleeper.delays).hasSize(1);
    }

    /**
     * The rule that makes a deadline more than decoration: a retry has to fit in what is LEFT.
     *
     * <p>Attempting one that the budget will cut short converts a clean "the provider failed"
     * into a slower "we ran out of time" — a worse answer, delivered later.
     */
    @Test
    void aRetryIsSkippedWhenTheRemainingBudgetCannotAffordIt() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Three attempts allowed, but only 300ms left — less than the backoff plus a useful
        // round trip. The second attempt is declined on budget, not on policy.
        assertThatThrownBy(() -> client(policy(3)).requestStructured(
                "system", "evidence", List.of(), "pronto_issue_routing", SCHEMA,
                Deadline.inMillis(300)))
                .isInstanceOf(ApiException.class);

        server.verify();
        assertThat(sleeper.delays).as("the loop never waited, because the wait itself did not fit")
                .isEmpty();
    }

    // ---- retry exhaustion, with budget to spare -------------------------------------------------

    @Test
    void exhaustingEveryAttemptWithBudgetRemainingIsAProviderErrorRatherThanATimeout() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client(policy(2)).requestStructured(
                "system", "evidence", List.of(), "pronto_issue_routing", SCHEMA,
                Deadline.in(Duration.ofSeconds(60))))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .as("the provider failed on its merits; time was not the problem")
                        .isEqualTo(ErrorCode.AI_SERVICE_ERROR));

        server.verify();
    }

    // ---- the budget narrows each attempt --------------------------------------------------------

    @Test
    void anAttemptNeverGetsLongerThanTheBudgetThatIsLeft() {
        Deadline deadline = Deadline.inMillis(1_500);

        // The configured per-attempt ceiling is 10s, but only ~1.5s of budget remains, so the
        // socket must be given the smaller of the two. This is the step that stops one socket
        // outliving the whole operation.
        assertThat(deadline.timeoutForAttemptMillis(10_000)).isLessThanOrEqualTo(1_500);
        assertThat(deadline.timeoutForAttemptMillis(10_000)).isGreaterThan(0);

        // And it never returns 0, which HttpURLConnection reads as "no timeout at all" — the
        // exact opposite of what an exhausted budget means.
        assertThat(Deadline.inMillis(0).timeoutForAttemptMillis(10_000)).isGreaterThan(0);
    }

    @Test
    void anUnboundedDeadlineNeverConstrainsTheBackgroundBrief() {
        Deadline unbounded = Deadline.unbounded();

        assertThat(unbounded.isBounded()).isFalse();
        assertThat(unbounded.remainingMillis()).isEqualTo(Long.MAX_VALUE);
        // The brief's own configured ceiling is what applies, unreduced.
        assertThat(unbounded.timeoutForAttemptMillis(30_000)).isEqualTo(30_000);
    }
}
