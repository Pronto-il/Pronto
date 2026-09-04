package com.pronto.bookings.service;

import com.pronto.availability.dto.CalendarSegment;
import com.pronto.availability.dto.SegmentType;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.service.AvailabilityDerivationService;
import com.pronto.bookings.config.BookingProperties;
import com.pronto.bookings.dto.ArrivalRequest;
import com.pronto.bookings.dto.AvailableWindow;
import com.pronto.bookings.dto.AvailableWindowsResponse;
import com.pronto.bookings.dto.CreateOrderRequest;
import com.pronto.bookings.dto.OrderDetailResponse;
import com.pronto.bookings.dto.OrderResponse;
import com.pronto.bookings.dto.OrderSummaryResponse;
import com.pronto.bookings.dto.OrdersListResponse;
import com.pronto.bookings.dto.ProfessionalCard;
import com.pronto.bookings.dto.ProfessionalListingResponse;
import com.pronto.bookings.dto.ProfessionalSort;
import com.pronto.bookings.entity.CancelledBy;
import com.pronto.bookings.entity.Order;
import com.pronto.bookings.entity.OrderStatus;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.bookings.repository.ProfessionalListingRepository;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueStatus;
import com.pronto.issues.entity.IssueUrgencyType;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.GeocodeResult;
import com.pronto.maps.PostalAddress;
import com.pronto.maps.SelectedPlace;
import com.pronto.maps.service.SelectedPlaceValidator;
import com.pronto.maps.RouteUnavailableReason;
import com.pronto.maps.config.LocationProperties;
import com.pronto.maps.service.ArrivalVerifier;
import com.pronto.maps.service.ServiceAddressGeocoder;
import com.pronto.matching.DistanceEtaStrategy;
import com.pronto.matching.EtaResult;
import com.pronto.locations.service.ServiceCityResolver;
import com.pronto.matching.ServiceLocation;
import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.service.NotificationService;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.professionals.service.ProfessionalLocationService;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.service.ContactVerificationGuard;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code /api/bookings/*} — Standard and SOS booking flows (professional listing, slot
 * listing, create/accept/reject/cancel, tracking, self-listing). See
 * {@code docs/architecture/api-contract-bookings.md} §2.2-2.9 (Standard, Milestone 3),
 * §2.12-2.13 (SOS, Milestone 4), and §2.16-2.17 (job-status progression, Milestone 6).
 * Route-level role checks ({@code CUSTOMER}-only /
 * {@code PROFESSIONAL}-only routes) happen in {@code bookings.config.BookingsWebConfig}; the
 * either-role routes (cancel, get-by-id, get-me) have no route-level gate and instead
 * authorize entirely here, once the resource is loaded (§0.1).
 */
@Service
public class BookingsService {

    private static final Logger log = LoggerFactory.getLogger(BookingsService.class);

    /** §4.5 of {@code api-contract-notifications.md} — hardcoded, no migration. */
    private static final Duration STANDARD_PENDING_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration SOS_PENDING_TIMEOUT = Duration.ofMinutes(5);

    /**
     * §9.2.1 of the professional weekly availability calendar design — the fixed default job
     * duration used to derive a Standard order's {@code bookedEnd} server-side from the
     * customer-chosen {@code bookedStart}.
     *
     * <p><b>Explicitly flagged as a placeholder business figure, not a sourced product
     * requirement</b> — an analogous made-up-for-MVP figure. 60 minutes is a genuine product decision made in the
     * calendar design document itself (design §9.2.1), with no PRD/poster/OnePage backing —
     * every architecture doc was grepped for "hour"/"minute"/"duration" during that design
     * pass and turned up no pre-existing job-duration convention anywhere in this codebase.
     * Chosen because: (1) it is a plausible "typical single visit" length across the fixed
     * 8-category service list — long enough not to be almost-always-too-short for a real
     * diagnosis-plus-repair visit, short enough not to needlessly over-block a professional's
     * calendar; (2) it is a clean multiple of this feature's own 30-minute grid convention
     * (design §5/§7.3), so generated start-time candidates land on the same grid the
     * professional's own calendar already uses; (3) it is a single, named,
     * trivially-changeable constant, not a schema value — changing it later needs no
     * migration. A category-specific or professional-configurable duration was considered and
     * explicitly not chosen — more accurate, but unsupported by any part of this task's scope.
     * See design §9.2.1 for the full rationale record.
     */
    static final int DEFAULT_JOB_DURATION_MINUTES = 60;

    /**
     * §9.2.2 of the design: {@code GET .../available-windows?issueId=} exposes no
     * {@code from}/{@code to} query params (simpler for the customer booking flow) — the
     * server applies this fixed internal lookahead instead. A generous but bounded near-term
     * booking horizon, cheap to derive, trivially adjustable later (an application constant,
     * not a schema value) — lower-stakes than {@link #DEFAULT_JOB_DURATION_MINUTES}, since it
     * only affects "how far ahead can a customer see open times," not the correctness of any
     * booking itself.
     */
    private static final long AVAILABLE_WINDOWS_LOOKAHEAD_DAYS = 14;

    /** Postgres SQLState for an exclusion-constraint violation (design §6/§9.2.2 step 5). */
    private static final String EXCLUSION_VIOLATION_SQLSTATE = "23P01";

    private final IssueRepository issueRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalListingRepository professionalListingRepository;
    /** Customer address text -> canonical service_cities id. See listProfessionals. */
    private final ServiceCityResolver serviceCityResolver;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final DistanceEtaStrategy distanceEtaStrategy;
    private final StorageService storageService;
    private final AvailabilityDerivationService availabilityDerivationService;
    private final ProfessionalCoverageService professionalCoverageService;
    private final ContactVerificationGuard contactVerificationGuard;
    private final ServiceAddressGeocoder serviceAddressGeocoder;
    private final ProfessionalLocationService professionalLocationService;
    private final LocationProperties locationProperties;
    private final ArrivalVerifier arrivalVerifier;
    private final SelectedPlaceValidator selectedPlaceValidator;
    private final CategoryRepository categoryRepository;
    /** The Standard-flow lead-time rule. SOS never reads this. See {@link BookingProperties}. */
    private final BookingProperties bookingProperties;

    public BookingsService(IssueRepository issueRepository,
                            ProfessionalRepository professionalRepository,
                            ProfessionalListingRepository professionalListingRepository,
                            ServiceCityResolver serviceCityResolver,
                            AvailabilitySlotRepository availabilitySlotRepository,
                            OrderRepository orderRepository,
                            UserRepository userRepository,
                            NotificationService notificationService,
                            DistanceEtaStrategy distanceEtaStrategy,
                            StorageService storageService,
                            AvailabilityDerivationService availabilityDerivationService,
                            ProfessionalCoverageService professionalCoverageService,
                            ContactVerificationGuard contactVerificationGuard,
                            ServiceAddressGeocoder serviceAddressGeocoder,
                            ProfessionalLocationService professionalLocationService,
                            LocationProperties locationProperties,
                            ArrivalVerifier arrivalVerifier,
                            SelectedPlaceValidator selectedPlaceValidator,
                            CategoryRepository categoryRepository,
                            BookingProperties bookingProperties) {
        this.bookingProperties = bookingProperties;
        this.serviceAddressGeocoder = serviceAddressGeocoder;
        this.selectedPlaceValidator = selectedPlaceValidator;
        this.categoryRepository = categoryRepository;
        this.professionalLocationService = professionalLocationService;
        this.locationProperties = locationProperties;
        this.arrivalVerifier = arrivalVerifier;
        this.issueRepository = issueRepository;
        this.professionalRepository = professionalRepository;
        this.professionalListingRepository = professionalListingRepository;
        this.serviceCityResolver = serviceCityResolver;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.distanceEtaStrategy = distanceEtaStrategy;
        this.storageService = storageService;
        this.availabilityDerivationService = availabilityDerivationService;
        this.professionalCoverageService = professionalCoverageService;
        this.contactVerificationGuard = contactVerificationGuard;
    }

