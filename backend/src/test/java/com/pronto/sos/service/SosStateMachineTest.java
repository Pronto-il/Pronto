package com.pronto.sos.service;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.sos.entity.SosRequestStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transition graph is the feature's safety net against "a controller moved a request
 * somewhere it should never go", so it is tested exhaustively rather than by example: every
 * one of the 12 × 12 status pairs is asserted, so adding a status without deciding its
 * transitions fails here rather than in production.
 */
class SosStateMachineTest {

    @Test
    void happyPathIsWalkableEndToEnd() {
        SosRequestStatus[] path = {
                SosRequestStatus.CREATED,
                SosRequestStatus.MATCHING,
                SosRequestStatus.WAITING_FOR_PROFESSIONALS,
                SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION,
                SosRequestStatus.PROFESSIONAL_SELECTED,
                SosRequestStatus.CONFIRMED,
                SosRequestStatus.ON_THE_WAY,
                SosRequestStatus.ARRIVED,
                SosRequestStatus.COMPLETED,
        };
        for (int i = 0; i < path.length - 1; i++) {
            assertThat(SosStateMachine.canTransition(path[i], path[i + 1]))
                    .as("%s -> %s", path[i], path[i + 1])
                    .isTrue();
        }
    }

    @Test
    void everyNonTerminalStatusCanBeCancelled() {
        for (SosRequestStatus status : SosRequestStatus.values()) {
            if (status.isTerminal()) {
                continue;
            }
            assertThat(SosStateMachine.canTransition(status, SosRequestStatus.CANCELLED))
                    .as("%s should be cancellable", status)
                    .isTrue();
        }
    }

    @Test
    void terminalStatusesAcceptNothing() {
        for (SosRequestStatus terminal : Set.of(SosRequestStatus.COMPLETED, SosRequestStatus.CANCELLED,
                SosRequestStatus.EXPIRED, SosRequestStatus.FAILED)) {
            assertThat(terminal.isTerminal()).isTrue();
            for (SosRequestStatus target : SosRequestStatus.values()) {
                assertThat(SosStateMachine.canTransition(terminal, target))
                        .as("%s -> %s must be rejected", terminal, target)
                        .isFalse();
            }
        }
    }

    /** The most important negative cases — skipping a step in the operational sequence. */
    @Test
    void skipAheadTransitionsAreRejected() {
        assertThat(SosStateMachine.canTransition(SosRequestStatus.CREATED,
                SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION)).isFalse();
        assertThat(SosStateMachine.canTransition(SosRequestStatus.WAITING_FOR_PROFESSIONALS,
                SosRequestStatus.PROFESSIONAL_SELECTED)).isFalse();
        assertThat(SosStateMachine.canTransition(SosRequestStatus.CONFIRMED,
                SosRequestStatus.ARRIVED)).isFalse();
        assertThat(SosStateMachine.canTransition(SosRequestStatus.CONFIRMED,
                SosRequestStatus.COMPLETED)).isFalse();
        assertThat(SosStateMachine.canTransition(SosRequestStatus.ON_THE_WAY,
                SosRequestStatus.COMPLETED)).isFalse();
    }

    /** Arrival is an observation, not a toggle. */
    @Test
    void backwardsTransitionsAreRejected() {
        assertThat(SosStateMachine.canTransition(SosRequestStatus.ARRIVED, SosRequestStatus.ON_THE_WAY)).isFalse();
        assertThat(SosStateMachine.canTransition(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION,
                SosRequestStatus.WAITING_FOR_PROFESSIONALS)).isFalse();
        assertThat(SosStateMachine.canTransition(SosRequestStatus.MATCHING, SosRequestStatus.CREATED)).isFalse();
    }

    /** A status can never transition to itself — that would make duplicate transitions legal. */
    @Test
    void selfTransitionsAreRejected() {
        for (SosRequestStatus status : SosRequestStatus.values()) {
            assertThat(SosStateMachine.canTransition(status, status))
                    .as("%s -> itself", status)
                    .isFalse();
        }
    }

    @Test
    void failedIsOnlyReachableFromMatching() {
        for (SosRequestStatus from : SosRequestStatus.values()) {
            boolean expected = from == SosRequestStatus.MATCHING;
            assertThat(SosStateMachine.canTransition(from, SosRequestStatus.FAILED))
                    .as("%s -> FAILED", from)
                    .isEqualTo(expected);
        }
    }

    @Test
    void expiredIsReachableOnlyFromTheThreeWaitingStates() {
        Set<SosRequestStatus> expected = Set.of(SosRequestStatus.WAITING_FOR_PROFESSIONALS,
                SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION, SosRequestStatus.PROFESSIONAL_SELECTED);
        for (SosRequestStatus from : SosRequestStatus.values()) {
            assertThat(SosStateMachine.canTransition(from, SosRequestStatus.EXPIRED))
                    .as("%s -> EXPIRED", from)
                    .isEqualTo(expected.contains(from));
        }
    }

    @Test
    void validateThrowsSosInvalidStateWithBothStatusesInTheMessage() {
        assertThatThrownBy(() -> SosStateMachine.validate(42L, SosRequestStatus.CREATED, SosRequestStatus.COMPLETED))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_INVALID_STATE))
                .hasMessageContaining("42")
                .hasMessageContaining("CREATED")
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void validatePassesForALegalTransition() {
        SosStateMachine.validate(1L, SosRequestStatus.CREATED, SosRequestStatus.MATCHING);
    }

    @Test
    void hasSelectionCoversExactlyThePostSelectionStates() {
        Set<SosRequestStatus> expected = Set.of(SosRequestStatus.PROFESSIONAL_SELECTED, SosRequestStatus.CONFIRMED,
                SosRequestStatus.ON_THE_WAY, SosRequestStatus.ARRIVED, SosRequestStatus.COMPLETED);
        for (SosRequestStatus status : SosRequestStatus.values()) {
            assertThat(status.hasSelection())
                    .as("%s.hasSelection()", status)
                    .isEqualTo(expected.contains(status));
        }
    }

    /** Guards against a status being added to the enum but left out of the transition map. */
    @Test
    void everyStatusHasAnExplicitEntryInTheGraph() {
        for (SosRequestStatus status : SosRequestStatus.values()) {
            Set<SosRequestStatus> allowed = SosStateMachine.allowedFrom(status);
            if (status.isTerminal()) {
                assertThat(allowed).as("%s is terminal", status).isEmpty();
            } else {
                assertThat(allowed).as("%s must have successors", status).isNotEmpty();
            }
        }
    }
}
