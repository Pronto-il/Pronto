package com.pronto.sos.repository;

import com.pronto.sos.entity.SosActorType;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every {@code sos_requests.status} change in this package goes through exactly one of the
 * {@code @Modifying} methods below — never a load-mutate-save round trip. This is the same
 * atomic {@code UPDATE ... WHERE <current-state-guard>} pattern
 * {@code bookings.repository.OrderRepository} established, and it is what makes concurrent
 * transitions safe: the guard is evaluated by the database under the row lock the UPDATE
 * itself takes, so of two callers racing out of the same status exactly one gets 1 affected
 * row and the other gets 0.
 *
 * <p>A {@code 0} return is therefore never an error condition in itself — it means "somebody
 * else got there first", and each caller decides whether that warrants a {@code 409} (an
 * interactive request) or a silent skip (a background sweep).
 */
public interface SosRequestRepository extends JpaRepository<SosRequest, Long> {

    boolean existsByIssueId(Long issueId);

    List<SosRequest> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** The selected professional's own view of the jobs they are on the hook for. */
    List<SosRequest> findBySelectedProfessionalIdOrderByCreatedAtDesc(Long professionalId);

    /**
     * {@code CREATED -> MATCHING}, stamping {@code matchedAt} and the response-window deadline.
     * The guard is what prevents two concurrent {@code POST}s (a double-tapped SOS button)
     * from both kicking off a dispatch wave for the same request.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.MATCHING, "
            + "r.matchedAt = :now, r.matchingExpiresAt = :matchingExpiresAt, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = com.pronto.sos.entity.SosRequestStatus.CREATED")
    int startMatching(@Param("id") Long id, @Param("now") Instant now,
                       @Param("matchingExpiresAt") Instant matchingExpiresAt);

    /** {@code MATCHING -> WAITING_FOR_PROFESSIONALS}, once offers are on their way out. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_PROFESSIONALS, "
            + "r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = com.pronto.sos.entity.SosRequestStatus.MATCHING")
    int markWaitingForProfessionals(@Param("id") Long id, @Param("now") Instant now);

    /** {@code MATCHING -> FAILED} — nobody eligible was found to ask. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.FAILED, "
            + "r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = com.pronto.sos.entity.SosRequestStatus.MATCHING")
    int markFailed(@Param("id") Long id, @Param("now") Instant now);

    /**
     * {@code WAITING_FOR_PROFESSIONALS -> WAITING_FOR_CUSTOMER_SELECTION}, opening the
     * customer's choosing window. {@code selectionExpiresAt} is computed by the caller and
     * written here, in the same statement as the status — the deadline and the state it
     * governs must become visible together or a reader could see a selection window with no
     * expiry.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = "
            + "com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION, "
            + "r.candidatesReadyAt = :now, r.selectionExpiresAt = :selectionExpiresAt, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = "
            + "com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_PROFESSIONALS")
    int openSelectionWindow(@Param("id") Long id, @Param("now") Instant now,
                             @Param("selectionExpiresAt") Instant selectionExpiresAt);

    /**
     * {@code WAITING_FOR_CUSTOMER_SELECTION -> PROFESSIONAL_SELECTED}. <b>The single most
     * concurrency-sensitive statement in the feature.</b>
     *
     * <p>Three protections are folded into one atomic statement:
     * <ol>
     *   <li>{@code status = WAITING_FOR_CUSTOMER_SELECTION} — a customer double-tapping two
     *       different candidates cannot select twice; the second call sees 0 rows because the
     *       first already moved the status.</li>
     *   <li>{@code selectedProfessionalId IS NULL} — belt-and-braces against the same race
     *       even if a future status were ever to permit re-selection.</li>
     *   <li>{@code selectionExpiresAt > :now} — selection after the window closed is refused
     *       <em>by the database at write time</em>, not by an application-level clock read that
     *       could be stale by the time the write lands.</li>
     * </ol>
     * A {@code 0} return means one of the three failed; the caller re-reads the row to decide
     * which error to report.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.PROFESSIONAL_SELECTED, "
            + "r.selectedProfessionalId = :professionalId, r.selectedOfferId = :offerId, r.orderId = :orderId, "
            + "r.selectedAt = :now, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = "
            + "com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION "
            + "AND r.selectedProfessionalId IS NULL AND r.selectionExpiresAt > :now")
    int selectProfessional(@Param("id") Long id, @Param("professionalId") Long professionalId,
                            @Param("offerId") Long offerId, @Param("orderId") Long orderId,
                            @Param("now") Instant now);

    /** {@code PROFESSIONAL_SELECTED -> CONFIRMED}, by the selected professional only. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.CONFIRMED, "
            + "r.confirmedAt = :now, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = com.pronto.sos.entity.SosRequestStatus.PROFESSIONAL_SELECTED "
            + "AND r.selectedProfessionalId = :professionalId")
    int confirm(@Param("id") Long id, @Param("professionalId") Long professionalId, @Param("now") Instant now);

    /**
     * The three operational transitions, each guarded on both the expected status <b>and</b>
     * {@code selectedProfessionalId}. Carrying the professional check in the WHERE clause
     * rather than relying solely on the service-layer authorization check means that even a
     * future caller that forgot to authorize cannot move another professional's job.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.ON_THE_WAY, "
            + "r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = com.pronto.sos.entity.SosRequestStatus.CONFIRMED "
            + "AND r.selectedProfessionalId = :professionalId")
    int markOnTheWay(@Param("id") Long id, @Param("professionalId") Long professionalId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.ARRIVED, "
            + "r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = com.pronto.sos.entity.SosRequestStatus.ON_THE_WAY "
            + "AND r.selectedProfessionalId = :professionalId")
    int markArrived(@Param("id") Long id, @Param("professionalId") Long professionalId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.COMPLETED, "
            + "r.completedAt = :now, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = com.pronto.sos.entity.SosRequestStatus.ARRIVED "
            + "AND r.selectedProfessionalId = :professionalId")
    int markCompleted(@Param("id") Long id, @Param("professionalId") Long professionalId, @Param("now") Instant now);

    /**
     * Cancellation from a specific expected status — the caller reads the current status,
     * checks the state machine and this actor's rights, then passes that status back as the
     * guard. {@code 0} rows means it changed in between.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.CANCELLED, "
            + "r.cancelledBy = :actor, r.cancelledAt = :now, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = :expectedStatus")
    int cancelIfStatus(@Param("id") Long id, @Param("expectedStatus") SosRequestStatus expectedStatus,
                        @Param("actor") SosActorType actor, @Param("now") Instant now);

    /** Expiry from a specific expected status, used by the sweep for each of its deadlines. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.EXPIRED, "
            + "r.updatedAt = :now WHERE r.id = :id AND r.status = :expectedStatus")
    int expireIfStatus(@Param("id") Long id, @Param("expectedStatus") SosRequestStatus expectedStatus,
                        @Param("now") Instant now);

    /**
     * The sweep's driving query: non-terminal requests whose relevant deadline has passed.
     * Returns ids only — each is then re-read and transitioned individually in its own
     * transaction, so one problem row cannot roll back a whole sweep. Mirrors
     * {@code OrderRepository#findPendingExpiryCandidateIds}.
     */
    @Query("SELECT r.id FROM SosRequest r WHERE "
            + "(r.status = com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_PROFESSIONALS "
            + "  AND r.matchingExpiresAt IS NOT NULL AND r.matchingExpiresAt <= :now) "
            + "OR (r.status = com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION "
            + "  AND r.selectionExpiresAt IS NOT NULL AND r.selectionExpiresAt <= :now)")
    List<Long> findExpiryCandidateIds(@Param("now") Instant now);

    /**
     * Requests stuck in {@code PROFESSIONAL_SELECTED} past {@code cutoff} — the selected
     * professional never confirmed. Separate from {@link #findExpiryCandidateIds} because the
     * deadline is derived from {@code selectedAt} plus a configured grace period rather than
     * from a stored column: adding a fourth deadline column for a case this rare would be
     * schema for schema's sake.
     */
    @Query("SELECT r.id FROM SosRequest r "
            + "WHERE r.status = com.pronto.sos.entity.SosRequestStatus.PROFESSIONAL_SELECTED "
            + "AND r.selectedAt IS NOT NULL AND r.selectedAt <= :cutoff")
    List<Long> findUnconfirmedSelectionIds(@Param("cutoff") Instant cutoff);
}