    /** §2.2, extended with the service-location/sort matching design. */
    @Transactional(readOnly = true)
    public ProfessionalListingResponse listProfessionals(Long callerId, Long issueId, Long categoryId,
                                                           ServiceLocation location, String sortParam) {
        // Two ways to name what the customer needs, and they are not two flows.
        //
        //   issueId    an issue already exists (the customer was signed in when they described it,
        //              or they are revisiting one). Ownership and bookability are checked exactly
        //              as before -- an issue is somebody's, so reading one requires being them.
        //
        //   categoryId no issue exists yet. This is the guest journey, and it is also every
        //              signed-in customer's journey now that the issue row is written at the
        //              booking commit rather than before matching. A category is not owned by
        //              anybody, so there is nothing to authorize: the listing it produces is the
        //              same public marketplace page either way.
        //
        // The issue path takes precedence when both are supplied, because an issue carries a
        // category of its own and letting the query string disagree with it would make the
        // authorization check meaningless.
        Long resolvedCategoryId;
        Long resolvedIssueId = null;

        if (issueId != null) {
            // "Nobody is signed in" and "somebody else is signed in" are different answers and get
            // different statuses -- see unauthenticatedIssueAccess. Checked BEFORE the issue is
            // loaded, so an anonymous caller probing ids cannot tell an existing issue from a
            // missing one by the status they get back.
            if (callerId == null) {
                throw unauthenticatedIssueAccess();
            }
            Issue issue = loadIssue(issueId);
            if (!issue.getCustomerId().equals(callerId)) {
                throw forbidden();
            }
            if (issue.getUrgencyType() != IssueUrgencyType.STANDARD) {
                throw urgencyMismatch(issue.getId());
            }
            if (issue.getStatus() != IssueStatus.OPEN) {
                throw notBookable(issue.getId());
            }
            resolvedIssueId = issue.getId();
            resolvedCategoryId = issue.getCategoryId();
        } else {
            resolvedCategoryId = requireListingCategory(categoryId);
        }

        // THE GEOGRAPHIC FILTER. Resolved to a canonical service_cities id before the query, so
        // eligibility is decided by the professional's own declared coverage
        // (professional_service_cities) rather than by distance, by base city, or -- as it was
        // until this line existed -- by nothing at all.
        //
        // An unresolvable city short-circuits to an empty listing rather than binding null into
        // the query. Same observable result, but it is a decision with a log line instead of an
        // empty result set produced by SQL null semantics, and it keeps "we do not cover this
        // place" distinguishable in the logs from "nobody in this covered city does this trade".
        Optional<Long> serviceCityId = serviceCityResolver.resolveId(location == null ? null : location.city());
        if (serviceCityId.isEmpty()) {
            log.info("bookings.listing.uncovered categoryId={} city=\"{}\" reason=city-not-in-catalogue",
                    resolvedCategoryId, location == null ? null : location.city());
            return new ProfessionalListingResponse(resolvedIssueId, resolvedCategoryId, List.of());
        }

        List<ProfessionalCard> professionals = professionalListingRepository
                .listByCategoryAndServiceCity(resolvedCategoryId, callerId, serviceCityId.get());
        // Sorting runs on the already-filtered candidate set, so RECOMMENDED/CHEAPEST/FASTEST all
        // rank the same location-eligible professionals. Ordering can only ever reorder what the
        // query returned -- there is no path by which a sort mode reintroduces someone the
        // coverage filter excluded.
        professionals = enrichAndSort(callerId, professionals, location, parseSort(sortParam, ProfessionalSort.CHEAPEST));
        return new ProfessionalListingResponse(resolvedIssueId, resolvedCategoryId, professionals);
    }

