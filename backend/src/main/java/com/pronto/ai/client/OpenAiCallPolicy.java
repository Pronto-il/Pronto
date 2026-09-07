package com.pronto.ai.client;

import java.util.Locale;

/**
 * Everything about HOW one OpenAI call is made, as a value — so the two responsibilities that
 * share {@link OpenAiChatClient} can be configured independently instead of inheriting one
 * global setting each.
 *
 * <p><b>The problem this solves.</b> Interactive classification and the background Professional
 * Brief had exactly one model, one timeout and one retry policy between them, because there was
 * one bean. They are not the same workload and should never have shared one: a customer is
 * sitting in front of the first with a five-second patience budget, and nobody at all is waiting
 * on the second. Tuning the shared setting for the customer silently truncated the brief; tuning
 * it for the brief is what left the customer waiting thirty seconds per attempt. Splitting the
 * configuration is the precondition for tuning either one honestly.
 *
 * @param model                   the OpenAI model id
 * @param perAttemptTimeoutMillis socket connect/read ceiling for a single attempt. Still needed
 *                                alongside a deadline: a deadline bounds the operation, this
 *                                bounds one socket, and an unbounded operation (the brief) has
 *                                only this.
 * @param maxAttempts             total attempts including the first — {@code 1} means no retry
 * @param reasoningEffort         {@code reasoning_effort} for the GPT-5 reasoning family, or
 *                                {@code null} to omit the parameter. See
 *                                {@link #sendsReasoningEffort()} for why it is not sent blindly.
 */
public record OpenAiCallPolicy(
        String model,
        long perAttemptTimeoutMillis,
        int maxAttempts,
        String reasoningEffort) {

    public OpenAiCallPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, was " + maxAttempts);
        }
        reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
                ? null
                : reasoningEffort.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Default total attempts, not retries — 3 means the original call plus two retries.
     *
     * <p>Still right for work nobody is waiting on. It is NOT what the interactive classification
     * path uses: three attempts against a provider that is slow rather than broken is three times
     * the wait for the same answer, and inside a four-second budget the second attempt cannot
     * finish even when it would have succeeded. That path configures {@code maxAttempts = 1} and
     * lets the customer's own retry be the retry — see
     * {@code pronto.openai.classification.max-attempts}.
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** The historical behaviour: three attempts, no reasoning-effort override. */
    public static OpenAiCallPolicy defaults(String model, long timeoutMillis) {
        return new OpenAiCallPolicy(model, timeoutMillis, DEFAULT_MAX_ATTEMPTS, null);
    }

    /**
     * Whether {@code reasoning_effort} should actually go on the wire.
     *
     * <p><b>Sent only to models known to accept it, for the same reason {@code temperature} is
     * withheld from those same models — but in the opposite direction.</b> On the Chat Completions
     * API {@code reasoning_effort} is a flat top-level string understood by the GPT-5 reasoning
     * family; sending it to a non-reasoning model such as {@code gpt-4.1-mini} is answered with
     * {@code "Unrecognized request argument supplied: reasoning_effort"} — a 400, which
     * {@link OpenAiChatClient#isRetryable} correctly declines to retry, so every classification
     * would fail outright. The capability check is what makes the model genuinely swappable
     * rather than swappable-if-you-also-remember-to-clear-this.
     *
     * <p>The condition is the exact complement of
     * {@link OpenAiChatClient#supportsCustomTemperature}: a model either samples (and takes
     * {@code temperature}) or reasons (and takes {@code reasoning_effort}). Deriving it from that
     * one predicate rather than writing a second model list means the two can never disagree
     * about which family a model is in.
     */
    public boolean sendsReasoningEffort() {
        return reasoningEffort != null && !OpenAiChatClient.supportsCustomTemperature(model);
    }
}
