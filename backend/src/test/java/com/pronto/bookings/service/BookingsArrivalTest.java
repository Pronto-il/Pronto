package com.pronto.bookings.service;

import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.service.AvailabilityDerivationService;
import com.pronto.bookings.dto.ArrivalRequest;
import com.pronto.bookings.entity.Order;
import com.pronto.bookings.entity.OrderStatus;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.bookings.repository.ProfessionalListingRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.maps.GeoCoordinates;
import com.pronto.maps.config.LocationProperties;
import com.pronto.maps.service.ArrivalVerifier;
import com.pronto.maps.service.ServiceAddressGeocoder;
import com.pronto.matching.DistanceEtaStrategy;
import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.service.NotificationService;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.professionals.service.ProfessionalLocationService;
import com.pronto.storage.service.StorageService;
import com.pronto.users.repository.UserRepository;
import com.pronto.users.service.ContactVerificationGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * <b>Verified arrival</b> — the {@code ON_THE_WAY -> ARRIVED} transition and its geofence.
 *
 * <p>This is the only status change in the platform gated on a physical fact, so the tests are
 * written around the ways that gate could be wrong rather than around the happy path: a fix that is
 * old, a fix that is imprecise, a position that is not there, an order with no destination, and a
 * second claim racing the first. Every one of them must leave the order in {@code ON_THE_WAY}.
 *
 * <p>The customer's coordinates are used for the comparison and never returned — asserted below,
 * because a refusal that reports how far off you are is a refusal that can be used to search for
 * the address.
 */
class BookingsArrivalTest {

    private static final Long ORDER_ID = 500L;
    private static final Long CUSTOMER_ID = 1L;
    private static final Long PROFESSIONAL_ID = 3L;
    private static final Long PROFESSIONAL_USER_ID = 33L;

    /** Dizengoff 10, Tel Aviv — the order's immutable destination snapshot. */
    private static final BigDecimal DEST_LAT = new BigDecimal("32.077000");
    private static final BigDecimal DEST_LON = new BigDecimal("34.773900");

    private OrderRepository orderRepository;
    private ProfessionalRepository professionalRepository;
    private NotificationService notificationService;
    private ProfessionalLocationService professionalLocationService;
    private LocationProperties locationProperties;
    private BookingsService service;

