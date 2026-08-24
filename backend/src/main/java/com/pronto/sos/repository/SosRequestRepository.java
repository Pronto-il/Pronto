package com.pronto.sos.repository;

import com.pronto.sos.entity.SosActorType;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

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

    /**
     * Is there an SOS attempt on this issue that has not finished yet?
     *
     * <p>The product rule this encodes: <b>an SOS request is an attempt, not the problem.</b> One
     * issue may accumulate many attempts over its life — the first expired because nobody
     * answered, the second was cancelled, the third found somebody — and the customer must never
     * be made to re-describe a problem they already described. What must not happen is two
     * attempts running at once, fanning out two competing dispatch waves for the same job.
     *
     * <p>The terminal set here is exactly {@link SosRequestStatus#isTerminal()}, and is the same
     * set {@code ux_sos_requests_active_issue} (V36) excludes. The two must agree: this is the
     * friendly pre-check, that index is the authoritative guard that decides a race.
     */
    @Query("SELECT COUNT(r) > 0 FROM SosRequest r WHERE r.issueId = :issueId AND r.status NOT IN ("
            + "com.pronto.sos.entity.SosRequestStatus.COMPLETED, "
            + "com.pronto.sos.entity.SosRequestStatus.CANCELLED, "
            + "com.pronto.sos.entity.SosRequestStatus.EXPIRED, "
            + "com.pronto.sos.entity.SosRequestStatus.FAILED)")
    boolean existsActiveByIssueId(@Param("issueId") Long issueId);

    /** History for one issue, newest attempt first — every attempt, terminal ones included. */
    List<SosRequest> findByIssueIdOrderByCreatedAtDesc(Long issueId);

    List<SosRequest> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** The selected professional's own view of the jobs they are on the hook for. */
    List<SosRequest> findBySelectedProfessionalIdOrderByCreatedAtDesc(Long professionalId);

    /**
     * {@code CREATED -> MATCHING}, stamping {@code matchedAt}, the scan-window deadline and the
     * first automatic-expansion due time. The guard is what prevents two concurrent
     * {@code POST}s (a double-tapped SOS button) from both kicking off a dispatch wave for the
     * same request.
     *
     * @param nextExpansionAt when the search should first widen by itself, or {@code null} when
     *                        expansion is disabled ({@code max-search-expansions = 0})
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.MATCHING, "
            + "r.matchedAt = :now, r.matchingExpiresAt = :matchingExpiresAt, "
            + "r.nextExpansionAt = :nextExpansionAt, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = com.pronto.sos.entity.SosRequestStatus.CREATED")
    int startMatching(@Param("id") Long id, @Param("now") Instant now,
                       @Param("matchingExpiresAt") Instant matchingExpiresAt,
                       @Param("nextExpansionAt") Instant nextExpansionAt);

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
     * {@code WAITING_FOR_PROFESSIONALS -> WAITING_FOR_CUSTOMER_SELECTION} — the customer can now
     * choose. {@code candidatesReadyAt} records when that became true.
     *
     * <p><b>No deadline is written</b> (MS3 follow-up). This statement used to stamp a
     * {@code selection_expires_at} ten minutes out, and that column no longer exists: a
     * professional who has committed to arrive is a real option, and the customer does not lose
     * it to a clock. What ends this status is the customer selecting or cancelling — see
     * {@code SosService#enforceDeadlines} for the one degenerate case that also ends it.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = "
            + "com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION, "
            + "r.candidatesReadyAt = :now, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = "
            + "com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_PROFESSIONALS")
    int openSelectionWindow(@Param("id") Long id, @Param("now") Instant now);

    /**
     * {@code WAITING_FOR_CUSTOMER_SELECTION -> PROFESSIONAL_SELECTED}. <b>The single most
     * concurrency-sensitive statement in the feature.</b>
     *
     * <p>Three protections are folded into one atomic statement:
     * <ol>
     *   <li>{@code status = WAITING_FOR_CUSTOMER_SELECTION} — a customer double-tapping two
     *       different candidates cannot select twice; the second call sees 0 rows because the
     *       first already moved the status. <b>This is still the whole double-selection
     *       protection</b>, unaffected by the deadline's removal.</li>
     *   <li>{@code selectedProfessionalId IS NULL} — belt-and-braces against the same race
     *       even if a future status were ever to permit re-selection.</li>
     * </ol>
     *
     * <p>There used to be a third: {@code selectionExpiresAt > :now}, refusing a selection that
     * arrived after the customer's decision deadline. That deadline is gone (MS3 follow-up), and
     * with it the single most common way this statement rejected a customer who was doing nothing
     * wrong — tapping a candidate who was still perfectly valid, a few seconds past a timer they
     * never saw. What remains are the two guards that encode real facts: the request is still
     * awaiting a choice, and nobody has been chosen yet.
     *
     * <p>A {@code 0} return means one of the two failed; the caller re-reads the row to decide
     * which error to report.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.status = com.pronto.sos.entity.SosRequestStatus.PROFESSIONAL_SELECTED, "
            + "r.selectedProfessionalId = :professionalId, r.selectedOfferId = :offerId, r.orderId = :orderId, "
            + "r.selectedAt = :now, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = "
            + "com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION "
            + "AND r.selectedProfessionalId IS NULL")
    int selectProfessional(@Param("id") Long id, @Param("professionalId") Long professionalId,
                            @Param("offerId") Long offerId, @Param("orderId") Long orderId,
                            @Param("now") Instant now);

    /**
     * One search expansion. <b>Not a status transition</b> — the request stays exactly where it
     * is; only how wide it is searching, and when it next widens, change.
     *
     * <p>Since the MS3 lifecycle redesign this is normally driven by {@code SosSweepJob} on the
     * request's own {@code nextExpansionAt} schedule rather than by a customer pressing
     * anything, so "two callers racing" now includes two overlapping sweep passes. The statement
     * is unchanged in shape, and that is precisely why it copes:
     * <ol>
     *   <li>{@code searchExpansions = :expectedExpansions} — a compare-and-set. Two callers both
     *       read {@code n}; exactly one writes {@code n+1} and the other gets 0 rows. <b>This is
     *       what makes a double trigger produce one expansion, not two dispatch waves.</b></li>
     *   <li>{@code searchExpansions < :maxExpansions} — the bound, enforced by the database
     *       rather than by an application check that could race the increment.</li>
     *   <li>{@code selectedProfessionalId IS NULL} <em>and</em> the status set — <b>selection
     *       always wins over an in-flight expansion.</b> An expansion that arrives after the
     *       customer has chosen affects nothing and creates no offers.</li>
     *   <li>{@code matchingExpiresAt > :now} — <b>the scan window is closed and that is final.</b>
     *       No professional is contacted after it, whatever a stale schedule or a stale client
     *       asks for.</li>
     *   <li>{@code nextExpansionAt} is advanced in the same write, so the schedule for the
     *       following expansion is set by whoever won this one, atomically. {@code null} parks
     *       the request permanently (the ceiling was reached).</li>
     * </ol>
     *
     * <p><b>What it deliberately no longer does is move a deadline.</b> Expansion used to push
     * both the response and the selection window out. The three timers are independent now: the
     * scan window is fixed at activation, each offer carries its own response deadline, and the
     * customer's decision window belongs to the customer. Widening the search is not a reason to
     * move any of them.
     *
     * <p>A {@code 0} return means one of the guards failed; the caller re-reads to decide which.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.searchExpansions = :nextExpansions, "
            + "r.nextExpansionAt = :nextExpansionAt, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.selectedProfessionalId IS NULL "
            + "AND r.status IN (com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_PROFESSIONALS, "
            + "  com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION) "
            + "AND r.searchExpansions = :expectedExpansions AND r.searchExpansions < :maxExpansions "
            + "AND r.matchingExpiresAt IS NOT NULL AND r.matchingExpiresAt > :now")
    int expandSearch(@Param("id") Long id,
                      @Param("expectedExpansions") short expectedExpansions,
                      @Param("nextExpansions") short nextExpansions,
                      @Param("maxExpansions") short maxExpansions,
                      @Param("nextExpansionAt") Instant nextExpansionAt,
                      @Param("now") Instant now);

    /**
     * The sweep's driving query for <b>automatic</b> expansion: requests whose next widening is
     * due, that are still searching, and whose scan window is still open.
     *
     * <p>Every condition here is also inside {@link #expandSearch}'s own {@code WHERE} clause —
     * this query only decides who to <em>try</em>; that statement decides who actually wins. A
     * row returned here that has just been selected or expired simply produces 0 affected rows
     * and no dispatch.
     */
    @Query("SELECT r.id FROM SosRequest r WHERE r.nextExpansionAt IS NOT NULL "
            + "AND r.nextExpansionAt <= :now AND r.selectedProfessionalId IS NULL "
            + "AND r.status IN (com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_PROFESSIONALS, "
            + "  com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION) "
            + "AND r.matchingExpiresAt IS NOT NULL AND r.matchingExpiresAt > :now")
    List<Long> findExpansionDueIds(@Param("now") Instant now);

    /**
     * Parks a request's expansion schedule ({@code nextExpansionAt = NULL}) without touching
     * anything else — used when the scan window has closed, so the sweep stops re-reading a row
     * it can never expand again.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosRequest r SET r.nextExpansionAt = NULL, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.nextExpansionAt IS NOT NULL")
    int clearExpansionSchedule(@Param("id") Long id, @Param("now") Instant now);

    /**
     * {@code (sosRequestId, issueId)} pairs for a batch of requests — the one thing the
     * {@code notifications} package needs to know about an SOS request in order to deep-link a
     * customer's notification at the screen that renders it.
     *
     * <p>Ids only, and reached through {@code notifications.service.SosRequestIssueResolver}
     * rather than by importing this repository: {@code sos} depends on {@code notifications}, so
     * the reverse edge would be a package cycle. See that interface for the whole arrangement.
     */
    @Query("SELECT r.id, r.issueId FROM SosRequest r WHERE r.id IN :ids")
    List<Object[]> findIssueIdsByIds(@Param("ids") Collection<Long> ids);

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
     * The sweep's driving query: requests that have genuinely run out of things that could
     * happen.
     *
     * <p><b>One rule now, for both searching statuses</b> (MS3 follow-up). It used to be two
     * deadlines — the scan window for {@code WAITING_FOR_PROFESSIONALS}, the customer's decision
     * window for {@code WAITING_FOR_CUSTOMER_SELECTION} — and the second of those expired
     * customers who still had valid options in front of them. A request now ends only when all
     * three of these are true:
     *
     * <ol>
     *   <li><b>the scan window has closed</b> — while it is open a future expansion could still
     *       contact somebody new, so the request has a future even with nothing in hand;</li>
     *   <li><b>nobody has accepted</b> — an {@code ACCEPTED} offer is a professional who said
     *       they would come, and the customer may take it whenever they get back to their
     *       phone;</li>
     *   <li><b>no outstanding offer can still be answered</b> — an {@code OFFERED}/{@code VIEWED}
     *       offer inside its own response window may yet become a candidate, and that window
     *       legitimately outlives the scan.</li>
     * </ol>
     *
     * <p>Note what is <em>not</em> here: any notion of how long the customer has been deciding.
     * Abandoned-request retention, if it is ever wanted, is a separate cleanup concern and
     * deliberately not smuggled in as product semantics.
     */
    @Query("SELECT r.id FROM SosRequest r WHERE r.status IN ("
            + "com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_PROFESSIONALS, "
            + "com.pronto.sos.entity.SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION) "
            + "AND r.matchingExpiresAt IS NOT NULL AND r.matchingExpiresAt <= :now "
            + "AND NOT EXISTS (SELECT 1 FROM SosOffer o WHERE o.sosRequestId = r.id "
            + "  AND (o.status = com.pronto.sos.entity.SosOfferStatus.ACCEPTED "
            + "    OR (o.status IN (com.pronto.sos.entity.SosOfferStatus.OFFERED, "
            + "      com.pronto.sos.entity.SosOfferStatus.VIEWED) AND o.expiresAt > :now)))")
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
