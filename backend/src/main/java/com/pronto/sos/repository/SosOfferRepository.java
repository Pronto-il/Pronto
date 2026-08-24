package com.pronto.sos.repository;

import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosOfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Same atomic-guarded-update discipline as {@code SosRequestRepository}: no
 * {@code sos_offers.status} change is ever a load-mutate-save.
 */
public interface SosOfferRepository extends JpaRepository<SosOffer, Long> {

    Optional<SosOffer> findBySosRequestIdAndProfessionalId(Long sosRequestId, Long professionalId);

    List<SosOffer> findBySosRequestIdOrderByMatchRankAsc(Long sosRequestId);

    /**
     * The request's offers in a given status, in <b>arrival order</b> — offer ids are assigned at
     * dispatch and never change, so ascending id is the order the professionals were contacted
     * (and, within one wave, the order they are ranked in).
     *
     * <p>This is what the customer's shortlist is filled from, and the ordering is load-bearing
     * rather than cosmetic. The shortlist is capped, and with a search that can be expanded a
     * professional may accept <em>after</em> the customer is already looking at somebody. Capping
     * an ETA-sorted list would then silently evict a visible candidate the moment a faster one
     * turned up — the customer taps the card they were reading and it is gone. Filling
     * first-come-first-served means a candidate that has appeared can never be pushed off by a
     * newcomer; the response then re-sorts what it kept by ETA for display.
     */
    List<SosOffer> findBySosRequestIdAndStatusOrderByIdAsc(Long sosRequestId, SosOfferStatus status);

    /** The professional's inbox. */
    List<SosOffer> findByProfessionalIdAndStatusInOrderByCreatedAtDesc(Long professionalId,
                                                                         List<SosOfferStatus> statuses);

    List<SosOffer> findByProfessionalIdOrderByCreatedAtDesc(Long professionalId);

    long countBySosRequestIdAndStatus(Long sosRequestId, SosOfferStatus status);

    /**
     * Professionals already holding an open or accepted offer on <em>any</em> live request.
     * Used by matching to avoid dispatching a third and fourth simultaneous urgent job to
     * somebody who is already juggling two — a cheap availability signal that costs one query
     * rather than a per-candidate lookup.
     */
    @Query("SELECT DISTINCT o.professionalId FROM SosOffer o "
            + "WHERE o.professionalId IN :professionalIds "
            + "AND o.status IN (com.pronto.sos.entity.SosOfferStatus.OFFERED, "
            + "com.pronto.sos.entity.SosOfferStatus.VIEWED, com.pronto.sos.entity.SosOfferStatus.ACCEPTED) "
            + "AND o.expiresAt > :now")
    List<Long> findProfessionalIdsWithLiveOffers(@Param("professionalIds") List<Long> professionalIds,
                                                   @Param("now") Instant now);

    /**
     * {@code OFFERED -> VIEWED}. Guarded on {@code OFFERED} alone so a second open is a no-op
     * rather than an error, and so it can never clobber a response that has already happened —
     * viewing is telemetry and must never be able to move an offer backwards.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosOffer o SET o.status = com.pronto.sos.entity.SosOfferStatus.VIEWED, "
            + "o.viewedAt = :now, o.updatedAt = :now "
            + "WHERE o.id = :id AND o.status = com.pronto.sos.entity.SosOfferStatus.OFFERED")
    int markViewed(@Param("id") Long id, @Param("now") Instant now);

    /**
     * {@code OFFERED|VIEWED -> ACCEPTED}, carrying the professional's own ETA. <b>The only
     * statement in this repository that writes an ETA</b>, which is what makes the commitment
     * immutable in the domain rather than merely in the UI: there is no other write path, so
     * there is nothing to forget to guard.
     *
     * <p>The same call stamps the two write-once audit columns ({@code V41}): the promised ETA
     * and the acceptance instant. Both are written here and nowhere else, so the record of what
     * was promised survives regardless of what any later code does.
     *
     * <p>{@code expiresAt > :now} is part of the guard, so <b>the database refuses an expired
     * acceptance</b> rather than trusting an application-level expiry check that raced the
     * write. That guard is per offer, so it is also what implements "each professional gets ten
     * minutes from when <em>they</em> received it": a professional contacted late in the scan is
     * still inside their own window long after the scan itself has stopped.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosOffer o SET o.status = com.pronto.sos.entity.SosOfferStatus.ACCEPTED, "
            + "o.estimatedArrivalMinutes = :etaMinutes, o.promisedEtaMinutes = :etaMinutes, "
            + "o.respondedAt = :now, o.acceptedAt = :now, o.updatedAt = :now "
            + "WHERE o.id = :id AND o.expiresAt > :now AND o.status IN ("
            + "com.pronto.sos.entity.SosOfferStatus.OFFERED, com.pronto.sos.entity.SosOfferStatus.VIEWED)")
    int accept(@Param("id") Long id, @Param("etaMinutes") Short etaMinutes, @Param("now") Instant now);

    /**
     * Is any offer on this request still answerable — dispatched, unanswered, and inside its own
     * response window?
     *
     * <p>What "the scan stopped but the request is not over" is decided by. The scan window and
     * the per-professional response windows are independent timers, so a request whose scan has
     * closed with no acceptances stays alive exactly as long as somebody can still say yes.
     */
    @Query("SELECT COUNT(o) > 0 FROM SosOffer o WHERE o.sosRequestId = :sosRequestId "
            + "AND o.status IN (com.pronto.sos.entity.SosOfferStatus.OFFERED, "
            + "com.pronto.sos.entity.SosOfferStatus.VIEWED) AND o.expiresAt > :now")
    boolean existsAnswerableOffer(@Param("sosRequestId") Long sosRequestId, @Param("now") Instant now);

