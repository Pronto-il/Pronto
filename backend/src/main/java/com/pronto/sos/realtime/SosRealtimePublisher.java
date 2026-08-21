package com.pronto.sos.realtime;

import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.sos.entity.SosEvent;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.event.SosDomainEvent;
import com.pronto.sos.repository.SosEventRepository;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.sos.repository.SosRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.pronto.sos.realtime.SosRealtimeMessage.data;

/**
 * Turns one committed {@link SosDomainEvent} into the right realtime messages for the right
 * people. This is the whole realtime layer's domain logic, and it is deliberately the only class
 * that has any.
 *
 * <p><b>It changes nothing about the business flow.</b> Every SOS transition already wrote its
 * {@code sos_events} row and published a {@link SosDomainEvent} before this phase existed; this
 * class is a listener bolted onto that seam. No service signature changed, no transition moved,
 * no state machine rule was touched.
 *
 * <h2>After-commit guarantee</h2>
 *
 * {@link TransactionalEventListener} with {@link TransactionPhase#AFTER_COMMIT} — the listener is
 * registered as a transaction synchronization when the event is published and only runs once the
 * publishing transaction has actually committed. A rolled-back selection therefore pushes nothing,
 * and no client can ever be told about a state the database does not hold. The reads this class
 * performs run in their own {@code REQUIRES_NEW} transaction, after that commit, so they see the
 * committed truth rather than the publisher's uncommitted view — which is exactly what makes
 * "query the audience from current state" safe.
 *
 * <p>Notably <b>not</b> {@code @Async}, unlike {@code IssueBriefService}. Two reasons: ordering and
 * cost. A single business transaction can emit several events ({@code CANDIDATES_READY} then
 * {@code CUSTOMER_SELECTION_STARTED}), and a client rendering a timeline must receive them in the
 * order they happened — handing them to a pool would make that ordering incidental. And the work
 * here is a couple of indexed reads plus an in-JVM handoff to the simple broker, so there is no
 * blocking call worth moving off the request thread. If delivery ever becomes genuinely slow (a
 * network broker), that decision should be revisited together with an ordering guarantee.
 *
 * <h2>Failure isolation</h2>
 *
 * The listener catches everything. An after-commit synchronization that throws propagates out of
 * the transaction manager to the original caller — which would turn "your selection succeeded and
 * is committed" into an HTTP 500 for a purely cosmetic delivery failure. That must not happen, so
 * nothing escapes here, and {@code SosRealtimeDelivery} independently isolates per recipient.
 *
 * <h2>How the audience is derived</h2>
 *
 * Always from committed database state — who owns the request, who holds an offer on it, what
 * status that offer now has — never from anything a client asserted. In particular
 * {@link SosRealtimeEventType#SOS_NOT_SELECTED} goes to exactly the offers now sitting at
 * {@link SosOfferStatus#NOT_SELECTED}, which by construction is the set that positively responded
 * and lost: {@code SosOfferRepository.closeLosingOffers} moves {@code ACCEPTED -> NOT_SELECTED} and
 * open offers to {@code EXPIRED}, so a professional who never answered is never told they were
 * passed over.
 */
