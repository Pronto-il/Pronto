package com.pronto.ai;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;

import java.time.Duration;

/**
 * One wall-clock budget for one customer-facing operation, shared by every step that can block.
 *
 * <p><b>Why this exists at all.</b> The AI path used to be bounded only by per-socket timeouts:
 * a connect timeout and a read timeout, applied independently to each attempt. That bounds a
 * single socket and nothing else. It says nothing about how many attempts happen, nothing about
 * the storage downloads that precede them, and nothing about the sum — so a request whose every
 * individual step was inside its own limit could still hold a customer for the better part of a
 * minute, which is exactly what the baseline measurement showed. A deadline is the only shape
 * that can express "this whole thing has four seconds", because it is the only one that shrinks
 * as work is done.
 *
 * <p><b>Monotonic.</b> Built on {@link System#nanoTime()}, never on the wall clock, so an NTP
 * correction mid-request cannot extend or collapse the budget.
 *
 * <p><b>Not a cancellation mechanism.</b> This bounds how long anything is willing to WAIT; it
 * cannot reach into a blocked syscall and stop it. That is why {@link #remainingMillis()} is fed
 * into each blocking call's own timeout rather than merely checked between steps — a check
 * between steps discovers the budget was blown, whereas passing it down is what stops it being
 * blown in the first place.
 */
public final class Deadline {

    /**
     * How little remaining budget is still worth starting a network round trip with.
     *
     * <p>Below this the honest answer is already "not in time": firing a request that cannot
     * plausibly finish spends a customer's remaining patience, an OpenAI charge and a connection,
     * and then fails anyway. Failing immediately at least fails while the customer still has time
     * to be offered something else.
     */
    private static final long MINIMUM_USEFUL_BUDGET_MILLIS = 250;

    private final long deadlineNanos;
    private final boolean bounded;

    private Deadline(long deadlineNanos, boolean bounded) {
        this.deadlineNanos = deadlineNanos;
        this.bounded = bounded;
    }

    /**
     * The largest budget that can be expressed in nanoseconds without overflowing, in
     * milliseconds — a little over 292 years, so no real budget is ever affected by the clamp.
     *
     * <p>It exists because {@link #remainingMillis()} reports {@link Long#MAX_VALUE} for an
     * unbounded deadline, and a caller doing arithmetic on that value and feeding the result back
     * in got {@code ArithmeticException: long overflow} out of {@link Duration#toNanos()} rather
     * than a deadline. Saturating here means the sentinel can only ever produce an
     * absurdly-distant deadline, which is what it means, instead of an exception.
     */
    private static final long MAX_BUDGET_MILLIS = Long.MAX_VALUE / 1_000_000L;

    /** A budget of {@code budget} from now. */
    public static Deadline in(Duration budget) {
        return inMillis(budget.toMillis());
    }

    public static Deadline inMillis(long budgetMillis) {
        long clamped = Math.min(Math.max(budgetMillis, 0), MAX_BUDGET_MILLIS);
        return new Deadline(System.nanoTime() + clamped * 1_000_000L, true);
    }

    /**
     * No deadline at all — for work nobody is waiting on.
     *
     * <p>The background Professional Brief uses this deliberately. Its budget must NOT be the
     * interactive one: it runs after the customer has gone, it is allowed to take as long as the
     * provider takes, and quietly inheriting a four-second ceiling would degrade it from "a
     * thorough brief" to "whatever fits in a customer-facing latency budget" for no reason.
     */
    public static Deadline unbounded() {
        return new Deadline(0, false);
    }

    public boolean isBounded() {
        return bounded;
    }

    /** Milliseconds left, never negative. {@link Long#MAX_VALUE} when unbounded. */
    public long remainingMillis() {
        if (!bounded) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    /** Whether there is still enough budget left for a network round trip to be worth starting. */
    public boolean hasUsefulBudget() {
        return !bounded || remainingMillis() >= MINIMUM_USEFUL_BUDGET_MILLIS;
    }

    /**
     * The timeout to give one blocking operation: the smaller of its own configured ceiling and
     * whatever is left of the budget. Never zero, because zero means "no timeout" to
     * {@code HttpURLConnection} — the exact opposite of what an exhausted budget wants.
     */
    public int timeoutForAttemptMillis(long configuredCeilingMillis) {
        long allowed = Math.min(configuredCeilingMillis, remainingMillis());
        return (int) Math.max(1, Math.min(allowed, Integer.MAX_VALUE));
    }

    /**
     * Fails the operation when the budget is spent.
     *
     * <p>Deliberately an {@link ErrorCode#AI_TIMEOUT}, and deliberately not a classification.
     * Running out of time means Pronto does not know the answer — it is not a low-confidence
     * answer, not a fallback to the handyman category, and not something any caller may convert
     * into a {@code CLASSIFIED} result. Making it a distinct, loud failure is what stops "we ran
     * out of time" from being quietly indistinguishable from "we decided".
     *
     * @param stage where the budget ran out, for the log line — never shown to the customer
     */
    public void requireBudget(String stage) {
        if (!hasUsefulBudget()) {
            throw new ApiException(ErrorCode.AI_TIMEOUT,
                    "Classification exceeded its time budget before " + stage + " could complete.");
        }
    }
}
