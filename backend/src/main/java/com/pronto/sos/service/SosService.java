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
import com.pronto.maps.GeocodeResult;
import com.pronto.maps.PostalAddress;
import com.pronto.maps.SelectedPlace;
import com.pronto.maps.service.SelectedPlaceValidator;
import com.pronto.maps.service.ServiceAddressGeocoder;
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
import com.pronto.users.entity.User;
import com.pronto.users.repository.UserRepository;
import com.pronto.users.service.ContactVerificationGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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
    private final ContactVerificationGuard contactVerificationGuard;

    private final ServiceAddressGeocoder serviceAddressGeocoder;
    private final SelectedPlaceValidator selectedPlaceValidator;
    private final UserRepository userRepository;

    public SosService(SosRequestRepository sosRequestRepository,
                       SosOfferRepository sosOfferRepository,
                       IssueRepository issueRepository,
                       OrderRepository orderRepository,
                       ProfessionalRepository professionalRepository,
                       SosDispatchService sosDispatchService,
                       SosEventService sosEventService,
                       SosResponseAssembler assembler,
                       NotificationService notificationService,
                       SosProperties properties,
                       ContactVerificationGuard contactVerificationGuard,
                       ServiceAddressGeocoder serviceAddressGeocoder,
                       SelectedPlaceValidator selectedPlaceValidator,
                       UserRepository userRepository) {
        this.serviceAddressGeocoder = serviceAddressGeocoder;
        this.selectedPlaceValidator = selectedPlaceValidator;
        this.userRepository = userRepository;
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
        this.contactVerificationGuard = contactVerificationGuard;
    }

    /**
     * Is this submitted destination the caller's own stored default address?
     *
     * <p>The grandfathering test, identical in intent and mechanism to
     * {@code BookingsService}'s: compared by {@link PostalAddress#contentHash()} so that spacing
     * and capitalisation do not make the same address look new, and safe to trust because the only
     * text it admits is text already saved on the caller's own {@code users} row.
     *
     * <p><b>SOS is the flow where getting this wrong would hurt most.</b> A customer with a burst
     * pipe, whose saved address predates address validation, must not be told to go and re-select
     * it from a dropdown before the platform will look for a plumber.
     */
    private boolean isOwnSavedDefaultAddress(Long callerId, PostalAddress requested) {
        if (!requested.isGeocodable()) {
            return false;
        }
        User customer = userRepository.findById(callerId).orElse(null);
        if (customer == null) {
            return false;
        }
        PostalAddress defaultAddress = new PostalAddress(customer.getDefaultCity(),
                customer.getDefaultStreet(), customer.getDefaultHouseNumber());
        return defaultAddress.isGeocodable()
                && requested.contentHash().equals(defaultAddress.contentHash());
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
     *
     * <h2>Retry</h2>
     *
     * An SOS request is <b>an attempt to find someone, not the problem itself</b>. The problem is
     * the {@code issues} row, and it keeps its category, description, photos, AI brief and
     * address across as many attempts as it takes. So this method deliberately permits a second,
     * third or fourth SOS request on the same issue once the previous attempt has finished:
     *
     * <pre>
     *   issue 42 -> SOS #1 EXPIRED   (nobody answered)
     *   issue 42 -> SOS #2 FAILED    (nobody eligible at that hour)
     *   issue 42 -> SOS #3 MATCHING  <- allowed; the customer re-describes nothing
     * </pre>
     *
     * What is refused is a <em>concurrent</em> attempt: at most one non-terminal
     * {@code sos_requests} row may exist per issue at a time, or one problem would fan out two
     * competing dispatch waves and two sets of offers to the same professionals. That invariant
     * is enforced by {@code ux_sos_requests_active_issue} in the database, not by this
     * pre-check — see {@code V36__replace_sos_request_issue_uniqueness.sql}.
     *
     * <p>Retry works for all three terminal failures because each leaves the issue bookable:
     * {@code EXPIRED} and {@code CANCELLED} run {@code IssueRepository.revertToOpen}, and
     * {@code FAILED} never booked the issue in the first place.
     */
    @Transactional
    public SosRequestResponse create(Long callerId, CreateSosRequestRequest request) {
        // Production MS1: SOS broadcasts this customer's emergency to a pool of professionals who
        // will drop what they are doing. An unreachable requester is exactly what must not happen.
        contactVerificationGuard.requireVerifiedContactChannels(callerId);

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
        // Fast, friendly pre-check. ux_sos_requests_active_issue (V36) is the authoritative
        // guard against two concurrent activations for the same issue. Note "active", not
        // "any": a previous attempt that expired, failed or was cancelled must not block a
        // retry -- see this method's Javadoc.
        if (sosRequestRepository.existsActiveByIssueId(issue.getId())) {
            throw new ApiException(ErrorCode.SOS_REQUEST_ALREADY_EXISTS,
                    "An SOS request is already in progress for issue " + issue.getId() + ".");
        }

        SosUrgency urgency = request.urgency() == null ? SosUrgency.URGENT : request.urgency();
        SosRequest sosRequest = new SosRequest(issue.getId(), callerId, issue.getCategoryId(), null,
                request.issueSummary(), urgency, request.serviceCity(), request.serviceStreet(),
                request.serviceHouseNumber(), request.serviceApartment(), request.serviceFloor(),
                request.serviceEntrance(), request.serviceAddressNotes(), request.latitude(), request.longitude());

        // Production MS2: the destination every candidate's real driving distance will be measured
        // to. Geocoded once, here, at creation -- never per candidate during matching.
        //
        // A client-supplied fix wins and skips this entirely: the customer's own device knows
        // where they are better than any geocode of the address they typed, and this is the one
        // flow where the customer is standing at the emergency. Only when they have not supplied
        // one is the address resolved.
        //
        // Failure is non-fatal at this point. The request is created either way and the address
        // text is untouched; what a missing destination costs is the ability to match
        // geographically, which SosDispatchService reports honestly as a platform failure rather
        // than as "nobody is available".
        // Address validation (V55), on the same conditional rule bookings uses: a destination the
        // customer typed for this request must have been selected from autocomplete, while their
        // own saved default address is grandfathered because it may predate the feature. See
        // BookingsService#resolveOrderDestination for why the digest comparison is the right test
        // and why it cannot be used to smuggle unvalidated text through.
        PostalAddress requested = new PostalAddress(request.serviceCity(), request.serviceStreet(),
                request.serviceHouseNumber());
        SelectedPlace place = isOwnSavedDefaultAddress(callerId, requested)
                ? selectedPlaceValidator.validateOptional(request.servicePlaceId(),
                        request.serviceFormattedAddress(), request.serviceLatitude(),
                        request.serviceLongitude(), SelectedPlaceValidator.FieldNames.camelCase("service"))
                : selectedPlaceValidator.requireSelected(request.servicePlaceId(),
                        request.serviceFormattedAddress(), request.serviceLatitude(),
                        request.serviceLongitude(), SelectedPlaceValidator.FieldNames.camelCase("service"));
        sosRequest.applySelectedPlace(place);

        if (sosRequest.getLatitude() == null || sosRequest.getLongitude() == null) {
            GeocodeResult geocode = serviceAddressGeocoder.resolve(requested);
            sosRequest.applyGeocode(
                    geocode.isResolved() ? geocode.coordinates().latitude() : null,
                    geocode.isResolved() ? geocode.coordinates().longitude() : null,
                    geocode.status());
        }

        try {
            sosRequest = sosRequestRepository.saveAndFlush(sosRequest);
        } catch (DataIntegrityViolationException e) {
            // ux_sos_requests_active_issue -- a double-tapped SOS button, or two retries racing
            // each other, where both passed the pre-check above before either committed. The
            // partial unique index is what actually decides which one wins.
            throw new ApiException(ErrorCode.SOS_REQUEST_ALREADY_EXISTS,
                    "An SOS request is already in progress for issue " + issue.getId() + ".");
        }

        sosEventService.recordCustomer(sosRequest.getId(), callerId, SosEventType.SOS_CREATED, null,
                SosRequestStatus.CREATED, "SOS activated for category " + issue.getCategoryId());

        Instant now = Instant.now();
        // Timer 1 of 3, fixed here and never moved again: how long the platform keeps looking
        // for new professionals. Timer 2 (each professional's own response window) is stamped
        // per offer at dispatch; timer 3 (the customer's decision window) starts when there is
        // finally something to decide between. See SosProperties for why they are independent.
        Instant scanExpiresAt = now.plus(Duration.ofSeconds(properties.getScanWindowSeconds()));
        SosStateMachine.validate(sosRequest.getId(), SosRequestStatus.CREATED, SosRequestStatus.MATCHING);
        int started = sosRequestRepository.startMatching(sosRequest.getId(), now, scanExpiresAt,
                firstExpansionDueAt(now));
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

        return assembler.toRequestResponse(reload(sosRequest.getId()), SosAddressAccess.FULL);
    }

    /**
     * When this request's search should first widen by itself, or {@code null} when expansion is
     * switched off ({@code max-search-expansions = 0}) — the whole automatic cadence is derived
     * from persisted instants, never from a client-side clock.
     */
    private Instant firstExpansionDueAt(Instant now) {
        return properties.getMaxSearchExpansions() <= 0
                ? null
                : now.plus(Duration.ofSeconds(properties.getExpansionIntervalSeconds()));
    }

    /**
     * When the <em>next</em> expansion after {@code appliedLevel} falls due, or {@code null}
     * once the ceiling has been reached — written by the same atomic statement that records the
     * expansion, so the schedule can never disagree with the counter.
     */
    private Instant nextExpansionDueAt(Instant now, int appliedLevel) {
        return appliedLevel >= properties.getMaxSearchExpansions()
                ? null
                : now.plus(Duration.ofSeconds(properties.getExpansionIntervalSeconds()));
    }

    // ------------------------------------------------------------------
    // Customer + professional: read
    // ------------------------------------------------------------------

    /**
     * {@code GET /api/sos/requests/{id}}. Readable by the owning customer, and by any
     * professional who was sent an offer on it — a professional deciding whether to accept
     * needs to see the job, and one who was selected needs to track it.
     *
     * <p><b>Readable is not the same as fully readable.</b> {@link #authorizeRead} returns how
     * much of the address the caller has earned: the customer and the selected professional get
     * the exact location, everybody else gets the city and nothing more. See
     * {@link SosAddressAccess}.
     */
    @Transactional
    public SosRequestResponse getRequest(Long callerId, String callerRole, Long sosRequestId) {
        SosRequest request = loadRequest(sosRequestId);
        SosAddressAccess access = authorizeRead(callerId, callerRole, request);
        return assembler.toRequestResponse(enforceDeadlines(request), access);
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
        // MS1: explicit three-way branch. The ternary this replaced sent every non-PROFESSIONAL
        // caller down the customer query, which with UserRole.ADMIN in existence would have meant
        // an operator quietly running "my SOS requests" against their own user id. Refuse instead
        // -- an operator is neither party to an SOS request.
        List<SosRequest> requests;
        if (UserRole.PROFESSIONAL.name().equals(callerRole)) {
            requests = sosRequestRepository.findBySelectedProfessionalIdOrderByCreatedAtDesc(
                    resolveProfessionalId(callerId));
        } else if (UserRole.CUSTOMER.name().equals(callerRole)) {
            requests = sosRequestRepository.findByCustomerIdOrderByCreatedAtDesc(callerId);
        } else {
            throw forbidden();
        }
        // FULL for both roles, and safe for both: a customer only ever sees their own rows, and
        // the professional query is filtered on selected_professional_id -- so a professional
        // reaches this list only for jobs they were actually chosen for. An offered-but-not-
        // selected professional's requests are not in it at all.
        return new SosRequestsListResponse(requests.stream()
                .map(request -> assembler.toRequestResponse(request, SosAddressAccess.FULL))
                .toList());
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
     * <p>Deliberately not an error to call this early or late — a polling client needs a
     * successful response with {@code selectionOpen} and {@code status} to reason about, not an
     * exception it has to special-case:
     * <ul>
     *   <li><b>Still gathering responses, nobody yet</b> — empty list,
     *       {@code selectionOpen = false}.</li>
     *   <li><b>At least one professional available</b> — that professional, and
     *       {@code selectionOpen = true}. There is no waiting for a second or a third.</li>
     *   <li><b>Window closed</b> — empty list, {@code selectionOpen = false}, {@code status =
     *       EXPIRED}. Empty because expiry closes every outstanding offer out of
     *       {@code ACCEPTED}, so the query below correctly finds none; the terminal
     *       {@code status} is what tells the client to render "your request expired".</li>
     * </ul>
     *
     * <h2>No cap (MS3)</h2>
     *
     * <b>Every professional who accepted is returned</b>, in arrival order and then sorted by
     * ETA for display. There used to be a shortlist cap that grew by one per manual expansion,
     * with careful arrival-order filling so that a later, faster acceptance could not evict a
     * candidate the customer was already reading. Automatic expansion makes that whole
     * construction the wrong shape: the search now widens by itself up to four times, so a cap
     * would routinely hide people who said yes — and the redesign's rule is the opposite, that
     * an accepted professional stays visible and selectable until the customer decides. The
     * fan-out is bounded by the pool size (40 at the defaults), so "all of them" is a bounded
     * list, not an unbounded one.
     */
    @Transactional
    public SosCandidatesResponse getCandidates(Long callerId, Long sosRequestId) {
        SosRequest request = loadRequest(sosRequestId);
        if (!request.getCustomerId().equals(callerId)) {
            throw forbidden();
        }
        SosRequest current = enforceDeadlines(request);

        List<SosCandidate> candidates = sosOfferRepository
                .findBySosRequestIdAndStatusOrderByIdAsc(sosRequestId, SosOfferStatus.ACCEPTED)
                .stream()
                .sorted(Comparator.comparing(SosOffer::getEstimatedArrivalMinutes,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(assembler::toCandidate)
                .toList();

        // No clock in this answer any more (MS3 follow-up): if the request is awaiting a choice,
        // the choice is open. There is no deadline left to be on the wrong side of.
        boolean selectionOpen = current.getStatus() == SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION;

        return new SosCandidatesResponse(sosRequestId, current.getStatus(), selectionOpen, candidates);
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
            // A request that ended still needs its own answer, distinct from every other
            // conflict. It no longer means "you took too long": since the MS3 follow-up the only
            // way to reach EXPIRED from a choosing state is that every offer lapsed with nothing
            // accepted, which means there was nothing on screen left to tap.
            if (current.getStatus() == SosRequestStatus.EXPIRED) {
                throw new ApiException(ErrorCode.SOS_WINDOW_EXPIRED,
                        "SOS request " + sosRequestId + " has ended and can no longer be chosen from.");
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

        // MS1 (D-B): re-check eligibility at the last moment before an order and a priced
        // commitment exist. SosCandidateRepository.findEligible already filtered at dispatch, but
        // minutes can pass between dispatch and selection -- long enough for an operator to reject
        // someone, or for that professional to clear their working hours. Mapped onto the existing
        // SOS_CANDIDATE_NOT_AVAILABLE (409), which is exactly what happened and which the frontend
        // already handles: this candidate cannot be taken; the others still can.
        //
        // Note where this check is NOT: SosOfferService#accept stays ungated deliberately. The
        // window between dispatch and offer TTL is seconds, and refusing a professional for doing
        // precisely what they were just asked to do explains nothing to them. Selection is the
        // moment that creates an obligation, so selection is where the rule belongs.
        if (!professionalRepository.existsEligibleById(professional.getId())) {
            throw new ApiException(ErrorCode.SOS_CANDIDATE_NOT_AVAILABLE,
                    "Offer " + offerId + " is no longer available.");
        }

        Instant now = Instant.now();

        // The order carries the agreed economics, and exists so an SOS job lands in exactly the
        // same orders/reviews/history machinery every other booking uses rather than in a
        // parallel one. Created PENDING: the professional's confirmation is what accepts it,
        // mirroring the standard order flow's PENDING -> CONFIRMED accept step.
        // bookedStart = now (SOS has no scheduled time), bookedEnd/slotId = null: an SOS job
        // never consumes an availability window. This is now the only writer of an order with
        // that shape -- BookingsService.createSosOrder wrote the same one until the
        // browse-and-pick flow was removed.
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
                    "SOS request " + sosRequestId + " is no longer awaiting a choice (status "
                            + after.getStatus() + ").");
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
        notifyLosingCandidates(sosRequestId, offerId);

        log.info("sos.selected sosRequestId={} offerId={} professionalId={} orderId={}",
                sosRequestId, offerId, professional.getId(), order.getId());
        return assembler.toRequestResponse(reload(sosRequestId), SosAddressAccess.FULL);
    }

    // ------------------------------------------------------------------
    // Customer: widen the search ("סרוק שוב")
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/sos/requests/{id}/scan-again} — <b>the customer asks the platform to look
     * further, on the same request.</b>
     *
     * <p>This is a real domain operation, not a refetch and not an animation. It widens the
     * request's search scope, dispatches offers to professionals who were <em>not</em> contacted
     * before, extends the deadline the search is running against, and writes a
     * {@code SEARCH_EXPANDED} history row. Nothing about the attempt is reset: the same
     * {@code sos_requests} row, the same issue, the same offers, and — critically — the same
     * candidates. A professional who has already said they are available stays visible and stays
     * selectable throughout.
     *
     * <h2>What it is bounded by</h2>
     *
     * {@code pronto.sos.max-search-expansions}, enforced inside the guarded update rather than by
     * a check here, so it holds under concurrency. There is no automatic expansion anywhere in
     * this feature: the search widens when the customer asks, at most that many times, and then
     * stops offering. {@link SosSearchScope} documents what "wider" actually means.
     *
     * <h2>The four races this has to survive, and where each is handled</h2>
     *
     * <ul>
     *   <li><b>A professional accepts while the expansion is in flight.</b> Nothing collides:
     *       acceptance touches the offer, expansion touches the request's expansion counter and
     *       writes new offer rows for different professionals. The accepting professional stays
     *       a candidate, and cannot be dispatched a second offer
     *       ({@code ux_sos_offers_request_professional} plus the exclusion set).</li>
     *   <li><b>The customer selects while the expansion is in flight.</b>
     *       {@code selectedProfessionalId IS NULL} and the status set are both inside the guarded
     *       update, so a late expansion affects nothing and dispatches nothing.
     *       <b>Selection always wins.</b></li>
     *   <li><b>The customer double-taps.</b> The update is a compare-and-set on the expansion
     *       count: both calls read {@code n}, exactly one writes {@code n+1}. The loser returns
     *       the current state rather than an error — it asked for something that had just
     *       happened, which is not a failure to report to somebody in a hurry.</li>
     *   <li><b>The request expires between the read and the write.</b> {@link #enforceDeadlines}
     *       runs first and the status set in the update is checked by the database, so an expired
     *       request cannot be expanded.</li>
     * </ul>
     *
     * @throws ApiException {@code 403} if not this customer's request, {@code 409
     *         SOS_ALREADY_SELECTED} once a professional has been chosen, {@code 409
     *         SOS_EXPANSION_LIMIT_REACHED} at the configured maximum, {@code 410
     *         SOS_WINDOW_EXPIRED} / {@code 409 SOS_INVALID_STATE} when the request is no longer
     *         searching
     */
    @Transactional
    public SosRequestResponse expandSearch(Long callerId, Long sosRequestId) {
        SosRequest request = loadRequest(sosRequestId);
        if (!request.getCustomerId().equals(callerId)) {
            throw forbidden();
        }
        SosRequest current = enforceDeadlines(request);
        requireExpandable(current);

        short expected = (short) current.getSearchExpansions();
        Instant now = Instant.now();
        int affected = sosRequestRepository.expandSearch(sosRequestId, expected, (short) (expected + 1),
                (short) properties.getMaxSearchExpansions(),
                nextExpansionDueAt(now, expected + 1),
                now);
        if (affected == 0) {
            // Lost a race. Re-read and report the accurate reason -- except for the double-tap
            // case, where the thing the customer asked for has just happened and the honest
            // answer is the current state, not an error.
            SosRequest after = reload(sosRequestId);
            if (after.getSelectedProfessionalId() != null) {
                throw new ApiException(ErrorCode.SOS_ALREADY_SELECTED,
                        "A professional has already been selected for SOS request " + sosRequestId + ".");
            }
            if (after.getSearchExpansions() >= properties.getMaxSearchExpansions()) {
                throw new ApiException(ErrorCode.SOS_EXPANSION_LIMIT_REACHED,
                        "SOS request " + sosRequestId + " has already been expanded the maximum "
                                + properties.getMaxSearchExpansions() + " time(s).");
            }
            if (!after.getStatus().isAcceptingProfessionalResponses()) {
                throw new ApiException(ErrorCode.SOS_INVALID_STATE,
                        "SOS request " + sosRequestId + " is no longer searching (status "
                                + after.getStatus() + ").");
            }
            log.info("sos.search-expanded.concurrent-duplicate sosRequestId={} expansions={}",
                    sosRequestId, after.getSearchExpansions());
            return assembler.toRequestResponse(after, SosAddressAccess.FULL);
        }

        SosRequest expanded = dispatchExpansionWave(sosRequestId, callerId);
        return assembler.toRequestResponse(expanded, SosAddressAccess.FULL);
    }

    /**
     * <b>The automatic widening the sweep drives</b> — the customer presses nothing, and there is
     * no "סרוק שוב" button anywhere in the product any more.
     *
     * <p>Identical machinery to {@link #expandSearch}: the same compare-and-set, the same bound,
     * the same dispatch wave that never re-contacts anybody. Two differences, both deliberate:
     * the history row is recorded with a {@code SYSTEM} actor because nobody asked for it, and
     * losing any of the guards is silent — a background job has no HTTP caller to report a
     * {@code 409} to, and every reason it can lose (selected, expired, cancelled, ceiling
     * reached, scan window closed) is an ordinary outcome rather than a failure.
     *
     * @return {@code true} if this call is the one that widened the search
     */
    @Transactional
    public boolean expandSearchAutomatically(Long sosRequestId) {
        SosRequest request = sosRequestRepository.findById(sosRequestId).orElse(null);
        if (request == null) {
            return false;
        }
        short expected = (short) request.getSearchExpansions();
        Instant now = Instant.now();
        int affected = sosRequestRepository.expandSearch(sosRequestId, expected, (short) (expected + 1),
                (short) properties.getMaxSearchExpansions(),
                nextExpansionDueAt(now, expected + 1),
                now);
        if (affected == 0) {
            // The scan window closed between the query and this write, or somebody was selected,
            // or another sweep pass won. Park the schedule so this row stops being re-read every
            // sweep; harmless if it was already null.
            sosRequestRepository.clearExpansionSchedule(sosRequestId, now);
            return false;
        }
        dispatchExpansionWave(sosRequestId, null);
        return true;
    }

    /**
     * The half both expansion paths share: rank at the new, wider scope, dispatch to
     * professionals nobody has contacted yet, and write the history row.
     *
     * @param actorCustomerId the customer who asked, or {@code null} when the platform widened
     *                        the search by itself (recorded as a {@code SYSTEM} event)
     */
    private SosRequest dispatchExpansionWave(Long sosRequestId, Long actorCustomerId) {
        SosRequest expanded = reload(sosRequestId);
        SosSearchScope scope = SosSearchScope.forLevel(expanded.getSearchExpansions(),
                expanded.getUrgency(), properties);
        int dispatched = sosDispatchService.expand(expanded, scope);

        String detail = "Search widened to scope level " + scope.level() + "; " + dispatched
                + " additional professional(s) contacted";
        if (actorCustomerId == null) {
            sosEventService.recordSystem(sosRequestId, SosEventType.SEARCH_EXPANDED,
                    expanded.getStatus(), expanded.getStatus(), detail);
        } else {
            sosEventService.recordCustomer(sosRequestId, actorCustomerId, SosEventType.SEARCH_EXPANDED,
                    expanded.getStatus(), expanded.getStatus(), detail);
        }

        log.info("sos.search-expanded sosRequestId={} level={} poolSize={} newOffers={} status={} automatic={}",
                sosRequestId, scope.level(), scope.poolSize(), dispatched, expanded.getStatus(),
                actorCustomerId == null);
        return reload(sosRequestId);
    }

    /** Sweep input: requests whose automatic search expansion is due. */
    @Transactional(readOnly = true)
    public List<Long> findExpansionDueIds() {
        return sosRequestRepository.findExpansionDueIds(Instant.now());
    }

    /** The friendly pre-check. The guarded update is what actually decides — see {@link #expandSearch}. */
    private void requireExpandable(SosRequest current) {
        if (current.getSelectedProfessionalId() != null) {
            throw new ApiException(ErrorCode.SOS_ALREADY_SELECTED,
                    "A professional has already been selected for SOS request " + current.getId() + ".");
        }
        if (!current.getStatus().isAcceptingProfessionalResponses()) {
            if (current.getStatus() == SosRequestStatus.EXPIRED) {
                throw new ApiException(ErrorCode.SOS_WINDOW_EXPIRED,
                        "SOS request " + current.getId() + " has expired and can no longer be expanded.");
            }
            throw new ApiException(ErrorCode.SOS_INVALID_STATE,
                    "SOS request " + current.getId() + " is not searching (status " + current.getStatus() + ").");
        }
        if (current.getSearchExpansions() >= properties.getMaxSearchExpansions()) {
            throw new ApiException(ErrorCode.SOS_EXPANSION_LIMIT_REACHED,
                    "SOS request " + current.getId() + " has already been expanded the maximum "
                            + properties.getMaxSearchExpansions() + " time(s).");
        }
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
        return assembler.toRequestResponse(reload(sosRequestId), SosAddressAccess.FULL);
    }

    // ------------------------------------------------------------------
    // Dispatch progression — called by SosOfferService when a professional responds
    // ------------------------------------------------------------------

    /**
     * Opens the customer's selection window as soon as there is anything to choose between.
     * Called after every professional acceptance, and by the sweep when the response window
     * closes.
     *
     * <h2>One acceptance is enough — and that is the change this method exists to record</h2>
     *
     * This used to hold the window shut until {@code target-candidate-count} professionals had
     * accepted (or the response window closed), so a customer whose first professional answered
     * in eight seconds could see them, read their profile, and not be allowed to take them for
     * another two minutes while the platform waited for a second and a third. For somebody with
     * water coming through a ceiling that is the wrong trade in every direction: the option is
     * real, it is on their screen, and the wait buys them nothing they asked for.
     *
     * <p>So the gate is now simply "is there at least one". Everything the old threshold was
     * protecting is still protected, by mechanisms that were always the real ones:
     * <ul>
     *   <li><b>More options still arrive.</b> Opening the window does not stop the search —
     *       {@code WAITING_FOR_CUSTOMER_SELECTION} still accepts professional responses (see
     *       {@code SosRequestStatus#isAcceptingProfessionalResponses}), so candidates two and
     *       three appear alongside the first if they answer.</li>
     *   <li><b>The customer can ask for more.</b> "סרוק שוב" widens the search on this same
     *       request and extends the window it runs in — see {@link #expandSearch}.</li>
     *   <li><b>Nobody is rushed.</b> The window is a deadline, not an instruction; it is the same
     *       {@code selection-window-seconds} it always was.</li>
     * </ul>
     *
     * <p>{@code CANDIDATES_READY}/{@code CUSTOMER_SELECTION_STARTED} are singleton events, so
     * they are written once, by whichever call actually wins the guarded update.
     *
     * @param force retained to distinguish the caller for the history row: {@code true} means the
     *              response window closed and this is the sweep's last chance to salvage the
     *              request, {@code false} means a professional just answered. Both open the
     *              window on one acceptance now; the parameter no longer changes the threshold.
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

        Instant now = Instant.now();
        SosStateMachine.validate(sosRequestId, SosRequestStatus.WAITING_FOR_PROFESSIONALS,
                SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        int opened = sosRequestRepository.openSelectionWindow(sosRequestId, now);
        if (opened == 0) {
            // Somebody else opened it, or the request was cancelled. Either way not our problem.
            return false;
        }

        sosEventService.recordSystem(sosRequestId, SosEventType.CANDIDATES_READY,
                SosRequestStatus.WAITING_FOR_PROFESSIONALS, SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION,
                accepted + " professional(s) available");
        sosEventService.recordSystem(sosRequestId, SosEventType.CUSTOMER_SELECTION_STARTED, null,
                SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION,
                "Customer may now choose; the choice stays open until they do");
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
     * <p><b>Since the MS3 follow-up this decides one question, not two: can anything still
     * happen?</b> There is no customer-decision deadline any more, so a request is never ended
     * because somebody was slow to choose — only because the platform has stopped looking, nobody
     * accepted, and no outstanding offer can still be answered. The three conditions are
     * evaluated in that order below.
     *
     * <p>This is what keeps the backend the source of truth: the answer is recomputed from
     * persisted state on every read, so a refresh, a second device and a client that has been
     * asleep for an hour all see the same thing — regardless of whether the sweep job has run, or
     * is even enabled. The sweep exists to also terminate requests <em>nobody</em> is reading; it
     * is a completeness mechanism, not the enforcement mechanism.
     *
     * <p>Cheap in the common case: a status check and, only once the scan window has closed, at
     * most two indexed counts.
     */
    private SosRequest enforceDeadlines(SosRequest request) {
        SosRequestStatus status = request.getStatus();
        if (status != SosRequestStatus.WAITING_FOR_PROFESSIONALS
                && status != SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION) {
            return request;
        }

        Instant now = Instant.now();

        // 1. Somebody accepted while the request was still gathering responses: the customer can
        //    choose. Cheap to attempt on every read, and idempotent -- the guarded update inside
        //    decides, and losing means somebody else already opened it.
        if (status == SosRequestStatus.WAITING_FOR_PROFESSIONALS
                && maybeOpenSelectionWindow(request.getId(), true)) {
            return reload(request.getId());
        }

        // 2. While the scan window is open the request always has a future: a further expansion
        //    may still contact somebody nobody has asked yet.
        if (request.getMatchingExpiresAt() == null || request.getMatchingExpiresAt().isAfter(now)) {
            return request;
        }

        // 3. The scan has closed. That stops dispatch and nothing else -- so before ending
        //    anything, ask whether anything can still happen:
        //      * an ACCEPTED offer is a professional who said they would come. The customer may
        //        take it whenever they get back to their phone, and no clock removes it. This is
        //        the case the old customer-decision deadline used to destroy.
        //      * an OFFERED/VIEWED offer inside its own response window may yet become one.
        if (sosOfferRepository.countBySosRequestIdAndStatus(request.getId(), SosOfferStatus.ACCEPTED) > 0
                || sosOfferRepository.existsAnswerableOffer(request.getId(), now)) {
            // Stop the sweep re-reading this row for an expansion it can no longer perform.
            sosRequestRepository.clearExpansionSchedule(request.getId(), now);
            return request;
        }

        // 4. Nothing accepted, nothing answerable, nobody new to ask. Now there is genuinely
        //    nothing left that could happen, which is the only reason this flow ends by itself.
        expire(request.getId(), status,
                "No professional accepted, and no offer can still be answered.");
        return reload(request.getId());
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

    /** Sweep input: requests that can no longer produce anything — see {@link #enforceDeadlines}. */
    @Transactional(readOnly = true)
    public List<Long> findExpiryCandidateIds() {
        return sosRequestRepository.findExpiryCandidateIds(Instant.now());
    }

    /**
     * Sweep input: requests whose selected professional never confirmed within
     * {@code pronto.sos.confirmation-grace-seconds}.
     */
    @Transactional(readOnly = true)
    public List<Long> findUnconfirmedSelectionIds() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(properties.getConfirmationGraceSeconds()));
        return sosRequestRepository.findUnconfirmedSelectionIds(cutoff);
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

    /** Sweep input: offers past their own {@code expiresAt} that nobody has answered. */
    @Transactional(readOnly = true)
    public List<Long> findOverdueOfferIds() {
        return sosOfferRepository.findOverdueOpenOfferIds(Instant.now());
    }

    /**
     * Sweep action for one lapsed offer: close it, and <b>tell the professional it was sent to</b>.
     *
     * <p>This used to be a single bulk {@code UPDATE} across every overdue offer, which closed
     * them correctly and silently. Silently was the problem — the professional's inbox kept
     * rendering a card that could no longer be accepted until they tapped it and got a
     * {@code 410}. Now each offer gets the same treatment every other SOS transition gets: a
     * guarded status change, an {@code sos_events} row, and (via the realtime layer, after
     * commit) a push to exactly one recipient.
     *
     * <p><b>Idempotent by construction.</b> Everything below is gated on
     * {@code expireOfferIfOpen} returning 1 row. Two overlapping sweeps, or a sweep racing a
     * professional's {@code accept}, produce exactly one winner — the loser returns here having
     * written nothing at all, so there is no second event and no duplicate notification.
     *
     * <p><b>Deliberately nothing for the customer.</b> "Professional X did not respond" is not
     * information a customer with a burst pipe can act on, and it reframes a normal, expected
     * outcome as a failure. Their view of dispatch stays aggregate — how many are available, and
     * when they can choose. {@code SosRealtimePublisher} enforces that; see its
     * {@code OFFER_EXPIRED} branch.
     *
     * @return {@code true} if this call is the one that expired the offer
     */
    @Transactional
    public boolean expireOffer(Long offerId) {
        Instant now = Instant.now();
        if (sosOfferRepository.expireOfferIfOpen(offerId, now) == 0) {
            return false;
        }
        SosOffer offer = sosOfferRepository.findById(offerId).orElse(null);
        if (offer == null) {
            return false;
        }

        SosRequest request = sosRequestRepository.findById(offer.getSosRequestId()).orElse(null);
        SosRequestStatus requestStatus = request == null ? null : request.getStatus();
        // actorType SYSTEM, not PROFESSIONAL: nobody did anything -- a clock ran out. The
        // professional and offer ids still ride along, because they are what makes this row
        // addressable to one recipient rather than to the whole request.
        sosEventService.record(offer.getSosRequestId(), SosEventType.OFFER_EXPIRED, SosActorType.SYSTEM, null,
                offer.getProfessionalId(), offerId, requestStatus, null,
                "Offer expired without a response");

        professionalRepository.findById(offer.getProfessionalId()).ifPresent(professional ->
                notificationService.recordSosNotification(offer.getSosRequestId(), professional.getUserId(),
                        NotificationMessageType.SOS_OFFER_EXPIRED));

        log.info("sos.offer.expired sosRequestId={} offerId={} professionalId={}",
                offer.getSosRequestId(), offerId, offer.getProfessionalId());
        return true;
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
     *
     * @return how much of the service address this caller may see. The customer always gets
     *         {@link SosAddressAccess#FULL}; a professional gets it only once
     *         {@code selected_professional_id} is them. An offered professional — including one
     *         who has already responded {@code ACCEPTED} — gets
     *         {@link SosAddressAccess#STREET_AND_CITY}: street and city are enough to estimate the
     *         journey, and the house number stays withheld because being available is not being
     *         chosen.
     * @throws com.pronto.common.exception.ApiException {@code 403} if the caller is neither the
     *         customer nor a professional holding an offer on this request
     */
    private SosAddressAccess authorizeRead(Long callerId, String callerRole, SosRequest request) {
        if (request.getCustomerId().equals(callerId)) {
            return SosAddressAccess.FULL;
        }
        if (UserRole.PROFESSIONAL.name().equals(callerRole)) {
            Optional<Professional> professional = professionalRepository.findByUserId(callerId);
            if (professional.isPresent() && sosOfferRepository
                    .findBySosRequestIdAndProfessionalId(request.getId(), professional.get().getId()).isPresent()) {
                // Read off the request rather than off the offer's status: selection is what
                // grants the address, and sos_requests.selected_professional_id is the single
                // field that records it.
                return professional.get().getId().equals(request.getSelectedProfessionalId())
                        ? SosAddressAccess.FULL
                        : SosAddressAccess.STREET_AND_CITY;
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

    /**
     * Tells the professionals who said "I'm available" and were not chosen.
     *
     * <p>{@code SOS_NOT_SELECTED} existed in {@code NotificationMessageType} and in the realtime
     * vocabulary, but nothing ever wrote the notification row — so a professional who held a slot
     * open for a stranger learned the outcome only if they happened to have the socket connected
     * at that moment, and otherwise never at all. Their inbox simply kept a card that had quietly
     * stopped being real.
     *
     * <p>Scoped to {@code NOT_SELECTED}, read back after {@code closeLosingOffers} has run.
     * That status is reachable only from {@code ACCEPTED}, so this is exactly the set that
     * positively responded and lost. Professionals who never answered are {@code EXPIRED} and are
     * deliberately skipped: being passed over is only meaningful to someone who was in the
     * running, and telling the rest would be inventing a rejection they never risked.
     *
     * <p>The winner's own offer is {@code SELECTED} by now and so cannot match, but
     * {@code selectedOfferId} is excluded explicitly anyway — a notification telling the chosen
     * professional they were passed over is the one mistake here that would actually cost a job.
     */
    private void notifyLosingCandidates(Long sosRequestId, Long selectedOfferId) {
        for (SosOffer offer : sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(sosRequestId)) {
            if (offer.getStatus() != SosOfferStatus.NOT_SELECTED || offer.getId().equals(selectedOfferId)) {
                continue;
            }
            professionalRepository.findById(offer.getProfessionalId())
                    .ifPresent(p -> notificationService.recordSosNotification(sosRequestId, p.getUserId(),
                            NotificationMessageType.SOS_NOT_SELECTED));
        }
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