    /** {@code OFFERED|VIEWED -> REJECTED}. No expiry guard: declining late is harmless. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosOffer o SET o.status = com.pronto.sos.entity.SosOfferStatus.REJECTED, "
            + "o.respondedAt = :now, o.updatedAt = :now "
            + "WHERE o.id = :id AND o.status IN ("
            + "com.pronto.sos.entity.SosOfferStatus.OFFERED, com.pronto.sos.entity.SosOfferStatus.VIEWED)")
    int reject(@Param("id") Long id, @Param("now") Instant now);

    // There is deliberately no updateEta statement here any more (MS3). A professional who has
    // accepted has made a commitment the customer chooses on, so revising it afterwards is not
    // an operation this domain offers: removing the write path is what makes that structural
    // rather than a rule someone has to remember to check. POST /api/sos/offers/{id}/eta still
    // exists and answers 409 SOS_ETA_LOCKED, so a stale client gets an explanation rather than a
    // 404 -- see SosOfferService#updateEta.

    /**
     * {@code ACCEPTED -> SELECTED} for the winner. Guarded on {@code ACCEPTED} so an offer that
     * was withdrawn, expired or never accepted cannot be selected, whatever the request-level
     * state says.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosOffer o SET o.status = com.pronto.sos.entity.SosOfferStatus.SELECTED, "
            + "o.updatedAt = :now "
            + "WHERE o.id = :id AND o.status = com.pronto.sos.entity.SosOfferStatus.ACCEPTED")
    int markSelected(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Marks the losing <em>accepted</em> offers on a decided request {@code NOT_SELECTED} —
     * they were genuinely in the running and lost, which is a different fact from an offer that
     * simply lapsed, and the distinction is what makes the acceptance-rate statistic honest.
     *
     * <p>Paired with {@link #expireUnansweredOffers}: together they leave no offer on a decided
     * request still looking like a live opportunity. Two plain guarded updates rather than one
     * statement with a {@code CASE} in its {@code SET} clause — same number of round trips in
     * practice, and each is the same trivially-reviewable shape as every other update here.
     *
     * @param winningOfferId excluded from the sweep; pass a non-matching id (e.g. {@code -1})
     *                       when the request ended with no winner at all
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosOffer o SET o.status = com.pronto.sos.entity.SosOfferStatus.NOT_SELECTED, "
            + "o.updatedAt = :now WHERE o.sosRequestId = :sosRequestId AND o.id <> :winningOfferId "
            + "AND o.status = com.pronto.sos.entity.SosOfferStatus.ACCEPTED")
    int markAcceptedOffersNotSelected(@Param("sosRequestId") Long sosRequestId,
                                        @Param("winningOfferId") Long winningOfferId,
                                        @Param("now") Instant now);

    /** Closes every still-unanswered offer on a request. See {@link #markAcceptedOffersNotSelected}. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosOffer o SET o.status = com.pronto.sos.entity.SosOfferStatus.EXPIRED, "
            + "o.updatedAt = :now WHERE o.sosRequestId = :sosRequestId "
            + "AND o.status IN (com.pronto.sos.entity.SosOfferStatus.OFFERED, "
            + "com.pronto.sos.entity.SosOfferStatus.VIEWED)")
    int expireUnansweredOffers(@Param("sosRequestId") Long sosRequestId, @Param("now") Instant now);

    /**
     * Closes out every offer on a request that is not the winner. Callers use this rather than
     * the two statements above so that "a request has been decided" always means the same pair
     * of writes.
     */
    default void closeLosingOffers(Long sosRequestId, Long winningOfferId, Instant now) {
        markAcceptedOffersNotSelected(sosRequestId, winningOfferId, now);
        expireUnansweredOffers(sosRequestId, now);
    }

