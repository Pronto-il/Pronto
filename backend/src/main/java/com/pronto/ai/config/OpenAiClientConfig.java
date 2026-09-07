package com.pronto.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.ai.client.OpenAiCallPolicy;
import com.pronto.ai.client.OpenAiChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the two OpenAI transports — one for the customer, one for the background job — and is
 * the only place their budgets are decided.
 *
 * <p><b>Why two.</b> They were one bean sharing one model, one timeout and one retry policy, and
 * the two workloads have opposite requirements. Classification runs on a request thread with a
 * person watching a spinner: it wants the fastest acceptable configuration and a hard ceiling.
 * The Professional Brief runs after that person has left: it wants thoroughness and does not care
 * how long it takes. One shared setting cannot serve both, and every attempt to tune it made one
 * of them worse.
 *
 * <p><b>The brief's budget is deliberately untouched by any of this.</b> Its bean below still
 * reads {@code pronto.openai.model} and {@code pronto.openai.timeout-ms} — the same values it
 * always did — and is explicitly unbounded at the call site. Speeding up the customer's path must
 * not quietly truncate the document a professional relies on to arrive at a job prepared.
 *
 * <p>Conditional on {@code pronto.ai.mode=openai}, exactly as the client class used to be, so a
 * local or test run with the mock classifier still constructs neither.
 */
@Configuration
@ConditionalOnProperty(prefix = "pronto.ai", name = "mode", havingValue = "openai")
public class OpenAiClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClientConfig.class);

    /** Bean name for the interactive path. */
    public static final String CLASSIFICATION_CLIENT = "classificationChatClient";
    /** Bean name for the background Professional Brief. */
    public static final String BRIEF_CLIENT = "professionalBriefChatClient";

    /**
     * The customer-facing transport.
     *
     * <p>Three departures from the brief's configuration, each measured rather than guessed:
     *
     * <ul>
     *   <li><b>Its own model.</b> {@code OPENAI_CLASSIFICATION_MODEL} defaults to whatever
     *       {@code OPENAI_MODEL} is, so nothing changes model by accident — but the two can now
     *       diverge, which is what makes a cheaper/faster classifier adoptable without touching
     *       the brief.</li>
     *   <li><b>One attempt.</b> Retries are how a 30-second socket timeout became a 90-second
     *       wait on the baseline run. A retry that cannot fit in the remaining budget is not a
     *       retry, it is a slower failure.</li>
     *   <li><b>{@code reasoning_effort}.</b> The dominant term in the measured latency, and the
     *       one lever that does not trade away any taxonomy coverage. See
     *       {@code OpenAiChatClient.buildRequestBody}.</li>
     * </ul>
     */
    @Bean(CLASSIFICATION_CLIENT)
    public OpenAiChatClient classificationChatClient(
            @Value("${pronto.openai.api-key}") String apiKey,
            @Value("${pronto.openai.classification.model}") String model,
            @Value("${pronto.openai.classification.timeout-ms}") long timeoutMs,
            @Value("${pronto.openai.classification.max-attempts}") int maxAttempts,
            @Value("${pronto.openai.classification.reasoning-effort:}") String reasoningEffort,
            ObjectMapper objectMapper) {

        OpenAiCallPolicy policy = new OpenAiCallPolicy(model, timeoutMs, maxAttempts, reasoningEffort);
        // Logged at startup because a misconfigured classification budget is otherwise invisible
        // until customers are already waiting. No secret is involved — the key is not touched here.
        log.info("openai.classification.policy model={} timeoutMs={} maxAttempts={} reasoningEffort={}",
                policy.model(), policy.perAttemptTimeoutMillis(), policy.maxAttempts(),
                policy.sendsReasoningEffort() ? policy.reasoningEffort() : "(not sent)");
        return new OpenAiChatClient(apiKey, policy, objectMapper, OpenAiChatClient.UsageListener.NONE);
    }

    /**
     * The background transport — unchanged behaviour, on purpose.
     *
     * <p>Reads the original {@code pronto.openai.*} keys and the original three-attempt default,
     * so the Professional Brief runs today exactly as it ran before the classification path was
     * given its own budget.
     */
    @Bean(BRIEF_CLIENT)
    public OpenAiChatClient professionalBriefChatClient(
            @Value("${pronto.openai.api-key}") String apiKey,
            @Value("${pronto.openai.model}") String model,
            @Value("${pronto.openai.timeout-ms}") long timeoutMs,
            ObjectMapper objectMapper) {

        OpenAiCallPolicy policy = OpenAiCallPolicy.defaults(model, timeoutMs);
        log.info("openai.brief.policy model={} timeoutMs={} maxAttempts={}",
                policy.model(), policy.perAttemptTimeoutMillis(), policy.maxAttempts());
        return new OpenAiChatClient(apiKey, policy, objectMapper, OpenAiChatClient.UsageListener.NONE);
    }
}
