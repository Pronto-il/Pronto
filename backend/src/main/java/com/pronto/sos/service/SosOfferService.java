package com.pronto.sos.service;

import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.service.NotificationService;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.sos.dto.SosOfferResponse;
import com.pronto.sos.dto.SosOffersListResponse;
import com.pronto.sos.dto.SosRequestResponse;
import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.sos.repository.SosRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The professional-facing half of Pronto SOS: see offers, accept or decline, then run the job.
 *
 * <p><b>Two distinct authorization rules apply here, and conflating them would be a real
 * security bug:</b>
 * <ul>
 *   <li><b>Offer operations</b> (view, accept, reject, ETA) — the caller must be the
 *       professional the offer was <em>sent to</em>. Checked against
 *       {@code sos_offers.professional_id}.</li>
 *   <li><b>Operational transitions</b> ({@code ON_THE_WAY}, {@code ARRIVED}, {@code COMPLETED})
 *       — the caller must be the professional who was <em>selected</em>. Checked against
 *       {@code sos_requests.selected_professional_id}, and re-checked inside every guarded
 *       update, so one of the losing candidates cannot drive the job of the one who won.</li>
 * </ul>
 */
@Service
public class SosOfferService {

    private static final Logger log = LoggerFactory.getLogger(SosOfferService.class);

    private final SosOfferRepository sosOfferRepository;
    private final SosRequestRepository sosRequestRepository;
    private final ProfessionalRepository professionalRepository;
    private final OrderRepository orderRepository;
    private final IssueRepository issueRepository;
    private final SosService sosService;
    private final SosEventService sosEventService;
    private final SosResponseAssembler assembler;
    private final NotificationService notificationService;

    public SosOfferService(SosOfferRepository sosOfferRepository,
                            SosRequestRepository sosRequestRepository,
                            ProfessionalRepository professionalRepository,
                            OrderRepository orderRepository,
                            IssueRepository issueRepository,
                            SosService sosService,
                            SosEventService sosEventService,
                            SosResponseAssembler assembler,
                            NotificationService notificationService) {
        this.sosOfferRepository = sosOfferRepository;
        this.sosRequestRepository = sosRequestRepository;
        this.professionalRepository = professionalRepository;
        this.orderRepository = orderRepository;
        this.issueRepository = issueRepository;
        this.sosService = sosService;
        this.sosEventService = sosEventService;
        this.assembler = assembler;
        this.notificationService = notificationService;
    }

    // ------------------------------------------------------------------
    // Inbox
    // ------------------------------------------------------------------

    /**
     * {@code GET /api/sos/offers}. Defaults to live offers only ({@code OFFERED}/{@code VIEWED}/
     * {@code ACCEPTED}/{@code SELECTED}) — an SOS inbox is a work queue, not a history, and
     * burying two live offers under fifty expired ones is how urgent calls get missed.
     * {@code includeClosed=true} returns everything.
     */
    @Transactional(readOnly = true)
    public SosOffersListResponse listOffers(Long callerId, boolean includeClosed) {
        Long professionalId = resolveProfessionalId(callerId);
        List<SosOffer> offers = includeClosed
                ? sosOfferRepository.findByProfessionalIdOrderByCreatedAtDesc(professionalId)
                : sosOfferRepository.findByProfessionalIdAndStatusInOrderByCreatedAtDesc(professionalId,
                        List.of(SosOfferStatus.OFFERED, SosOfferStatus.VIEWED, SosOfferStatus.ACCEPTED,
                                SosOfferStatus.SELECTED));

        return new SosOffersListResponse(offers.stream()
                .map(offer -> assembler.toOfferResponse(offer, sosService.loadRequest(offer.getSosRequestId())))
                .toList());
    }

    /**
     * {@code GET /api/sos/offers/{id}}. Opening an offer marks it {@code VIEWED} — a genuine
     * side effect on a GET, chosen deliberately: it is idempotent, it is the only honest moment
     * to record that the professional saw the opportunity, and response latency is one of the
     * ranking signals this feature is meant to start collecting. The guarded update means a
     * second open is a no-op and can never move an already-answered offer backwards.
     */
    @Transactional
    public SosOfferResponse getOffer(Long callerId, Long offerId) {
        SosOffer offer = loadOffer(offerId);
        Long professionalId = resolveProfessionalId(callerId);
        authorizeOfferRecipient(offer, professionalId);

        int viewed = sosOfferRepository.markViewed(offerId, Instant.now());
        if (viewed > 0) {
            SosRequest request = sosService.loadRequest(offer.getSosRequestId());
            sosEventService.recordProfessional(request.getId(), callerId, professionalId, offerId,
                    SosEventType.OFFER_VIEWED, request.getStatus(), null, "Offer opened");
        }

        SosOffer current = loadOffer(offerId);
        return assembler.toOfferResponse(current, sosService.loadRequest(current.getSosRequestId()));
    }

