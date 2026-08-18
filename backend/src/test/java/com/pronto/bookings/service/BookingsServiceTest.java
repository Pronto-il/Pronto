package com.pronto.bookings.service;

import com.pronto.availability.entity.AvailabilitySlot;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.repository.SosAvailabilityRepository;
import com.pronto.bookings.dto.CreateOrderRequest;
import com.pronto.bookings.dto.CreateSosOrderRequest;
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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    private static final Long SLOT_ID = 5L;

    private IssueRepository issueRepository;
    private ProfessionalRepository professionalRepository;
    private ProfessionalListingRepository professionalListingRepository;
    private AvailabilitySlotRepository availabilitySlotRepository;
    private SosAvailabilityRepository sosAvailabilityRepository;
    private OrderRepository orderRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private DistanceEtaStrategy distanceEtaStrategy;
    private StorageService storageService;
    private BookingsService bookingsService;

    @BeforeEach
    void setUp() {
        issueRepository = Mockito.mock(IssueRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        professionalListingRepository = Mockito.mock(ProfessionalListingRepository.class);
        availabilitySlotRepository = Mockito.mock(AvailabilitySlotRepository.class);
        sosAvailabilityRepository = Mockito.mock(SosAvailabilityRepository.class);
        orderRepository = Mockito.mock(OrderRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        notificationService = Mockito.mock(NotificationService.class);
        distanceEtaStrategy = Mockito.mock(DistanceEtaStrategy.class);
        storageService = Mockito.mock(StorageService.class);
        bookingsService = new BookingsService(issueRepository, professionalRepository, professionalListingRepository,
                availabilitySlotRepository, sosAvailabilityRepository, orderRepository, userRepository,
                notificationService, distanceEtaStrategy, storageService);
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

    private AvailabilitySlot slot() {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        AvailabilitySlot slot = new AvailabilitySlot(PROFESSIONAL_ID, start, start.plus(2, ChronoUnit.HOURS));
        setField(slot, "id", SLOT_ID);
        return slot;
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
        when(availabilitySlotRepository.claimSlot(eq(SLOT_ID), eq(PROFESSIONAL_ID), any())).thenReturn(1);
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot()));
        when(issueRepository.bookIfOpen(eq(ISSUE_ID), any())).thenReturn(1);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateOrderRequest request = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, SLOT_ID,
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

    @Test
    void createSosOrder_hasSurchargeAndFinalPriceIncludesIt() {
        Issue issue = openIssue(IssueUrgencyType.SOS);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(99L)).thenReturn(Optional.of(activeUser(99L)));
        com.pronto.availability.entity.SosAvailability sosAvailability =
                new com.pronto.availability.entity.SosAvailability(PROFESSIONAL_ID);
        setField(sosAvailability, "isAvailable", true);
        when(sosAvailabilityRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(sosAvailability));
        when(issueRepository.bookIfOpen(eq(ISSUE_ID), any())).thenReturn(1);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateSosOrderRequest request = new CreateSosOrderRequest(ISSUE_ID, PROFESSIONAL_ID,
                "Haifa", "Ben Gurion", "5", "3B", null, null, null);
        OrderResponse response = bookingsService.createSosOrder(CUSTOMER_ID, request);

        assertThat(response.sosSurcharge()).isEqualByComparingTo("50.00");
        assertThat(response.basePriceSnapshot()).isEqualByComparingTo("100.00");
        assertThat(response.finalPrice()).isEqualByComparingTo("150.00");
        assertThat(response.serviceApartment()).isEqualTo("3B");
    }

    // ---- service-address snapshot independence ----

    @Test
    void twoOrders_forSameCustomer_carryIndependentServiceAddresses() {
        Issue issue = openIssue(IssueUrgencyType.STANDARD);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        Professional professional = activeProfessional();
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional));
        when(userRepository.findById(99L)).thenReturn(Optional.of(activeUser(99L)));
        when(availabilitySlotRepository.claimSlot(eq(SLOT_ID), eq(PROFESSIONAL_ID), any())).thenReturn(1);
        when(availabilitySlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot()));
        when(issueRepository.bookIfOpen(eq(ISSUE_ID), any())).thenReturn(1);
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        when(orderRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        CreateOrderRequest first = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, SLOT_ID,
                "Tel Aviv", "Herzl", "10", "2", null, null, null);
        bookingsService.createOrder(CUSTOMER_ID, first);
        CreateOrderRequest second = new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, SLOT_ID,
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
}
