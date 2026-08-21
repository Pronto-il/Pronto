package com.pronto.bookings.service;

import com.pronto.availability.dto.CalendarSegment;
import com.pronto.availability.dto.SegmentType;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.service.AvailabilityDerivationService;
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
import com.pronto.matching.DistanceEtaStrategy;
import com.pronto.matching.EtaResult;
import com.pronto.matching.ServiceLocation;
import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.service.NotificationService;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final DistanceEtaStrategy distanceEtaStrategy;
    private final StorageService storageService;
    private final AvailabilityDerivationService availabilityDerivationService;

    public BookingsService(IssueRepository issueRepository,
                            ProfessionalRepository professionalRepository,
                            ProfessionalListingRepository professionalListingRepository,
                            AvailabilitySlotRepository availabilitySlotRepository,
                            OrderRepository orderRepository,
                            UserRepository userRepository,
                            NotificationService notificationService,
                            DistanceEtaStrategy distanceEtaStrategy,
                            StorageService storageService,
                            AvailabilityDerivationService availabilityDerivationService) {
        this.issueRepository = issueRepository;
        this.professionalRepository = professionalRepository;
        this.professionalListingRepository = professionalListingRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.distanceEtaStrategy = distanceEtaStrategy;
        this.storageService = storageService;
        this.availabilityDerivationService = availabilityDerivationService;
    }

    /** §2.2, extended with the service-location/sort matching design. */
    @Transactional(readOnly = true)
    public ProfessionalListingResponse listProfessionals(Long callerId, Long issueId, ServiceLocation location,
                                                           String sortParam) {
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
        List<ProfessionalCard> professionals =
                professionalListingRepository.listByCategory(issue.getCategoryId(), callerId);
        professionals = enrichAndSort(callerId, professionals, location, parseSort(sortParam, ProfessionalSort.CHEAPEST));
        return new ProfessionalListingResponse(issue.getId(), issue.getCategoryId(), professionals);
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
        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Professional " + professionalId + " not found."));
        if (!professional.getCategoryId().equals(issue.getCategoryId())) {
            throw categoryMismatch();
        }

        Instant from = Instant.now();
        Instant to = from.plus(Duration.ofDays(AVAILABLE_WINDOWS_LOOKAHEAD_DAYS));
        List<AvailableWindow> windows = availabilityDerivationService
                .deriveAvailableWindows(professionalId, from, to, Duration.ofMinutes(DEFAULT_JOB_DURATION_MINUTES))
                .stream()
                .map(segment -> new AvailableWindow(segment.startAt(), segment.endAt()))
                .toList();
        return new AvailableWindowsResponse(professionalId, issueId, DEFAULT_JOB_DURATION_MINUTES,
                AvailabilityDerivationService.BUSINESS_TIMEZONE.getId(), windows);
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
        if (professional == null || !isProfessionalActive(professional)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("professionalId", "must reference an existing, active professional")));
        }
        if (!professional.getCategoryId().equals(issue.getCategoryId())) {
            throw categoryMismatch();
        }

        Instant now = Instant.now();
        if (!request.bookedStart().isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("bookedStart", "must be strictly in the future")));
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
     * persist {@code expectedArrivalAt} at the moment of transition, reusing the same
     * {@link DistanceEtaStrategy#calculate} call {@link #enrichAndSort} already makes for
     * listing-card ETA. See {@code docs/architecture/active-booking-floating-indicator.md}
     * §1.4/§0.1 (supersedes the prior "ETA never persisted" ruling).
     */
    @Transactional
    public OrderResponse onTheWay(Long callerId, Long orderId) {
        Order order = loadOrder(orderId);
        Long professionalId = resolveProfessionalId(callerId);
        if (!order.getProfessionalId().equals(professionalId)) {
            throw forbidden();
        }

        Instant now = Instant.now();
        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Professional " + professionalId + " not found."));
        ServiceLocation customerLocation = new ServiceLocation(order.getServiceCity(), order.getServiceStreet(),
                order.getServiceHouseNumber(), order.getServiceApartment());
        EtaResult eta = distanceEtaStrategy.calculate(professional.getCity(), customerLocation, now);
        Instant expectedArrivalAt = now.plus(Duration.ofMinutes(eta.etaMinutes()));

        int affected = orderRepository.onTheWayIfConfirmed(orderId, now, expectedArrivalAt);
        if (affected == 0) {
            throw orderNotConfirmed(orderId);
        }
        // issues.status is not touched -- stays BOOKED (§2.16 step 5).
        notificationService.recordOrderNotification(orderId, order.getCustomerId(),
                NotificationMessageType.ORDER_ON_THE_WAY);
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
        } else {
            Long professionalId = resolveProfessionalId(callerId);
            orders = status == null
                    ? orderRepository.findByProfessionalIdOrderByCreatedAtDesc(professionalId)
                    : orderRepository.findByProfessionalIdAndOrderStatusOrderByCreatedAtDesc(professionalId, status);
        }

        List<OrderSummaryResponse> summaries = orders.stream()
                .map(o -> new OrderSummaryResponse(o.getId(), o.getIssueId(), o.getOrderStatus(),
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

    private boolean isProfessionalActive(Professional professional) {
        return userRepository.findById(professional.getUserId())
                .map(u -> u.getDeletedAt() == null)
                .orElse(false);
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
     * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §9.1), and computes
     * distance/ETA via {@link DistanceEtaStrategy#calculate} using a single, uniform
     * {@code requestTime = Instant.now()} for the whole listing — applied unconditionally
     * regardless of {@code sort} (§7). When {@code sort == FASTEST}, the resulting list is
     * re-sorted by {@code etaMinutes} ascending; when {@code sort == RECOMMENDED}, by
     * {@code averageRating} descending (professionals with no reviews yet sort last), then
     * {@code reviewCount} descending as a tiebreak; {@code CHEAPEST} leaves the DB's
     * {@code base_price ASC} order untouched.
     */
    private List<ProfessionalCard> enrichAndSort(Long callerId, List<ProfessionalCard> cards,
                                                  ServiceLocation location, ProfessionalSort sort) {
        Instant requestTime = Instant.now();
        List<ProfessionalCard> enriched = cards.stream()
                .map(card -> {
                    String profileImageUrl = card.profileImageUrl() == null
                            ? null
                            : storageService.getPresignedUrl(callerId, card.profileImageUrl());
                    EtaResult eta = distanceEtaStrategy.calculate(card.city(), location, requestTime);
                    return new ProfessionalCard(card.professionalId(), card.fullName(), card.serviceArea(),
                            card.basePrice(), card.reliabilityScore(), card.city(), profileImageUrl,
                            card.averageRating(), card.reviewCount(), card.favorited(), eta.sameCity(),
                            eta.distanceKm(), eta.baseTravelTimeMinutes(), eta.trafficAdjustmentMinutes(),
                            eta.etaMinutes());
                })
                .toList();

        if (sort == ProfessionalSort.FASTEST) {
            return enriched.stream()
                    .sorted(Comparator.comparingInt(ProfessionalCard::etaMinutes))
                    .toList();
        }
        if (sort == ProfessionalSort.RECOMMENDED) {
            return enriched.stream()
                    .sorted(Comparator.comparing(ProfessionalCard::averageRating,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(Comparator.comparingLong(ProfessionalCard::reviewCount).reversed()))
                    .toList();
        }
        return enriched;
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
                "Order " + orderId + " is not in ON_THE_WAY status and cannot be completed.");
    }
}
