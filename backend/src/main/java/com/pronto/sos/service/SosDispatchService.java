package com.pronto.sos.service;

import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.service.NotificationService;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.sos.repository.SosRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a ranked candidate list into {@code sos_offers} rows and notifies the professionals.
 *
 * <p>Also owns SOS pricing, because the price is fixed at the moment the offer is written and
 * the two must not be able to drift apart: {@link #priceOffer} is the only place visit fee, SOS
 * surcharge and Pronto's commission are computed, and its output is snapshotted onto the offer
 * row immediately.
 */
@Service
public class SosDispatchService {

    private static final Logger log = LoggerFactory.getLogger(SosDispatchService.class);

    private final SosRequestRepository sosRequestRepository;
    private final SosOfferRepository sosOfferRepository;
    private final SosMatchingService sosMatchingService;
    private final SosEventService sosEventService;
    private final NotificationService notificationService;
    private final SosProperties properties;

    public SosDispatchService(SosRequestRepository sosRequestRepository,
                               SosOfferRepository sosOfferRepository,
                               SosMatchingService sosMatchingService,
                               SosEventService sosEventService,
                               NotificationService notificationService,
                               SosProperties properties) {
        this.sosRequestRepository = sosRequestRepository;
        this.sosOfferRepository = sosOfferRepository;
        this.sosMatchingService = sosMatchingService;
        this.sosEventService = sosEventService;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    /**
     * Runs one dispatch wave for a request already in {@link SosRequestStatus#MATCHING}: rank,
     * create offers, notify, and move the request to
     * {@link SosRequestStatus#WAITING_FOR_PROFESSIONALS} — or to {@link SosRequestStatus#FAILED}
     * if nobody eligible exists.
     *
     * <p>Runs inside the caller's transaction (no {@code REQUIRES_NEW}), so a request is never
     * left in {@code MATCHING} with half its offers written: either the whole wave commits or
     * none of it does.
     *
     * @return the number of offers actually dispatched
     */
    @Transactional
    public int dispatch(SosRequest request) {
        Instant now = Instant.now();
        WaveResult wave = writeWave(request, SosSearchScope.initial(request.getUrgency(), properties), now);
        int dispatched = wave.dispatched();

        if (dispatched == 0) {
            // Production MS2: two different failures, and the customer is told which one happened.
            //
            // "Nobody eligible" is a fact about the marketplace. "We could not reach the routing
            // provider" is a fact about Pronto. Before MS2 only the first could occur, because
            // distance was a string comparison that could not fail; now that candidate distance
            // comes from an external provider, collapsing the second into the first would tell a
            // customer with a burst pipe that no plumber is available when the truth is that the
            // platform could not measure how far away the available ones are. Different fact,
            // different recovery ("try again in a moment" rather than "nobody is coming"),
            // different message.
            if (wave.degradation() != null) {
                failDegraded(request, wave.degradation(), now);
            } else {
                failNoProfessionals(request, now);
            }
            return 0;
        }

        // 0 affected rows means something else already moved the request out of MATCHING
        // (a cancellation racing the dispatch). The offers are still valid and will be closed
        // out by whatever transition won, so this is not an error.
        int moved = sosRequestRepository.markWaitingForProfessionals(request.getId(), now);
        if (moved == 0) {
            log.info("sos.dispatch.request-moved-concurrently sosRequestId={} offersCreated={}",
                    request.getId(), dispatched);
            return dispatched;
        }

        sosEventService.recordSystem(request.getId(), SosEventType.OFFERS_SENT, SosRequestStatus.MATCHING,
                SosRequestStatus.WAITING_FOR_PROFESSIONALS,
                dispatched + " offer(s) dispatched, expiring in " + properties.getOfferTtlSeconds() + "s");
        return dispatched;
    }

    /**
     * One <b>expansion</b> wave, for a request the customer has asked to widen ("סרוק שוב").
     *
     * <p>The same offer-writing machinery as {@link #dispatch}, with three deliberate differences,
     * each of which would be a bug the other way round:
     *
     * <ol>
     *   <li><b>No status transition.</b> The request is already
     *       {@code WAITING_FOR_PROFESSIONALS} or {@code WAITING_FOR_CUSTOMER_SELECTION} and stays
     *       there. In particular it does not go back to {@code MATCHING}: a customer who already
     *       has one professional on screen must not lose the ability to pick them because they
     *       asked to see more.</li>
     *   <li><b>An empty wave is not a failure.</b> {@link #failNoProfessionals} is never called
     *       here. "The wider scope turned up nobody new" is an ordinary outcome — usually it just
     *       means the platform has already asked everybody it can — and terminating a request
     *       that has usable candidates because of it would destroy the customer's options for
     *       pressing a button.</li>
     *   <li><b>Nobody previously offered is contacted again.</b> The exclusion set and
     *       {@code ux_sos_offers_request_professional} both hold, and the scope's pool size is a
     *       running total rather than a per-wave allowance — see
     *       {@code SosMatchingService#findCandidates(SosRequest, Set, SosSearchScope)}.</li>
     * </ol>
     *
     * <p>Runs inside the caller's transaction, so the expansion counter, the new offers and the
     * {@code SEARCH_EXPANDED} history row commit together or not at all.
     *
     * @return how many additional professionals were contacted; {@code 0} is a normal result
     */
    @Transactional
    public int expand(SosRequest request, SosSearchScope scope) {
        WaveResult wave = writeWave(request, scope, Instant.now());
        // An expansion never terminates a request -- see this method's contract above -- so a
        // degradation here is logged and otherwise treated exactly like an empty wave. A provider
        // blip during one expansion must not destroy candidates the customer already has on
        // screen, and the next expansion will simply try again.
        if (wave.degradation() != null) {
            log.warn("sos.dispatch.expansion-degraded sosRequestId={} scopeLevel={} degradation={}",
                    request.getId(), scope.level(), wave.degradation());
        }
        log.info("sos.dispatch.expanded sosRequestId={} scopeLevel={} poolSize={} newOffers={}",
                request.getId(), scope.level(), scope.poolSize(), wave.dispatched());
        return wave.dispatched();
    }

    /**
     * Ranks at {@code scope}, writes the offer rows, and notifies exactly the professionals whose
     * rows were actually created. Shared by the initial dispatch and every expansion so the two
     * cannot drift on pricing, ranking, duplicate handling or notification.
     *
     * @return the number of offers written
     */
    private WaveResult writeWave(SosRequest request, SosSearchScope scope, Instant now) {
        Set<Long> alreadyOffered = new HashSet<>(
                sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(request.getId()).stream()
                        .map(SosOffer::getProfessionalId)
                        .toList());

        SosMatchingService.MatchingOutcome outcome =
                sosMatchingService.findCandidates(request, alreadyOffered, scope);
        List<RankedCandidate> candidates = outcome.candidates();
        if (candidates.isEmpty()) {
            return new WaveResult(0, outcome.degradation());
        }

        Instant expiresAt = now.plus(Duration.ofSeconds(properties.getOfferTtlSeconds()));
        // The user ids to notify, collected as offers are written rather than derived from the
        // candidate list afterwards -- a skipped duplicate would otherwise shift the two out of
        // alignment and notify the wrong people.
        List<Long> recipientUserIds = new ArrayList<>();
        int rank = alreadyOffered.size();

        for (RankedCandidate candidate : candidates) {
            OfferPricing pricing = priceOffer(candidate.professional().basePrice());
            SosOffer offer = new SosOffer(request.getId(), candidate.professional().professionalId(), rank + 1,
                    candidate.score(), candidate.distanceKm(), candidate.etaMinutes(), pricing.visitFee(),
                    pricing.sosFee(), pricing.commission(), now, expiresAt);
            try {
                sosOfferRepository.saveAndFlush(offer);
            } catch (DataIntegrityViolationException e) {
                // ux_sos_offers_request_professional. The exclusion set above should already have
                // prevented this; reaching it means a concurrent wave won the race for this
                // professional. Skipping is correct -- they already have the offer, which is the
                // outcome we wanted -- and is why the constraint exists rather than being assumed.
                log.warn("sos.dispatch.duplicate-offer-skipped sosRequestId={} professionalId={}",
                        request.getId(), candidate.professional().professionalId());
                continue;
            }
            rank++;
            recipientUserIds.add(candidate.professional().userId());
            log.debug("sos.dispatch.offer sosRequestId={} professionalId={} rank={} scopeLevel={} score={} "
                            + "components={}",
                    request.getId(), candidate.professional().professionalId(), rank, scope.level(),
                    candidate.score(), candidate.componentScores());
        }

        // Notify after every offer row exists, so a professional cannot open the notification
        // and find no offer behind it.
        for (Long recipientUserId : recipientUserIds) {
            notificationService.recordSosNotification(request.getId(), recipientUserId,
                    NotificationMessageType.SOS_OFFER_RECEIVED);
        }
        return new WaveResult(recipientUserIds.size(), outcome.degradation());
    }

    /**
     * What one wave produced: how many offers, and whether the evaluation was degraded rather
     * than merely empty. See {@link #dispatch} on why those two must not be the same value.
     */
    private record WaveResult(int dispatched, SosMatchingService.SosMatchingDegradation degradation) {
    }

    /**
     * Terminate a request that could not be evaluated, saying so.
     *
     * <p>Same terminal {@code FAILED} status as {@link #failNoProfessionals} — a request nobody
     * can be dispatched for is over either way, and leaving it in {@code MATCHING} would strand it
     * (nothing re-drives that status). What differs is the history detail and the customer's
     * notification, which is the part that has to be true.
     */
    private void failDegraded(SosRequest request, SosMatchingService.SosMatchingDegradation degradation,
                               Instant now) {
        int failed = sosRequestRepository.markFailed(request.getId(), now);
        if (failed == 0) {
            return;
        }
        String detail = switch (degradation) {
            case ROUTING_UNAVAILABLE -> "The routing provider could not be reached, so no candidate's real "
                    + "distance or arrival time could be evaluated. This is a platform failure, not an "
                    + "absence of available professionals.";
            case DESTINATION_UNKNOWN -> "The service address could not be resolved to coordinates, so no "
                    + "candidate's distance could be measured.";
        };
        sosEventService.recordSystem(request.getId(), SosEventType.FAILED, SosRequestStatus.MATCHING,
                SosRequestStatus.FAILED, detail);
        notificationService.recordSosNotification(request.getId(), request.getCustomerId(),
                NotificationMessageType.SOS_TEMPORARILY_UNAVAILABLE);
        log.error("sos.dispatch.failed-degraded sosRequestId={} categoryId={} degradation={}",
                request.getId(), request.getCategoryId(), degradation);
    }

    private void failNoProfessionals(SosRequest request, Instant now) {
        int failed = sosRequestRepository.markFailed(request.getId(), now);
        if (failed == 0) {
            return;
        }
        sosEventService.recordSystem(request.getId(), SosEventType.FAILED, SosRequestStatus.MATCHING,
                SosRequestStatus.FAILED, "No eligible professionals were available for this category and area.");
        notificationService.recordSosNotification(request.getId(), request.getCustomerId(),
                NotificationMessageType.SOS_NO_PROFESSIONALS);
        log.info("sos.dispatch.failed sosRequestId={} categoryId={}", request.getId(), request.getCategoryId());
    }

    /**
     * The business model, in one method.
     *
     * <p>The professional's visit fee, plus a flat SOS surcharge that makes dropping everything
     * worth their while. Pronto's commission is a configured rate applied to <b>those two fees
     * only</b> — never to the value of the repair itself, which the platform takes nothing from
     * and could not verify anyway. On a 250 ILS visit fee with the default 10% rate and 50 ILS
     * surcharge: the customer is quoted 300, Pronto takes 30, the professional nets 270.
     *
     * <p>{@code basePrice} is nullable on {@code professionals}, so a professional who has never
     * set one yields a null visit fee. The surcharge and its commission still apply — the offer
     * is dispatchable, and the visit fee is settled between the parties. Treating a missing
     * price as zero would instead quietly promise the customer a free visit.
     */
    OfferPricing priceOffer(BigDecimal basePrice) {
        BigDecimal visitFee = basePrice;
        BigDecimal sosFee = properties.getVisitSurcharge();
        BigDecimal commissionBase = (visitFee == null ? BigDecimal.ZERO : visitFee).add(sosFee);
        BigDecimal commission = commissionBase.multiply(properties.getCommissionRate())
                .setScale(2, RoundingMode.HALF_UP);
        return new OfferPricing(visitFee, sosFee.setScale(2, RoundingMode.HALF_UP), commission);
    }

    /** The three snapshotted money figures for one offer. */
    record OfferPricing(BigDecimal visitFee, BigDecimal sosFee, BigDecimal commission) {
    }
}
