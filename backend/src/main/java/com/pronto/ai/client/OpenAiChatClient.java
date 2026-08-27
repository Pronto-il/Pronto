package com.pronto.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.dto.ImageAttachment;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The OpenAI transport: one place that knows how to send a system prompt, a multimodal user
 * message and a strict JSON Schema to {@code /v1/chat/completions}, retry, and hand back the
 * parsed structured payload.
 *
 * <p>Both AI responsibilities (routing and the Professional Brief) go through here, so
 * timeouts, retries, image encoding and "the response envelope was not what we expected"
 * handling exist once rather than twice.
 *
 * <p>Never logs the API key, prompt bodies or image bytes — only sizes, the schema name and
 * failure messages.
 */
@Component
@ConditionalOnProperty(prefix = "pronto.ai", name = "mode", havingValue = "openai")
public class OpenAiChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    // ---- retry policy (Production MS5, closing the MS3 report's "thin retry policy" risk) --------
    //
    // MS3's evaluation runs hit NINE consecutive AI_SERVICE_ERRORs from transient OpenAI failures.
    // The policy then was two attempts fired back to back with no pause at all, which is close to
    // the worst possible response to a rate limit or a brief upstream wobble: the second attempt
    // lands inside the same failure window as the first, so it is not a retry so much as a second
    // way to fail. Backoff is what makes an attempt meaningfully different from its predecessor.

    /** Total attempts, not retries — 3 means the original call plus two retries. */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * Deliberately short. This client is called on a customer-facing request thread
     * ({@code POST /api/issues/classify}), so the retry budget competes directly with how long
     * somebody sits in front of a spinner. A quarter of a second is long enough to be on the far
     * side of a momentary blip and short enough to be invisible when it works.
     */
    private static final long INITIAL_BACKOFF_MILLIS = 250;

    private static final double BACKOFF_MULTIPLIER = 2.0;

    /**
     * Ceiling on any single wait, including one requested by the provider's {@code Retry-After}.
     * OpenAI is entitled to answer a 429 with "come back in 60 seconds"; this application is not
     * entitled to hold a request thread — and a customer — for 60 seconds on the strength of it.
     * Beyond this the honest answer is to fail now and let the customer retry.
     */
    private static final long MAX_BACKOFF_MILLIS = 4_000;

    /**
     * Deterministic decoding. Both AI responsibilities here are classification/extraction
     * against a strict schema, where the most probable token is always the wanted one — see
     * {@link #buildRequestBody} for the measurement that motivated pinning this.
     */
    private static final double TEMPERATURE = 0.0;

    /** Fixed so that two runs of the same evaluation set are comparable. Not a guarantee. */
    private static final int SEED = 20260825;

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;

    // @Autowired is REQUIRED here, not decorative. Spring uses a sole constructor implicitly, but
    // this class has two -- the test seam below is the second -- and with more than one it will not
    // guess: it falls back to looking for a no-arg constructor and fails the context with
    // "No default constructor found". That is a startup failure in production only, because
    // @ConditionalOnProperty means this bean exists only when AI_MODE=openai, which no unit test
    // and no local run ever sets. backend/tools/production-config-smoke.sh caught exactly this.
    @Autowired
    public OpenAiChatClient(@Value("${pronto.openai.api-key}") String apiKey,
                             @Value("${pronto.openai.model}") String model,
                             @Value("${pronto.openai.timeout-ms}") long timeoutMs,
                             ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
        this.sleeper = REAL_SLEEP;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeoutMs);
        requestFactory.setReadTimeout((int) timeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Test seam, package-private. Takes an already-built {@code RestClient} so a test can bind
     * {@code MockRestServiceServer} to it and drive the real retry loop over stubbed responses, and
     * a {@link Sleeper} so that exercising a backoff policy costs no wall-clock time. There is no
     * production caller: the Spring-injected constructor above is the only one.
     */
    OpenAiChatClient(RestClient restClient, String model, ObjectMapper objectMapper, Sleeper sleeper) {
        this.restClient = restClient;
        this.model = model;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
    }

    /**
     * Sends one structured-output request and returns the model's parsed JSON payload.
     *
     * @param schemaName schema identifier reported to OpenAI (and used in logs)
     * @param schema     the JSON Schema, enforced with {@code strict: true}
     * @throws ApiException {@code AI_SERVICE_ERROR} after {@value #MAX_ATTEMPTS} failed attempts
     */
    public JsonNode requestStructured(String systemPrompt, String evidencePrompt, List<ImageAttachment> images,
                                       String schemaName, Map<String, Object> schema) {

        Map<String, Object> requestBody = buildRequestBody(systemPrompt, evidencePrompt, images, schemaName, schema);
        Exception lastError = null;
        int attemptsMade = 0;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attemptsMade = attempt;
            try {
                String rawResponse = restClient.post()
                        .uri(CHAT_COMPLETIONS_PATH)
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);

                return extractPayload(rawResponse);
            } catch (Exception e) {
                lastError = e;
                log.warn("openai.request.failed schema={} attempt={}/{} reason={}",
                        schemaName, attempt, MAX_ATTEMPTS, e.getMessage());

                // A request OpenAI has already rejected on its merits will be rejected identically
                // however many times it is re-sent. Retrying a 400 (malformed schema) or a 401
                // (wrong key) only makes a permanent failure take three times as long to surface,
                // on a thread a customer is waiting on, while billing for the privilege.
                if (!isRetryable(e)) {
                    log.error("openai.request.rejected schema={} attempt={} retryable=false", schemaName, attempt);
                    break;
                }

                if (attempt == MAX_ATTEMPTS) {
                    break;
                }

                Long retryAfterSeconds = retryAfterSecondsOf(e);
                long delayMillis = backoffDelayMillis(attempt, retryAfterSeconds);
                if (delayMillis < 0) {
                    // The provider asked for longer than this application is willing to wait. Not a
                    // failure to retry correctly — a decision not to hold the request open.
                    log.warn("openai.request.retry_after_too_long schema={} requestedSeconds={} capMillis={}",
                            schemaName, retryAfterSeconds, MAX_BACKOFF_MILLIS);
                    break;
                }
                if (!sleeper.sleep(delayMillis)) {
                    // Interrupted. The flag has been restored; abandoning the retry is the only
                    // correct response to a shutdown in progress.
                    log.warn("openai.request.interrupted schema={} attempt={}", schemaName, attempt);
                    break;
                }
            }
        }

        log.error("openai.request.exhausted schema={} attempts={}", schemaName, attemptsMade, lastError);
        throw new ApiException(ErrorCode.AI_SERVICE_ERROR,
                "OpenAI request failed after " + attemptsMade + " attempt(s).");
    }

    // ==============================================================================================
    // Retry policy
    // ==============================================================================================

    /**
     * The wait before {@code attempt + 1}, in milliseconds, or {@code -1} to stop retrying.
     *
     * <p><b>Jitter is not decoration.</b> Without it every caller that failed at the same instant
     * retries at the same instant, which is how a brief provider hiccup turns into a synchronised
     * stampede that keeps the provider unhealthy. Half the delay is fixed and half is random, so a
     * retry is always meaningfully later than the failure that caused it and never at the same
     * moment as a sibling's.
     *
     * <p><b>{@code Retry-After} wins when the provider sends one</b>, because it is the only party
     * that knows when its own rate-limit window resets — guessing shorter is rude and guessing
     * longer is wasteful. It is still clamped to {@link #MAX_BACKOFF_MILLIS}, and a request for
     * longer than that returns {@code -1}: see that constant for why this application will not hold
     * a customer's request thread open on a provider's say-so.
     *
     * @param attempt            1-based number of the attempt that just failed
     * @param retryAfterSeconds  the provider's {@code Retry-After}, or {@code null} if absent
     */
    static long backoffDelayMillis(int attempt, Long retryAfterSeconds) {
        if (retryAfterSeconds != null) {
            long requestedMillis = retryAfterSeconds * 1000L;
            if (requestedMillis > MAX_BACKOFF_MILLIS) {
                return -1;
            }
            return Math.max(0, requestedMillis);
        }

        double exponential = INITIAL_BACKOFF_MILLIS * Math.pow(BACKOFF_MULTIPLIER, attempt - 1D);
        long capped = (long) Math.min(exponential, MAX_BACKOFF_MILLIS);
        long half = capped / 2;
        return half + ThreadLocalRandom.current().nextLong(half + 1);
    }

    /**
     * Whether re-sending the identical request could plausibly succeed.
     *
     * <p>Transient by nature: 429 (rate limited), 5xx (provider-side), 408, and any transport-level
     * failure — a connect timeout, a read timeout, a reset connection. Deliberately also true for
     * anything this class does not recognise, including a malformed response envelope: the previous
     * behaviour retried everything, and widening the fail-fast set beyond the cases that are
     * provably permanent would be trading a known cost for an unknown one.
     *
     * <p>Permanent, and therefore not retried: every other 4xx. A schema OpenAI considers invalid
     * and a key it considers unauthorised do not become valid by asking again.
     */
    static boolean isRetryable(Exception e) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 429 || status == 408 || status >= 500;
        }
        return true;
    }

    /**
     * The provider's {@code Retry-After}, in seconds, or {@code null} when it did not send one or
     * sent it in the HTTP-date form. Only the delta-seconds form is read: OpenAI sends seconds, and
     * a date would need clock-skew handling to be worth anything — falling back to this class's own
     * exponential backoff is both simpler and safe.
     */
    static Long retryAfterSecondsOf(Exception e) {
        if (!(e instanceof RestClientResponseException response)) {
            return null;
        }
        HttpHeaders headers = response.getResponseHeaders();
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : seconds;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * The one seam that makes the retry policy testable without a test that actually waits. Tests
     * substitute a recorder; production sleeps.
     */
    @FunctionalInterface
    interface Sleeper {
        /** @return {@code false} if interrupted — the caller must then abandon the retry loop. */
        boolean sleep(long millis);
    }

    static final Sleeper REAL_SLEEP = millis -> {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    };

    Map<String, Object> buildRequestBody(String systemPrompt, String evidencePrompt, List<ImageAttachment> images,
                                          String schemaName, Map<String, Object> schema) {

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", buildUserContent(evidencePrompt, images)));

        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", schemaName);
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", schema);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchema));
        // Routing is a classification decision, not a creative one: for a given description
        // there is one right trade, and sampling variety is pure downside. Left unset, the API
        // default of 1.0 applies — which measurably showed up as the same evaluation set
        // scoring 98.4% and 95.2% on consecutive runs of identical code, with a completely
        // different set of cases failing each time. That is not a system anyone can tune,
        // because no change can be told apart from the noise.
        body.put("temperature", TEMPERATURE);
        // Best-effort reproducibility on top of temperature 0. OpenAI documents `seed` as a
        // hint rather than a guarantee (system_fingerprint can still change under them), so it
        // is a way to make repeat runs comparable, never something correctness depends on.
        body.put("seed", SEED);
        return body;
    }

    /**
     * Images ride inline as data URLs — a presigned/local storage URL is not reachable from
     * OpenAI (see {@code dto.ImageAttachment}). The encoding already happened once, when the
     * attachment was resolved, so this method only assembles; it never re-encodes, however
     * many times the same attachment is sent. An attachment with no content is skipped rather
     * than sent as an empty data URL, which the API rejects.
     */
    private List<Map<String, Object>> buildUserContent(String evidencePrompt, List<ImageAttachment> images) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", evidencePrompt));

        if (images != null) {
            for (ImageAttachment image : images) {
                if (image == null || !image.hasContent()) {
                    log.warn("openai.image.skipped reason=empty key={}", image == null ? "null" : image.key());
                    continue;
                }
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", image.dataUri())));
            }
        }
        return content;
    }

    /** Unwraps the chat-completion envelope and parses the JSON string the model produced. */
    JsonNode extractPayload(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode messageContent = root.path("choices").path(0).path("message").path("content");

        if (!messageContent.isTextual() || messageContent.asText().isBlank()) {
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, "OpenAI response did not contain a message body.");
        }
        return objectMapper.readTree(messageContent.asText());
    }
}
