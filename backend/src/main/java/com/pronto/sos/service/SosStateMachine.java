package com.pronto.sos.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.sos.entity.SosRequestStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single, central definition of which {@link SosRequestStatus} transitions are legal.
 *
 * <p><b>Why this exists at all,</b> given that every transition is also guarded in SQL by an
 * atomic {@code UPDATE ... WHERE status = :expected}: those guards protect against
 * <em>concurrency</em> (two callers racing out of the same status — one wins, one sees 0
 * affected rows). They do not protect against a caller asking for a transition that makes no
 * business sense in the first place, because each guarded update hardcodes one specific
 * from-status and knows nothing about the graph as a whole. This class is where the graph
 * lives, so that "can an {@code ARRIVED} request go back to {@code MATCHING}?" has exactly one
 * answer in exactly one place, and adding a status means editing one map rather than auditing
 * every repository method.
 *
 * <p>The two layers are complementary and both are always applied: services call
 * {@link #validate} before attempting a transition (fail fast, correct error code), and the
 * repository's guarded update is the authoritative backstop that actually decides the race.
 *
 * <p>Stateless and side-effect free — deliberately a plain final class with static methods,
 * not a Spring bean, for the same reason {@code common.security.RoleGuard} is: there is
 * nothing to inject and nothing to mock.
 */
public final class SosStateMachine {

    /**
     * The full transition graph. Any pair not listed here is rejected.
     *
     * <p>{@code CANCELLED} is reachable from every non-terminal state — an urgent request the
     * customer no longer needs (the leak stopped, a neighbour fixed it) must always be
     * abandonable, and a professional who has committed but cannot make it must be able to
     * back out rather than silently no-show.
     */
    private static final Map<SosRequestStatus, Set<SosRequestStatus>> ALLOWED =
            new EnumMap<>(SosRequestStatus.class);

    static {
        ALLOWED.put(SosRequestStatus.CREATED, EnumSet.of(
                SosRequestStatus.MATCHING,
                SosRequestStatus.CANCELLED));

        // FAILED, not EXPIRED: nobody was eligible to ask, which is a different product
        // problem (thin supply in this category/area) than nobody answering.
        ALLOWED.put(SosRequestStatus.MATCHING, EnumSet.of(
                SosRequestStatus.WAITING_FOR_PROFESSIONALS,
                SosRequestStatus.FAILED,
                SosRequestStatus.CANCELLED));

        // Straight to WAITING_FOR_CUSTOMER_SELECTION once enough professionals accept, or once
        // the response window closes with at least one. EXPIRED means it closed with none.
        ALLOWED.put(SosRequestStatus.WAITING_FOR_PROFESSIONALS, EnumSet.of(
                SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION,
                SosRequestStatus.EXPIRED,
                SosRequestStatus.CANCELLED));

        ALLOWED.put(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION, EnumSet.of(
                SosRequestStatus.PROFESSIONAL_SELECTED,
                SosRequestStatus.EXPIRED,
                SosRequestStatus.CANCELLED));

        // EXPIRED is reachable here too: the customer chose, but the professional never
        // confirmed. Leaving the customer waiting indefinitely on a silent professional is the
        // worst outcome in an urgent flow.
        ALLOWED.put(SosRequestStatus.PROFESSIONAL_SELECTED, EnumSet.of(
                SosRequestStatus.CONFIRMED,
                SosRequestStatus.EXPIRED,
                SosRequestStatus.CANCELLED));

        ALLOWED.put(SosRequestStatus.CONFIRMED, EnumSet.of(
                SosRequestStatus.ON_THE_WAY,
                SosRequestStatus.CANCELLED));

        ALLOWED.put(SosRequestStatus.ON_THE_WAY, EnumSet.of(
                SosRequestStatus.ARRIVED,
                SosRequestStatus.CANCELLED));

        // No ARRIVED -> ON_THE_WAY. Arrival is an observation, not a toggle; letting it be
        // reversed would make the event log unreadable for no real-world gain.
        ALLOWED.put(SosRequestStatus.ARRIVED, EnumSet.of(
                SosRequestStatus.COMPLETED,
                SosRequestStatus.CANCELLED));

        ALLOWED.put(SosRequestStatus.COMPLETED, EnumSet.noneOf(SosRequestStatus.class));
        ALLOWED.put(SosRequestStatus.CANCELLED, EnumSet.noneOf(SosRequestStatus.class));
        ALLOWED.put(SosRequestStatus.EXPIRED, EnumSet.noneOf(SosRequestStatus.class));
        ALLOWED.put(SosRequestStatus.FAILED, EnumSet.noneOf(SosRequestStatus.class));
    }

    private SosStateMachine() {
    }

    /** True if {@code from -> to} is a legal transition. */
    public static boolean canTransition(SosRequestStatus from, SosRequestStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(SosRequestStatus.class)).contains(to);
    }

    /**
     * @throws ApiException {@code 409 SOS_INVALID_STATE} if {@code from -> to} is not legal.
     *                       Controllers never catch this — it surfaces through
     *                       {@code GlobalExceptionHandler} like any other domain error.
     */
    public static void validate(Long sosRequestId, SosRequestStatus from, SosRequestStatus to) {
        if (!canTransition(from, to)) {
            throw new ApiException(ErrorCode.SOS_INVALID_STATE,
                    "SOS request " + sosRequestId + " cannot move from " + from + " to " + to + ".");
        }
    }

    /** The legal successors of {@code from} — exposed for tests and for diagnostics. */
    public static Set<SosRequestStatus> allowedFrom(SosRequestStatus from) {
        return EnumSet.copyOf(ALLOWED.getOrDefault(from, EnumSet.noneOf(SosRequestStatus.class)));
    }
}