    /**
     * The category a guest listing is keyed on, validated against the real catalogue.
     *
     * <p>Checked rather than trusted so an unknown id produces a clean {@code VALIDATION_ERROR}
     * instead of an empty listing that looks like "no professionals serve your area" — the two are
     * completely different answers and a customer cannot tell them apart.
     */
    private Long requireListingCategory(Long categoryId) {
        if (categoryId == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request failed validation.",
                    List.of(new FieldError("categoryId", "is required when no issueId is supplied")));
        }
        if (!categoryRepository.existsById(categoryId)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request failed validation.",
                    List.of(new FieldError("categoryId", "must reference an existing category")));
        }
        return categoryId;
    }

    /**
     * §9.2.2 of the professional weekly availability calendar design — replaces the retired
     * {@code listSlots}/{@code GET .../slots?issueId=} entirely. Auth/role/validation steps
     * 1-5 are identical to the old §2.3 (caller role — enforced at the route level by
     * {@code BookingsWebConfig}, not here — issue ownership, urgency-type match, bookable-
     * status check, professional existence, category match); only the final query (old step
     * 6) changes: a derived-availability lookup instead of a raw {@code availability_slots}
     * read.
     */
    @Transactional(readOnly = true)
    public AvailableWindowsResponse listAvailableWindows(Long callerId, Long professionalId, Long issueId) {
        // issueId is optional as of deferred authentication. When it is present the checks below
        // are exactly what they were; when it is absent this answers the only question a guest is
        // actually asking -- "when is this professional free?" -- which is derived entirely from
        // the professional's own published working hours and existing bookings.
        //
        // What is deliberately NOT skipped for a guest: the professional must still exist and be
        // bookable. An ineligible professional's schedule is not public information, and answering
        // for one would leak that they exist at all (see professionalNotBookable's 404 rule).
        //
        // The category-serves check is the one thing that genuinely needs an issue, because there
        // is no category to compare against without one. That is not a hole: the same check runs
        // again at order creation, where the category is known for certain, so a guest who picks a
        // professional who does not serve their trade is refused at the commit rather than at the
        // browse.
        Issue issue = null;
        if (issueId != null) {
            // Same split, same reason, as listProfessionals above: an expired token reaches this
            // permitAll route indistinguishably from a guest, and answering 403 stranded the
            // customer instead of ending their dead session.
            if (callerId == null) {
                throw unauthenticatedIssueAccess();
            }
            issue = loadIssue(issueId);
            if (!issue.getCustomerId().equals(callerId)) {
                throw forbidden();
            }
            if (issue.getUrgencyType() != IssueUrgencyType.STANDARD) {
                throw urgencyMismatch(issue.getId());
            }
            if (issue.getStatus() != IssueStatus.OPEN) {
                throw notBookable(issue.getId());
            }
        }
        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> professionalNotBookable(professionalId));
        // MS1: an ineligible (or soft-deleted) professional is refused with the SAME 404 and the
        // SAME message a nonexistent id produces -- see professionalNotBookable. This check runs
        // before the category comparison deliberately: which category an unverified professional
        // claims is not information this endpoint should be confirming.
        if (!isProfessionalBookable(professional)) {
            throw professionalNotBookable(professionalId);
        }
        if (issue != null
                && !professionalCoverageService.servesCategory(professional.getId(), issue.getCategoryId())) {
            throw categoryMismatch();
        }

        Instant from = Instant.now();
        Instant to = from.plus(Duration.ofDays(AVAILABLE_WINDOWS_LOOKAHEAD_DAYS));
        List<AvailableWindow> windows = availabilityDerivationService
                .deriveAvailableWindows(professionalId, from, to, Duration.ofMinutes(DEFAULT_JOB_DURATION_MINUTES))
                .stream()
                .map(segment -> new AvailableWindow(segment.startAt(), segment.endAt()))
                .toList();
        // The windows are the professional's real availability and are NOT clipped by the lead
        // time -- see AvailableWindowsResponse's Javadoc for why that distinction is the whole
        // point of shipping the boundary as a separate field instead.
        return new AvailableWindowsResponse(professionalId, issueId, DEFAULT_JOB_DURATION_MINUTES,
                AvailabilityDerivationService.BUSINESS_TIMEZONE.getId(),
                earliestRegularBookingTime(from), bookingProperties.getRegularBookingMinLeadMinutes(),
                windows);
    }

    /**
     * <b>The Standard-booking lead-time rule, in one expression.</b> {@code now + pronto.bookings
     * .regular-booking-min-lead-minutes} — the earliest instant a Standard order may start.
     *
     * <p>Both consumers call this rather than doing the arithmetic themselves, which is what makes
     * "the rule is recalculated at validation time" true rather than merely intended:
     * {@link #listAvailableWindows} publishes it for display, and {@link #createOrder} re-derives
     * it from its own {@code now} at the moment of commit. The screen's copy of the boundary is
     * never trusted, and cannot be — it is not an input to anything.
     *
     * @see BookingProperties#regularBookingMinLeadMinutes
     */
    private Instant earliestRegularBookingTime(Instant now) {
        return now.plus(bookingProperties.regularBookingMinLead());
    }

    /**
     * §2.4, extended with the service-address snapshot, and, as of the professional weekly
     * availability calendar design (§9.2.2), fully reworked to a direct-{@code bookedStart}
     * creation path (no more {@code slotId}/{@code availability_slots} claim):
     * <ol>
     *   <li>Compute {@code bookedEnd = bookedStart + DEFAULT_JOB_DURATION_MINUTES}.</li>
     *   <li>Fast pre-check via {@link AvailabilityDerivationService#deriveCalendar} — confirm
     *       {@code [bookedStart, bookedEnd)} is fully contained in a single derived
     *       {@code AVAILABLE} segment; {@code 409 BOOKING_TIME_UNAVAILABLE} otherwise. This is
     *       the direct functional replacement for the old atomic slot-claim guard's "affected
     *       rows = 0" branch.</li>
     *   <li>Atomically transition the issue {@code OPEN -> BOOKED} — unchanged.</li>
     *   <li>Insert the {@code orders} row: {@code slot_id = NULL} always (every order created
     *       via this path from now on).</li>
     *   <li>The insert itself is protected by the {@code ck_orders_no_overlap} exclusion
     *       constraint (design §6) — the sole authoritative backstop for the true concurrency
     *       race; step 2's pre-check is a friendly first line of defense only. Catch Postgres
     *       {@code 23P01} on insert → map to the same {@code 409 BOOKING_TIME_UNAVAILABLE}.</li>
     * </ol>
     */
    @Transactional
    public OrderResponse createOrder(Long callerId, CreateOrderRequest request) {
        // Production MS1: an order dispatches a named professional to this customer's address on a
        // specific date. Both parties need a phone number that has actually been proved.
        contactVerificationGuard.requireVerifiedContactChannels(callerId);

        Issue issue = issueRepository.findById(request.issueId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Issue " + request.issueId() + " not found."));
        if (!issue.getCustomerId().equals(callerId)) {
            throw forbidden();
        }
        if (issue.getUrgencyType() != IssueUrgencyType.STANDARD) {
            throw urgencyMismatch(issue.getId());
        }
        if (issue.getStatus() != IssueStatus.OPEN) {
            throw notBookable(issue.getId());
        }

        Professional professional = professionalRepository.findById(request.professionalId()).orElse(null);
        if (professional == null || !isProfessionalBookable(professional)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("professionalId",
                            "must reference an existing, bookable professional")));
        }
        if (!professionalCoverageService.servesCategory(professional.getId(), issue.getCategoryId())) {
            throw categoryMismatch();
        }

        Instant now = Instant.now();
        if (!request.bookedStart().isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("bookedStart", "must be strictly in the future")));
        }

        // THE MINIMUM-NOTICE RULE, recomputed here from this request's own clock -- never from the
        // earliestBookableAt the customer's screen was handed when it loaded. That is the only
        // ordering that survives the case this rule exists to catch: a customer who opened the
        // availability screen at 11:25, chose 13:55, and posts it at 11:40 is inside the window
        // again by their own action, and a client-side filter cannot know that.
        //
        // Deliberately AFTER the strictly-in-the-future check above, so a start time in the past
        // keeps its existing, more specific error rather than being re-reported as a lead-time
        // failure. Deliberately BEFORE checkBookingWindowAvailable, so a slot that is refused by
        // policy is not also described as "the professional is busy" -- it may well be free.
        Instant earliestBookable = earliestRegularBookingTime(now);
        if (request.bookedStart().isBefore(earliestBookable)) {
            throw new ApiException(ErrorCode.BOOKING_LEAD_TIME_NOT_MET,
                    "A standard booking must start at least "
                            + bookingProperties.getRegularBookingMinLeadMinutes()
                            + " minutes from now (no earlier than " + earliestBookable + ").",
                    List.of(new FieldError("bookedStart",
                            "must be at least " + bookingProperties.getRegularBookingMinLeadMinutes()
                                    + " minutes from now")));
        }

        // Step 1: server derives bookedEnd -- never accepted from the client (design §9.2.2).
        Instant bookedStart = request.bookedStart();
        Instant bookedEnd = bookedStart.plus(Duration.ofMinutes(DEFAULT_JOB_DURATION_MINUTES));

        // Step 2: fast pre-check -- confirm [bookedStart, bookedEnd) is fully contained in a
        // single derived AVAILABLE segment.
        checkBookingWindowAvailable(professional.getId(), bookedStart, bookedEnd);

        // Step 3: atomically transition the issue; 0 rows rolls back the whole transaction
        // (single @Transactional method).
        int booked = issueRepository.bookIfOpen(issue.getId(), now);
        if (booked == 0) {
            throw notBookable(issue.getId());
        }

        // Step 4: insert the order. slot_id is always NULL for a Standard order created via
        // this path -- the same already-proven-safe pattern SOS orders have used since
        // Milestone 4. Standard orders always carry sosSurcharge = 0.00, explicitly (not
        // relying on the DB column default alone in this insert path).
        BigDecimal basePriceSnapshot = professional.getBasePrice();
        BigDecimal sosSurcharge = BigDecimal.ZERO;
        BigDecimal finalPrice = basePriceSnapshot == null ? null : basePriceSnapshot.add(sosSurcharge);
        Order order = new Order(issue.getId(), callerId, professional.getId(), bookedStart, bookedEnd, finalPrice,
                null, request.serviceCity(), request.serviceStreet(), request.serviceHouseNumber(),
                request.serviceApartment(), request.serviceFloor(), request.serviceEntrance(),
                request.serviceAddressNotes(), basePriceSnapshot, sosSurcharge);

        // Production MS2: snapshot the destination coordinates alongside the address text, once,
        // here. This is the moment the address becomes an agreement -- from now on the customer
        // may edit their default address freely without moving an order that already exists, and
        // arrival will be verified against exactly this point. See Order#snapshotServiceCoordinates
        // and V50's header.
        //
        // A null result is accepted rather than fatal. The order is still perfectly valid; it
        // simply cannot have its arrival geofence-verified later. Refusing to create an order
        // because a geocoding provider was unreachable would let an external dependency take down
        // the platform's core flow, which is a far worse failure than an unverifiable arrival.
        OrderDestination destination = resolveOrderDestination(callerId, request, now);
        order.snapshotServiceCoordinates(destination.coordinates());
        order.snapshotSelectedPlace(destination.place());

        // Step 5: the insert is protected by ck_orders_no_overlap -- the sole authoritative
        // backstop for the true concurrency race (two simultaneous createOrder calls for the
        // same professional with overlapping ranges, both passing step 2's pre-check before
        // either commits). saveAndFlush forces the INSERT to execute now, inside this
        // try/catch, rather than at end-of-transaction flush time.
        try {
            order = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            throw mapOrderConstraintViolation(e);
        }

        notificationService.recordOrderNotification(order.getId(), professional.getUserId(),
                NotificationMessageType.ORDER_CREATED);
        return toOrderResponse(order);
    }

    /**
     * §9.2.2 step 2: does a single derived {@code AVAILABLE} segment fully contain
     * {@code [bookedStart, bookedEnd)}? Mirrors {@code AvailabilityService}'s own two-layer
     * overlap-protection pattern (fast pre-check here, exclusion constraint as the
     * authoritative backstop in {@link #mapOrderConstraintViolation}).
     */
    private void checkBookingWindowAvailable(Long professionalId, Instant bookedStart, Instant bookedEnd) {
        List<CalendarSegment> segments =
                availabilityDerivationService.deriveCalendar(professionalId, bookedStart, bookedEnd);
        boolean contained = segments.stream()
                .anyMatch(segment -> segment.type() == SegmentType.AVAILABLE
                        && !segment.startAt().isAfter(bookedStart) && !segment.endAt().isBefore(bookedEnd));
        if (!contained) {
            throw bookingTimeUnavailable();
        }
    }

    /**
     * §9.2.2 step 5 / design §6: catches Postgres's {@code 23P01} (exclusion-violation)
     * SQLState on the order insert and maps it to the same domain error the pre-check throws,
     * rather than letting a raw {@code DataIntegrityViolationException} surface as an
     * unhandled {@code 500} — same pattern {@code AvailabilityService#mapBlockConstraintViolation}
     * already established for block creation. Any other constraint violation is rethrown
     * unchanged (unexpected, handled by {@code GlobalExceptionHandler}'s catch-all).
     */
    private ApiException mapOrderConstraintViolation(DataIntegrityViolationException e) {
        if (EXCLUSION_VIOLATION_SQLSTATE.equals(extractSqlState(e))) {
            return bookingTimeUnavailable();
        }
        throw e;
    }

    private static String extractSqlState(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }

    /** §2.5. */
    @Transactional
    public OrderResponse accept(Long callerId, Long orderId) {
        Order order = loadOrder(orderId);
        Long professionalId = resolveProfessionalId(callerId);
        if (!order.getProfessionalId().equals(professionalId)) {
            throw forbidden();
        }

        Instant now = Instant.now();
        int affected = orderRepository.acceptIfPending(orderId, now);
        if (affected == 0) {
            throw orderNotPending(orderId);
        }
        // issues.status is not touched -- stays BOOKED (§2.5 step 5).
        notificationService.recordOrderNotification(orderId, order.getCustomerId(),
                NotificationMessageType.ORDER_CONFIRMED);
        return toOrderResponse(loadOrder(orderId));
    }

    /** §2.6. */
    @Transactional
    public OrderResponse reject(Long callerId, Long orderId) {
        Order order = loadOrder(orderId);
        Long professionalId = resolveProfessionalId(callerId);
        if (!order.getProfessionalId().equals(professionalId)) {
            throw forbidden();
        }

        Instant now = Instant.now();
        int affected = orderRepository.rejectIfPending(orderId, now);
        if (affected == 0) {
            throw orderNotPending(orderId);
        }
        releaseSlotAndReopenIssue(order, now);
        notificationService.recordOrderNotification(orderId, order.getCustomerId(),
                NotificationMessageType.ORDER_REJECTED);
        return toOrderResponse(loadOrder(orderId));
    }

    /** §2.7. */
    @Transactional
    public OrderResponse cancel(Long callerId, String callerRole, Long orderId) {
        Order order = loadOrder(orderId);
        CancelledBy actor = determineActor(callerId, callerRole, order);

        OrderStatus expectedStatus = order.getOrderStatus();
        boolean allowed = switch (actor) {
            case CUSTOMER -> expectedStatus == OrderStatus.PENDING || expectedStatus == OrderStatus.CONFIRMED
                    || expectedStatus == OrderStatus.ON_THE_WAY;
            case PROFESSIONAL -> expectedStatus == OrderStatus.CONFIRMED || expectedStatus == OrderStatus.ON_THE_WAY;
            case SYSTEM -> false;
        };
        if (!allowed) {
            throw notCancellable(orderId);
        }

        Instant now = Instant.now();
        int affected = orderRepository.cancelIfStatus(orderId, expectedStatus, actor, now);
        if (affected == 0) {
            throw notCancellable(orderId);
        }
        releaseSlotAndReopenIssue(order, now);
        notificationService.recordOrderNotification(orderId, resolveCancelNotificationRecipient(actor, order),
                NotificationMessageType.ORDER_CANCELLED);
        return toOrderResponse(loadOrder(orderId));
    }

    /**
     * §2.16, extended by the active-booking-floating-indicator design to also compute and
     * persist {@code expectedArrivalAt} at the moment of transition. See
     * {@code docs/architecture/active-booking-floating-indicator.md} §1.4/§0.1 (supersedes the
     * prior "ETA never persisted" ruling).
     *
     * <h2>Production MS2</h2>
     *
     * The estimate is now a real routed duration from the professional's fresh device position to
     * the order's snapshotted destination — not a base-city string comparison. Two consequences
     * worth being explicit about:
     *
     * <ul>
     *   <li><b>{@code expectedArrivalAt} may be {@code null}.</b> If the professional has no
     *       usable position, or the address was never geocodable, or the provider is down, the
     *       transition still succeeds and the column stays empty. The alternative — refusing to
     *       let a professional start driving because a maps API is unreachable — would make an
     *       external dependency able to halt the platform's core flow. The customer's tracking
     *       screen shows the job is under way without a countdown, which is honest.</li>
     *   <li><b>It remains a snapshot.</b> Computed once, here, and never recomputed as the
     *       professional's GPS moves. Live location exists to make the estimate <em>better before
     *       it is promised</em>, not to make a promise that slides around for the rest of the
     *       journey — a countdown that jumps every thirty seconds is worse than a slightly wrong
     *       fixed one.</li>
     * </ul>
     */
    @Transactional
    public OrderResponse onTheWay(Long callerId, Long orderId) {
        Order order = loadOrder(orderId);
        Long professionalId = resolveProfessionalId(callerId);
        if (!order.getProfessionalId().equals(professionalId)) {
            throw forbidden();
        }

        Instant now = Instant.now();
        EtaResult eta = distanceEtaStrategy.calculate(professionalId, order.serviceCoordinates(), now);
        Instant expectedArrivalAt = eta.available()
                ? now.plus(Duration.ofMinutes(eta.etaMinutes()))
                : null;
        if (!eta.available()) {
            log.info("bookings.on-the-way.no-eta orderId={} reason={}", orderId, eta.unavailableReasonName());
        }

        int affected = orderRepository.onTheWayIfConfirmed(orderId, now, expectedArrivalAt);
        if (affected == 0) {
            throw orderNotConfirmed(orderId);
        }
        // issues.status is not touched -- stays BOOKED (§2.16 step 5).
        notificationService.recordOrderNotification(orderId, order.getCustomerId(),
                NotificationMessageType.ORDER_ON_THE_WAY);
        return toOrderResponse(loadOrder(orderId));
    }

    /**
     * <b>Production MS2 — {@code POST /api/bookings/orders/{orderId}/arrived}.</b> The
     * {@code ON_THE_WAY -> ARRIVED} transition, and the only status change in this platform gated
     * on a physical fact.
     *
     * <p>The flow, in the order it must happen:
     * <ol>
     *   <li>Authorize: the caller is the professional on this order (403 otherwise).</li>
     *   <li>Validate the submitted fix — coordinates in range, accuracy positive and plausible,
     *       {@code capturedAt} present and not implausibly in the future ({@code 400}).</li>
     *   <li>Reject a fix that is too old ({@code pronto.location.arrival-max-age}) or too
     *       imprecise ({@code pronto.location.arrival-max-accuracy-meters}) —
     *       {@code 422 LOCATION_QUALITY_INSUFFICIENT}. Note this bar is much stricter than the
     *       routing bar: routing asks "roughly where are you", this asks "are you at this
     *       door".</li>
     *   <li>Load the order's <b>immutable destination snapshot</b>. Absent ⇒
     *       {@code 409 ORDER_DESTINATION_UNKNOWN}, because there is nothing to verify against and
     *       no amount of retrying will change that.</li>
     *   <li>Measure the great-circle distance server-side ({@code maps.GeoDistance}) and compare
     *       it to {@code pronto.location.arrival-radius-meters}. Outside ⇒
     *       {@code 422 ARRIVAL_OUT_OF_RANGE}.</li>
     *   <li>Only then perform the atomic transition and write the evidence.</li>
     * </ol>
     *
     * <h2>Why the backend is authoritative, and what that does and does not prove</h2>
     *
     * The customer's coordinates never leave the server. A design that sent them to the
     * professional's client to compare locally would leak the address to anyone holding an offer
     * and would let any modified client claim to be anywhere — the check would be decoration.
     *
     * <p>What this <b>does</b> prove is that the position the professional's device reported,
     * moments ago and with an accuracy it also reported, is within the geofence. What it does
     * <b>not</b> prove is that the device was telling the truth: browser geolocation originates on
     * the client and can be spoofed by a determined user. MS2 makes no claim to be fraud-proof,
     * and deliberately builds no device attestation — see the maps README's anti-spoofing note.
     * The honest description of this feature is "server-validated proximity based on a fresh
     * geolocation reading supplied by the professional's device", and that is what the report
     * says.
     *
     * <p>There is <b>no manual override</b>. Adding one silently would hand every professional a
     * way to bypass the check that justifies the feature existing; if operations later needs one,
     * it belongs on the operator surface as an audited exception, not as a flag on this endpoint.
     */
    @Transactional
    public OrderResponse arrived(Long callerId, Long orderId, ArrivalRequest request) {
        Order order = loadOrder(orderId);
        Long professionalId = resolveProfessionalId(callerId);
        if (!order.getProfessionalId().equals(professionalId)) {
            throw forbidden();
        }
        if (order.getOrderStatus() != OrderStatus.ON_THE_WAY) {
            throw orderNotArrivable(orderId);
        }

        Instant now = Instant.now();

        // Steps 2-5, all of them, in one place: shape, freshness, precision, proximity. Shared
        // verbatim with the SOS flow (sos.service.SosOfferService#arrived) through
        // maps.service.ArrivalVerifier -- "the professional pressed הגעתי" has to mean exactly the
        // same thing on the calm flow and on the urgent one, and two copies of a geofence rule is
        // how that stops being true. Throws 400/422 with the specific reason; the verified
        // distance comes back for the evidence record.
        BigDecimal distanceMeters = arrivalVerifier.verify(professionalId, order.serviceCoordinates(),
                request.latitude(), request.longitude(), request.accuracyMeters(), request.capturedAt(),
                now, "order:" + orderId);
        GeoCoordinates position = arrivalVerifier.positionOf(request.latitude(), request.longitude());

        // Step 6 -- transition, then evidence, in that order. 0 rows means somebody else moved the
        // order between the load and here; refusing rather than restamping keeps arrived_at the
        // record of the FIRST verified arrival.
        int affected = orderRepository.arrivedIfOnTheWay(orderId, now);
        if (affected == 0) {
            throw orderNotArrivable(orderId);
        }
        Order fresh = loadOrder(orderId);
        fresh.recordArrivalEvidence(position, request.accuracyMeters(), distanceMeters, now);
        orderRepository.save(fresh);

        notificationService.recordOrderNotification(orderId, order.getCustomerId(),
                NotificationMessageType.ORDER_ARRIVED);
        return toOrderResponse(loadOrder(orderId));
    }

    /** §2.17. */
    @Transactional
    public OrderResponse complete(Long callerId, Long orderId) {
        Order order = loadOrder(orderId);
        Long professionalId = resolveProfessionalId(callerId);
        if (!order.getProfessionalId().equals(professionalId)) {
            throw forbidden();
        }

        Instant now = Instant.now();
        int affected = orderRepository.completeIfOnTheWay(orderId, now);
        if (affected == 0) {
            throw orderNotOnTheWay(orderId);
        }
        // §3.3's single-active-order invariant guarantees this always affects 1 row when
        // reached -- not branched on/checked, same as expireIfPending's call to reopenIfBooked.
        issueRepository.completeIfBooked(order.getIssueId(), now);
        notificationService.recordOrderNotification(orderId, order.getCustomerId(),
                NotificationMessageType.ORDER_COMPLETED);
        return toOrderResponse(loadOrder(orderId));
    }

    /** §2.8. */
    @Transactional(readOnly = true)
    public OrderDetailResponse getOrderDetail(Long callerId, String callerRole, Long orderId) {
        Order order = loadOrder(orderId);

        boolean isCustomer = order.getCustomerId().equals(callerId);
        boolean isProfessional = !isCustomer && UserRole.PROFESSIONAL.name().equals(callerRole)
                && professionalRepository.findByUserId(callerId)
                        .map(p -> p.getId().equals(order.getProfessionalId()))
                        .orElse(false);
        if (!isCustomer && !isProfessional) {
            throw forbidden();
        }

        // §9.1 of the calendar design: customerPhone is read off this same already-loaded
        // User row, no new query/authorization branch -- the existing party-to-order check
        // above already gates the whole response, same as customerName always has.
        User customerUser = userRepository.findById(order.getCustomerId()).orElse(null);
        String customerName = customerUser == null ? null : customerUser.getFullName();
        String customerPhone = customerUser == null ? null : customerUser.getPhone();
        String professionalName = resolveProfessionalName(order.getProfessionalId());

        return new OrderDetailResponse(order.getId(), order.getIssueId(), order.getCustomerId(), customerName,
                customerPhone, order.getProfessionalId(), professionalName, order.getOrderStatus(), order.getBookedStart(),
                order.getBookedEnd(), order.getExpectedArrivalAt(), order.getFinalPrice(),
                order.getBasePriceSnapshot(), order.getSosSurcharge(),
                order.getServiceCity(), order.getServiceStreet(), order.getServiceHouseNumber(),
                order.getServiceApartment(), order.getServiceFloor(), order.getServiceEntrance(),
                order.getServiceAddressNotes(), order.getCancelledBy(), order.getCreatedAt(), order.getUpdatedAt());
    }

    /** §2.9. */
    @Transactional(readOnly = true)
    public OrdersListResponse listMine(Long callerId, String callerRole, String statusParam) {
        OrderStatus status = parseStatus(statusParam);

        List<Order> orders;
        if (UserRole.CUSTOMER.name().equals(callerRole)) {
            orders = status == null
                    ? orderRepository.findByCustomerIdOrderByCreatedAtDesc(callerId)
                    : orderRepository.findByCustomerIdAndOrderStatusOrderByCreatedAtDesc(callerId, status);
        } else if (UserRole.PROFESSIONAL.name().equals(callerRole)) {
            Long professionalId = resolveProfessionalId(callerId);
            orders = status == null
                    ? orderRepository.findByProfessionalIdOrderByCreatedAtDesc(professionalId)
                    : orderRepository.findByProfessionalIdAndOrderStatusOrderByCreatedAtDesc(professionalId, status);
        } else {
            // MS1: this used to be a bare `else`, which resolved every non-CUSTOMER caller down
            // the professional branch. With UserRole.ADMIN existing, an operator would have been
            // silently treated as a professional and refused only incidentally, by having no
            // professional profile. "My orders" is meaningless for an operator; say so.
            throw forbidden();
        }

        Map<Long, String> namesByProfessionalId = resolveProfessionalNames(orders);

        List<OrderSummaryResponse> summaries = orders.stream()
                .map(o -> new OrderSummaryResponse(o.getId(), o.getIssueId(), o.getProfessionalId(),
                        namesByProfessionalId.get(o.getProfessionalId()), o.getOrderStatus(),
                        o.getBookedStart(), o.getBookedEnd(), o.getExpectedArrivalAt(), o.getFinalPrice(),
                        o.getCreatedAt(), o.getUpdatedAt()))
                .toList();
        return new OrdersListResponse(summaries);
    }

    /** §4.5 of {@code api-contract-notifications.md}. */
    @Transactional(readOnly = true)
    public List<Long> findExpiredOrderCandidateIds() {
        Instant now = Instant.now();
        return orderRepository.findPendingExpiryCandidateIds(
                now.minus(STANDARD_PENDING_TIMEOUT), now.minus(SOS_PENDING_TIMEOUT));
    }

    /**
     * §4.5 — mirrors {@link #reject}'s shape exactly, but is called by a background job
     * ({@code notifications.scheduler.OrderExpirySweepJob}), not an HTTP request: {@code 0}
     * affected rows just means another caller already moved the order out of {@code PENDING},
     * treated as a normal, silent outcome (no {@link ApiException}, no HTTP caller to report a
     * {@code 409} to).
     *
     * <p><b>The issue is reopened, not expired.</b> Everything the customer already gave us —
     * description, photos, AI classification, category, sub-service, address — belongs to the
     * {@code issues} row and is left completely untouched; only the order is terminal. The
     * customer's recovery is therefore "pick a different professional for the same problem"
     * ({@code /issues/{issueId}/booking}), not "start over". See
     * {@code IssueRepository.reopenIfBooked}.
     */
    @Transactional
    public Optional<OrderResponse> expireIfPending(Long orderId) {
        Instant now = Instant.now();
        int affected = orderRepository.expireIfPending(orderId, now);
        if (affected == 0) {
            return Optional.empty();
        }
        Order order = loadOrder(orderId);
        // §3.3's single-active-order invariant guarantees this always affects 1 row when reached.
        issueRepository.reopenIfBooked(order.getIssueId(), now);
        // Same §3.4 slot-release mechanism reject/cancel already use; safe no-op for SOS orders.
        availabilitySlotRepository.releaseSlot(order.getSlotId(), now);
        notificationService.recordOrderNotification(orderId, order.getCustomerId(), NotificationMessageType.ORDER_EXPIRED);
        return Optional.of(toOrderResponse(order));
    }

    // ---- shared helpers ----

    private Issue loadIssue(Long issueId) {
        return issueRepository.findById(issueId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Issue " + issueId + " not found."));
    }

    private Order loadOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Order " + orderId + " not found."));
    }

    /** §3.5: resolves the caller's {@code professionals.id}; treats "no profile" as {@code 403}. */
    private Long resolveProfessionalId(Long callerId) {
        return professionalRepository.findByUserId(callerId)
                .map(Professional::getId)
                .orElseThrow(this::forbidden);
    }

    /**
     * <b>May this professional be given new work?</b> Two independent conditions, deliberately
     * kept separate:
     *
     * <ul>
     *   <li>the owning account is not soft-deleted — the pre-existing check, unchanged;</li>
     *   <li><b>MS1:</b> the professional is marketplace-eligible, delegated to
     *       {@code ProfessionalRepository#existsEligibleById}, which is built from the same
     *       {@link com.pronto.professionals.ProfessionalEligibility#ELIGIBLE_JPQL} constant the
     *       listing query filters on. Nothing about the rule is re-expressed in Java here; if it
     *       were, the listing and the booking guard could disagree about the same person, which
     *       is worse than either being wrong consistently.</li>
     * </ul>
     *
     * <p>The soft-delete half stays outside the eligibility predicate on purpose — see that
     * constant's Javadoc.
     *
     * <p>Used by {@link #createOrder} (which already called the soft-delete half) and by
     * {@link #listAvailableWindows} (which called neither: MS0 recorded that a customer could
     * page through the calendar of a deleted or unverified professional right up to the moment
     * the order was refused).
     */
    private boolean isProfessionalBookable(Professional professional) {
        boolean accountActive = userRepository.findById(professional.getUserId())
                .map(u -> u.getDeletedAt() == null)
                .orElse(false);
        return accountActive && professionalRepository.existsEligibleById(professional.getId());
    }

    /**
     * The single-row {@link #resolveProfessionalName} applied to a whole list, in two batched
     * queries instead of two per order: professionals by id, then their users by id. Orders in a
     * customer's list very often share professionals, and even when they don't, a list of N orders
     * must not cost 2N round trips.
     *
     * <p>Ids that resolve to no professional, or to a professional whose user row is gone, are
     * simply absent from the map — callers read {@code null}, exactly as the single-row form
     * returns {@code null}.
     */
    private Map<Long, String> resolveProfessionalNames(List<Order> orders) {
        List<Long> professionalIds = orders.stream()
                .map(Order::getProfessionalId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (professionalIds.isEmpty()) {
            return Map.of();
        }

        List<Professional> professionals = professionalRepository.findAllById(professionalIds);
        Map<Long, String> namesByUserId = userRepository
                .findAllById(professionals.stream().map(Professional::getUserId).filter(Objects::nonNull).toList())
                .stream()
                .filter(user -> user.getFullName() != null)
                .collect(Collectors.toMap(User::getId, User::getFullName));

        Map<Long, String> namesByProfessionalId = new HashMap<>();
        for (Professional professional : professionals) {
            String fullName = professional.getUserId() == null ? null : namesByUserId.get(professional.getUserId());
            if (fullName != null) {
                namesByProfessionalId.put(professional.getId(), fullName);
            }
        }
        return namesByProfessionalId;
    }

    private String resolveProfessionalName(Long professionalId) {
        return professionalRepository.findById(professionalId)
                .flatMap(p -> userRepository.findById(p.getUserId()))
                .map(User::getFullName)
                .orElse(null);
    }

    /** §2.7 step 3: determine which party the caller is, or {@code 403} if neither. */
    private CancelledBy determineActor(Long callerId, String callerRole, Order order) {
        if (UserRole.CUSTOMER.name().equals(callerRole) && order.getCustomerId().equals(callerId)) {
            return CancelledBy.CUSTOMER;
        }
        if (UserRole.PROFESSIONAL.name().equals(callerRole)) {
            Long professionalId = professionalRepository.findByUserId(callerId).map(Professional::getId).orElse(null);
            if (professionalId != null && professionalId.equals(order.getProfessionalId())) {
                return CancelledBy.PROFESSIONAL;
            }
        }
        throw forbidden();
    }

    /**
     * §4.2 of {@code api-contract-notifications.md}: the party who backed out doesn't get
     * notified about their own action — resolve the *other* party's {@code user_id}.
     */
    private Long resolveCancelNotificationRecipient(CancelledBy actor, Order order) {
        if (actor == CancelledBy.CUSTOMER) {
            Professional professional = professionalRepository.findById(order.getProfessionalId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                            "Professional " + order.getProfessionalId() + " not found."));
            return professional.getUserId();
        }
        return order.getCustomerId();
    }

    /** §3.4: sole slot-release mechanism, safe no-op when {@code order.slotId} is {@code null}. */
    private void releaseSlotAndReopenIssue(Order order, Instant now) {
        availabilitySlotRepository.releaseSlot(order.getSlotId(), now);
        issueRepository.revertToOpen(order.getIssueId(), now);
    }

    private OrderStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request failed validation.",
                    List.of(new FieldError("status", "must be one of the valid order statuses")));
        }
    }

    /**
     * {@code sort} query param: missing/blank defaults to {@code defaultSort} ({@link
     * ProfessionalSort#CHEAPEST} for the Standard listing, {@link ProfessionalSort#FASTEST}
     * for the SOS listing — mirrors {@link #parseStatus}'s "blank means no filter" convention,
     * adapted for this param's "blank means default" semantics); any non-blank value that
     * isn't a valid enum constant is {@code 400 VALIDATION_ERROR}, same convention as
     * {@link #parseStatus}.
     */
    private ProfessionalSort parseSort(String raw, ProfessionalSort defaultSort) {
        if (raw == null || raw.isBlank()) {
            return defaultSort;
        }
        try {
            return ProfessionalSort.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request failed validation.",
                    List.of(new FieldError("sort", "must be one of CHEAPEST, RECOMMENDED, FASTEST")));
        }
    }

    /**
     * Post-fetch, in-Java-only enrichment pass (never in SQL, per the approved design):
     * resolves each card's raw {@code profileImageUrl} slot (currently the raw
     * {@code profile_image_key} column value, per {@code ProfessionalCard}'s Javadoc) to a
     * presigned URL via {@link StorageService#getPresignedUrl(Long, String)} (backend MS9,
     * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §9.1), and attaches
     * real travel figures.
     *
     * <h2>Production MS2 — two calls, not two per card</h2>
     *
     * The customer's address is geocoded <b>once</b> for the whole listing (and, for their saved
     * default address, usually not at all — the coordinates are already persisted), and
     * {@link DistanceEtaStrategy#calculateBatch} then routes every candidate in one batched
     * provider request. The pre-MS2 code called {@code calculate} inside the per-card
     * {@code map}, which was free only because the implementation was a string comparison; doing
     * the same against a real provider would be one HTTP round trip per card. See the
     * {@code maps} README's call-budget section.
     *
     * <p>Business filters have already run before this method is reached — the SQL listing query
     * has narrowed to the issue's category and to marketplace-eligible professionals — so only
     * plausible candidates are ever routed.
     *
     * <h2>Sorting</h2>
     *
     * {@code FASTEST} sorts by real driving duration ascending, with <b>professionals whose ETA is
     * unavailable last</b> and a deterministic id tie-break. That ordering rule is not a detail:
     * with the pre-MS2 model every professional had an ETA, so "fastest" could never be wrong
     * about who was missing one. Now that unavailable is a real outcome, sorting {@code null}
     * anywhere but last would let a professional the platform cannot route win a tab whose entire
     * promise is arrival speed. {@code RECOMMENDED} and {@code CHEAPEST} are unchanged — see
     * {@link #sortCards}.
     */
    private List<ProfessionalCard> enrichAndSort(Long callerId, List<ProfessionalCard> cards,
                                                  ServiceLocation location, ProfessionalSort sort) {
        Instant requestTime = Instant.now();
        List<Long> professionalIds = cards.stream().map(ProfessionalCard::professionalId).toList();

        // MS4: one batched professional_categories read for the whole page, before the per-card
        // pass -- a card has to be able to say a professional serves several trades, and JPQL
        // cannot project a collection into the listing's SELECT NEW (see ProfessionalCard).
        Map<Long, List<Long>> categoryIdsByProfessional =
                professionalCoverageService.categoryIdsByProfessional(professionalIds);

        // MS2: one geocode for the listing, then one batched routing call for every candidate.
        GeoCoordinates destination = resolveListingDestination(callerId, location);
        Map<Long, EtaResult> etaByProfessional =
                distanceEtaStrategy.calculateBatch(professionalIds, destination, requestTime);

        List<ProfessionalCard> enriched = cards.stream()
                .map(card -> {
                    String profileImageUrl = card.profileImageUrl() == null
                            ? null
                            : storageService.getPresignedUrl(callerId, card.profileImageUrl());
                    EtaResult eta = etaByProfessional.getOrDefault(card.professionalId(),
                            EtaResult.unavailable(RouteUnavailableReason.PROVIDER_UNAVAILABLE));
                    return new ProfessionalCard(card.professionalId(), card.fullName(), card.serviceRegion(),
                            card.basePrice(), card.reliabilityScore(), card.city(), profileImageUrl,
                            card.averageRating(), card.reviewCount(), card.favorited(),
                            categoryIdsByProfessional.getOrDefault(card.professionalId(), List.of()),
                            eta.distanceKm(), eta.etaMinutes(), eta.trafficAware(), eta.unavailableReasonName());
                })
                .toList();

        return sortCards(enriched, sort);
    }

    /**
     * The listing's destination coordinates.
     *
     * <p>Two sources, in order of preference, and the ordering is entirely about cost. If the
     * customer is booking to their own saved default address — overwhelmingly the common case —
     * the coordinates are already persisted on their {@code users} row from the write path that
     * accepted the address, and no provider call happens at all. Only an address with no usable
     * stored result is geocoded live, and then once, here, rather than once per card.
     *
     * <p><b>This method never persists, and the reason is not stylistic.</b>
     * {@link #listProfessionals} runs in a {@code readOnly = true} transaction, so an entity
     * mutation here would be silently discarded at flush time — the geocode would be paid for on
     * every single listing request and its result thrown away every time, with nothing failing
     * loudly enough to notice. Persisting a resolved address is therefore the job of the write
     * paths that accept one ({@code auth.service.AuthService#register},
     * {@code users.service.UsersService#updateMe}, {@link #createOrder}), and this read path only
     * ever reads what they stored, or resolves transiently when they stored nothing.
     *
     * <p>Returns {@code null} when the address cannot be resolved. That is not an error: the
     * listing still renders, every card simply reports no ETA with reason
     * {@code DESTINATION_UNKNOWN}, and the customer can still browse and book. Failing the whole
     * request because a geocoder was unavailable would be a much worse trade.
     */
    private GeoCoordinates resolveListingDestination(Long callerId, ServiceLocation location) {
        // A guest has no saved default address to fall back to, so the address they typed is the
        // only destination there is. Guarded rather than passed through: Spring Data's findById
        // throws on a null id, so an unguarded call here would turn every guest listing into a 500.
        User customer = callerId == null ? null : userRepository.findById(callerId).orElse(null);
        PostalAddress requested = location == null ? null : location.toPostalAddress();

        if (customer != null) {
            PostalAddress defaultAddress = new PostalAddress(customer.getDefaultCity(),
                    customer.getDefaultStreet(), customer.getDefaultHouseNumber());
            boolean wantsDefaultAddress = requested == null || !requested.isGeocodable()
                    || (defaultAddress.isGeocodable()
                        && requested.contentHash().equals(defaultAddress.contentHash()));
            if (wantsDefaultAddress) {
                GeoCoordinates stored = serviceAddressGeocoder.storedCustomerDefault(customer, Instant.now());
                if (stored != null) {
                    return stored;
                }
                // Nothing usable stored (a legacy row, or an address the write path could not
                // resolve at the time). Resolve transiently so this listing still works; the next
                // write path to touch the address will persist it properly.
                requested = defaultAddress;
            }
        }

        if (requested == null || !requested.isGeocodable()) {
            return null;
        }
        GeocodeResult result = serviceAddressGeocoder.resolve(requested);
        return result.isResolved() ? result.coordinates() : null;
    }

    /**
     * The three sort modes, extracted so the {@code FASTEST} null-handling rule is stated once and
     * testable on its own.
     *
     * <ul>
     *   <li><b>{@code FASTEST}</b> — real driving duration ascending; unavailable ETAs last;
     *       professional id ascending as a deterministic final tie-break, so two runs over
     *       identical data produce identical output and a disputed ordering is reproducible.
     *       Base city plays no part, hidden or otherwise.</li>
     *   <li><b>{@code RECOMMENDED}</b> — unchanged by MS2, and deliberately so: it ranks on
     *       {@code averageRating} then {@code reviewCount} and has never had a distance or ETA
     *       component, so there was nothing here for real routing to replace.</li>
     *   <li><b>{@code CHEAPEST}</b> — unchanged; leaves the query's {@code base_price ASC}
     *       ordering alone. Price-driven, as it should be.</li>
     * </ul>
     */
    static List<ProfessionalCard> sortCards(List<ProfessionalCard> cards, ProfessionalSort sort) {
        if (sort == ProfessionalSort.FASTEST) {
            return cards.stream()
                    .sorted(Comparator.comparing(ProfessionalCard::etaMinutes,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(ProfessionalCard::professionalId))
                    .toList();
        }
        if (sort == ProfessionalSort.RECOMMENDED) {
            return cards.stream()
                    .sorted(Comparator.comparing(ProfessionalCard::averageRating,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(Comparator.comparingLong(ProfessionalCard::reviewCount).reversed()))
                    .toList();
        }
        return cards;
    }

    private OrderResponse toOrderResponse(Order order) {
        return new OrderResponse(order.getId(), order.getIssueId(), order.getCustomerId(),
                order.getProfessionalId(), order.getOrderStatus(), order.getBookedStart(), order.getBookedEnd(),
                order.getExpectedArrivalAt(), order.getFinalPrice(), order.getBasePriceSnapshot(),
                order.getSosSurcharge(), order.getServiceCity(), order.getServiceStreet(),
                order.getServiceHouseNumber(), order.getServiceApartment(), order.getServiceFloor(),
                order.getServiceEntrance(), order.getServiceAddressNotes(), order.getCancelledBy(),
                order.getCreatedAt(), order.getUpdatedAt());
    }

    private ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "You are not authorized to perform this action.");
    }

    /**
     * <b>An {@code issueId} was named by a caller this request could not identify.</b>
     *
     * <p>Separate from {@link #forbidden()}, and the separation is the fix for a real Production
     * defect rather than a taxonomy preference. Deferred authentication made
     * {@code GET /api/bookings/professionals} and {@code .../available-windows} {@code permitAll},
     * so {@code auth.security.JwtAuthenticationFilter} no longer rejects a request carrying an
     * expired or revoked token — it leaves the security context empty and lets the request through,
     * exactly as it does for a genuine guest. Both then arrive here with {@code callerId == null}.
     *
     * <p>Collapsing the two into {@code 403} meant a customer whose 24h token died in an open tab
     * asked for a listing keyed on their own issue and was told "you are not authorized to perform
     * this action" — permanently. The frontend's dead-session handler
     * ({@code httpClient.setUnauthorizedHandler}) fires on {@code 401} and only on {@code 401}, so
     * nothing cleared the dead token, every retry produced the same {@code 403}, and the customer
     * could not recover without clearing {@code localStorage} by hand.
     *
     * <p>{@code 401} is also simply the truthful answer, per {@code api-contract.md} §1:
     * {@code 401} is "no or invalid authentication", {@code 403} is "authenticated, but not
     * permitted". Nobody was authenticated here. A caller who <em>is</em> authenticated and names
     * somebody else's issue still gets {@link #forbidden()} — that check is unchanged, and this
     * opens nothing: an anonymous caller learns only that reading an issue needs an account, which
     * is what the guest journey already tells them.
     */
    private ApiException unauthenticatedIssueAccess() {
        return new ApiException(ErrorCode.UNAUTHORIZED,
                "Sign in to continue with this request.");
    }

    private ApiException notBookable(Long issueId) {
        return new ApiException(ErrorCode.ISSUE_NOT_BOOKABLE, "Issue " + issueId + " is not open for booking.");
    }

    private ApiException urgencyMismatch(Long issueId) {
        return new ApiException(ErrorCode.ISSUE_URGENCY_MISMATCH,
                "Issue " + issueId + "'s urgency type does not match this booking path.");
    }

    private ApiException categoryMismatch() {
        return new ApiException(ErrorCode.CATEGORY_MISMATCH,
                "The professional's category does not match the issue's category.");
    }

    /**
     * MS1: {@code GET .../{professionalId}/available-windows}'s single refusal for "this
     * professional cannot be booked", covering both a nonexistent id and an existing but
     * ineligible/soft-deleted one — <b>identical code and identical message</b>, so the endpoint
     * cannot be used to learn that a particular professional exists but was rejected or has not
     * been verified. Same anti-enumeration convention {@code storage.service.StorageService}
     * already applies ("always 403, never 404, on an ownership mismatch") and the same reasoning
     * {@code AuthService#login} uses for its deliberately generic invalid-credentials error.
     *
     * <p>{@code 404} rather than a new 409: from the booking flow's point of view there is
     * nothing at that id to show a calendar for. The customer-facing "why" belongs on the
     * professional's profile response, which carries the neutral {@code bookable} flag (D-G).
     */
    private ApiException professionalNotBookable(Long professionalId) {
        return new ApiException(ErrorCode.NOT_FOUND, "Professional " + professionalId + " not found.");
    }

    /**
     * §9.2.2: the direct-{@code bookedStart} creation path's sole "time not available" error
     * — thrown by both {@link #checkBookingWindowAvailable} (the pre-check) and {@link
     * #mapOrderConstraintViolation} (the race backstop). {@code SLOT_UNAVAILABLE} (still a
     * valid {@link ErrorCode} enum value) is vestigial as of this milestone — nothing returns
     * it anymore, since no caller can supply a {@code slotId} to {@code createOrder} at all.
     */
    private ApiException bookingTimeUnavailable() {
        return new ApiException(ErrorCode.BOOKING_TIME_UNAVAILABLE,
                "The requested booking time is no longer available.");
    }

    private ApiException orderNotPending(Long orderId) {
        return new ApiException(ErrorCode.ORDER_NOT_PENDING,
                "Order " + orderId + " is not in PENDING status and cannot be accepted or rejected.");
    }

    private ApiException notCancellable(Long orderId) {
        return new ApiException(ErrorCode.ORDER_NOT_CANCELLABLE,
                "Order " + orderId + " cannot be cancelled in its current state.");
    }

    private ApiException orderNotConfirmed(Long orderId) {
        return new ApiException(ErrorCode.ORDER_NOT_CONFIRMED,
                "Order " + orderId + " is not in CONFIRMED status and cannot be marked on the way.");
    }

    private ApiException orderNotOnTheWay(Long orderId) {
        return new ApiException(ErrorCode.ORDER_NOT_ON_THE_WAY,
                "Order " + orderId + " is not in ON_THE_WAY or ARRIVED status and cannot be completed.");
    }

    /** Production MS2 — {@code ARRIVED} is reachable only from {@code ON_THE_WAY}. */
    private ApiException orderNotArrivable(Long orderId) {
        return new ApiException(ErrorCode.ORDER_NOT_ARRIVABLE,
                "Order " + orderId + " is not in ON_THE_WAY status and cannot be marked as arrived.");
    }

    /**
     * The destination coordinates to snapshot onto a new order.
     *
     * <p>Same two-source preference as {@link #resolveListingDestination}, and for the same
     * reason: an order to the customer's own saved address costs no provider call at all, because
     * those coordinates are already persisted and still current for that exact address text. Only
     * a one-off address typed for this booking is geocoded live — once, here, at write time.
     */
    private OrderDestination resolveOrderDestination(Long callerId, CreateOrderRequest request, Instant now) {
        PostalAddress requested = new PostalAddress(request.serviceCity(), request.serviceStreet(),
                request.serviceHouseNumber());
        User customer = userRepository.findById(callerId).orElse(null);
        boolean isOwnSavedDefault = isOwnSavedDefaultAddress(customer, requested);

        // Address validation (V55). The rule differs by case, and deliberately so:
        //
        //   * ANOTHER ADDRESS FOR THIS BOOKING -- new text nobody has ever confirmed. A selected
        //     place is REQUIRED. This is the case the whole feature exists for: it is where a
        //     customer could previously type a street and house number that do not exist and have
        //     a professional dispatched to them.
        //
        //   * THE CALLER'S OWN SAVED DEFAULT ADDRESS -- grandfathered. It may legitimately predate
        //     address validation (V55 backfills nothing), and stopping an existing customer
        //     mid-booking to re-enter an address that has been serving them fine would be a
        //     self-inflicted outage for a data-quality gain. They are asked to re-select it the
        //     next time they EDIT it, which is the only moment that costs nobody anything.
        //
        // The "is this their own default?" test is the V50 address digest, and it cannot be
        // exploited to smuggle an unvalidated address through: the only text it admits is text
        // already saved on the caller's own users row.
        SelectedPlace place = isOwnSavedDefault
                ? selectedPlaceValidator.validateOptional(request.servicePlaceId(),
                        request.serviceFormattedAddress(), request.serviceLatitude(),
                        request.serviceLongitude(), SelectedPlaceValidator.FieldNames.camelCase("service"))
                : selectedPlaceValidator.requireSelected(request.servicePlaceId(),
                        request.serviceFormattedAddress(), request.serviceLatitude(),
                        request.serviceLongitude(), SelectedPlaceValidator.FieldNames.camelCase("service"));

        if (place != null) {
            // Zero provider calls: the coordinates came from the place the customer picked, which
            // is a better answer than geocoding the text would give and one already paid for.
            return new OrderDestination(place.coordinates(), place);
        }

        // Grandfathered legacy default address only -- the pre-V55 behaviour, unchanged.
        if (!requested.isGeocodable()) {
            return OrderDestination.NONE;
        }
        if (isOwnSavedDefault) {
            return new OrderDestination(serviceAddressGeocoder.resolveCustomerDefault(customer, now), null);
        }
        GeocodeResult result = serviceAddressGeocoder.resolve(requested);
        return new OrderDestination(result.isResolved() ? result.coordinates() : null, null);
    }

    /**
     * Is this submitted address the caller's own stored default, character for character?
     *
     * <p>Compared by {@link PostalAddress#contentHash()} rather than field by field, so that
     * whitespace and capitalisation differences do not make the same address look like a new one —
     * the same digest that already decides whether stored coordinates may be reused.
     */
    private static boolean isOwnSavedDefaultAddress(User customer, PostalAddress requested) {
        if (customer == null || !requested.isGeocodable()) {
            return false;
        }
        PostalAddress defaultAddress = new PostalAddress(customer.getDefaultCity(),
                customer.getDefaultStreet(), customer.getDefaultHouseNumber());
        return defaultAddress.isGeocodable()
                && requested.contentHash().equals(defaultAddress.contentHash());
    }

    /**
     * What an order's destination snapshot is made of: where it is, and which place the customer
     * selected to mean it.
     *
     * <p>Both halves are independently nullable and that is not laziness — a grandfathered legacy
     * address has coordinates but no selected place, and an address the geocoder could not resolve
     * has neither. Returned as one value so the two can never be fetched from different addresses.
     */
    private record OrderDestination(GeoCoordinates coordinates, SelectedPlace place) {
        static final OrderDestination NONE = new OrderDestination(null, null);
    }
}
