package com.pronto.sos.service;

import com.pronto.bookings.entity.CancelledBy;
import com.pronto.bookings.entity.Order;
import com.pronto.bookings.entity.OrderStatus;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueStatus;
import com.pronto.issues.entity.IssueUrgencyType;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.service.NotificationService;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.CreateSosRequestRequest;
import com.pronto.sos.dto.SosCandidate;
import com.pronto.sos.dto.SosCandidatesResponse;
import com.pronto.sos.dto.SosEventResponse;
import com.pronto.sos.dto.SosRequestResponse;
import com.pronto.sos.dto.SosRequestsListResponse;
import com.pronto.sos.dto.SosTimelineResponse;
import com.pronto.sos.entity.SosActorType;
import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.sos.repository.SosRequestRepository;
import com.pronto.users.entity.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The customer-facing half of Pronto SOS: activate, observe, choose, cancel. The
 * professional-facing half lives in {@link SosOfferService}; the two are split along the same
 * seam the API is, and share {@link SosResponseAssembler} so their overlapping views of the
 * same rows cannot drift.
 *
 * <p>Every status change here goes through {@link SosStateMachine#validate} (is this legal at
 * all?) <em>and</em> an atomic guarded update in {@code SosRequestRepository} (did I win the
 * race?). Neither is redundant — see {@link SosStateMachine}'s Javadoc.
 *
 * <p><b>Deadline enforcement is lazy plus swept.</b> Every read path calls
 * {@link #enforceDeadlines}, so an expired request is never served as live even if the
 * background sweep has not run yet; {@code SosSweepJob} then guarantees requests nobody is
 * looking at also terminate. The frontend timer is presentation only and is never trusted.
 */
@Service
public class SosService {

    private static final Logger log = LoggerFactory.getLogger(SosService.class);

    /**
     * How long a selected professional has to confirm before the request expires. Distinct from
     * the offer TTL — they already said yes, so this is about them still being reachable, and a
     * customer left staring at "waiting for confirmation" is the worst state in the flow.
     * Deliberately generous relative to the other windows.
     */
    private static final Duration CONFIRMATION_GRACE = Duration.ofMinutes(3);

    private final SosRequestRepository sosRequestRepository;
    private final SosOfferRepository sosOfferRepository;
    private final IssueRepository issueRepository;
    private final OrderRepository orderRepository;
    private final ProfessionalRepository professionalRepository;
    private final SosDispatchService sosDispatchService;
    private final SosEventService sosEventService;
    private final SosResponseAssembler assembler;
    private final NotificationService notificationService;
    private final SosProperties properties;

    public SosService(SosRequestRepository sosRequestRepository,
                       SosOfferRepository sosOfferRepository,
                       IssueRepository issueRepository,
                       OrderRepository orderRepository,
                       ProfessionalRepository professionalRepository,
                       SosDispatchService sosDispatchService,
                       SosEventService sosEventService,
                       SosResponseAssembler assembler,
                       NotificationService notificationService,
                       SosProperties properties) {
        this.sosRequestRepository = sosRequestRepository;
        this.sosOfferRepository = sosOfferRepository;
        this.issueRepository = issueRepository;
        this.orderRepository = orderRepository;
        this.professionalRepository = professionalRepository;
        this.sosDispatchService = sosDispatchService;
        this.sosEventService = sosEventService;
        this.assembler = assembler;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Customer: activate
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/sos/requests}. Creates the request and runs the first dispatch wave
     * synchronously, inside one transaction:
     *
     * <ol>
     *   <li>Load and authorize the issue — owned by the caller, {@code urgencyType = SOS},
     *       {@code status = OPEN}.</li>
     *   <li>Insert the {@code sos_requests} row ({@code CREATED}) and record {@code SOS_CREATED}.</li>
     *   <li>{@code CREATED -> MATCHING} via the guarded update, recording {@code MATCHING_STARTED}.</li>
     *   <li>Delegate to {@link SosDispatchService#dispatch}, which ranks, writes offers, notifies,
     *       and lands on {@code WAITING_FOR_PROFESSIONALS} or {@code FAILED}.</li>
     * </ol>
     *
     * <p><b>Why synchronous.</b> Matching and dispatch are two indexed queries and some
     * arithmetic — pushing them onto {@code @Async} would buy nothing but a window in which the
     * customer's screen says "searching" while no offers exist, and would make the whole thing
     * unable to roll back as one unit. If matching later grows expensive (a real routing API,
     * say), the seam to move is this one call.
     *
     * <p>The issue is <b>not</b> transitioned to {@code BOOKED} here. It becomes booked at
     * selection, when a real order exists — a dispatch that fails to find anyone must leave the
     * issue open so the customer can fall back to the standard booking flow.
     */
    @Transactional
    public SosRequestResponse create(Long callerId, CreateSosRequestRequest request) {
        Issue issue = issueRepository.findById(request.issueId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Issue " + request.issueId() + " not found."));
        if (!issue.getCustomerId().equals(callerId)) {
            throw forbidden();
        }
        if (issue.getUrgencyType() != IssueUrgencyType.SOS) {
            throw new ApiException(ErrorCode.ISSUE_URGENCY_MISMATCH,
                    "Issue " + issue.getId() + " is not an SOS issue.");
        }
        if (issue.getStatus() != IssueStatus.OPEN) {
            throw new ApiException(ErrorCode.ISSUE_NOT_BOOKABLE,
                    "Issue " + issue.getId() + " is not open.");
        }
        // Fast, friendly pre-check. ux_sos_requests_issue below is the authoritative guard
        // against two concurrent activations for the same issue.
        if (sosRequestRepository.existsByIssueId(issue.getId())) {
            throw new ApiException(ErrorCode.SOS_REQUEST_ALREADY_EXISTS,
                    "An SOS request already exists for issue " + issue.getId() + ".");
        }

        SosUrgency urgency = request.urgency() == null ? SosUrgency.URGENT : request.urgency();
        SosRequest sosRequest = new SosRequest(issue.getId(), callerId, issue.getCategoryId(), null,
                request.issueSummary(), urgency, request.serviceCity(), request.serviceStreet(),
                request.serviceHouseNumber(), request.serviceApartment(), request.serviceFloor(),
                request.serviceEntrance(), request.serviceAddressNotes(), request.latitude(), request.longitude());
        try {
            sosRequest = sosRequestRepository.saveAndFlush(sosRequest);
        } catch (DataIntegrityViolationException e) {
            // ux_sos_requests_issue -- a double-tapped SOS button, where both requests passed the
            // pre-check above before either committed.
            throw new ApiException(ErrorCode.SOS_REQUEST_ALREADY_EXISTS,
                    "An SOS request already exists for issue " + issue.getId() + ".");
        }

        sosEventService.recordCustomer(sosRequest.getId(), callerId, SosEventType.SOS_CREATED, null,
                SosRequestStatus.CREATED, "SOS activated for category " + issue.getCategoryId());

        Instant now = Instant.now();
        Instant matchingExpiresAt = now.plus(Duration.ofSeconds(properties.getMatchingWindowSeconds()));
        SosStateMachine.validate(sosRequest.getId(), SosRequestStatus.CREATED, SosRequestStatus.MATCHING);
        int started = sosRequestRepository.startMatching(sosRequest.getId(), now, matchingExpiresAt);
        if (started == 0) {
            // Unreachable in practice -- the row was inserted microseconds ago in this same
            // transaction and nothing else can see it yet. Handled rather than assumed.
            throw new ApiException(ErrorCode.SOS_INVALID_STATE,
                    "SOS request " + sosRequest.getId() + " could not start matching.");
        }
        sosEventService.recordSystem(sosRequest.getId(), SosEventType.MATCHING_STARTED, SosRequestStatus.CREATED,
                SosRequestStatus.MATCHING, "Searching for available professionals nearby.");

        SosRequest matching = reload(sosRequest.getId());
        int dispatched = sosDispatchService.dispatch(matching);
        log.info("sos.created sosRequestId={} issueId={} customerId={} urgency={} offersDispatched={}",
                sosRequest.getId(), issue.getId(), callerId, urgency, dispatched);

        return assembler.toRequestResponse(reload(sosRequest.getId()));
    }

    // ------------------------------------------------------------------
    // Customer + professional: read
    // ------------------------------------------------------------------

    /**
     * {@code GET /api/sos/requests/{id}}. Readable by the owning customer, and by any
     * professional who was sent an offer on it — a professional deciding whether to accept
     * needs to see the job, and one who was selected needs to track it.
     */
    @Transactional
    public SosRequestResponse getRequest(Long callerId, String callerRole, Long sosRequestId) {
        SosRequest request = loadRequest(sosRequestId);
        authorizeRead(callerId, callerRole, request);
        return assembler.toRequestResponse(enforceDeadlines(request));
    }

    /**
     * {@code GET /api/sos/requests/me}.
     *
     * <p>Deliberately does <em>not</em> apply {@link #enforceDeadlines} to each row: a list read
     * should not fan out into N writes, and the summary a list shows is not something anyone
     * acts on. Every path that acts on a request — get, candidates, select, cancel — enforces
     * deadlines first, and the sweep catches the rest, so a stale-looking row here can never be
     * operated on as though it were live.
     */
    @Transactional(readOnly = true)
    public SosRequestsListResponse listMine(Long callerId, String callerRole) {
        List<SosRequest> requests = UserRole.PROFESSIONAL.name().equals(callerRole)
                ? sosRequestRepository.findBySelectedProfessionalIdOrderByCreatedAtDesc(
                        resolveProfessionalId(callerId))
                : sosRequestRepository.findByCustomerIdOrderByCreatedAtDesc(callerId);
        return new SosRequestsListResponse(requests.stream().map(assembler::toRequestResponse).toList());
    }

    /** {@code GET /api/sos/requests/{id}/events} — the timeline both parties see. */
    @Transactional
    public SosTimelineResponse getTimeline(Long callerId, String callerRole, Long sosRequestId) {
        SosRequest request = loadRequest(sosRequestId);
        authorizeRead(callerId, callerRole, request);
        SosRequest current = enforceDeadlines(request);
        List<SosEventResponse> events = sosEventService.timeline(sosRequestId).stream()
                .map(assembler::toEventResponse)
                .toList();
        return new SosTimelineResponse(sosRequestId, current.getStatus(), events);
    }

    /**
     * {@code GET /api/sos/requests/{id}/candidates} — customer only, and only their own request.
     *
     * <p>Returns at most {@code pronto.sos.target-candidate-count} accepted offers, soonest ETA
     * first. Deliberately not an error to call this early or late — a polling client needs a
     * successful response with {@code selectionOpen} and {@code status} to reason about, not an
     * exception it has to special-case:
     * <ul>
     *   <li><b>Still gathering responses</b> — empty list, {@code selectionOpen = false}.</li>
     *   <li><b>Window open</b> — up to 3 candidates, {@code selectionOpen = true}.</li>
     *   <li><b>Window closed</b> — empty list, {@code selectionOpen = false}, {@code status =
     *       EXPIRED}. Empty because expiry closes every outstanding offer out of
     *       {@code ACCEPTED}, so the query below correctly finds none; the terminal
     *       {@code status} is what tells the client to render "your request expired".</li>
     * </ul>
     */
    @Transactional
    public SosCandidatesResponse getCandidates(Long callerId, Long sosRequestId) {
        SosRequest request = loadRequest(sosRequestId);
        if (!request.getCustomerId().equals(callerId)) {
            throw forbidden();
        }
        SosRequest current = enforceDeadlines(request);

        List<SosCandidate> candidates = sosOfferRepository
                .findBySosRequestIdAndStatusOrderByEstimatedArrivalMinutesAsc(sosRequestId, SosOfferStatus.ACCEPTED)
                .stream()
                .limit(properties.getTargetCandidateCount())
                .map(assembler::toCandidate)
                .toList();

        boolean selectionOpen = current.getStatus() == SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION
                && current.getSelectionExpiresAt() != null
                && current.getSelectionExpiresAt().isAfter(Instant.now());

        return new SosCandidatesResponse(sosRequestId, current.getStatus(), current.getSelectionExpiresAt(),
                selectionOpen, candidates);
    }

    // ------------------------------------------------------------------
    // Customer: choose
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/sos/requests/{id}/select} — <b>the concurrency-critical operation of the
     * whole feature.</b> One customer, possibly two taps, three candidates, a two-minute
     * deadline, and money at the end of it.
     *
     * <p>The protections, in the order they apply:
     * <ol>
     *   <li><b>Ownership</b> — only the request's own customer, checked before anything else.</li>
     *   <li><b>Offer validity</b> — the offer must belong to <em>this</em> request and be
     *       {@code ACCEPTED}. An offer that expired, was rejected, or belongs to someone else's
     *       request cannot be selected regardless of what the request's status says.</li>
     *   <li><b>The order is created first</b>, before the request is mutated. If order creation
     *       fails, nothing has been claimed and the transaction rolls back cleanly.</li>
     *   <li><b>One atomic guarded update</b> ({@code selectProfessional}) simultaneously checks
     *       the status, that no professional has been selected yet, <em>and</em> that the
     *       deadline has not passed — all evaluated by the database under the row lock, so two
     *       concurrent selections cannot both succeed and a selection cannot slip past the
     *       deadline between the check and the write. 0 rows means we lost; the row is re-read
     *       to report precisely why.</li>
     *   <li><b>Losing offers are closed</b> in one statement, so no offer is left dangling as a
     *       live opportunity on a decided request.</li>
     * </ol>
     *
     * <p>The whole method is one transaction: order, request mutation, offer statuses, issue
     * transition, events and notifications either all commit or none do. A partially-selected
     * SOS request is not a state this system can reach.
     */
    @Transactional
    public SosRequestResponse selectProfessional(Long callerId, Long sosRequestId, Long offerId) {
        SosRequest request = loadRequest(sosRequestId);
        if (!request.getCustomerId().equals(callerId)) {
            throw forbidden();
        }

        SosRequest current = enforceDeadlines(request);
        if (current.getSelectedProfessionalId() != null) {
            throw new ApiException(ErrorCode.SOS_ALREADY_SELECTED,
                    "A professional has already been selected for SOS request " + sosRequestId + ".");
        }
        if (current.getStatus() != SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION) {
            // Distinguish "you ran out of time" from every other reason. This is the common
            // real failure -- a customer tapping a candidate a second after the window closes,
            // very often because enforceDeadlines above just expired it on this very call -- and
            // it deserves a specific 410 rather than a generic 409 the frontend cannot explain.
            if (current.getStatus() == SosRequestStatus.EXPIRED) {
                throw new ApiException(ErrorCode.SOS_WINDOW_EXPIRED,
                        "The selection window for SOS request " + sosRequestId + " has closed.");
            }
            throw new ApiException(ErrorCode.SOS_INVALID_STATE,
                    "SOS request " + sosRequestId + " is not awaiting customer selection (status "
                            + current.getStatus() + ").");
        }
        SosStateMachine.validate(sosRequestId, current.getStatus(), SosRequestStatus.PROFESSIONAL_SELECTED);

        SosOffer offer = sosOfferRepository.findById(offerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "SOS offer " + offerId + " not found."));
        if (!offer.getSosRequestId().equals(sosRequestId) || offer.getStatus() != SosOfferStatus.ACCEPTED) {
            throw new ApiException(ErrorCode.SOS_CANDIDATE_NOT_AVAILABLE,
                    "Offer " + offerId + " is not an available candidate for this SOS request.");
        }

        Professional professional = professionalRepository.findById(offer.getProfessionalId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Professional " + offer.getProfessionalId() + " not found."));

        Instant now = Instant.now();

        // The order carries the agreed economics, and exists so an SOS job lands in exactly the
        // same orders/reviews/history machinery every other booking uses rather than in a
        // parallel one. Created PENDING: the professional's confirmation is what accepts it,
        // mirroring the standard order flow's PENDING -> CONFIRMED accept step.
        // bookedStart = now (SOS has no scheduled time), bookedEnd/slotId = null -- the same
        // shape BookingsService.createSosOrder already writes for the browse-and-pick path.
        BigDecimal visitFee = offer.getVisitFee();
        BigDecimal finalPrice = (visitFee == null ? BigDecimal.ZERO : visitFee).add(offer.getSosFee());
        Order order = orderRepository.saveAndFlush(new Order(current.getIssueId(), callerId,
                professional.getId(), now, null, finalPrice, null, current.getServiceCity(),
                current.getServiceStreet(), current.getServiceHouseNumber(), current.getServiceApartment(),
                current.getServiceFloor(), current.getServiceEntrance(), current.getServiceAddressNotes(),
                visitFee, offer.getSosFee()));

        int selected = sosRequestRepository.selectProfessional(sosRequestId, professional.getId(), offerId,
                order.getId(), now);
        if (selected == 0) {
            // Lost the race, or the deadline passed between the read above and this write.
            // Re-read to report the accurate reason rather than a generic conflict.
            SosRequest after = reload(sosRequestId);
            if (after.getSelectedProfessionalId() != null) {
                throw new ApiException(ErrorCode.SOS_ALREADY_SELECTED,
                        "A professional has already been selected for SOS request " + sosRequestId + ".");
            }
            throw new ApiException(ErrorCode.SOS_WINDOW_EXPIRED,
                    "The selection window for SOS request " + sosRequestId + " has closed.");
        }

        int markedWinner = sosOfferRepository.markSelected(offerId, now);
        if (markedWinner == 0) {
            // The offer left ACCEPTED between the check above and here. The request has already
            // been claimed for it, so this cannot be allowed to stand -- roll the whole thing
            // back rather than leave a request pointing at an offer that is no longer valid.
            throw new ApiException(ErrorCode.SOS_CANDIDATE_NOT_AVAILABLE,
                    "Offer " + offerId + " is no longer available.");
        }
        sosOfferRepository.closeLosingOffers(sosRequestId, offerId, now);

        // The issue is booked now, not at activation -- a dispatch that found nobody must leave
        // the issue open for the standard booking flow. 0 rows is possible if the issue was
        // concurrently moved; the SOS request and order remain the source of truth either way,
        // and the standard flow treats this the same way (see BookingsService).
        issueRepository.bookIfOpen(current.getIssueId(), now);

        sosEventService.recordCustomer(sosRequestId, callerId, SosEventType.PROFESSIONAL_SELECTED,
                SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION, SosRequestStatus.PROFESSIONAL_SELECTED,
                "Customer selected professional " + professional.getId());
        notificationService.recordSosNotification(sosRequestId, professional.getUserId(),
                NotificationMessageType.SOS_PROFESSIONAL_SELECTED);

        log.info("sos.selected sosRequestId={} offerId={} professionalId={} orderId={}",
                sosRequestId, offerId, professional.getId(), order.getId());
        return assembler.toRequestResponse(reload(sosRequestId));
    }

    // ------------------------------------------------------------------
    // Cancel
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/sos/requests/{id}/cancel}. Either party may cancel, from any non-terminal
     * state — but a professional may only cancel once they are the selected professional (before
     * that they have an offer to reject, not a job to cancel).
     */
    @Transactional
    public SosRequestResponse cancel(Long callerId, String callerRole, Long sosRequestId) {
        SosRequest request = loadRequest(sosRequestId);
        SosActorType actor = determineCancelActor(callerId, callerRole, request);
        SosRequest current = enforceDeadlines(request);

        SosRequestStatus expected = current.getStatus();
        if (expected.isTerminal()) {
            throw new ApiException(ErrorCode.SOS_INVALID_STATE,
                    "SOS request " + sosRequestId + " is already " + expected + ".");
        }
        SosStateMachine.validate(sosRequestId, expected, SosRequestStatus.CANCELLED);

        Instant now = Instant.now();
        int affected = sosRequestRepository.cancelIfStatus(sosRequestId, expected, actor, now);
        if (affected == 0) {
            throw new ApiException(ErrorCode.SOS_INVALID_STATE,
                    "SOS request " + sosRequestId + " changed state and could not be cancelled.");
        }

        sosOfferRepository.closeAllOpenOffers(sosRequestId, now);
        cancelOrderIfAny(current, actor, now);
        issueRepository.revertToOpen(current.getIssueId(), now);

        sosEventService.record(sosRequestId, SosEventType.CANCELLED, actor, callerId,
                current.getSelectedProfessionalId(), current.getSelectedOfferId(), expected,
                SosRequestStatus.CANCELLED, "Cancelled by " + actor);
        notifyCounterparty(current, actor, NotificationMessageType.SOS_CANCELLED);

        log.info("sos.cancelled sosRequestId={} by={} fromStatus={}", sosRequestId, actor, expected);
        return assembler.toRequestResponse(reload(sosRequestId));
    }

    // ------------------------------------------------------------------
    // Dispatch progression — called by SosOfferService when a professional responds
    // ------------------------------------------------------------------

    /**
     * Opens the customer's selection window if the request is ready for it. Called after every
     * professional acceptance, and by the sweep when the response window closes.
     *
     * @param force when {@code true} (the response window has closed), opens with however many
     *              acceptances exist. When {@code false} (a professional just accepted), opens
     *              only once the target count is reached — so a customer with three good options
     *              chooses immediately rather than waiting out a timer for a fourth.
     * @return {@code true} if the window was opened by this call
     */
    @Transactional
    public boolean maybeOpenSelectionWindow(Long sosRequestId, boolean force) {
        SosRequest request = loadRequest(sosRequestId);
        if (request.getStatus() != SosRequestStatus.WAITING_FOR_PROFESSIONALS) {
            return false;
        }

        long accepted = sosOfferRepository.countBySosRequestIdAndStatus(sosRequestId, SosOfferStatus.ACCEPTED);
        if (accepted == 0) {
            return false;
        }
        if (!force && accepted < properties.getTargetCandidateCount()) {
            return false;
        }

        Instant now = Instant.now();
        Instant selectionExpiresAt = now.plus(Duration.ofSeconds(properties.getSelectionWindowSeconds()));
        SosStateMachine.validate(sosRequestId, SosRequestStatus.WAITING_FOR_PROFESSIONALS,
                SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        int opened = sosRequestRepository.openSelectionWindow(sosRequestId, now, selectionExpiresAt);
        if (opened == 0) {
            // Somebody else opened it, or the request was cancelled. Either way not our problem.
            return false;
        }

        sosEventService.recordSystem(sosRequestId, SosEventType.CANDIDATES_READY,
                SosRequestStatus.WAITING_FOR_PROFESSIONALS, SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION,
                accepted + " professional(s) available");
        sosEventService.recordSystem(sosRequestId, SosEventType.CUSTOMER_SELECTION_STARTED, null,
                SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION,
                "Customer has " + properties.getSelectionWindowSeconds() + "s to choose");
        notificationService.recordSosNotification(sosRequestId, request.getCustomerId(),
                NotificationMessageType.SOS_CANDIDATES_READY);

        log.info("sos.candidates-ready sosRequestId={} accepted={} force={}", sosRequestId, accepted, force);
        return true;
    }

    // ------------------------------------------------------------------
    // Expiry (lazy + swept)
    // ------------------------------------------------------------------

    /**
     * Applies any elapsed deadline to {@code request} and returns the current row.
     *
     * <p>This is what makes the backend the source of truth for the two-minute window: a request
     * whose deadline has passed is transitioned on the very next read, so no API call can ever
     * observe or act on it as though it were still live — regardless of whether the sweep job has
     * run, or is even enabled. The sweep exists to also terminate requests <em>nobody</em> is
     * reading; it is a completeness mechanism, not the enforcement mechanism.
     *
     * <p>Cheap in the common case: two field comparisons and no write unless a deadline has
     * actually elapsed.
     */
    private SosRequest enforceDeadlines(SosRequest request) {
        Instant now = Instant.now();

        if (request.getStatus() == SosRequestStatus.WAITING_FOR_PROFESSIONALS
                && request.getMatchingExpiresAt() != null && !request.getMatchingExpiresAt().isAfter(now)) {
            // The response window closed. If anyone accepted, move to selection with whoever
            // there is rather than expiring a request that has usable candidates.
            if (maybeOpenSelectionWindow(request.getId(), true)) {
                return reload(request.getId());
            }
            expire(request.getId(), SosRequestStatus.WAITING_FOR_PROFESSIONALS,
                    "No professionals accepted within the response window.");
            return reload(request.getId());
        }

        if (request.getStatus() == SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION
                && request.getSelectionExpiresAt() != null && !request.getSelectionExpiresAt().isAfter(now)) {
            expire(request.getId(), SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION,
                    "Customer did not choose a professional in time.");
            return reload(request.getId());
        }

        return request;
    }

    /**
     * Terminates a request as {@code EXPIRED} from {@code expectedStatus}. Safe to call
     * concurrently — the guarded update decides, and losing simply means somebody else already
     * handled it.
     */
    @Transactional
    public void expire(Long sosRequestId, SosRequestStatus expectedStatus, String reason) {
        Instant now = Instant.now();
        int affected = sosRequestRepository.expireIfStatus(sosRequestId, expectedStatus, now);
        if (affected == 0) {
            return;
        }
        SosRequest request = reload(sosRequestId);
        sosOfferRepository.closeAllOpenOffers(sosRequestId, now);
        cancelOrderIfAny(request, SosActorType.SYSTEM, now);
        issueRepository.revertToOpen(request.getIssueId(), now);

        sosEventService.recordSystem(sosRequestId, SosEventType.EXPIRED, expectedStatus,
                SosRequestStatus.EXPIRED, reason);
        notificationService.recordSosNotification(sosRequestId, request.getCustomerId(),
                NotificationMessageType.SOS_EXPIRED);
        log.info("sos.expired sosRequestId={} fromStatus={} reason={}", sosRequestId, expectedStatus, reason);
    }

    /** Sweep input: requests whose matching or selection deadline has passed. */
    @Transactional(readOnly = true)
    public List<Long> findExpiryCandidateIds() {
        return sosRequestRepository.findExpiryCandidateIds(Instant.now());
    }

    /**
     * Sweep input: requests whose selected professional never confirmed. See
     * {@link #CONFIRMATION_GRACE}.
     */
    @Transactional(readOnly = true)
    public List<Long> findUnconfirmedSelectionIds() {
        return sosRequestRepository.findUnconfirmedSelectionIds(Instant.now().minus(CONFIRMATION_GRACE));
    }

    /**
     * Sweep action for one candidate id. Re-reads and re-derives rather than trusting the id's
     * staleness, then routes to the right terminal transition. Silent on losing a race — a
     * background job has no HTTP caller to report a {@code 409} to.
     */
    @Transactional
    public void sweepOne(Long sosRequestId) {
        SosRequest request = sosRequestRepository.findById(sosRequestId).orElse(null);
        if (request == null) {
            return;
        }
        enforceDeadlines(request);
    }

    /** Sweep action for a selection the professional never confirmed. */
    @Transactional
    public void expireUnconfirmedSelection(Long sosRequestId) {
        expire(sosRequestId, SosRequestStatus.PROFESSIONAL_SELECTED,
                "The selected professional did not confirm in time.");
    }

    /** Sweep action: close individually-overdue offers. Returns the number closed. */
    @Transactional
    public int expireOverdueOffers() {
        return sosOfferRepository.expireOverdueOffers(Instant.now());
    }

    // ------------------------------------------------------------------
    // Shared helpers (package-visible where SosOfferService needs them)
    // ------------------------------------------------------------------

    SosRequest loadRequest(Long sosRequestId) {
        return sosRequestRepository.findById(sosRequestId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "SOS request " + sosRequestId + " not found."));
    }

    SosRequest reload(Long sosRequestId) {
        return loadRequest(sosRequestId);
    }

    /**
     * A customer may read their own request; a professional may read one they were offered.
     * Deliberately broader than the write rules — visibility of a job you were asked about is
     * not the same authority as the right to change it.
     */
    private void authorizeRead(Long callerId, String callerRole, SosRequest request) {
        if (request.getCustomerId().equals(callerId)) {
            return;
        }
        if (UserRole.PROFESSIONAL.name().equals(callerRole)) {
            Optional<Professional> professional = professionalRepository.findByUserId(callerId);
            if (professional.isPresent() && sosOfferRepository
                    .findBySosRequestIdAndProfessionalId(request.getId(), professional.get().getId()).isPresent()) {
                return;
            }
        }
        throw forbidden();
    }

    /**
     * Which party is cancelling, or {@code 403}. A professional qualifies only if they are the
     * <em>selected</em> professional — holding an unselected offer confers no right to cancel
     * the customer's request.
     */
    private SosActorType determineCancelActor(Long callerId, String callerRole, SosRequest request) {
        if (UserRole.CUSTOMER.name().equals(callerRole) && request.getCustomerId().equals(callerId)) {
            return SosActorType.CUSTOMER;
        }
        if (UserRole.PROFESSIONAL.name().equals(callerRole) && request.getSelectedProfessionalId() != null) {
            Long professionalId = professionalRepository.findByUserId(callerId)
                    .map(Professional::getId).orElse(null);
            if (request.getSelectedProfessionalId().equals(professionalId)) {
                return SosActorType.PROFESSIONAL;
            }
        }
        throw forbidden();
    }

    /**
     * Cancels the linked order, if one exists, in whatever status it currently holds. Best
     * effort by design: the SOS request is the source of truth for this flow, and an order that
     * has already moved on (completed, say) must not block the SOS request from terminating.
     */
    private void cancelOrderIfAny(SosRequest request, SosActorType actor, Instant now) {
        if (request.getOrderId() == null) {
            return;
        }
        orderRepository.findById(request.getOrderId()).ifPresent(order -> {
            if (order.getOrderStatus() == OrderStatus.COMPLETED
                    || order.getOrderStatus() == OrderStatus.CANCELLED) {
                return;
            }
            orderRepository.cancelIfStatus(order.getId(), order.getOrderStatus(), toCancelledBy(actor), now);
        });
    }

    private CancelledBy toCancelledBy(SosActorType actor) {
        return switch (actor) {
            case CUSTOMER -> CancelledBy.CUSTOMER;
            case PROFESSIONAL -> CancelledBy.PROFESSIONAL;
            case SYSTEM -> CancelledBy.SYSTEM;
        };
    }

    /** Notifies whichever party did <em>not</em> take the action. Nobody needs telling what they just did. */
    private void notifyCounterparty(SosRequest request, SosActorType actor, NotificationMessageType type) {
        if (actor == SosActorType.CUSTOMER) {
            if (request.getSelectedProfessionalId() != null) {
                professionalRepository.findById(request.getSelectedProfessionalId())
                        .ifPresent(p -> notificationService.recordSosNotification(request.getId(), p.getUserId(), type));
            }
            return;
        }
        notificationService.recordSosNotification(request.getId(), request.getCustomerId(), type);
    }

    private Long resolveProfessionalId(Long callerId) {
        return professionalRepository.findByUserId(callerId)
                .map(Professional::getId)
                .orElseThrow(this::forbidden);
    }

    private ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "You are not authorized to perform this action.");
    }
}
