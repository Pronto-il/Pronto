package com.pronto.bookings.service;

import com.pronto.availability.dto.CalendarSegment;
import com.pronto.availability.repository.AvailabilitySlotRepository;
import com.pronto.availability.service.AvailabilityDerivationService;
import com.pronto.bookings.config.BookingProperties;
import com.pronto.bookings.dto.AvailableWindowsResponse;
import com.pronto.bookings.dto.CreateOrderRequest;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.bookings.repository.ProfessionalListingRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueStatus;
import com.pronto.issues.entity.IssueUrgencyType;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.maps.GeocodeResult;
import com.pronto.maps.config.LocationProperties;
import com.pronto.maps.service.ServiceAddressGeocoder;
import com.pronto.matching.DistanceEtaStrategy;
import com.pronto.notifications.service.NotificationService;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.professionals.service.ProfessionalLocationService;
import com.pronto.storage.service.StorageService;
import com.pronto.users.entity.User;
import com.pronto.users.entity.UserRole;
import com.pronto.users.repository.UserRepository;
import com.pronto.users.service.ContactVerificationGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>The Standard-booking minimum lead time.</b> A customer may not book a professional for a time
 * sooner than {@code pronto.bookings.regular-booking-min-lead-minutes} from now, even when that
 * professional's calendar is genuinely free then.
 *
 * <h2>Why these tests are written against a duration rather than a wall clock</h2>
 *
 * The brief's worked example is "at 11:25, the earliest bookable time is 13:55". There is no
 * injectable {@code Clock} anywhere in this codebase — {@code BookingsService} calls
 * {@code Instant.now()} directly, as does every other service — so pinning 11:25 would mean either
 * introducing a clock seam across the whole booking path or mocking static time. Neither is
 * warranted for a rule that is pure arithmetic on a duration.
 *
 * <p>Instead every case below is expressed as an <em>offset from the request's own now</em>, which
 * is the same statement: {@code now + 5min} stands for 11:30, {@code now + 2h05m} for 13:30, and
 * {@code now + 150min} for 13:55 exactly. That makes the tests independent of when they run, of the
 * machine's timezone, and of daylight saving — none of which the rule depends on either. The
 * boundary case uses a small tolerance in the one direction that matters; see
 * {@link #exactlyAtTheBoundaryIsBookable}.
 */
class BookingsLeadTimeTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final Long ISSUE_ID = 2L;
    private static final Long PROFESSIONAL_ID = 3L;
    private static final Long CATEGORY_ID = 4L;
    private static final Long PROFESSIONAL_USER_ID = 99L;
    private static final long SERVICE_REGION_ID = 4L;
    private static final long BASE_CITY_ID = 40L;

    private IssueRepository issueRepository;
    private ProfessionalRepository professionalRepository;
    private OrderRepository orderRepository;
    private AvailabilityDerivationService availabilityDerivationService;
    private ProfessionalCoverageService professionalCoverageService;
    private UserRepository userRepository;
    private BookingProperties bookingProperties;
    private BookingsService bookingsService;

    @BeforeEach
    void setUp() {
        issueRepository = Mockito.mock(IssueRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        orderRepository = Mockito.mock(OrderRepository.class);
        availabilityDerivationService = Mockito.mock(AvailabilityDerivationService.class);
        professionalCoverageService = Mockito.mock(ProfessionalCoverageService.class);
        userRepository = Mockito.mock(UserRepository.class);
        ServiceAddressGeocoder serviceAddressGeocoder = Mockito.mock(ServiceAddressGeocoder.class);
        LocationProperties locationProperties = new LocationProperties();
        ProfessionalLocationService professionalLocationService = new ProfessionalLocationService(
                Mockito.mock(com.pronto.professionals.repository.ProfessionalLocationRepository.class),
                locationProperties);

        // The REAL properties object, deliberately: the production default (150) is the rule under
        // test, and a mock returning a convenient number would test the plumbing rather than the rule.
        bookingProperties = new BookingProperties();

        bookingsService = new BookingsService(issueRepository, professionalRepository,
                Mockito.mock(ProfessionalListingRepository.class),
                Mockito.mock(com.pronto.locations.service.ServiceCityResolver.class),
                Mockito.mock(AvailabilitySlotRepository.class), orderRepository, userRepository,
                Mockito.mock(NotificationService.class), Mockito.mock(DistanceEtaStrategy.class),
                Mockito.mock(StorageService.class), availabilityDerivationService,
                professionalCoverageService, Mockito.mock(ContactVerificationGuard.class),
                serviceAddressGeocoder, professionalLocationService, locationProperties,
                new com.pronto.maps.service.ArrivalVerifier(professionalLocationService, locationProperties),
                new com.pronto.maps.service.SelectedPlaceValidator(),
                Mockito.mock(com.pronto.professionals.repository.CategoryRepository.class),
                bookingProperties);

        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(openStandardIssue()));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(activeProfessional()));
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(true);
        when(userRepository.findById(PROFESSIONAL_USER_ID))
                .thenReturn(Optional.of(activeUser(PROFESSIONAL_USER_ID)));
        when(professionalCoverageService.servesCategory(PROFESSIONAL_ID, CATEGORY_ID)).thenReturn(true);
        Mockito.lenient().when(serviceAddressGeocoder.resolve(any())).thenReturn(GeocodeResult.failed());
        // The professional is free at every time these tests ask about -- so nothing below can pass
        // or fail for an availability reason, only for the lead-time rule.
        Mockito.lenient().when(availabilityDerivationService.deriveCalendar(eq(PROFESSIONAL_ID), any(), any()))
                .thenAnswer(inv -> List.of(CalendarSegment.available(inv.getArgument(1), inv.getArgument(2))));
        Mockito.lenient().when(availabilityDerivationService
                        .deriveAvailableWindows(eq(PROFESSIONAL_ID), any(), any(), any()))
                .thenAnswer(inv -> List.of(CalendarSegment.available(inv.getArgument(1), inv.getArgument(2))));
        Mockito.lenient().when(issueRepository.bookIfOpen(eq(ISSUE_ID), any())).thenReturn(1);
        Mockito.lenient().when(orderRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------
    // The brief's three worked cases, as offsets from the request's own now
    // ------------------------------------------------------------------

    /** "11:30, five minutes away" — free on the calendar, and still not bookable. */
    @Test
    void fiveMinutesFromNowIsNotBookable() {
        assertRefusedForLeadTime(Duration.ofMinutes(5));
    }

    /** "13:30" — two and a half hours is the rule, and 2h05m is inside it. */
    @Test
    void twoHoursFiveMinutesFromNowIsNotBookable() {
        assertRefusedForLeadTime(Duration.ofMinutes(125));
    }

    /** One minute short of the boundary. The rule is not "roughly two and a half hours". */
    @Test
    void oneMinuteInsideTheWindowIsNotBookable() {
        assertRefusedForLeadTime(Duration.ofMinutes(149));
    }

    /**
     * "13:55" — the boundary itself is bookable, because the rule is "at least 150 minutes", not
     * "more than".
     *
     * <p>Asked for a few seconds past the boundary rather than exactly on it. Not a fudge: the
     * service reads its own {@code Instant.now()} microseconds after this line computes one, so an
     * exact-boundary request is genuinely, correctly, a few microseconds early by the time it is
     * evaluated. Any real client has the same property. The margin is far smaller than the minute
     * granularity the rule is expressed in, and {@link #oneMinuteInsideTheWindowIsNotBookable}
     * above pins the other side closely enough that this cannot hide an off-by-a-lot.
     */
    @Test
    void exactlyAtTheBoundaryIsBookable() {
        Instant bookedStart = Instant.now().plus(Duration.ofMinutes(150)).plusSeconds(2);

        assertThatCode(() -> bookingsService.createOrder(CUSTOMER_ID, orderAt(bookedStart)))
                .doesNotThrowAnyException();
    }

    @Test
    void wellBeyondTheBoundaryIsBookable() {
        Instant bookedStart = Instant.now().plus(Duration.ofDays(1));

        assertThatCode(() -> bookingsService.createOrder(CUSTOMER_ID, orderAt(bookedStart)))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // The rule is the backend's, not the screen's
    // ------------------------------------------------------------------

    /**
     * <b>A hand-rolled request is refused exactly as a mis-clicked one is.</b> This is the test that
     * makes the feature a rule rather than a rendering choice: nothing about the refusal depends on
     * the client having asked for, received, or respected {@code earliestBookableAt}.
     */
    @Test
    void aManuallySubmittedBookingInsideTheWindowIsRejectedByTheBackend() {
        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID,
                orderAt(Instant.now().plus(Duration.ofMinutes(30)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.BOOKING_LEAD_TIME_NOT_MET);
    }

    /**
     * Refused <em>before</em> anything is claimed. A lead-time failure must not book the issue, and
     * must not leave an order behind — the customer has to be able to submit again at a later time.
     */
    @Test
    void aRefusedBookingClaimsNothing() {
        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID,
                orderAt(Instant.now().plus(Duration.ofMinutes(30)))))
                .isInstanceOf(ApiException.class);

        verify(issueRepository, never()).bookIfOpen(any(), any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    /**
     * A start time in the past keeps its own, more specific error. The lead-time rule is not allowed
     * to swallow it: "you picked a time that has already happened" and "you picked a time too soon
     * from now" are different mistakes, and only the second has SOS as its remedy.
     */
    @Test
    void aStartTimeInThePastIsStillAPlainValidationError() {
        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID,
                orderAt(Instant.now().minus(Duration.ofHours(1)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    /**
     * The refusal is its own code, not {@code BOOKING_TIME_UNAVAILABLE}. The frontend keys its
     * "try SOS instead" prompt on this distinction, and a customer must never be told a
     * professional is busy when the professional is free and the platform is the one saying no.
     */
    @Test
    void theLeadTimeRefusalIsDistinctFromTheProfessionalBeingBusy() {
        ApiException thrown = (ApiException) org.assertj.core.api.Assertions.catchThrowable(() ->
                bookingsService.createOrder(CUSTOMER_ID, orderAt(Instant.now().plus(Duration.ofMinutes(10)))));

        assertThat(thrown.getCode()).isEqualTo(ErrorCode.BOOKING_LEAD_TIME_NOT_MET);
        assertThat(thrown.getCode()).isNotEqualTo(ErrorCode.BOOKING_TIME_UNAVAILABLE);
    }

    // ------------------------------------------------------------------
    // What the availability screen is told
    // ------------------------------------------------------------------

    /**
     * The boundary is published so the screen can grey out the early chips — and the professional's
     * real windows are published <em>unclipped</em> alongside it, so the screen can show that the
     * professional is in fact free then. Conflating the two would make "we will not take this
     * booking" indistinguishable from "they are busy".
     */
    @Test
    void availableWindowsCarriesTheBoundaryAndTheRuleWithoutClippingTheWindows() {
        Instant beforeCall = Instant.now();

        AvailableWindowsResponse response =
                bookingsService.listAvailableWindows(CUSTOMER_ID, PROFESSIONAL_ID, null);

        assertThat(response.minLeadMinutes()).isEqualTo(150);
        assertThat(response.earliestBookableAt())
                .isBetween(beforeCall.plus(Duration.ofMinutes(150)),
                        Instant.now().plus(Duration.ofMinutes(150)));
        // The derivation was asked for windows starting from now, not from the boundary.
        assertThat(response.windows()).isNotEmpty();
        assertThat(response.windows().get(0).startAt()).isBefore(response.earliestBookableAt());
    }

    // ------------------------------------------------------------------
    // Configurability
    // ------------------------------------------------------------------

    /**
     * The rule lives in one place. Setting it to zero restores the pre-feature behaviour exactly —
     * which is what makes it a configured product decision rather than a constant somebody has to
     * find in Java.
     */
    @Test
    void zeroLeadTimeRestoresTheOldBehaviour() {
        bookingProperties.setRegularBookingMinLeadMinutes(0);

        assertThatCode(() -> bookingsService.createOrder(CUSTOMER_ID,
                orderAt(Instant.now().plus(Duration.ofMinutes(1)))))
                .doesNotThrowAnyException();
    }

    /** A longer lead time is honoured without touching a line of booking code. */
    @Test
    void aLongerConfiguredLeadTimeIsHonoured() {
        bookingProperties.setRegularBookingMinLeadMinutes(300);

        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID,
                orderAt(Instant.now().plus(Duration.ofMinutes(200)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.BOOKING_LEAD_TIME_NOT_MET);
    }

    // ------------------------------------------------------------------
    // SOS is a different product and keeps a different rule
    // ------------------------------------------------------------------

    /**
     * <b>SOS is not subject to the lead time, structurally.</b>
     *
     * <p>Asserted as "the SOS activation path cannot read this property" rather than by driving an
     * SOS request through the clock, and that is the stronger statement of the two: a behavioural
     * test would pass just as happily if somebody later added a lead-time check to
     * {@code SosService} with a different default, whereas this fails the moment the dependency
     * appears at all.
     *
     * <p>The rule matters because the two flows exist in tension. The lead time is partly there to
     * keep SOS the fast-response option; an SOS request that inherited it would defeat the reason
     * the Standard flow has one.
     */
    @Test
    void sosActivationDoesNotDependOnTheStandardBookingLeadTime() {
        boolean anySosConstructorTakesBookingProperties =
                java.util.Arrays.stream(com.pronto.sos.service.SosService.class.getDeclaredConstructors())
                        .flatMap(constructor -> java.util.Arrays.stream(constructor.getParameterTypes()))
                        .anyMatch(BookingProperties.class::equals);

        assertThat(anySosConstructorTakesBookingProperties)
                .as("SosService must not depend on the Standard-booking lead time")
                .isFalse();

        boolean anySosFieldIsBookingProperties =
                java.util.Arrays.stream(com.pronto.sos.service.SosService.class.getDeclaredFields())
                        .anyMatch(field -> field.getType().equals(BookingProperties.class));

        assertThat(anySosFieldIsBookingProperties).isFalse();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void assertRefusedForLeadTime(Duration fromNow) {
        assertThatThrownBy(() -> bookingsService.createOrder(CUSTOMER_ID, orderAt(Instant.now().plus(fromNow))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.BOOKING_LEAD_TIME_NOT_MET);
    }

    private static CreateOrderRequest orderAt(Instant bookedStart) {
        return new CreateOrderRequest(ISSUE_ID, PROFESSIONAL_ID, bookedStart,
                "תל אביב-יפו", "דיזנגוף", "100", null, null, null, null,
                "ChIJprontoTestPlaceId", "דיזנגוף 100, תל אביב-יפו",
                new BigDecimal("32.0811"), new BigDecimal("34.7739"));
    }

    private static Issue openStandardIssue() {
        Issue issue = new Issue(CUSTOMER_ID, CATEGORY_ID, "נזילה מתחת לכיור", IssueUrgencyType.STANDARD);
        setField(issue, "id", ISSUE_ID);
        setField(issue, "status", IssueStatus.OPEN);
        return issue;
    }

    private static Professional activeProfessional() {
        Professional professional = new Professional(PROFESSIONAL_USER_ID, SERVICE_REGION_ID, BASE_CITY_ID,
                new BigDecimal("250.00"));
        setField(professional, "id", PROFESSIONAL_ID);
        return professional;
    }

    private static User activeUser(Long id) {
        User user = new User("בעל מקצוע", "pro@example.com", "hash", UserRole.PROFESSIONAL);
        setField(user, "id", id);
        return user;
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
}