    /**
     * Closes every remaining live offer when a request ends with nobody chosen (cancelled,
     * expired). {@code -1} can never be a real generated id, so no offer is excluded.
     */
    default void closeAllOpenOffers(Long sosRequestId, Instant now) {
        markAcceptedOffersNotSelected(sosRequestId, -1L, now);
        expireUnansweredOffers(sosRequestId, now);
    }

    /**
     * The sweep's driving query for individual offer expiry: still-open offers whose own
     * {@code expiresAt} has passed. Ids only, so each can then be expired in its own transaction
     * and produce its own history row and realtime message.
     *
     * <p>Replaced a single bulk {@code UPDATE} that closed them all at once. The bulk statement
     * was cheaper and completely silent — it could not name which offers it had closed, so
     * nothing downstream could tell the affected professionals. Backed by
     * {@code idx_sos_offers_expires_at}, which is already partial on exactly these two statuses.
     */
    @Query("SELECT o.id FROM SosOffer o WHERE o.expiresAt <= :now AND o.status IN ("
            + "com.pronto.sos.entity.SosOfferStatus.OFFERED, com.pronto.sos.entity.SosOfferStatus.VIEWED)")
    List<Long> findOverdueOpenOfferIds(@Param("now") Instant now);

    /**
     * {@code OFFERED|VIEWED -> EXPIRED} for one offer. <b>This guard is what makes expiry
     * idempotent</b>: two sweep passes overlapping, or a sweep racing a professional's accept,
     * produce exactly one winner with 1 affected row. Every caller treats {@code 0} as "somebody
     * else already closed this" and writes no event, so an offer can never produce two
     * {@code OFFER_EXPIRED} rows or two notifications.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SosOffer o SET o.status = com.pronto.sos.entity.SosOfferStatus.EXPIRED, o.updatedAt = :now "
            + "WHERE o.id = :id AND o.status IN ("
            + "com.pronto.sos.entity.SosOfferStatus.OFFERED, com.pronto.sos.entity.SosOfferStatus.VIEWED)")
    int expireOfferIfOpen(@Param("id") Long id, @Param("now") Instant now);

    // ---- ranking inputs (see SosMatchingService) ----

    /**
     * Offers dispatched to each of {@code professionalIds} since {@code since}, and how many of
     * them were accepted — the acceptance-rate signal. One grouped query for the whole
     * candidate pool rather than N per-professional queries, because matching is on the
     * critical path of an urgent request.
     *
     * <p>Returns {@code [professionalId, offered, accepted]} rows. {@code ACCEPTED},
     * {@code SELECTED} and {@code NOT_SELECTED} all count as accepted — the professional said
     * yes in every one of those cases, and whether the customer then picked them is not a fact
     * about their responsiveness.
     */
    @Query("SELECT o.professionalId, COUNT(o), "
            + "SUM(CASE WHEN o.status IN (com.pronto.sos.entity.SosOfferStatus.ACCEPTED, "
            + "com.pronto.sos.entity.SosOfferStatus.SELECTED, "
            + "com.pronto.sos.entity.SosOfferStatus.NOT_SELECTED) THEN 1 ELSE 0 END) "
            + "FROM SosOffer o WHERE o.professionalId IN :professionalIds AND o.createdAt >= :since "
            + "GROUP BY o.professionalId")
    List<Object[]> findAcceptanceStats(@Param("professionalIds") List<Long> professionalIds,
                                         @Param("since") Instant since);
}
