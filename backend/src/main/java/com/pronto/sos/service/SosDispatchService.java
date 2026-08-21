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

        Set<Long> alreadyOffered = new HashSet<>(
                sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(request.getId()).stream()
                        .map(SosOffer::getProfessionalId)
                        .toList());

        List<RankedCandidate> candidates = sosMatchingService.findCandidates(request, alreadyOffered);

        if (candidates.isEmpty()) {
            failNoProfessionals(request, now);
            return 0;
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
            log.debug("sos.dispatch.offer sosRequestId={} professionalId={} rank={} score={} components={}",
                    request.getId(), candidate.professional().professionalId(), rank, candidate.score(),
                    candidate.componentScores());
        }

        if (recipientUserIds.isEmpty()) {
            failNoProfessionals(request, now);
            return 0;
        }

        // Notify after every offer row exists, so a professional cannot open the notification
        // and find no offer behind it.
        for (Long recipientUserId : recipientUserIds) {
            notificationService.recordSosNotification(request.getId(), recipientUserId,
                    NotificationMessageType.SOS_OFFER_RECEIVED);
        }

        // 0 affected rows means something else already moved the request out of MATCHING
        // (a cancellation racing the dispatch). The offers are still valid and will be closed
        // out by whatever transition won, so this is not an error.
        int moved = sosRequestRepository.markWaitingForProfessionals(request.getId(), now);
        if (moved == 0) {
            log.info("sos.dispatch.request-moved-concurrently sosRequestId={} offersCreated={}",
                    request.getId(), recipientUserIds.size());
            return recipientUserIds.size();
        }

        sosEventService.recordSystem(request.getId(), SosEventType.OFFERS_SENT, SosRequestStatus.MATCHING,
                SosRequestStatus.WAITING_FOR_PROFESSIONALS,
                recipientUserIds.size() + " offer(s) dispatched, expiring in "
                        + properties.getOfferTtlSeconds() + "s");
        return recipientUserIds.size();
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