    // ------------------------------------------------------------------
    // Respond
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/sos/offers/{id}/accept}.
     *
     * <p>Expiry is enforced <b>in the guarded update</b> ({@code expiresAt > :now}), not by the
     * pre-check below — the pre-check exists only to produce a specific error message. That
     * ordering matters: an offer that expires in the microseconds between a read and a write
     * must be rejected by the database, not slip through because application code checked the
     * clock a moment too early.
     *
     * <p>Accepting the last needed candidate opens the customer's selection window immediately
     * rather than waiting out the response timer — with three good options in hand there is
     * nothing to gain from making someone with a burst pipe wait for a fourth.
     */
    @Transactional
    public SosOfferResponse accept(Long callerId, Long offerId, Integer estimatedArrivalMinutes) {
        SosOffer offer = loadOffer(offerId);
        Long professionalId = resolveProfessionalId(callerId);
        authorizeOfferRecipient(offer, professionalId);

        SosRequest request = sosService.loadRequest(offer.getSosRequestId());
        if (request.getStatus() != SosRequestStatus.WAITING_FOR_PROFESSIONALS) {
            // The request moved on -- someone was already chosen, or it was cancelled/expired.
            throw new ApiException(ErrorCode.SOS_INVALID_STATE,
                    "SOS request " + request.getId() + " is no longer accepting responses (status "
                            + request.getStatus() + ").");
        }
        if (!offer.getStatus().isOpen()) {
            throw new ApiException(ErrorCode.SOS_OFFER_NOT_OPEN,
                    "Offer " + offerId + " is " + offer.getStatus() + " and can no longer be answered.");
        }

        Instant now = Instant.now();
        Short eta = estimatedArrivalMinutes == null
                ? offer.getEstimatedArrivalMinutes()
                : estimatedArrivalMinutes.shortValue();

        int accepted = sosOfferRepository.accept(offerId, eta, now);
        if (accepted == 0) {
            // Either it expired (the common case) or another call answered it first.
            SosOffer after = loadOffer(offerId);
            if (after.getStatus().isOpen()) {
                throw new ApiException(ErrorCode.SOS_WINDOW_EXPIRED,
                        "Offer " + offerId + " has expired and can no longer be accepted.");
            }
            throw new ApiException(ErrorCode.SOS_OFFER_NOT_OPEN,
                    "Offer " + offerId + " is " + after.getStatus() + " and can no longer be answered.");
        }

        sosEventService.recordProfessional(request.getId(), callerId, professionalId, offerId,
                SosEventType.PROFESSIONAL_RESPONDED, request.getStatus(), null,
                "Accepted" + (eta == null ? "" : ", ETA " + eta + " min"));

        sosService.maybeOpenSelectionWindow(request.getId(), false);

        log.info("sos.offer.accepted sosRequestId={} offerId={} professionalId={} etaMinutes={}",
                request.getId(), offerId, professionalId, eta);
        return assembler.toOfferResponse(loadOffer(offerId), sosService.loadRequest(request.getId()));
    }

    /**
     * {@code POST /api/sos/offers/{id}/reject}. No expiry guard — declining late is harmless,
     * and refusing a decline because a timer lapsed would just produce a confusing error for a
     * professional doing the right thing.
     */
    @Transactional
    public SosOfferResponse reject(Long callerId, Long offerId) {
        SosOffer offer = loadOffer(offerId);
        Long professionalId = resolveProfessionalId(callerId);
        authorizeOfferRecipient(offer, professionalId);

        int rejected = sosOfferRepository.reject(offerId, Instant.now());
        if (rejected == 0) {
            throw new ApiException(ErrorCode.SOS_OFFER_NOT_OPEN,
                    "Offer " + offerId + " is " + loadOffer(offerId).getStatus()
                            + " and can no longer be answered.");
        }

        SosRequest request = sosService.loadRequest(offer.getSosRequestId());
        sosEventService.recordProfessional(request.getId(), callerId, professionalId, offerId,
                SosEventType.PROFESSIONAL_RESPONDED, request.getStatus(), null, "Declined");

        log.info("sos.offer.rejected sosRequestId={} offerId={} professionalId={}",
                request.getId(), offerId, professionalId);
        return assembler.toOfferResponse(loadOffer(offerId), request);
    }

    /**
     * {@code POST /api/sos/offers/{id}/eta} — revise a committed ETA. Allowed while
     * {@code ACCEPTED} or {@code SELECTED}: traffic changes, and a customer watching a stale ETA
     * is worse than one watching a revised one.
     */
    @Transactional
    public SosOfferResponse updateEta(Long callerId, Long offerId, Integer estimatedArrivalMinutes) {
        SosOffer offer = loadOffer(offerId);
        Long professionalId = resolveProfessionalId(callerId);
        authorizeOfferRecipient(offer, professionalId);

        int updated = sosOfferRepository.updateEta(offerId, estimatedArrivalMinutes.shortValue(), Instant.now());
        if (updated == 0) {
            throw new ApiException(ErrorCode.SOS_OFFER_NOT_OPEN,
                    "Offer " + offerId + " is " + offer.getStatus() + "; its ETA can no longer be changed.");
        }

        SosRequest request = sosService.loadRequest(offer.getSosRequestId());
        sosEventService.recordProfessional(request.getId(), callerId, professionalId, offerId,
                SosEventType.PROFESSIONAL_RESPONDED, request.getStatus(), null,
                "ETA updated to " + estimatedArrivalMinutes + " min");
        return assembler.toOfferResponse(loadOffer(offerId), request);
    }

    // ------------------------------------------------------------------
    // Operational transitions — selected professional only
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/sos/requests/{id}/confirm} — the selected professional confirms they are
     * taking the job. Also accepts the linked order ({@code PENDING -> CONFIRMED}), so the SOS
     * request and its order stay in step.
     */
    @Transactional
    public SosRequestResponse confirm(Long callerId, Long sosRequestId) {
        SosRequest request = sosService.loadRequest(sosRequestId);
        Long professionalId = requireSelectedProfessional(callerId, request);
        SosStateMachine.validate(sosRequestId, request.getStatus(), SosRequestStatus.CONFIRMED);

        Instant now = Instant.now();
        int affected = sosRequestRepository.confirm(sosRequestId, professionalId, now);
        if (affected == 0) {
            throw invalidState(request, SosRequestStatus.CONFIRMED);
        }
        if (request.getOrderId() != null) {
            orderRepository.acceptIfPending(request.getOrderId(), now);
        }

        sosEventService.recordProfessional(sosRequestId, callerId, professionalId, request.getSelectedOfferId(),
                SosEventType.PROFESSIONAL_CONFIRMED, SosRequestStatus.PROFESSIONAL_SELECTED,
                SosRequestStatus.CONFIRMED, "Professional confirmed the job");
        notificationService.recordSosNotification(sosRequestId, request.getCustomerId(),
                NotificationMessageType.SOS_PROFESSIONAL_CONFIRMED);

        log.info("sos.confirmed sosRequestId={} professionalId={}", sosRequestId, professionalId);
        return assembler.toRequestResponse(sosService.reload(sosRequestId));
    }

    /** {@code POST /api/sos/requests/{id}/on-the-way}. Mirrors the transition onto the order. */
    @Transactional
    public SosRequestResponse onTheWay(Long callerId, Long sosRequestId) {
        SosRequest request = sosService.loadRequest(sosRequestId);
        Long professionalId = requireSelectedProfessional(callerId, request);
        SosStateMachine.validate(sosRequestId, request.getStatus(), SosRequestStatus.ON_THE_WAY);

        Instant now = Instant.now();
        int affected = sosRequestRepository.markOnTheWay(sosRequestId, professionalId, now);
        if (affected == 0) {
            throw invalidState(request, SosRequestStatus.ON_THE_WAY);
        }
        if (request.getOrderId() != null) {
            // Expected arrival is derived from the professional's own committed ETA rather than
            // recomputed from the distance estimator -- they have already told us, and their
            // figure is the one the customer was shown when choosing.
            orderRepository.onTheWayIfConfirmed(request.getOrderId(), now, expectedArrival(request, now));
        }

        sosEventService.recordProfessional(sosRequestId, callerId, professionalId, request.getSelectedOfferId(),
                SosEventType.ON_THE_WAY, SosRequestStatus.CONFIRMED, SosRequestStatus.ON_THE_WAY,
                "Professional is on the way");
        notificationService.recordSosNotification(sosRequestId, request.getCustomerId(),
                NotificationMessageType.SOS_ON_THE_WAY);
        return assembler.toRequestResponse(sosService.reload(sosRequestId));
    }

    /**
     * {@code POST /api/sos/requests/{id}/arrived}. SOS-only — {@code orders} has no
     * {@code ARRIVED} status, so the order deliberately stays {@code ON_THE_WAY} here. Adding a
     * status to the shared order state machine for one flow's benefit would be a far larger
     * change than this beat is worth.
     */
    @Transactional
    public SosRequestResponse arrived(Long callerId, Long sosRequestId) {
        SosRequest request = sosService.loadRequest(sosRequestId);
        Long professionalId = requireSelectedProfessional(callerId, request);
        SosStateMachine.validate(sosRequestId, request.getStatus(), SosRequestStatus.ARRIVED);

        int affected = sosRequestRepository.markArrived(sosRequestId, professionalId, Instant.now());
        if (affected == 0) {
            throw invalidState(request, SosRequestStatus.ARRIVED);
        }

        sosEventService.recordProfessional(sosRequestId, callerId, professionalId, request.getSelectedOfferId(),
                SosEventType.ARRIVED, SosRequestStatus.ON_THE_WAY, SosRequestStatus.ARRIVED,
                "Professional arrived on site");
        notificationService.recordSosNotification(sosRequestId, request.getCustomerId(),
                NotificationMessageType.SOS_ARRIVED);
        return assembler.toRequestResponse(sosService.reload(sosRequestId));
    }

    /**
     * {@code POST /api/sos/requests/{id}/complete}. Completes the SOS request, its order and its
     * issue together — which is what makes the job reviewable, since
     * {@code reviews.service.ReviewsService} requires a {@code COMPLETED} order.
     */
    @Transactional
    public SosRequestResponse complete(Long callerId, Long sosRequestId) {
        SosRequest request = sosService.loadRequest(sosRequestId);
        Long professionalId = requireSelectedProfessional(callerId, request);
        SosStateMachine.validate(sosRequestId, request.getStatus(), SosRequestStatus.COMPLETED);

        Instant now = Instant.now();
        int affected = sosRequestRepository.markCompleted(sosRequestId, professionalId, now);
        if (affected == 0) {
            throw invalidState(request, SosRequestStatus.COMPLETED);
        }
        if (request.getOrderId() != null) {
            orderRepository.completeIfOnTheWay(request.getOrderId(), now);
        }
        issueRepository.completeIfBooked(request.getIssueId(), now);

        sosEventService.recordProfessional(sosRequestId, callerId, professionalId, request.getSelectedOfferId(),
                SosEventType.COMPLETED, SosRequestStatus.ARRIVED, SosRequestStatus.COMPLETED, "Job completed");
        notificationService.recordSosNotification(sosRequestId, request.getCustomerId(),
                NotificationMessageType.SOS_COMPLETED);

        log.info("sos.completed sosRequestId={} professionalId={} orderId={}",
                sosRequestId, professionalId, request.getOrderId());
        return assembler.toRequestResponse(sosService.reload(sosRequestId));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private SosOffer loadOffer(Long offerId) {
        return sosOfferRepository.findById(offerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "SOS offer " + offerId + " not found."));
    }

    /**
     * A professional may only ever touch an offer that was sent to them. Note this returns
     * {@code 403}, not {@code 404} — the offer id is not secret, and the codebase's convention
     * is that an authorization failure on a located resource is {@code FORBIDDEN}.
     */
    private void authorizeOfferRecipient(SosOffer offer, Long professionalId) {
        if (!offer.getProfessionalId().equals(professionalId)) {
            throw forbidden();
        }
    }

    /**
     * The gate on every operational transition: the caller must be the professional actually
     * selected for this request. Losing candidates, and professionals with no connection to the
     * request at all, are both rejected here — and again by the {@code selectedProfessionalId}
     * clause inside each guarded update.
     */
    private Long requireSelectedProfessional(Long callerId, SosRequest request) {
        Long professionalId = resolveProfessionalId(callerId);
        if (request.getSelectedProfessionalId() == null
                || !request.getSelectedProfessionalId().equals(professionalId)) {
            throw forbidden();
        }
        return professionalId;
    }

    /** Derives an expected-arrival instant from the selected offer's committed ETA, if there is one. */
    private Instant expectedArrival(SosRequest request, Instant now) {
        if (request.getSelectedOfferId() == null) {
            return null;
        }
        return sosOfferRepository.findById(request.getSelectedOfferId())
                .map(SosOffer::getEstimatedArrivalMinutes)
                .map(eta -> now.plusSeconds(eta * 60L))
                .orElse(null);
    }

    private Long resolveProfessionalId(Long callerId) {
        return professionalRepository.findByUserId(callerId)
                .map(Professional::getId)
                .orElseThrow(this::forbidden);
    }

    private ApiException invalidState(SosRequest request, SosRequestStatus target) {
        return new ApiException(ErrorCode.SOS_INVALID_STATE,
                "SOS request " + request.getId() + " cannot move from " + request.getStatus()
                        + " to " + target + ".");
    }

    private ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "You are not authorized to perform this action.");
    }
}