@Component
public class SosRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(SosRealtimePublisher.class);

    private final SosEventRepository sosEventRepository;
    private final SosRequestRepository sosRequestRepository;
    private final SosOfferRepository sosOfferRepository;
    private final ProfessionalRepository professionalRepository;
    private final SosRealtimeDelivery delivery;

    public SosRealtimePublisher(SosEventRepository sosEventRepository,
                                 SosRequestRepository sosRequestRepository,
                                 SosOfferRepository sosOfferRepository,
                                 ProfessionalRepository professionalRepository,
                                 SosRealtimeDelivery delivery) {
        this.sosEventRepository = sosEventRepository;
        this.sosRequestRepository = sosRequestRepository;
        this.sosOfferRepository = sosOfferRepository;
        this.professionalRepository = professionalRepository;
        this.delivery = delivery;
    }

    /**
     * The entry point. {@code REQUIRES_NEW} because the publishing transaction has already
     * committed by the time this runs and there is no transaction left to join.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onSosDomainEvent(SosDomainEvent domainEvent) {
        try {
            publish(domainEvent);
        } catch (RuntimeException e) {
            // See "Failure isolation" above. The business outcome is already durable.
            log.error("sos.realtime.publish-failed sosRequestId={} eventId={} eventType={}",
                    domainEvent.sosRequestId(), domainEvent.sosEventId(), domainEvent.eventType(), e);
        }
    }

    /**
     * Package-visible so routing can be driven directly in tests, without the transaction/event
     * machinery — the same pattern {@code IssueBriefService#generateFor} uses.
     */
    void publish(SosDomainEvent domainEvent) {
        SosEvent event = sosEventRepository.findById(domainEvent.sosEventId()).orElse(null);
        SosRequest request = sosRequestRepository.findById(domainEvent.sosRequestId()).orElse(null);
        if (event == null || request == null) {
            // Only reachable if the row vanished between commit and here (a cascading delete).
            // Nothing to say, and nothing worth failing over.
            return;
        }

        switch (event.getEventType()) {
            case SOS_CREATED -> toCustomer(request, event, SosRealtimeEventType.SOS_CREATED,
                    data("status", request.getStatus().name(), "urgency", request.getUrgency().name()));

            case MATCHING_STARTED -> toCustomer(request, event, SosRealtimeEventType.MATCHING_STARTED,
                    data("status", request.getStatus().name()));

            case OFFERS_SENT -> publishOffersSent(request, event);

            // Telemetry only. Nobody needs to be woken up because a professional opened a card.
            case OFFER_VIEWED -> { }

            case PROFESSIONAL_RESPONDED -> publishProfessionalResponded(request, event);

            case CANDIDATES_READY -> toCustomer(request, event, SosRealtimeEventType.CANDIDATES_UPDATED,
                    data("availableCandidateCount", availableCandidateCount(request.getId())));

            case CUSTOMER_SELECTION_STARTED -> toCustomer(request, event,
                    SosRealtimeEventType.CUSTOMER_SELECTION_STARTED,
                    data("availableCandidateCount", availableCandidateCount(request.getId()),
                            "selectionExpiresAt", request.getSelectionExpiresAt()));

            case PROFESSIONAL_SELECTED -> publishSelection(request, event);

            case PROFESSIONAL_CONFIRMED -> toCustomerAndSelectedProfessional(request, event,
                    SosRealtimeEventType.PROFESSIONAL_CONFIRMED,
                    data("status", request.getStatus().name(),
                            "professionalId", request.getSelectedProfessionalId()));

            case ON_THE_WAY -> toCustomerAndSelectedProfessional(request, event,
                    SosRealtimeEventType.ON_THE_WAY, data("status", request.getStatus().name()));

            case ARRIVED -> toCustomerAndSelectedProfessional(request, event,
                    SosRealtimeEventType.ARRIVED, data("status", request.getStatus().name()));

            case COMPLETED -> toCustomerAndSelectedProfessional(request, event,
                    SosRealtimeEventType.COMPLETED,
                    data("status", request.getStatus().name(), "orderId", request.getOrderId()));

            case CANCELLED -> publishTermination(request, event, SosRealtimeEventType.CANCELLED,
                    data("status", request.getStatus().name(),
                            "cancelledBy", request.getCancelledBy() == null
                                    ? null : request.getCancelledBy().name()));

            case EXPIRED -> publishTermination(request, event, SosRealtimeEventType.EXPIRED,
                    data("status", request.getStatus().name(), "reason", event.getDetail()));

            case FAILED -> toCustomer(request, event, SosRealtimeEventType.SOS_FAILED,
                    data("status", request.getStatus().name(), "reason", event.getDetail()));
        }
    }

    // ------------------------------------------------------------------
    // Per-event routing
    // ------------------------------------------------------------------

    /**
     * Dispatch fan-out. The customer learns only <em>how many</em> professionals were contacted —
     * who they are is not theirs to know until those professionals choose to respond. Each
     * contacted professional gets their own offer, and only their own.
     */
    private void publishOffersSent(SosRequest request, SosEvent event) {
        List<SosOffer> offers = sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(request.getId());

        toCustomer(request, event, SosRealtimeEventType.OFFERS_SENT,
                data("offerCount", offers.size(), "status", request.getStatus().name()));

        Map<Long, Long> userIds = professionalUserIds(offers.stream().map(SosOffer::getProfessionalId).toList());
        for (SosOffer offer : offers) {
            if (offer.getStatus() != SosOfferStatus.OFFERED) {
                // A later dispatch wave re-running over an already-answered offer must not
                // re-notify it as a fresh opportunity.
                continue;
            }
            delivery.sendToUser(userIds.get(offer.getProfessionalId()),
                    message(event, SosRealtimeEventType.SOS_OFFER_RECEIVED, request, offerPayload(request, offer)));
        }
    }

    /**
     * A professional answered. What the customer is told depends entirely on <em>what</em> they
     * answered, read back from the committed offer rather than guessed from the event text.
     *
     * <ul>
     *   <li>{@code ACCEPTED} — they are available. The customer gets
     *       {@link SosRealtimeEventType#PROFESSIONAL_AVAILABLE} with the running count. This is
     *       explicitly <b>not</b> an assignment; nothing about the request's ownership changes.</li>
     *   <li>{@code REJECTED} — the customer is told nothing. A decline changes nothing they can
     *       see or act on, and naming who declined would leak a professional's business decision
     *       for no benefit.</li>
     *   <li>{@code SELECTED} — the only way to reach this branch is an ETA revision after being
     *       chosen, which the customer very much wants.</li>
     * </ul>
     *
     * The responding professional always gets a self-ack, so their other devices stay in step.
     */
    private void publishProfessionalResponded(SosRequest request, SosEvent event) {
        SosOffer offer = event.getSosOfferId() == null
                ? null
                : sosOfferRepository.findById(event.getSosOfferId()).orElse(null);
        if (offer == null) {
            return;
        }

        switch (offer.getStatus()) {
            case ACCEPTED -> toCustomer(request, event, SosRealtimeEventType.PROFESSIONAL_AVAILABLE,
                    data("availableCandidateCount", availableCandidateCount(request.getId()),
                            "offerId", offer.getId()));
            case SELECTED -> toCustomer(request, event, SosRealtimeEventType.ETA_UPDATED,
                    data("offerId", offer.getId(),
                            "estimatedArrivalMinutes", offer.getEstimatedArrivalMinutes()));
            default -> { /* REJECTED and every closed status: nothing for the customer. */ }
        }

        delivery.sendToUser(professionalUserId(offer.getProfessionalId()),
                message(event, SosRealtimeEventType.OFFER_RESPONSE_RECORDED, request,
                        data("offerId", offer.getId(), "offerStatus", offer.getStatus().name(),
                                "estimatedArrivalMinutes", offer.getEstimatedArrivalMinutes())));
    }

    /**
     * The award. Three distinct audiences, three distinct messages — the point in the flow where
     * conflating "available" with "selected" would do real damage, so each party is told precisely
     * their own outcome.
     */
    private void publishSelection(SosRequest request, SosEvent event) {
        toCustomer(request, event, SosRealtimeEventType.PROFESSIONAL_SELECTED,
                data("status", request.getStatus().name(),
                        "professionalId", request.getSelectedProfessionalId(),
                        "offerId", request.getSelectedOfferId(),
                        "orderId", request.getOrderId()));

        List<SosOffer> offers = sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(request.getId());
        Map<Long, Long> userIds = professionalUserIds(offers.stream().map(SosOffer::getProfessionalId).toList());

        for (SosOffer offer : offers) {
            Long recipient = userIds.get(offer.getProfessionalId());
            if (offer.getStatus() == SosOfferStatus.SELECTED) {
                delivery.sendToUser(recipient, message(event, SosRealtimeEventType.SOS_SELECTED, request,
                        data("offerId", offer.getId(), "orderId", request.getOrderId(),
                                "status", request.getStatus().name())));
            } else if (offer.getStatus() == SosOfferStatus.NOT_SELECTED) {
                // NOT_SELECTED is reachable only from ACCEPTED, so this is exactly the set that
                // said "I'm available" and lost. Offers that were never answered are EXPIRED and
                // fall through here deliberately -- being passed over is only meaningful to
                // someone who was actually in the running.
                delivery.sendToUser(recipient, message(event, SosRealtimeEventType.SOS_NOT_SELECTED, request,
                        data("offerId", offer.getId())));
            }
        }
    }

    /**
     * Cancellation and expiry. Reaches the customer plus every professional with a live stake —
     * anyone still holding an offer, anyone who said they were available, and the selected
     * professional. Professionals who actively declined are skipped: they already opted out, and
     * telling them the request they refused is over is noise.
     */
    private void publishTermination(SosRequest request, SosEvent event, SosRealtimeEventType type,
                                     Map<String, Object> payload) {
        toCustomer(request, event, type, payload);

        List<SosOffer> offers = sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(request.getId()).stream()
                .filter(offer -> offer.getStatus() != SosOfferStatus.REJECTED)
                .toList();
        Map<Long, Long> userIds = professionalUserIds(offers.stream().map(SosOffer::getProfessionalId).toList());

        for (SosOffer offer : offers) {
            delivery.sendToUser(userIds.get(offer.getProfessionalId()),
                    message(event, type, request, data("offerId", offer.getId(),
                            "status", request.getStatus().name())));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void toCustomer(SosRequest request, SosEvent event, SosRealtimeEventType type,
                             Map<String, Object> payload) {
        delivery.sendToUser(request.getCustomerId(), message(event, type, request, payload));
    }

    /** Customer plus the selected professional's self-ack — the shared operational lifecycle shape. */
    private void toCustomerAndSelectedProfessional(SosRequest request, SosEvent event,
                                                    SosRealtimeEventType type, Map<String, Object> payload) {
        toCustomer(request, event, type, payload);
        if (request.getSelectedProfessionalId() != null) {
            delivery.sendToUser(professionalUserId(request.getSelectedProfessionalId()),
                    message(event, type, request, payload));
        }
    }

    /**
     * What a professional may see about a job they have not been selected for: enough to decide,
     * and nothing more. City only — never street, house number, customer name or phone. The full
     * address becomes available through REST once they are actually selected and an order exists.
     */
    private Map<String, Object> offerPayload(SosRequest request, SosOffer offer) {
        return data(
                "offerId", offer.getId(),
                "categoryId", request.getCategoryId(),
                "subServiceId", request.getSubServiceId(),
                "issueSummary", request.getIssueSummary(),
                "urgency", request.getUrgency().name(),
                "serviceCity", request.getServiceCity(),
                "distanceKm", offer.getDistanceKm(),
                "estimatedArrivalMinutes", offer.getEstimatedArrivalMinutes(),
                "visitFee", offer.getVisitFee(),
                "sosFee", offer.getSosFee(),
                "platformCommission", offer.getPlatformCommission(),
                "professionalNet", offer.getProfessionalNet(),
                "expiresAt", offer.getExpiresAt());
    }

    private SosRealtimeMessage message(SosEvent event, SosRealtimeEventType type, SosRequest request,
                                        Map<String, Object> payload) {
        return new SosRealtimeMessage(event.getId(), type, request.getId(), event.getCreatedAt(), payload);
    }

    private long availableCandidateCount(Long sosRequestId) {
        return sosOfferRepository.countBySosRequestIdAndStatus(sosRequestId, SosOfferStatus.ACCEPTED);
    }

    /** One batched lookup for a whole fan-out, rather than a query per recipient. */
    private Map<Long, Long> professionalUserIds(Collection<Long> professionalIds) {
        if (professionalIds.isEmpty()) {
            return Map.of();
        }
        return professionalRepository.findAllById(professionalIds).stream()
                .collect(Collectors.toMap(Professional::getId, Professional::getUserId, (a, b) -> a));
    }

    private Long professionalUserId(Long professionalId) {
        return professionalRepository.findById(professionalId)
                .map(Professional::getUserId)
                .orElse(null);
    }
}
