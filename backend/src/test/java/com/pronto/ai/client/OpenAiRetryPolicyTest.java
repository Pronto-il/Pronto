package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The OpenAI retry policy — Production MS5, closing the risk the MS3 report recorded as
 * <b>"thin retry policy"</b>.
 *
 * <p><b>The defect.</b> Two attempts were fired back to back with no pause whatsoever. One MS3
 * evaluation run hit <b>nine consecutive {@code AI_SERVICE_ERROR}s</b> from transient provider
 * failures, which is what that policy looks like from the outside: the second attempt lands inside
 * the same failure window as the first, so it is not really a retry, it is a second way to fail at
 * the same moment. MS3 recorded it as an availability gap worth fixing before beta and did not fix
 * it.
 *
 * <p><b>Why these tests cost no wall-clock time.</b> {@code OpenAiChatClient} takes a
 * {@link OpenAiChatClient.Sleeper} seam. Production sleeps; here a recorder captures the requested
 * delays, so the policy's <em>decisions</em> are asserted rather than its duration. A test that
 * genuinely waited would be slow, flaky, and would still not tell us what was waited for.
 *
 * <p>No network: {@link MockRestServiceServer} is bound to the same {@code RestClient} the loop
 * drives, so the retry behaviour under test is the real one and not a reimplementation.
 */
class OpenAiRetryPolicyTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("primaryCategoryCode", Map.of("type", "string")),
            "required", List.of("primaryCategoryCode"),
            "additionalProperties", false);

    private static final String OK_BODY = """
            {"choices":[{"message":{"content":"{\\"primaryCategoryCode\\":\\"PLUMBING\\"}"}}]}""";

    private static final String URL = "https://api.openai.com/v1/chat/completions";

    /** Records what the loop asked to wait for, and never actually waits. */
    private static final class RecordingSleeper implements OpenAiChatClient.Sleeper {
        private final List<Long> delays = new ArrayList<>();
        private boolean interrupt;

        @Override
        public boolean sleep(long millis) {
            delays.add(millis);
            return !interrupt;
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

    private OpenAiChatClient client() {
        return new OpenAiChatClient(builder.build(), "gpt-4o-mini", new ObjectMapper(), sleeper);
    }

    private JsonNode call() {
        return client().requestStructured("system", "evidence", List.of(), "pronto_issue_routing", SCHEMA);
    }

    // ---- the behaviour MS3 asked for -----------------------------------------------------------

    @Test
    void aTransientFailureIsRetried_andTheSecondAttemptSucceeds() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        assertThat(call().path("primaryCategoryCode").asText()).isEqualTo("PLUMBING");
        server.verify();
        assertThat(sleeper.delays).as("it waited before retrying, rather than firing immediately")
                .hasSize(1);
    }

    @Test
    void threeAttemptsAreMade_notTwo() {
        // The headline number. MS3 measured two; this asserts the original call plus two retries.
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(this::call)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AI_SERVICE_ERROR))
                .hasMessageContaining("3 attempt(s)");
        server.verify();
    }

    @Test
    void backoffGrowsBetweenAttempts_andThereIsNoWaitAfterTheLastOne() {
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(this::call).isInstanceOf(ApiException.class);

        // Two waits for three attempts: sleeping after the final failure would delay the error the
        // customer is already waiting for, and buy nothing.
        assertThat(sleeper.delays).hasSize(2);
        assertThat(sleeper.delays.get(1))
                .as("second backoff is drawn from a strictly higher band than the first")
                .isGreaterThanOrEqualTo(250L);
        assertThat(sleeper.delays.get(0)).isLessThanOrEqualTo(250L);
    }

    // ---- Retry-After -----------------------------------------------------------------------

    @Test
    void a429WithRetryAfter_isHonouredRatherThanGuessed() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "2");
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));
        server.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        assertThat(call().path("primaryCategoryCode").asText()).isEqualTo("PLUMBING");
        assertThat(sleeper.delays).containsExactly(2_000L);
    }

    @Test
    void a429AskingForLongerThanTheCap_stopsRetryingRatherThanHoldingTheRequestOpen() {
        // OpenAI is entitled to say "come back in 60 seconds". This application is not entitled to
        // hold a customer's request thread for 60 seconds on the strength of it — the honest answer
        // is a prompt error the customer can retry.
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "60");
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThatThrownBy(this::call).isInstanceOf(ApiException.class).hasMessageContaining("1 attempt(s)");
        server.verify();
        assertThat(sleeper.delays).isEmpty();
    }

    // ---- fail fast on what cannot succeed -------------------------------------------------------

    @Test
    void aRejectedRequestIsNotRetried() {
        // A 400 means OpenAI has already judged this exact request and found it invalid. Sending it
        // twice more makes a permanent failure take three times as long to surface, on a thread a
        // customer is waiting on, and bills for the privilege.
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(this::call).isInstanceOf(ApiException.class).hasMessageContaining("1 attempt(s)");
        server.verify();
        assertThat(sleeper.delays).isEmpty();
    }

    @Test
    void anUnauthorizedKeyIsNotRetried() {
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(this::call).isInstanceOf(ApiException.class).hasMessageContaining("1 attempt(s)");
        server.verify();
    }

    @Test
    void anInterruptedRetryAbandonsTheLoop() {
        sleeper.interrupt = true;
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(this::call).isInstanceOf(ApiException.class);
        server.verify();
    }

    // ---- the policy's arithmetic, directly ------------------------------------------------------

    @Test
    void backoffIsBoundedAndJittered() {
        // Jitter is not decoration: without it every caller that failed at the same instant retries
        // at the same instant, which turns a provider hiccup into a synchronised stampede. Half the
        // delay is fixed and half is random, so a retry is always later than its failure and never
        // simultaneous with a sibling's.
        for (int attempt = 1; attempt <= 10; attempt++) {
            long delay = OpenAiChatClient.backoffDelayMillis(attempt, null);
            assertThat(delay).as("attempt %d", attempt).isBetween(0L, 4_000L);
        }

        // Attempt 3's band is 500..1000 ms, so 200 draws collide constantly and asserting "no
        // duplicates" would be asserting the birthday paradox away. What matters is that the value
        // is spread rather than constant, and that it stays inside its band.
        List<Long> draws = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            draws.add(OpenAiChatClient.backoffDelayMillis(3, null));
        }
        assertThat(draws).allSatisfy(delay -> assertThat(delay).isBetween(500L, 1_000L));
        assertThat(draws.stream().distinct().count())
                .as("jitter actually varies the delay rather than returning a constant")
                .isGreaterThan(50L);
    }

    @Test
    void backoffNeverGrowsWithoutBound() {
        assertThat(OpenAiChatClient.backoffDelayMillis(50, null)).isLessThanOrEqualTo(4_000L);
    }

    @Test
    void retryableClassification() {
        assertThat(OpenAiChatClient.isRetryable(responseException(429))).isTrue();
        assertThat(OpenAiChatClient.isRetryable(responseException(408))).isTrue();
        assertThat(OpenAiChatClient.isRetryable(responseException(500))).isTrue();
        assertThat(OpenAiChatClient.isRetryable(responseException(503))).isTrue();
        assertThat(OpenAiChatClient.isRetryable(responseException(400))).isFalse();
        assertThat(OpenAiChatClient.isRetryable(responseException(401))).isFalse();
        assertThat(OpenAiChatClient.isRetryable(responseException(404))).isFalse();
        // A read timeout carries no status at all, and is the single most likely transient failure.
        assertThat(OpenAiChatClient.isRetryable(new java.net.SocketTimeoutException("Read timed out")))
                .isTrue();
    }

    @Test
    void retryAfterIsReadOnlyInItsSecondsForm() {
        assertThat(OpenAiChatClient.retryAfterSecondsOf(responseException(429, "7"))).isEqualTo(7L);
        // The HTTP-date form is deliberately ignored: honouring it would need clock-skew handling,
        // and falling back to this class's own exponential backoff is simpler and just as safe.
        assertThat(OpenAiChatClient.retryAfterSecondsOf(
                responseException(429, "Wed, 21 Oct 2026 07:28:00 GMT"))).isNull();
        assertThat(OpenAiChatClient.retryAfterSecondsOf(responseException(429))).isNull();
        assertThat(OpenAiChatClient.retryAfterSecondsOf(new IllegalStateException("no status"))).isNull();
    }

    private static RestClientResponseException responseException(int status) {
        return responseException(status, null);
    }

    private static RestClientResponseException responseException(int status, String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return new RestClientResponseException("stub", status, "stub", headers, null, null);
    }
}
