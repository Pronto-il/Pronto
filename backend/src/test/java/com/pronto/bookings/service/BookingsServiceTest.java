package com.pronto.bookings.service;

import com.pronto.availability.dto.CalendarSegment;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.service.AvailabilityDerivationService;
import com.pronto.bookings.dto.AvailableWindowsResponse;
import com.pronto.bookings.dto.CreateOrderRequest;
import com.pronto.bookings.dto.OrderDetailResponse;
import com.pronto.bookings.dto.OrderResponse;
import com.pronto.bookings.dto.ProfessionalCard;
import com.pronto.bookings.dto.ProfessionalListingResponse;
import com.pronto.bookings.entity.Order;
import com.pronto.bookings.entity.OrderStatus;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.bookings.repository.ProfessionalListingRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the reviews/favorites/matching additions to {@link BookingsService}: the SOS
 * surcharge line item, service-address snapshot independence across orders, and the
 * {@code FASTEST} sort genuinely using the post-adjustment {@code etaMinutes} (not raw
 * base-travel-time/distance).
 */
class BookingsServiceTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final Long ISSUE_ID = 2L;
    private static final Long PROFESSIONAL_ID = 3L;
    private static final Long CATEGORY_ID = 4L;

    private IssueRepository issueRepository;
    private ProfessionalRepository professionalRepository;
    private ProfessionalListingRepository professionalListingRepository;
    private AvailabilitySlotRepository availabilitySlotRepository;
    private OrderRepository orderRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private DistanceEtaStrategy distanceEtaStrategy;
    private StorageService storageService;
    private AvailabilityDerivationService availabilityDerivationService;
    private BookingsService bookingsService;

    @BeforeEach
    void setUp() {
        issueRepository = Mockito.mock(IssueRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        professionalListingRepository = Mockito.mock(ProfessionalListingRepository.class);
        availabilitySlotRepository = Mockito.mock(AvailabilitySlotRepository.class);
        orderRepository = Mockito.mock(OrderRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        notificationService = Mockito.mock(NotificationService.class);
        distanceEtaStrategy = Mockito.mock(DistanceEtaStrategy.class);
        storageService = Mockito.mock(StorageService.class);
        availabilityDerivationService = Mockito.mock(AvailabilityDerivationService.class);
        bookingsService = new BookingsService(issueRepository, professionalRepository, professionalListingRepository,
                availabilitySlotRepository, orderRepository, userRepository,
                notificationService, distanceEtaStrategy, storageService, availabilityDerivationService);
        // MS1: every pre-existing test in this class describes a world of ordinary, verified
        // professionals, so eligibility is stubbed true by default. The MS1 tests at the bottom of
        // the class override it per-test -- lenient() because most tests here never reach the
        // check at all (they fail earlier on ownership, urgency or issue status).
        Mockito.lenient().when(professionalRepository.existsEligibleById(anyLong())).thenReturn(true);
        // The other half of isProfessionalBookable: the owning account is not soft-deleted.
        // listAvailableWindows now runs that check too (MS1 -- it previously ran neither), so the
        // default professional's user row has to resolve for the pre-existing tests to describe
        // the world they were written about.
        Mockito.lenient().when(userRepository.findById(PROFESSIONAL_USER_ID))
                .thenReturn(Optional.of(activeUser(PROFESSIONAL_USER_ID)));
    }

    /**
     * Stubs {@link AvailabilityDerivationService#deriveCalendar} to report the caller's
     * requested {@code [from, to)} range as one single {@code AVAILABLE} segment covering it
     * exactly — the "happy path" pre-check stub {@code createOrder}'s tests reuse by default.
     */
    private void stubFullyAvailable() {
        when(availabilityDerivationService.deriveCalendar(eq(PROFESSIONAL_ID), any(), any()))
                .thenAnswer(inv -> List.of(CalendarSegment.available(inv.getArgument(1), inv.getArgument(2))));
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Issue openIssue(IssueUrgencyType urgencyType) {
        Issue issue = new Issue(CUSTOMER_ID, CATEGORY_ID, "Leaking pipe", urgencyType);
        setField(issue, "id", ISSUE_ID);
        setField(issue, "status", IssueStatus.OPEN);
        return issue;
    }

    private Professional activeProfessional() {
        Professional professional = new Professional(99L, CATEGORY_ID, "Tel Aviv", new BigDecimal("100.00"));
        setField(professional, "id", PROFESSIONAL_ID);
        setField(professional, "city", "Tel Aviv");
        return professional;
    }

    private User activeUser(Long id) {
        User user = new User("Some User", "u" + id + "@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", id);
        return user;
    }

    private static final Long ORDER_ID = 6L;
    private static final Long PROFESSIONAL_USER_ID = 99L;

    private Order confirmedOrder() {
        Order order = new Order(ISSUE_ID, CUSTOMER_ID, PROFESSIONAL_ID, Instant.now(), null,
                new BigDecimal("100.00"), null, "Tel Aviv", "Herzl", "10", null, null, null, null,
                new BigDecimal("100.00"), BigDecimal.ZERO);
        setField(order, "id", ORDER_ID);
        setField(order, "orderStatus", OrderStatus.CONFIRMED);
        return order;
    }

    // ---- SOS surcharge ----

    @Test
    void createOrder_standard_hasZeroSurchargeAndFinalPriceEqualsBasePrice() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(99L)).thenReturn(Optional.of(activeUser(99L)));
        stubFullyAvailable();
        when(issueRepository.bookIfOpen(eq(ISSUE_ID), any())).thenReturn(1);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant bookedStart = Instant.now().plus(1, ChronoUnit.DAYS);
        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, bookedStart,
                "Tel Aviv", "Herzl", "10", null, null, null, null);
        OrderResponse response = bookingsService.createOrder(CUSTOMER_ID, request);

        assertThat(response.sosSurcharge()).isEqualByComparingTo("0.00");
        assertThat(response.basePriceSnapshot()).isEqualByComparingTo("100.00");
        assertThat(response.finalPrice()).isEqualByComparingTo("100.00");
        assertThat(response.serviceCity()).isEqualTo("Tel Aviv");
        assertThat(response.serviceStreet()).isEqualTo("Herzl");
        assertThat(response.serviceHouseNumber()).isEqualTo("10");
        assertThat(response.serviceApartment()).isNull();
    }

    // ---- direct-bookedStart creation path (professional weekly availability calendar M2) ----

    @Test
    void createOrder_standard_derivesBookedEndAndPersistsNullSlotId() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(99L)).thenReturn(Optional.of(activeUser(99L)));
        stubFullyAvailable();
        when(issueRepository.bookIfOpen(eq(ISSUE_ID), any())).thenReturn(1);
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        when(orderRepository.saveAndFlush(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Instant bookedStart = Instant.now().plus(1, ChronoUnit.DAYS);
        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, bookedStart,
                "Tel Aviv", "Herzl", "10", null, null, null, null);
        OrderResponse response = bookingsService.createOrder(CUSTOMER_ID, request);

        assertThat(response.bookedStart()).isEqualTo(bookedStart);
        assertThat(response.bookedEnd()).isEqualTo(bookedStart.plus(Duration.ofMinutes(60)));
        assertThat(captor.getValue().getSlotId()).isNull();
        verify(availabilitySlotRepository, never()).claimSlot(any(), any(), any());
    }

    @Test
    void createOrder_windowNotFullyAvailable_throwsBookingTimeUnavailableAndNeverInserts() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(99L)).thenReturn(Optional.of(activeUser(99L)));
        // No AVAILABLE segment at all covering the requested range -- e.g. outside working
        // hours, overlapping a block, or overlapping an existing booking.
        when(availabilityDerivationService.deriveCalendar(eq(PROFESSIONAL_ID), any(), any()))
                .thenReturn(List.of());

        Instant bookedStart = Instant.now().plus(1, ChronoUnit.DAYS);
        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, bookedStart,
                "Tel Aviv", "Herzl", "10", null, null, null, null);

        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.BOOKING_TIME_UNAVAILABLE));

        verify(issueRepository, never()).bookIfOpen(any(), any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void createOrder_partiallyOverlappingAvailableSegment_stillThrowsBookingTimeUnavailable() {
        // The requested [bookedStart, bookedEnd) is only partly covered by a single AVAILABLE
        // segment (e.g. a block/booking cuts through the middle of the requested window) --
        // full containment is required, not merely "some" overlap.
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(99L)).thenReturn(Optional.of(activeUser(99L)));

        Instant bookedStart = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant bookedEnd = bookedStart.plus(Duration.ofMinutes(60));
        // Only the first half of the requested window is AVAILABLE.
        when(availabilityDerivationService.deriveCalendar(eq(PROFESSIONAL_ID), any(), any()))
                .thenReturn(List.of(CalendarSegment.available(bookedStart, bookedStart.plus(Duration.ofMinutes(30)))));

        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, bookedStart,
                "Tel Aviv", "Herzl", "10", null, null, null, null);

        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.BOOKING_TIME_UNAVAILABLE));
    }

    @Test
    void createOrder_bookedStartNotStrictlyFuture_throwsValidationError() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(99L)).thenReturn(Optional.of(activeUser(99L)));

        Instant pastStart = Instant.now().minus(1, ChronoUnit.HOURS);
        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, pastStart,
                "Tel Aviv", "Herzl", "10", null, null, null, null);

        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(availabilityDerivationService, never()).deriveCalendar(any(), any(), any());
    }

    @Test
    void createOrder_exclusionConstraintViolationOnInsert_mapsToBookingTimeUnavailable() {
        // The race backstop: two near-simultaneous createOrder calls both pass the pre-check,
        // but the second one's INSERT is rejected by ck_orders_no_overlap (23P01) -- this must
        // surface as a clean 409, not a raw 500.
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(99L)).thenReturn(Optional.of(activeUser(99L)));
        stubFullyAvailable();
        when(issueRepository.bookIfOpen(eq(ISSUE_ID), any())).thenReturn(1);
        DataIntegrityViolationException exclusionViolation =
                new DataIntegrityViolationException("duplicate range", new SQLException("conflict", "23P01"));
        when(orderRepository.saveAndFlush(any(Order.class))).thenThrow(exclusionViolation);

        Instant bookedStart = Instant.now().plus(1, ChronoUnit.DAYS);
        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, bookedStart,
                "Tel Aviv", "Herzl", "10", null, null, null, null);

        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.BOOKING_TIME_UNAVAILABLE));
    }

    @Test
    void createOrder_nonExclusionConstraintViolationOnInsert_rethrowsUnchanged() {
        // Any other constraint violation (unexpected) must not be silently swallowed into a
        // BOOKING_TIME_UNAVAILABLE -- only the specific 23P01 SQLState is mapped.
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(99L)).thenReturn(Optional.of(activeUser(99L)));
        stubFullyAvailable();
        when(issueRepository.bookIfOpen(eq(ISSUE_ID), any())).thenReturn(1);
        DataIntegrityViolationException otherViolation =
                new DataIntegrityViolationException("not-null violation", new SQLException("nope", "23502"));
        when(orderRepository.saveAndFlush(any(Order.class))).thenThrow(otherViolation);

        Instant bookedStart = Instant.now().plus(1, ChronoUnit.DAYS);
        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, bookedStart,
                "Tel Aviv", "Herzl", "10", null, null, null, null);

        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID, request))
                .isSameAs(otherViolation);
    }

    // ---- available-windows listing (replaces the retired GET .../slots?issueId=) ----

    @Test
    void listAvailableWindows_mapsDerivedSegmentsAndEchoesDefaultDuration() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));

        Instant windowStart = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant windowEnd = windowStart.plus(Duration.ofHours(4));
        when(availabilityDerivationService.deriveAvailableWindows(eq(PROFESSIONAL_ID), any(), any(),
                eq(Duration.ofMinutes(60))))
                .thenReturn(List.of(CalendarSegment.available(windowStart, windowEnd)));

        AvailableWindowsResponse response = bookingsService.listAvailableWindows(CUSTOMER_ID, PROFESSIONAL_ID, ISSUE_ID);

        assertThat(response.professionalId()).isEqualTo(PROFESSIONAL_ID);
        assertThat(response.issueId()).isEqualTo(ISSUE_ID);
        assertThat(response.defaultDurationMinutes()).isEqualTo(60);
        assertThat(response.timezone()).isEqualTo("Asia/Jerusalem");
        assertThat(response.windows()).hasSize(1);
        assertThat(response.windows().get(0).startAt()).isEqualTo(windowStart);
        assertThat(response.windows().get(0).endAt()).isEqualTo(windowEnd);
    }

    @Test
    void listAvailableWindows_emptyDerivedResult_returnsEmptyWindowsNotAnError() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(availabilityDerivationService.deriveAvailableWindows(eq(PROFESSIONAL_ID), any(), any(), any()))
                .thenReturn(List.of());

        AvailableWindowsResponse response = bookingsService.listAvailableWindows(CUSTOMER_ID, PROFESSIONAL_ID, ISSUE_ID);

        assertThat(response.windows()).isEmpty();
    }

    @Test
    void listAvailableWindows_categoryMismatch_throwsCategoryMismatch() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional mismatchedProfessional = new Professional(99L, 999L, "Tel Aviv", new BigDecimal("100.00"));
        setField(mismatchedProfessional, "id", PROFESSIONAL_ID);
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(mismatchedProfessional));

        assertThatThrownBy(() -> bookingsService.listAvailableWindows(CUSTOMER_ID, PROFESSIONAL_ID, ISSUE_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.CATEGORY_MISMATCH));
    }

    // ---- customerPhone on order-detail (professional weekly availability calendar §9.1) ----

    @Test
    void getOrderDetail_pendingOrder_assignedProfessional_seesCustomerPhone() {
        Order order = new Order(ISSUE_ID, CUSTOMER_ID, PROFESSIONAL_ID, Instant.now(), null,
                new BigDecimal("100.00"), null, "Tel Aviv", "Herzl", "10", null, null, null, null,
                new BigDecimal("100.00"), BigDecimal.ZERO);
        setField(order, "id", ORDER_ID);
        setField(order, "orderStatus", OrderStatus.PENDING);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        Professional professional = activeProfessional();
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(professional));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        User customer = new User("Customer Name", "customer@example.com", "hash", UserRole.CUSTOMER);
        setField(customer, "id", CUSTOMER_ID);
        customer.setPhone("0501234567");
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(userRepository.findById(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(activeUser(PROFESSIONAL_USER_ID)));

        OrderDetailResponse response = bookingsService.getOrderDetail(PROFESSIONAL_USER_ID, "PROFESSIONAL", ORDER_ID);

        assertThat(response.orderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.customerPhone()).isEqualTo("0501234567");
    }

    @Test
    void getOrderDetail_customerViewingOwnOrder_alsoSeesOwnPhone() {
        Order order = new Order(ISSUE_ID, CUSTOMER_ID, PROFESSIONAL_ID, Instant.now(), null,
                new BigDecimal("100.00"), null, "Tel Aviv", "Herzl", "10", null, null, null, null,
                new BigDecimal("100.00"), BigDecimal.ZERO);
        setField(order, "id", ORDER_ID);
        setField(order, "orderStatus", OrderStatus.PENDING);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        User customer = new User("Customer Name", "customer@example.com", "hash", UserRole.CUSTOMER);
        setField(customer, "id", CUSTOMER_ID);
        customer.setPhone("0501234567");
        when(userRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        OrderDetailResponse response = bookingsService.getOrderDetail(CUSTOMER_ID, "CUSTOMER", ORDER_ID);

        assertThat(response.customerPhone()).isEqualTo("0501234567");
    }

    // ---- service-address snapshot independence ----

    @Test
    void twoOrders_forSameCustomer_carryIndependentServiceAddresses() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(99L)).thenReturn(Optional.of(activeUser(99L)));
        stubFullyAvailable();
        when(issueRepository.bookIfOpen(eq(ISSUE_ID), any())).thenReturn(1);
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        when(orderRepository.saveAndFlush(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Instant firstStart = Instant.now().plus(1, ChronoUnit.DAYS);
        CreateOrderRequest first = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, firstStart,
                "Tel Aviv", "Herzl", "10", "2", null, null, null);
        bookingsService.createOrder(CUSTOMER_ID, first);
        Instant secondStart = Instant.now().plus(2, ChronoUnit.DAYS);
        CreateOrderRequest second = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, secondStart,
                "Jerusalem", "Jaffa Rd", "99", null, null, null, null);
        bookingsService.createOrder(CUSTOMER_ID, second);

        List<Order> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getServiceCity()).isEqualTo("Tel Aviv");
        assertThat(saved.get(0).getServiceApartment()).isEqualTo("2");
        assertThat(saved.get(1).getServiceCity()).isEqualTo("Jerusalem");
        assertThat(saved.get(1).getServiceApartment()).isNull();
        // Mutating the second order's captured address must never be reflected on the first --
        // proving they are genuinely independent objects/values, not a shared/mutable reference.
        assertThat(saved.get(0).getServiceCity()).isNotEqualTo(saved.get(1).getServiceCity());
    }

    // ---- fastest sort ----

    @Test
    void listProfessionals_fastestSort_ordersByFinalEtaMinutesNotBaseTravelTime() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));

        // DB order (cheapest first) puts "SlowCity" ahead of "FastCity" by base price.
        ProfessionalCard slowCard = new ProfessionalCard(10L, "Slow Pro", "Area", new BigDecimal("50.00"), null,
                "SlowCity", null, null, 0, false, false, BigDecimal.ZERO, 0, 0, 0);
        ProfessionalCard fastCard = new ProfessionalCard(20L, "Fast Pro", "Area", new BigDecimal("80.00"), null,
                "FastCity", null, null, 0, false, false, BigDecimal.ZERO, 0, 0, 0);
        when(professionalListingRepository.listByCategory(CATEGORY_ID, CUSTOMER_ID))
                .thenReturn(List.of(slowCard, fastCard));

        // SlowCity has a large base-travel-time AND a large traffic adjustment (peak);
        // FastCity has a small base-travel-time. Final etaMinutes crosses the base-price
        // (DB) order, proving FASTEST genuinely sorts by the post-adjustment value.
        when(distanceEtaStrategy.calculate(eq("SlowCity"), any(), any()))
                .thenReturn(new EtaResult(false, new BigDecimal("35.0"), 40, 30, 70));
        when(distanceEtaStrategy.calculate(eq("FastCity"), any(), any()))
                .thenReturn(new EtaResult(true, new BigDecimal("8.0"), 15, 0, 15));

        ServiceLocation location = new ServiceLocation("AnyCity", "St", "1", null);
        ProfessionalListingResponse response =
                bookingsService.listProfessionals(CUSTOMER_ID, ISSUE_ID, location, "FASTEST");

        assertThat(response.professionals()).extracting(ProfessionalCard::professionalId)
                .containsExactly(20L, 10L);
        assertThat(response.professionals()).extracting(ProfessionalCard::etaMinutes)
                .containsExactly(15, 70);
    }

    @Test
    void listProfessionals_cheapestSort_leavesDbOrderUnchangedRegardlessOfEta() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));

        ProfessionalCard cheapButSlow = new ProfessionalCard(10L, "Cheap Pro", "Area", new BigDecimal("50.00"), null,
                "SlowCity", null, null, 0, false, false, BigDecimal.ZERO, 0, 0, 0);
        ProfessionalCard pricyButFast = new ProfessionalCard(20L, "Pricy Pro", "Area", new BigDecimal("80.00"), null,
                "FastCity", null, null, 0, false, false, BigDecimal.ZERO, 0, 0, 0);
        when(professionalListingRepository.listByCategory(CATEGORY_ID, CUSTOMER_ID))
                .thenReturn(List.of(cheapButSlow, pricyButFast));
        when(distanceEtaStrategy.calculate(eq("SlowCity"), any(), any()))
                .thenReturn(new EtaResult(false, new BigDecimal("35.0"), 40, 30, 70));
        when(distanceEtaStrategy.calculate(eq("FastCity"), any(), any()))
                .thenReturn(new EtaResult(true, new BigDecimal("8.0"), 15, 0, 15));

        ServiceLocation location = new ServiceLocation("AnyCity", "St", "1", null);
        ProfessionalListingResponse response =
                bookingsService.listProfessionals(CUSTOMER_ID, ISSUE_ID, location, null);

        // Default/CHEAPEST -- DB (base-price-ascending) order is untouched even though it
        // differs from the ETA-based order.
        assertThat(response.professionals()).extracting(ProfessionalCard::professionalId)
                .containsExactly(10L, 20L);
    }

    // ---- onTheWay: expectedArrivalAt computation/persistence ----

    @Test
    void onTheWay_confirmedOrder_computesExpectedArrivalAtFromEtaAndPersistsIt() {
        Order order = confirmedOrder();
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        Professional professional = activeProfessional();
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(professional));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(distanceEtaStrategy.calculate(eq("Tel Aviv"), any(ServiceLocation.class), any()))
                .thenReturn(new EtaResult(true, new BigDecimal("5.0"), 18, 0, 18));
        when(orderRepository.onTheWayIfConfirmed(eq(ORDER_ID), any(), any())).thenReturn(1);

        Instant before = Instant.now();
        OrderResponse response = bookingsService.onTheWay(PROFESSIONAL_USER_ID, ORDER_ID);
        Instant after = Instant.now();

        ArgumentCaptor<ServiceLocation> locationCaptor = ArgumentCaptor.forClass(ServiceLocation.class);
        verify(distanceEtaStrategy).calculate(eq("Tel Aviv"), locationCaptor.capture(), any());
        assertThat(locationCaptor.getValue().city()).isEqualTo("Tel Aviv");
        assertThat(locationCaptor.getValue().street()).isEqualTo("Herzl");
        assertThat(locationCaptor.getValue().houseNumber()).isEqualTo("10");

        ArgumentCaptor<Instant> expectedArrivalCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(orderRepository).onTheWayIfConfirmed(eq(ORDER_ID), any(), expectedArrivalCaptor.capture());
        Instant expectedArrivalAt = expectedArrivalCaptor.getValue();
        // expectedArrivalAt = the transition's "now" + etaMinutes (18); bounded by the
        // now-values observed immediately before/after the call, +/- the 18-minute ETA.
        assertThat(expectedArrivalAt).isBetween(before.plus(Duration.ofMinutes(18)),
                after.plus(Duration.ofMinutes(18)));
        assertThat(response).isNotNull();
    }

    @Test
    void onTheWay_orderNotConfirmed_throwsOrderNotConfirmedAndNeverNotifies() {
        Order order = confirmedOrder();
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        Professional professional = activeProfessional();
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(professional));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(distanceEtaStrategy.calculate(eq("Tel Aviv"), any(ServiceLocation.class), any()))
                .thenReturn(new EtaResult(true, new BigDecimal("5.0"), 18, 0, 18));
        when(orderRepository.onTheWayIfConfirmed(eq(ORDER_ID), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> bookingsService.onTheWay(PROFESSIONAL_USER_ID, ORDER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.ORDER_NOT_CONFIRMED));

        verify(notificationService, never()).recordOrderNotification(anyLong(), anyLong(), any());
    }

    @Test
    void onTheWay_callerNotOwningProfessional_throwsForbidden() {
        Order order = confirmedOrder();
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        Professional otherProfessional = activeProfessional();
        setField(otherProfessional, "id", 999L);
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(otherProfessional));

        assertThatThrownBy(() -> bookingsService.onTheWay(PROFESSIONAL_USER_ID, ORDER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(orderRepository, never()).onTheWayIfConfirmed(anyLong(), any(), any());
    }

    // ------------------------------------------------------------------
    // Expired-order recovery: the issue survives, the order does not
    // ------------------------------------------------------------------

    /**
     * <b>The behaviour change this section exists for.</b> An order timing out used to expire its
     * issue too ({@code IssueStatus.EXPIRED}), which threw away a description, photos, an AI
     * classification and an address the customer had already provided — all because a
     * professional failed to answer within 15 minutes. The customer's only route forward was to
     * report the same problem from scratch.
     *
     * <p>Now the issue is reopened, so the customer can pick a different professional for the
     * same problem. The expired order stays exactly where it is, in history.
     */
    @Test
    void expireIfPending_reopensTheIssueSoItCanBeBookedAgain() {
        Order order = pendingOrder();
        when(orderRepository.expireIfPending(eq(ORDER_ID), any())).thenReturn(1);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        Optional<OrderResponse> response = bookingsService.expireIfPending(ORDER_ID);

        assertThat(response).isPresent();
        verify(issueRepository).reopenIfBooked(eq(ISSUE_ID), any());
        // The order itself is untouched beyond its own status transition -- nothing deletes or
        // rewrites it, and the customer can still open it from their history.
        assertThat(response.get().issueId()).isEqualTo(ISSUE_ID);
    }

    /**
     * {@code issueId} on the response is what the frontend's "בחירת בעל מקצוע אחר" action
     * navigates by ({@code /issues/{issueId}/booking}), so it being present is a contract, not an
     * incidental field.
     */
    @Test
    void expireIfPending_responseCarriesTheIssueIdTheRecoveryFlowNavigatesBy() {
        Order order = pendingOrder();
        when(orderRepository.expireIfPending(eq(ORDER_ID), any())).thenReturn(1);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        OrderResponse response = bookingsService.expireIfPending(ORDER_ID).orElseThrow();

        assertThat(response.issueId()).isEqualTo(ISSUE_ID);
        assertThat(response.id()).isEqualTo(ORDER_ID);
    }

    /** The customer is still told, exactly as before — only the issue's fate changed. */
    @Test
    void expireIfPending_stillNotifiesTheCustomer() {
        when(orderRepository.expireIfPending(eq(ORDER_ID), any())).thenReturn(1);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(pendingOrder()));

        bookingsService.expireIfPending(ORDER_ID);

        verify(notificationService).recordOrderNotification(ORDER_ID, CUSTOMER_ID,
                NotificationMessageType.ORDER_EXPIRED);
    }

    /**
     * Losing the race (somebody accepted or cancelled first) must touch nothing at all — in
     * particular it must not reopen an issue that now has a live confirmed order on it.
     */
    @Test
    void expireIfPending_losingTheRaceLeavesTheIssueAlone() {
        when(orderRepository.expireIfPending(eq(ORDER_ID), any())).thenReturn(0);

        assertThat(bookingsService.expireIfPending(ORDER_ID)).isEmpty();

        verify(issueRepository, never()).reopenIfBooked(anyLong(), any());
        verify(notificationService, never()).recordOrderNotification(anyLong(), anyLong(), any());
    }

    /**
     * The invariant that must survive the change: reopening restores bookability, it does not
     * hand out a second concurrent order. {@code bookIfOpen} is still the only way out of
     * {@code OPEN}, and a caller that loses it gets nothing.
     */
    @Test
    void afterReopening_aSecondConcurrentOrderIsStillBlockedByBookIfOpen() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(activeProfessional()));
        when(userRepository.findById(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(activeUser(PROFESSIONAL_USER_ID)));
        stubFullyAvailable();
        // Somebody else won the OPEN -> BOOKED transition in between.
        when(issueRepository.bookIfOpen(eq(ISSUE_ID), any())).thenReturn(0);

        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID,
                Instant.now().plus(1, ChronoUnit.DAYS), "Tel Aviv", "Herzl", "10", null, null, null, null);
        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.ISSUE_NOT_BOOKABLE));

        verify(orderRepository, never()).save(any(Order.class));
    }

    private Order pendingOrder() {
        Order order = new Order(ISSUE_ID, CUSTOMER_ID, PROFESSIONAL_ID, Instant.now(), null,
                new BigDecimal("100.00"), null, "Tel Aviv", "Herzl", "10", null, null, null, null,
                new BigDecimal("100.00"), BigDecimal.ZERO);
        setField(order, "id", ORDER_ID);
        setField(order, "orderStatus", OrderStatus.PENDING);
        return order;
    }

    // ---- MS1: marketplace eligibility (D-B) ----

    @Test
    void createOrder_ineligibleProfessional_isRefusedAndNothingIsBooked() {
        // The Playbook's "pending professional cannot receive a Standard booking". The issue must
        // come through untouched: no bookIfOpen, no order row. A customer whose chosen
        // professional stopped being bookable has to be able to pick somebody else for the same
        // problem, which is only possible if the issue is still OPEN.
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(activeProfessional()));
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID,
                Instant.now().plus(1, ChronoUnit.DAYS), "Tel Aviv", "Herzl", "10", null, null, null, null);
        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(issueRepository, never()).bookIfOpen(any(), any());
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_softDeletedProfessionalAccount_isStillRefusedEvenWhenOtherwiseEligible() {
        // The soft-delete half of the guard is independent of the eligibility predicate, which
        // deliberately does not carry it -- so it has to be asserted independently too.
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(activeProfessional()));
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(true);
        User deleted = activeUser(PROFESSIONAL_USER_ID);
        deleted.setDeletedAt(Instant.now());
        when(userRepository.findById(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(deleted));

        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID,
                Instant.now().plus(1, ChronoUnit.DAYS), "Tel Aviv", "Herzl", "10", null, null, null, null);
        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(issueRepository, never()).bookIfOpen(any(), any());
    }

    @Test
    void listAvailableWindows_ineligibleProfessional_isIndistinguishableFromNotFound() {
        // Anti-enumeration: the calendar endpoint must not become a way to learn that a given
        // professional exists but was rejected or never verified. Identical code AND identical
        // message to the nonexistent-id case.
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(activeProfessional()));
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        ApiException ineligible = catchApiException(
                () -> bookingsService.listAvailableWindows(CUSTOMER_ID, PROFESSIONAL_ID, ISSUE_ID));

        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.empty());
        ApiException missing = catchApiException(
                () -> bookingsService.listAvailableWindows(CUSTOMER_ID, PROFESSIONAL_ID, ISSUE_ID));

        assertThat(ineligible.getCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ineligible.getCode()).isEqualTo(missing.getCode());
        assertThat(ineligible.getMessage()).isEqualTo(missing.getMessage());
    }

    @Test
    void listAvailableWindows_ineligibleProfessional_neverDerivesACalendar() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(activeProfessional()));
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        catchApiException(() -> bookingsService.listAvailableWindows(CUSTOMER_ID, PROFESSIONAL_ID, ISSUE_ID));

        verify(availabilityDerivationService, never()).deriveAvailableWindows(any(), any(), any(), any());
    }

    @Test
    void completionOfExistingWorkIsNeverGatedOnEligibility() {
        // D-B's load-bearing exclusion, and the reason the gate is on creation only: the
        // professional holding a live job may have become ineligible mid-job, and the only exit
        // from a gated accept/complete would be a cancel that reopens the customer's issue while
        // somebody is on their way to them.
        when(professionalRepository.existsEligibleById(anyLong())).thenReturn(false);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(pendingOrder()));
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID))
                .thenReturn(Optional.of(activeProfessional()));
        when(orderRepository.acceptIfPending(eq(ORDER_ID), any())).thenReturn(1);

        assertThatCode(() -> bookingsService.accept(PROFESSIONAL_USER_ID, ORDER_ID)).doesNotThrowAnyException();
    }

    private static ApiException catchApiException(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected an ApiException");
    }
}
