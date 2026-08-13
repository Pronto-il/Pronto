package com.pronto.bookings.service;

import com.pronto.availability.entity.AvailabilitySlot;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.bookings.dto.CreateOrderRequest;
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
import com.pronto.issues.repository.IssueRepository;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * {@code /api/bookings/*} — Standard booking flow (professional listing, slot listing,
 * create/accept/reject/cancel, tracking, self-listing). See
 * {@code docs/architecture/api-contract-bookings.md} §2.2-2.9. Route-level role checks
 * ({@code CUSTOMER}-only / {@code PROFESSIONAL}-only routes) happen in
 * {@code bookings.config.BookingsWebConfig}; the either-role routes (cancel, get-by-id,
 * get-me) have no route-level gate and instead authorize entirely here, once the resource is
 * loaded (§0.1).
 */
@Service
public class BookingsService {

    private final IssueRepository issueRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalListingRepository professionalListingRepository;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public BookingsService(IssueRepository issueRepository,
                            ProfessionalRepository professionalRepository,
                            ProfessionalListingRepository professionalListingRepository,
                            AvailabilitySlotRepository availabilitySlotRepository,
                            OrderRepository orderRepository,
                            UserRepository userRepository) {
        this.issueRepository = issueRepository;
        this.professionalRepository = professionalRepository;
        this.professionalListingRepository = professionalListingRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    /** §2.2. */
    @Transactional(readOnly = true)
    public ProfessionalListingResponse listProfessionals(Long callerId, Long issueId) {
        Issue issue = loadIssue(issueId);
        if (!issue.getCustomerId().equals(callerId)) {
            throw forbidden();
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

        // Step 8: atomically claim the slot.
        int claimed = availabilitySlotRepository.claimSlot(request.slotId(), professional.getId(), now);
        if (claimed == 0) {
            throw slotUnavailable(request.slotId());
        }
        AvailabilitySlot slot = availabilitySlotRepository.findById(request.slotId())
                .orElseThrow(() -> slotUnavailable(request.slotId()));

        // Step 9: atomically transition the issue; 0 rows rolls back the whole transaction,
        // including the slot claim above (single @Transactional method).
        int booked = issueRepository.bookIfOpen(issue.getId(), now);
        if (booked == 0) {
            throw notBookable(issue.getId());
        }

        // Step 10: insert the order.
        Order order = new Order(issue.getId(), callerId, professional.getId(), slot.getStartTime(),
                slot.getEndTime(), professional.getBasePrice(), slot.getId());
        order = orderRepository.save(order);

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
}
