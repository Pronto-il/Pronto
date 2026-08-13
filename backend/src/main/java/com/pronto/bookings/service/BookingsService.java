package com.pronto.bookings.service;

import com.pronto.availability.entity.AvailabilitySlot;
import com.pronto.availability.entity.SosAvailability;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.bookings.dto.CreateOrderRequest;
import com.pronto.bookings.dto.CreateSosOrderRequest;
import com.pronto.bookings.dto.OrderDetailResponse;
import com.pronto.bookings.dto.OrderResponse;
import com.pronto.bookings.dto.OrderSummaryResponse;
import com.pronto.bookings.dto.OrdersListResponse;
import com.pronto.bookings.dto.ProfessionalCard;
import com.pronto.bookings.dto.ProfessionalListingResponse;
import com.pronto.bookings.dto.SlotListingResponse;
import com.pronto.bookings.dto.SlotSummary;
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
import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.service.NotificationService;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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

    private final IssueRepository issueRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalListingRepository professionalListingRepository;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final SosAvailabilityRepository sosAvailabilityRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public BookingsService(IssueRepository issueRepository,
                            ProfessionalRepository professionalRepository,
                            ProfessionalListingRepository professionalListingRepository,
                            AvailabilitySlotRepository availabilitySlotRepository,
                            SosAvailabilityRepository sosAvailabilityRepository,
                            OrderRepository orderRepository,
                            UserRepository userRepository,
                            NotificationService notificationService) {
        this.issueRepository = issueRepository;
        this.professionalRepository = professionalRepository;
        this.professionalListingRepository = professionalListingRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.sosAvailabilityRepository = sosAvailabilityRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    /** §2.2. */
    @Transactional(readOnly = true)
    public ProfessionalListingResponse listProfessionals(Long callerId, Long issueId) {
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
        List<ProfessionalCard> professionals = professionalListingRepository.listByCategory(issue.getCategoryId());
        return new ProfessionalListingResponse(issue.getId(), issue.getCategoryId(), professionals);
    }

    /** §2.3. */
    @Transactional(readOnly = true)
    public SlotListingResponse listSlots(Long callerId, Long professionalId, Long issueId) {
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

        Instant now = Instant.now();
        List<SlotSummary> slots = availabilitySlotRepository
                .findByProfessionalIdAndIsAvailableTrueAndStartTimeAfterOrderByStartTimeAsc(professionalId, now)
                .stream()
                .map(s -> new SlotSummary(s.getId(), s.getStartTime(), s.getEndTime()))
                .toList();
        return new SlotListingResponse(professionalId, slots);
    }

    /** §2.4. */
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

        // Step 9: atomically claim the slot.
        int claimed = availabilitySlotRepository.claimSlot(request.slotId(), professional.getId(), now);
        if (claimed == 0) {
            throw slotUnavailable(request.slotId());
        }
        AvailabilitySlot slot = availabilitySlotRepository.findById(request.slotId())
                .orElseThrow(() -> slotUnavailable(request.slotId()));

        // Step 10: atomically transition the issue; 0 rows rolls back the whole transaction,
        // including the slot claim above (single @Transactional method).
        int booked = issueRepository.bookIfOpen(issue.getId(), now);
        if (booked == 0) {
            throw notBookable(issue.getId());
        }

        // Step 11: insert the order.
        Order order = new Order(issue.getId(), callerId, professional.getId(), slot.getStartTime(),
                slot.getEndTime(), professional.getBasePrice(), slot.getId());
        order = orderRepository.save(order);

        notificationService.recordOrderNotification(order.getId(), professional.getUserId(),
                NotificationMessageType.ORDER_CREATED);
        return toOrderResponse(order);
    }

    /** §2.12. */
    @Transactional(readOnly = true)
    public ProfessionalListingResponse listSosProfessionals(Long callerId, Long issueId) {
        Issue issue = loadIssue(issueId);
        if (!issue.getCustomerId().equals(callerId)) {
            throw forbidden();
        }
        if (issue.getUrgencyType() != IssueUrgencyType.SOS) {
            throw urgencyMismatch(issue.getId());
        }
        if (issue.getStatus() != IssueStatus.OPEN) {
            throw notBookable(issue.getId());
        }
        List<ProfessionalCard> professionals =
                professionalListingRepository.listSosAvailableByCategory(issue.getCategoryId());
        return new ProfessionalListingResponse(issue.getId(), issue.getCategoryId(), professionals);
    }

    /** §2.13. */
    @Transactional
    public OrderResponse createSosOrder(Long callerId, CreateSosOrderRequest request) {
        Issue issue = issueRepository.findById(request.issueId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Issue " + request.issueId() + " not found."));
        if (!issue.getCustomerId().equals(callerId)) {
            throw forbidden();
        }
        if (issue.getUrgencyType() != IssueUrgencyType.SOS) {
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

        // Step 9: plain read-check of the professional's SOS availability -- not an atomic
        // claim (§2.13's "why a plain read-check" note; sos_availability is a live signal,
        // not a single-consumer resource like an availability_slots row).
        boolean available = sosAvailabilityRepository.findById(professional.getId())
                .map(SosAvailability::isAvailable)
                .orElse(false);
        if (!available) {
            throw sosProfessionalUnavailable(professional.getId());
        }

        Instant now = Instant.now();

        // Step 10: atomically transition the issue -- same bookIfOpen mechanism as §2.4 step 10.
        int booked = issueRepository.bookIfOpen(issue.getId(), now);
        if (booked == 0) {
            throw notBookable(issue.getId());
        }

        // Step 11: insert the order. bookedStart = now (request time), bookedEnd = null,
        // slotId = null -- SOS never consumes an availability_slots row.
        Order order = new Order(issue.getId(), callerId, professional.getId(), now, null,
                professional.getBasePrice(), null);
        order = orderRepository.save(order);

        notificationService.recordOrderNotification(order.getId(), professional.getUserId(),
                NotificationMessageType.ORDER_CREATED);
        return toOrderResponse(order);
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

    /** §2.16. */
    @Transactional
    public OrderResponse onTheWay(Long callerId, Long orderId) {
        Order order = loadOrder(orderId);
        Long professionalId = resolveProfessionalId(callerId);
        if (!order.getProfessionalId().equals(professionalId)) {
            throw forbidden();
        }

        Instant now = Instant.now();
        int affected = orderRepository.onTheWayIfConfirmed(orderId, now);
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
        // reached -- not branched on/checked, same as expireIfPending's call to expireIfBooked.
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

        String customerName = userRepository.findById(order.getCustomerId()).map(User::getFullName).orElse(null);
        String professionalName = resolveProfessionalName(order.getProfessionalId());

        return new OrderDetailResponse(order.getId(), order.getIssueId(), order.getCustomerId(), customerName,
                order.getProfessionalId(), professionalName, order.getOrderStatus(), order.getBookedStart(),
                order.getBookedEnd(), order.getFinalPrice(), order.getCancelledBy(), order.getCreatedAt(),
                order.getUpdatedAt());
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
                        o.getBookedStart(), o.getBookedEnd(), o.getFinalPrice(), o.getCreatedAt()))
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
        issueRepository.expireIfBooked(order.getIssueId(), now);
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

    private OrderResponse toOrderResponse(Order order) {
        return new OrderResponse(order.getId(), order.getIssueId(), order.getCustomerId(),
                order.getProfessionalId(), order.getOrderStatus(), order.getBookedStart(), order.getBookedEnd(),
                order.getFinalPrice(), order.getCancelledBy(), order.getCreatedAt(), order.getUpdatedAt());
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

    private ApiException sosProfessionalUnavailable(Long professionalId) {
        return new ApiException(ErrorCode.SOS_PROFESSIONAL_UNAVAILABLE,
                "Professional " + professionalId + " is not currently available for SOS work.");
    }

    private ApiException categoryMismatch() {
        return new ApiException(ErrorCode.CATEGORY_MISMATCH,
                "The professional's category does not match the issue's category.");
    }

    private ApiException slotUnavailable(Long slotId) {
        return new ApiException(ErrorCode.SLOT_UNAVAILABLE, "Slot " + slotId + " is not available.");
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