    @BeforeEach
    void setUp() {
        orderRepository = Mockito.mock(OrderRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        notificationService = Mockito.mock(NotificationService.class);
        locationProperties = new LocationProperties();
        // The real policy object, not a mock: freshness/accuracy/skew validation is exactly what
        // this endpoint delegates, and stubbing it would test the delegation rather than the rule.
        professionalLocationService = new ProfessionalLocationService(
                Mockito.mock(com.pronto.professionals.repository.ProfessionalLocationRepository.class),
                locationProperties);

        service = new BookingsService(Mockito.mock(IssueRepository.class), professionalRepository,
                Mockito.mock(ProfessionalListingRepository.class),
                Mockito.mock(com.pronto.locations.service.ServiceCityResolver.class),
                Mockito.mock(AvailabilitySlotRepository.class), orderRepository,
                Mockito.mock(UserRepository.class), notificationService,
                Mockito.mock(DistanceEtaStrategy.class), Mockito.mock(StorageService.class),
                Mockito.mock(AvailabilityDerivationService.class),
                Mockito.mock(ProfessionalCoverageService.class),
                Mockito.mock(ContactVerificationGuard.class),
                Mockito.mock(ServiceAddressGeocoder.class), professionalLocationService, locationProperties,
                // The real verifier, not a mock: the geofence rule IS what this class tests, and
                // stubbing it would leave the tests asserting that a mock was called.
                new ArrivalVerifier(professionalLocationService, locationProperties),
                new com.pronto.maps.service.SelectedPlaceValidator(),
                Mockito.mock(com.pronto.professionals.repository.CategoryRepository.class),
                new com.pronto.bookings.config.BookingProperties());

        Professional professional = Mockito.mock(Professional.class);
        Mockito.lenient().when(professional.getId()).thenReturn(PROFESSIONAL_ID);
        Mockito.lenient().when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID))
                .thenReturn(Optional.of(professional));
        Mockito.lenient().when(orderRepository.arrivedIfOnTheWay(anyLong(), any())).thenReturn(1);
        Mockito.lenient().when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Order onTheWayOrder(BigDecimal destinationLatitude, BigDecimal destinationLongitude) {
        Order order = new Order(2L, CUSTOMER_ID, PROFESSIONAL_ID, Instant.now(), null, BigDecimal.TEN, null,
                "תל אביב", "דיזנגוף", "10", null, null, null, null, BigDecimal.TEN, BigDecimal.ZERO);
        setField(order, "id", ORDER_ID);
        setField(order, "orderStatus", OrderStatus.ON_THE_WAY);
        if (destinationLatitude != null) {
            order.snapshotServiceCoordinates(new GeoCoordinates(destinationLatitude, destinationLongitude));
        }
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A position offset from the destination by roughly {@code metresNorth}. */
    private static ArrivalRequest fixNorthOfDestination(double metresNorth, String accuracyMeters) {
        BigDecimal latitude = DEST_LAT.add(BigDecimal.valueOf(metresNorth / 111_320.0));
        return new ArrivalRequest(latitude, DEST_LON, new BigDecimal(accuracyMeters), Instant.now());
    }

    // ---- inside the geofence ----

    @Test
    void aFreshPreciseFixAtTheDoorIsAccepted() {
        Order order = onTheWayOrder(DEST_LAT, DEST_LON);

        service.arrived(PROFESSIONAL_USER_ID, ORDER_ID, fixNorthOfDestination(10, "12"));

        verify(orderRepository).arrivedIfOnTheWay(eq(ORDER_ID), any());
        verify(notificationService).recordOrderNotification(ORDER_ID, CUSTOMER_ID,
                NotificationMessageType.ORDER_ARRIVED);
        // The evidence is recorded, including the measured distance -- an operator reviewing a
        // dispute later needs the number, not what today's threshold would have decided.
        assertThat(order.getArrivedAt()).isNotNull();
        assertThat(order.getArrivalDistanceMeters()).isNotNull();
        assertThat(order.getArrivalDistanceMeters().doubleValue()).isBetween(5.0, 15.0);
        assertThat(order.getArrivalAccuracyMeters()).isEqualByComparingTo("12");
    }

    /**
     * A professional parked a street away is a normal arrival, not a fraud attempt. The radius is
     * generous on purpose — see {@code LocationProperties#arrivalRadiusMeters}.
     */
    @Test
    void aFixJustInsideTheRadiusIsAccepted() {
        onTheWayOrder(DEST_LAT, DEST_LON);

        service.arrived(PROFESSIONAL_USER_ID, ORDER_ID,
                fixNorthOfDestination(locationProperties.getArrivalRadiusMeters() - 5, "20"));

        verify(orderRepository).arrivedIfOnTheWay(eq(ORDER_ID), any());
    }

    // ---- outside the geofence ----

    @Test
    void aFixJustOutsideTheRadiusIsRefusedAndTheOrderDoesNotMove() {
        onTheWayOrder(DEST_LAT, DEST_LON);

        assertThatThrownBy(() -> service.arrived(PROFESSIONAL_USER_ID, ORDER_ID,
                fixNorthOfDestination(locationProperties.getArrivalRadiusMeters() + 20, "20")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.ARRIVAL_OUT_OF_RANGE));

        verify(orderRepository, never()).arrivedIfOnTheWay(anyLong(), any());
        verify(notificationService, never()).recordOrderNotification(anyLong(), anyLong(), any());
    }

    @Test
    void pressingArrivedFromAnotherCityIsRefused() {
        onTheWayOrder(DEST_LAT, DEST_LON);
        // Haifa.
        ArrivalRequest fromHaifa = new ArrivalRequest(new BigDecimal("32.794000"), new BigDecimal("34.989600"),
                new BigDecimal("10"), Instant.now());

        assertThatThrownBy(() -> service.arrived(PROFESSIONAL_USER_ID, ORDER_ID, fromHaifa))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.ARRIVAL_OUT_OF_RANGE));
    }

    /**
     * A refusal must not disclose the customer's position, directly or by inference. Reporting the
     * miss distance would let a professional holding an order triangulate the address by pressing
     * the button from three places.
     */
    @Test
    void anOutOfRangeRefusalDoesNotDiscloseTheDistanceOrTheDestination() {
        onTheWayOrder(DEST_LAT, DEST_LON);

        assertThatThrownBy(() -> service.arrived(PROFESSIONAL_USER_ID, ORDER_ID,
                fixNorthOfDestination(3000, "20")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    String message = e.getMessage();
                    assertThat(message).doesNotContain("32.077").doesNotContain("34.773");
                    assertThat(message).doesNotContainPattern("\\d{3,}");
                });
    }

    // ---- fix quality ----

    @Test
    void aStaleFixCannotVerifyArrivalEvenFromTheCorrectPlace() {
        onTheWayOrder(DEST_LAT, DEST_LON);
        Instant tooOld = Instant.now().minus(locationProperties.getArrivalMaxAge()).minusSeconds(30);
        ArrivalRequest stale = new ArrivalRequest(DEST_LAT, DEST_LON, new BigDecimal("10"), tooOld);

        assertThatThrownBy(() -> service.arrived(PROFESSIONAL_USER_ID, ORDER_ID, stale))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.LOCATION_QUALITY_INSUFFICIENT));

        verify(orderRepository, never()).arrivedIfOnTheWay(anyLong(), any());
    }

    /**
     * A fix whose own error circle is wider than the geofence cannot answer "are you at this door",
     * so accepting it would be a verification in name only. Note this bar is far stricter than the
     * routing bar — routing tolerates 500 m, this does not.
     */
    @Test
    void anImpreciseFixCannotVerifyArrivalEvenFromTheCorrectPlace() {
        onTheWayOrder(DEST_LAT, DEST_LON);
        ArrivalRequest imprecise = new ArrivalRequest(DEST_LAT, DEST_LON,
                new BigDecimal(String.valueOf((long) locationProperties.getArrivalMaxAccuracyMeters() + 50)),
                Instant.now());

        assertThatThrownBy(() -> service.arrived(PROFESSIONAL_USER_ID, ORDER_ID, imprecise))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.LOCATION_QUALITY_INSUFFICIENT));
    }

    @Test
    void anAccuracyExactlyAtTheArrivalLimitIsStillAccepted() {
        onTheWayOrder(DEST_LAT, DEST_LON);
        ArrivalRequest atLimit = new ArrivalRequest(DEST_LAT, DEST_LON,
                new BigDecimal(String.valueOf((long) locationProperties.getArrivalMaxAccuracyMeters())),
                Instant.now());

        service.arrived(PROFESSIONAL_USER_ID, ORDER_ID, atLimit);

        verify(orderRepository).arrivedIfOnTheWay(eq(ORDER_ID), any());
    }

    @Test
    void aMalformedCoordinateIsAValidationErrorNotAGeofenceRefusal() {
        onTheWayOrder(DEST_LAT, DEST_LON);
        ArrivalRequest malformed = new ArrivalRequest(new BigDecimal("999"), DEST_LON,
                new BigDecimal("10"), Instant.now());

        assertThatThrownBy(() -> service.arrived(PROFESSIONAL_USER_ID, ORDER_ID, malformed))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    // ---- order state ----

    /**
     * An order whose address never geocoded cannot be verified geographically, and no amount of
     * retrying from the doorstep will change that — a different problem, with a different code,
     * from being in the wrong place.
     */
    @Test
    void anOrderWithNoDestinationSnapshotCannotBeArrivedAt() {
        onTheWayOrder(null, null);

        assertThatThrownBy(() -> service.arrived(PROFESSIONAL_USER_ID, ORDER_ID,
                fixNorthOfDestination(0, "10")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.ORDER_DESTINATION_UNKNOWN));
    }

    @Test
    void arrivalIsOnlyReachableFromOnTheWay() {
        Order order = onTheWayOrder(DEST_LAT, DEST_LON);
        setField(order, "orderStatus", OrderStatus.CONFIRMED);

        assertThatThrownBy(() -> service.arrived(PROFESSIONAL_USER_ID, ORDER_ID,
                fixNorthOfDestination(0, "10")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.ORDER_NOT_ARRIVABLE));
    }

    /**
     * Losing the atomic guard means somebody else moved the order between the load and the write —
     * a cancel, or a duplicate claim from a second tab. Refusing rather than proceeding is what
     * keeps {@code arrived_at} the record of the FIRST verified arrival.
     */
    @Test
    void aSecondArrivalClaimThatLosesTheRaceIsRefusedRatherThanRestampingTheEvidence() {
        Order order = onTheWayOrder(DEST_LAT, DEST_LON);
        when(orderRepository.arrivedIfOnTheWay(eq(ORDER_ID), any())).thenReturn(0);

        assertThatThrownBy(() -> service.arrived(PROFESSIONAL_USER_ID, ORDER_ID,
                fixNorthOfDestination(0, "10")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.ORDER_NOT_ARRIVABLE));

        assertThat(order.getArrivedAt()).isNull();
    }

    @Test
    void aProfessionalWhoIsNotOnThisOrderIsRefusedBeforeAnyLocationIsExamined() {
        onTheWayOrder(DEST_LAT, DEST_LON);
        Professional other = Mockito.mock(Professional.class);
        when(other.getId()).thenReturn(999L);
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.arrived(PROFESSIONAL_USER_ID, ORDER_ID,
                fixNorthOfDestination(0, "10")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    /**
     * Completion stays reachable from {@code ON_THE_WAY} as well as {@code ARRIVED}. Arrival is a
     * verification step, not a toll gate — a professional whose phone cannot get a fix must still
     * be able to finish the job.
     */
    @Test
    void completionRemainsReachableWithoutEverPassingThroughArrived() {
        Order order = onTheWayOrder(DEST_LAT, DEST_LON);
        when(orderRepository.completeIfOnTheWay(eq(ORDER_ID), any())).thenReturn(1);

        service.complete(PROFESSIONAL_USER_ID, ORDER_ID);

        verify(orderRepository).completeIfOnTheWay(eq(ORDER_ID), any());
        assertThat(order.getArrivedAt()).isNull();
    }

    /** A rejected arrival still improves the platform's knowledge of where the professional is. */
    @Test
    void aRejectedArrivalStillRecordsTheFreshPositionForRouting() {
        onTheWayOrder(DEST_LAT, DEST_LON);
        var locationRepository =
                Mockito.mock(com.pronto.professionals.repository.ProfessionalLocationRepository.class);
        when(locationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ProfessionalLocationService recording = new ProfessionalLocationService(locationRepository,
                locationProperties);
        BookingsService withRecording = new BookingsService(Mockito.mock(IssueRepository.class),
                professionalRepository, Mockito.mock(ProfessionalListingRepository.class),
                Mockito.mock(com.pronto.locations.service.ServiceCityResolver.class),
                Mockito.mock(AvailabilitySlotRepository.class), orderRepository,
                Mockito.mock(UserRepository.class), notificationService,
                Mockito.mock(DistanceEtaStrategy.class), Mockito.mock(StorageService.class),
                Mockito.mock(AvailabilityDerivationService.class),
                Mockito.mock(ProfessionalCoverageService.class),
                Mockito.mock(ContactVerificationGuard.class),
                Mockito.mock(ServiceAddressGeocoder.class), recording, locationProperties,
                new ArrivalVerifier(recording, locationProperties),
                new com.pronto.maps.service.SelectedPlaceValidator(),
                Mockito.mock(com.pronto.professionals.repository.CategoryRepository.class),
                new com.pronto.bookings.config.BookingProperties());

        assertThatThrownBy(() -> withRecording.arrived(PROFESSIONAL_USER_ID, ORDER_ID,
                fixNorthOfDestination(5000, "15")))
                .isInstanceOf(ApiException.class);

        verify(locationRepository).save(any());
    }

    @Test
    void theArrivalMaxAgeIsMuchTighterThanTheRoutingFreshnessWindow() {
        // Not an implementation detail: routing estimates something, arrival is the entire evidence
        // for a claim about where a person is right now.
        assertThat(locationProperties.getArrivalMaxAge())
                .isLessThan(locationProperties.getProfessionalFreshness());
        assertThat(locationProperties.getArrivalMaxAccuracyMeters())
                .isLessThan(locationProperties.getMaxAccuracyMeters());
    }

    @Test
    void aFixCapturedSlightlyInTheFutureIsToleratedAsClockSkew() {
        onTheWayOrder(DEST_LAT, DEST_LON);
        ArrivalRequest slightlyAhead = new ArrivalRequest(DEST_LAT, DEST_LON, new BigDecimal("10"),
                Instant.now().plus(Duration.ofSeconds(20)));

        service.arrived(PROFESSIONAL_USER_ID, ORDER_ID, slightlyAhead);

        verify(orderRepository).arrivedIfOnTheWay(eq(ORDER_ID), any());
    }
}
