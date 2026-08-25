package com.pronto.sos.service;

import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.bookings.dto.ArrivalRequest;
import com.pronto.maps.service.ArrivalVerifier;
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

    private final ArrivalVerifier arrivalVerifier;

    public SosOfferService(SosOfferRepository sosOfferRepository,
                            SosRequestRepository sosRequestRepository,
                            ProfessionalRepository professionalRepository,
                            OrderRepository orderRepository,
                            IssueRepository issueRepository,
                            SosService sosService,
                            SosEventService sosEventService,
                            SosResponseAssembler assembler,
                            NotificationService notificationService,
                            ArrivalVerifier arrivalVerifier) {
        this.arrivalVerifier = arrivalVerifier;
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
     * <p><b>An ETA is required</b> (MS3): accepting is a commitment to arrive within a stated
     * time, and the customer chooses on that number, so there is no such thing here as saying
     * "yes" without saying "when". It used to be optional, falling back to the platform's own
     * dispatch-time estimate — which meant a candidate could be shown to a customer advertising
     * a figure no human had agreed to.
     *
     * <p>Expiry is enforced <b>in the guarded update</b> ({@code expiresAt > :now}), not by the
     * pre-check below — the pre-check exists only to produce a specific error message. That
     * ordering matters: an offer that expires in the microseconds between a read and a write
     * must be rejected by the database, not slip through because application code checked the
     * clock a moment too early. Because that deadline lives on the offer row, it is genuinely
     * per professional: somebody first contacted in the last minute of the scan can still accept
     * long after the platform stopped looking for anybody new.
     *
     * <p><b>Acceptance is what makes a professional visible to the customer</b>, and it happens
     * in one transaction: the offer becomes {@code ACCEPTED} with its promised ETA, and the
     * customer's selection window opens on the very first one. So a candidate never appears
     * without an ETA beside them, and never has to wait for the scan to finish to appear at all.
     * The search does not stop when the window opens — this method still accepts responses in
     * {@code WAITING_FOR_CUSTOMER_SELECTION}, so later professionals keep appearing alongside
     * the first while the customer decides.
     */
    @Transactional
    public SosOfferResponse accept(Long callerId, Long offerId, Integer estimatedArrivalMinutes) {
        if (estimatedArrivalMinutes == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "An estimated arrival time is required when accepting an SOS offer.",
                    List.of(new FieldError("estimatedArrivalMinutes", "is required")));
        }
        SosOffer offer = loadOffer(offerId);
        Long professionalId = resolveProfessionalId(callerId);
        authorizeOfferRecipient(offer, professionalId);

        SosRequest request = sosService.loadRequest(offer.getSosRequestId());
        if (!request.getStatus().isAcceptingProfessionalResponses()) {
            // The request moved on -- someone was already chosen, or it was cancelled/expired.
            // This is also the backend half of "selection stops the search": once the customer
            // has picked, no further acceptance can create a candidate, whatever any client
            // still has on screen.
            throw new ApiException(ErrorCode.SOS_INVALID_STATE,
                    "SOS request " + request.getId() + " is no longer accepting responses (status "
                            + request.getStatus() + ").");
        }
        if (!offer.getStatus().isOpen()) {
            throw new ApiException(ErrorCode.SOS_OFFER_NOT_OPEN,
                    "Offer " + offerId + " is " + offer.getStatus() + " and can no longer be answered.");
        }

        Instant now = Instant.now();
        Short eta = estimatedArrivalMinutes.shortValue();

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
     * {@code POST /api/sos/offers/{id}/eta} — <b>always refused (MS3).</b>
     *
     * <p>An ETA used to be revisable while {@code ACCEPTED} or {@code SELECTED}, on the reasoning
     * that traffic changes and a stale figure serves nobody. The reasoning that beats it: the
     * customer picks a professional <em>because of</em> that number, so a revisable ETA lets
     * somebody win the job with a promise of fifteen minutes and change it to fifty once they
     * have. That is not a stale-data problem, it is an incentive problem, and hiding the control
     * in the professional's app would not fix it — anyone can call the endpoint.
     *
     * <p>So the refusal lives here, and the write path is gone from
     * {@code SosOfferRepository} entirely: <b>no statement in this package can change an ETA
     * after acceptance</b>, which is a stronger guarantee than a check somebody has to remember.
     * {@code sos_offers.promised_eta_minutes}/{@code accepted_at} ({@code V41}) keep the original
     * commitment on the record independently.
     *
     * <p>The route is kept rather than deleted so that a client still holding the old build gets
     * {@code 409 SOS_ETA_LOCKED} — an explanation — instead of a {@code 404} it cannot interpret.
     * A professional who genuinely cannot make it still has an honest action: cancel the job,
     * which the customer sees and can act on, rather than quietly moving the goalposts.
     */
    @Transactional(readOnly = true)
    public SosOfferResponse updateEta(Long callerId, Long offerId, Integer estimatedArrivalMinutes) {
        SosOffer offer = loadOffer(offerId);
        Long professionalId = resolveProfessionalId(callerId);
        // Authorized first, deliberately: "you may not touch someone else's offer" outranks "no
        // one may do this at all", and a 403 must not be leaked as a 409.
        authorizeOfferRecipient(offer, professionalId);

        log.info("sos.offer.eta-change-refused sosRequestId={} offerId={} professionalId={} attemptedEta={}",
                offer.getSosRequestId(), offerId, professionalId, estimatedArrivalMinutes);
        throw new ApiException(ErrorCode.SOS_ETA_LOCKED,
                "The arrival time committed when offer " + offerId + " was accepted cannot be changed.");
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
        return assembler.toRequestResponse(sosService.reload(sosRequestId), SosAddressAccess.FULL);
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
        return assembler.toRequestResponse(sosService.reload(sosRequestId), SosAddressAccess.FULL);
    }

    /**
     * {@code POST /api/sos/requests/{id}/arrived}.
     *
     * <p><b>Production MS2 — this is now geofence-verified, exactly like the Standard flow.</b>
     * It previously moved the request to {@code ARRIVED} because a professional pressed a button,
     * which was the only thing it could do: the platform had no coordinates for the customer and
     * no position for the professional. It now runs the identical check
     * ({@code maps.service.ArrivalVerifier}) against the SOS request's own destination
     * coordinates — the same point every candidate's distance was measured to when they were
     * dispatched.
     *
     * <p>Sharing the verifier rather than reimplementing it is the point. If the calm flow
     * verified arrival and the urgent one took somebody's word for it, the guarantee would be
     * worth very little: SOS is the flow where arrival speed is the entire promise.
     *
     * <p>The {@code orders} row deliberately stays {@code ON_THE_WAY}. {@code orders} does now
     * have an {@code ARRIVED} status ({@code V51}), but the SOS lifecycle tracks its own state on
     * {@code sos_requests} and moving both would give this flow two sources of truth about the
     * same fact — see {@code SosStateMachine}. Completion still reconciles the two.
     */
    @Transactional
    public SosRequestResponse arrived(Long callerId, Long sosRequestId, ArrivalRequest arrival) {
        SosRequest request = sosService.loadRequest(sosRequestId);
        Long professionalId = requireSelectedProfessional(callerId, request);
        SosStateMachine.validate(sosRequestId, request.getStatus(), SosRequestStatus.ARRIVED);

        Instant now = Instant.now();
        arrivalVerifier.verify(professionalId,
                com.pronto.maps.GeoCoordinates.ofNullable(request.getLatitude(), request.getLongitude()),
                arrival.latitude(), arrival.longitude(), arrival.accuracyMeters(), arrival.capturedAt(),
                now, "sos:" + sosRequestId);

        int affected = sosRequestRepository.markArrived(sosRequestId, professionalId, now);
        if (affected == 0) {
            throw invalidState(request, SosRequestStatus.ARRIVED);
        }

        sosEventService.recordProfessional(sosRequestId, callerId, professionalId, request.getSelectedOfferId(),
                SosEventType.ARRIVED, SosRequestStatus.ON_THE_WAY, SosRequestStatus.ARRIVED,
                "Professional arrived on site");
        notificationService.recordSosNotification(sosRequestId, request.getCustomerId(),
                NotificationMessageType.SOS_ARRIVED);
        return assembler.toRequestResponse(sosService.reload(sosRequestId), SosAddressAccess.FULL);
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
        return assembler.toRequestResponse(sosService.reload(sosRequestId), SosAddressAccess.FULL);
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
