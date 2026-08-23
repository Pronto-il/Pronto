package com.pronto.sos.service;

import com.pronto.bookings.entity.Order;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.entity.Issue;
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
import com.pronto.sos.dto.SosRequestResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Customer-side lifecycle: activation, the concurrency-critical selection, deadline
 * enforcement, and authorization.
 */
class SosServiceTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final Long OTHER_CUSTOMER_ID = 99L;
    private static final Long ISSUE_ID = 2L;
    private static final Long CATEGORY_ID = 7L;
    private static final Long REQUEST_ID = 100L;
    private static final Long OFFER_ID = 200L;
    private static final Long PROFESSIONAL_ID = 3L;
    private static final Long PROFESSIONAL_USER_ID = 33L;
    private static final Long ORDER_ID = 500L;

    private SosRequestRepository sosRequestRepository;
    private SosOfferRepository sosOfferRepository;
    private IssueRepository issueRepository;
    private OrderRepository orderRepository;
    private ProfessionalRepository professionalRepository;
    private SosDispatchService sosDispatchService;
    private SosEventService sosEventService;
    private SosResponseAssembler assembler;
    private NotificationService notificationService;
    private SosProperties properties;
    private SosService service;

    @BeforeEach
    void setUp() {
        sosRequestRepository = Mockito.mock(SosRequestRepository.class);
        sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        issueRepository = Mockito.mock(IssueRepository.class);
        orderRepository = Mockito.mock(OrderRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        sosDispatchService = Mockito.mock(SosDispatchService.class);
        sosEventService = Mockito.mock(SosEventService.class);
        assembler = Mockito.mock(SosResponseAssembler.class);
        notificationService = Mockito.mock(NotificationService.class);
        properties = new SosProperties();
        service = new SosService(sosRequestRepository, sosOfferRepository, issueRepository, orderRepository,
                professionalRepository, sosDispatchService, sosEventService, assembler, notificationService,
                properties);

        when(assembler.toRequestResponse(any(), any()))
                .thenAnswer(inv -> stubResponse(inv.getArgument(0), inv.getArgument(1)));
        // MS1: selectProfessional re-checks eligibility immediately before creating the order.
        // Every pre-existing test here describes a professional who is still perfectly eligible at
        // that moment, so it is stubbed true by default; selectRefusesACandidateWhoBecameIneligible
        // overrides it. lenient() because most tests in this class never reach selection.
        Mockito.lenient().when(professionalRepository.existsEligibleById(anyLong())).thenReturn(true);
    }

    // ---- fixtures ----

    /**
     * Mirrors the real assembler closely enough to be worth asserting on: it echoes back the
     * {@link SosAddressAccess} it was handed via the latitude field, so a test can tell a redacted
     * response from a full one without standing up the real assembler.
     *
     * <p>Latitude rather than street, since street is now disclosed at <em>both</em> access levels
     * — a professional needs it to estimate an arrival time. Coordinates are not disclosed at
     * either level below {@code FULL}, which makes latitude the honest discriminator here.
     */
    private static SosRequestResponse stubResponse(SosRequest request, SosAddressAccess access) {
        boolean exact = access == SosAddressAccess.FULL;
        return new SosRequestResponse(request.getId(), request.getIssueId(), request.getCustomerId(),
                request.getCategoryId(), null, null, request.getUrgency(), request.getStatus(),
                request.getServiceCity(),
                request.getServiceStreet(), null, null, null, null, null,
                exact ? request.getLatitude() : null, null,
                request.getSelectedProfessionalId(), null, request.getSelectedOfferId(), null,
                request.getOrderId(),
                request.getCancelledBy(), 0, 0,
                request.getSearchExpansions(), 2,
                request.getSelectedProfessionalId() == null
                        && request.getStatus().isAcceptingProfessionalResponses()
                        && request.getSearchExpansions() < 2,
                request.getMatchingExpiresAt(), request.getSelectionExpiresAt(),
                null, null, null, null, null, null, null, null);
    }

    private static Issue issue(IssueUrgencyType urgency, com.pronto.issues.entity.IssueStatus status) {
        Issue issue = new Issue(CUSTOMER_ID, CATEGORY_ID, "Burst pipe", urgency);
        setField(issue, "id", ISSUE_ID);
        setField(issue, "status", status);
        return issue;
    }

    private static SosRequest request(SosRequestStatus status) {
        SosRequest request = new SosRequest(ISSUE_ID, CUSTOMER_ID, CATEGORY_ID, null, "leak", SosUrgency.URGENT,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null, null, null);
        setField(request, "id", REQUEST_ID);
        setField(request, "status", status);
        return request;
    }

    private static SosOffer offer(SosOfferStatus status) {
        SosOffer offer = new SosOffer(REQUEST_ID, PROFESSIONAL_ID, 1, new BigDecimal("0.8"),
                new BigDecimal("8.0"), 15, new BigDecimal("250.00"), new BigDecimal("50.00"),
                new BigDecimal("30.00"), Instant.now(), Instant.now().plusSeconds(120));
        setField(offer, "id", OFFER_ID);
        setField(offer, "status", status);
        return offer;
    }

    /** An accepted offer with a distinct id and ETA, for the ordering/cap tests. */
    private static SosOffer offerWith(long offerId, int etaMinutes) {
        SosOffer offer = new SosOffer(REQUEST_ID, PROFESSIONAL_ID, 1, new BigDecimal("0.8"),
                new BigDecimal("8.0"), etaMinutes, new BigDecimal("250.00"), new BigDecimal("50.00"),
                new BigDecimal("30.00"), Instant.now(), Instant.now().plusSeconds(120));
        setField(offer, "id", offerId);
        setField(offer, "status", SosOfferStatus.ACCEPTED);
        return offer;
    }

    /** Just enough of a candidate to assert identity and order on. */
    private static SosCandidate candidateOf(SosOffer offer) {
        return new SosCandidate(offer.getId(), offer.getProfessionalId(), "Dana", null, null, null,
                null, 0L, offer.getEstimatedArrivalMinutes(), offer.getDistanceKm(), offer.getVisitFee(),
                offer.getSosFee(), offer.getSosFee(), offer.getPlatformCommission(), Instant.now());
    }

    private static Professional professional() {
        Professional professional = new Professional(PROFESSIONAL_USER_ID, CATEGORY_ID, "Center",
                new BigDecimal("250.00"));
        setField(professional, "id", PROFESSIONAL_ID);
        return professional;
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

    private static CreateSosRequestRequest createRequest() {
        return new CreateSosRequestRequest(ISSUE_ID, "Burst pipe", SosUrgency.URGENT,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null, null, null);
    }

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    @Test
    void createStartsMatchingAndDispatches() {
        when(issueRepository.findById(ISSUE_ID))
                .thenReturn(Optional.of(issue(IssueUrgencyType.SOS, com.pronto.issues.entity.IssueStatus.OPEN)));
        when(sosRequestRepository.existsActiveByIssueId(ISSUE_ID)).thenReturn(false);
        when(sosRequestRepository.saveAndFlush(any(SosRequest.class)))
                .thenAnswer(inv -> {
                    SosRequest saved = inv.getArgument(0);
                    setField(saved, "id", REQUEST_ID);
                    return saved;
                });
        when(sosRequestRepository.startMatching(eq(REQUEST_ID), any(), any())).thenReturn(1);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.MATCHING)));

        SosRequestResponse response = service.create(CUSTOMER_ID, createRequest());

        assertThat(response.id()).isEqualTo(REQUEST_ID);
        verify(sosRequestRepository).startMatching(eq(REQUEST_ID), any(), any());
        verify(sosDispatchService).dispatch(any(SosRequest.class));
        verify(sosEventService).recordCustomer(eq(REQUEST_ID), eq(CUSTOMER_ID), eq(SosEventType.SOS_CREATED),
                any(), eq(SosRequestStatus.CREATED), any());
        verify(sosEventService).recordSystem(eq(REQUEST_ID), eq(SosEventType.MATCHING_STARTED),
                eq(SosRequestStatus.CREATED), eq(SosRequestStatus.MATCHING), any());
    }

    /** The issue must stay OPEN so a failed dispatch can fall back to the standard booking flow. */
    @Test
    void createDoesNotBookTheIssue() {
        when(issueRepository.findById(ISSUE_ID))
                .thenReturn(Optional.of(issue(IssueUrgencyType.SOS, com.pronto.issues.entity.IssueStatus.OPEN)));
        when(sosRequestRepository.saveAndFlush(any(SosRequest.class))).thenAnswer(inv -> {
            SosRequest saved = inv.getArgument(0);
            setField(saved, "id", REQUEST_ID);
            return saved;
        });
        when(sosRequestRepository.startMatching(anyLong(), any(), any())).thenReturn(1);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.MATCHING)));

        service.create(CUSTOMER_ID, createRequest());

        verify(issueRepository, never()).bookIfOpen(anyLong(), any());
    }

    @Test
    void createRejectsAnIssueOwnedBySomebodyElse() {
        when(issueRepository.findById(ISSUE_ID))
                .thenReturn(Optional.of(issue(IssueUrgencyType.SOS, com.pronto.issues.entity.IssueStatus.OPEN)));

        assertThatThrownBy(() -> service.create(OTHER_CUSTOMER_ID, createRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void createRejectsAStandardIssue() {
        when(issueRepository.findById(ISSUE_ID))
                .thenReturn(Optional.of(issue(IssueUrgencyType.STANDARD, com.pronto.issues.entity.IssueStatus.OPEN)));

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, createRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.ISSUE_URGENCY_MISMATCH));
    }

    @Test
    void createRejectsAnAlreadyBookedIssue() {
        when(issueRepository.findById(ISSUE_ID))
                .thenReturn(Optional.of(issue(IssueUrgencyType.SOS, com.pronto.issues.entity.IssueStatus.BOOKED)));

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, createRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.ISSUE_NOT_BOOKABLE));
    }

    /** One <em>active</em> SOS request per issue — no double-activation. */
    @Test
    void createRejectsASecondSosRequestForTheSameIssue() {
        when(issueRepository.findById(ISSUE_ID))
                .thenReturn(Optional.of(issue(IssueUrgencyType.SOS, com.pronto.issues.entity.IssueStatus.OPEN)));
        when(sosRequestRepository.existsActiveByIssueId(ISSUE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, createRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.SOS_REQUEST_ALREADY_EXISTS));
    }

    /** The unique constraint is the authoritative guard when two activations race the pre-check. */
    @Test
    void createMapsTheUniqueConstraintViolationToAConflict() {
        when(issueRepository.findById(ISSUE_ID))
                .thenReturn(Optional.of(issue(IssueUrgencyType.SOS, com.pronto.issues.entity.IssueStatus.OPEN)));
        when(sosRequestRepository.existsActiveByIssueId(ISSUE_ID)).thenReturn(false);
        when(sosRequestRepository.saveAndFlush(any(SosRequest.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("ux_sos_requests_active_issue"));

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, createRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.SOS_REQUEST_ALREADY_EXISTS));
    }

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    private void stubSelectableRequest() {
        SosRequest request = request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(request, "selectionExpiresAt", Instant.now().plusSeconds(60));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.ACCEPTED)));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional()));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            setField(order, "id", ORDER_ID);
            return order;
        });
        when(sosRequestRepository.selectProfessional(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(1);
        when(sosOfferRepository.markSelected(eq(OFFER_ID), any())).thenReturn(1);
    }

    /**
     * A losing candidate: a second professional who responded available and was passed over. Its
     * status is what {@code closeLosingOffers} leaves behind, since {@code notifyLosingCandidates}
     * reads the offers back <em>after</em> that call rather than trusting the pre-selection view.
     */
    private static SosOffer losingOffer(Long offerId, Long professionalId, SosOfferStatus status) {
        SosOffer offer = new SosOffer(REQUEST_ID, professionalId, 2, new BigDecimal("1.2"),
                new BigDecimal("9.0"), 25, new BigDecimal("250.00"), new BigDecimal("50.00"),
                new BigDecimal("30.00"), Instant.now(), Instant.now().plusSeconds(120));
        setField(offer, "id", offerId);
        setField(offer, "status", status);
        return offer;
    }

    private static Professional professional(Long id, Long userId) {
        Professional professional = new Professional(userId, CATEGORY_ID, "Center", new BigDecimal("250.00"));
        setField(professional, "id", id);
        return professional;
    }

    @Test
    void selectCreatesAnOrderMarksTheWinnerAndClosesTheRest() {
        stubSelectableRequest();

        service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID);

        verify(orderRepository).saveAndFlush(any(Order.class));
        verify(sosRequestRepository).selectProfessional(eq(REQUEST_ID), eq(PROFESSIONAL_ID), eq(OFFER_ID),
                eq(ORDER_ID), any());
        verify(sosOfferRepository).markSelected(eq(OFFER_ID), any());
        verify(sosOfferRepository).closeLosingOffers(eq(REQUEST_ID), eq(OFFER_ID), any());
        verify(issueRepository).bookIfOpen(eq(ISSUE_ID), any());
        verify(sosEventService).recordCustomer(eq(REQUEST_ID), eq(CUSTOMER_ID),
                eq(SosEventType.PROFESSIONAL_SELECTED), eq(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION),
                eq(SosRequestStatus.PROFESSIONAL_SELECTED), any());
        verify(notificationService).recordSosNotification(REQUEST_ID, PROFESSIONAL_USER_ID,
                NotificationMessageType.SOS_PROFESSIONAL_SELECTED);
    }

    /**
     * The professionals who said "I'm available" and lost are told so.
     *
     * <p>{@code SOS_NOT_SELECTED} existed in the notification enum and in the realtime vocabulary,
     * but nothing ever wrote the row — so a professional who held time open for a stranger learned
     * the outcome only if their socket happened to be connected at that instant, and otherwise
     * never. Their inbox just kept a card that had quietly stopped being real.
     */
    @Test
    void selectTellsTheAvailableProfessionalsWhoWerePassedOver() {
        stubSelectableRequest();
        when(sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(REQUEST_ID)).thenReturn(List.of(
                offer(SosOfferStatus.SELECTED),
                losingOffer(201L, 4L, SosOfferStatus.NOT_SELECTED)));
        when(professionalRepository.findById(4L)).thenReturn(Optional.of(professional(4L, 44L)));

        service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID);

        verify(notificationService).recordSosNotification(REQUEST_ID, 44L,
                NotificationMessageType.SOS_NOT_SELECTED);
    }

    /**
     * Being passed over is only meaningful to somebody who was actually in the running. A
     * professional whose offer simply lapsed never risked anything, and inventing a rejection for
     * them would be both untrue and demoralising.
     */
    @Test
    void selectDoesNotTellProfessionalsWhoNeverAnsweredThatTheyWerePassedOver() {
        stubSelectableRequest();
        when(sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(REQUEST_ID)).thenReturn(List.of(
                offer(SosOfferStatus.SELECTED),
                losingOffer(201L, 4L, SosOfferStatus.EXPIRED),
                losingOffer(202L, 5L, SosOfferStatus.REJECTED)));

        service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID);

        verify(notificationService, never()).recordSosNotification(anyLong(), anyLong(),
                eq(NotificationMessageType.SOS_NOT_SELECTED));
    }

    /** The one mistake here that would actually cost somebody a job. */
    @Test
    void selectNeverTellsTheWinnerTheyWerePassedOver() {
        stubSelectableRequest();
        when(sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(REQUEST_ID)).thenReturn(List.of(
                offer(SosOfferStatus.SELECTED),
                losingOffer(201L, 4L, SosOfferStatus.NOT_SELECTED)));
        when(professionalRepository.findById(4L)).thenReturn(Optional.of(professional(4L, 44L)));

        service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID);

        verify(notificationService, never()).recordSosNotification(REQUEST_ID, PROFESSIONAL_USER_ID,
                NotificationMessageType.SOS_NOT_SELECTED);
        verify(notificationService).recordSosNotification(REQUEST_ID, PROFESSIONAL_USER_ID,
                NotificationMessageType.SOS_PROFESSIONAL_SELECTED);
    }

    /**
     * Exactly one notification per losing professional. The duplicate-looking notification list
     * this milestone investigated turned out to be a labelling problem rather than duplicate rows,
     * and this is the assertion that keeps it that way from the newest producer.
     */
    @Test
    void eachPassedOverProfessionalIsNotifiedExactlyOnce() {
        stubSelectableRequest();
        when(sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(REQUEST_ID)).thenReturn(List.of(
                offer(SosOfferStatus.SELECTED),
                losingOffer(201L, 4L, SosOfferStatus.NOT_SELECTED),
                losingOffer(202L, 5L, SosOfferStatus.NOT_SELECTED)));
        when(professionalRepository.findById(4L)).thenReturn(Optional.of(professional(4L, 44L)));
        when(professionalRepository.findById(5L)).thenReturn(Optional.of(professional(5L, 55L)));

        service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID);

        verify(notificationService, times(1))
                .recordSosNotification(REQUEST_ID, 44L, NotificationMessageType.SOS_NOT_SELECTED);
        verify(notificationService, times(1))
                .recordSosNotification(REQUEST_ID, 55L, NotificationMessageType.SOS_NOT_SELECTED);
    }

    /** The order carries the offer's snapshotted economics, not a freshly-read base price. */
    @Test
    void selectSnapshotsTheOfferPricingOntoTheOrder() {
        stubSelectableRequest();

        service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(captor.capture());
        Order order = captor.getValue();
        assertThat(order.getBasePriceSnapshot()).isEqualByComparingTo("250.00");
        assertThat(order.getSosSurcharge()).isEqualByComparingTo("50.00");
        assertThat(order.getFinalPrice()).isEqualByComparingTo("300.00");
        assertThat(order.getProfessionalId()).isEqualTo(PROFESSIONAL_ID);
    }

    @Test
    void selectRejectsANonOwningCustomer() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION)));

        assertThatThrownBy(() -> service.selectProfessional(OTHER_CUSTOMER_ID, REQUEST_ID, OFFER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
    }

    /** Selection is one-shot: a second attempt after a professional is set must be refused. */
    @Test
    void selectRejectsASecondSelection() {
        SosRequest request = request(SosRequestStatus.PROFESSIONAL_SELECTED);
        setField(request, "selectedProfessionalId", PROFESSIONAL_ID);
        setField(request, "selectedOfferId", OFFER_ID);
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.selectProfessional(CUSTOMER_ID, REQUEST_ID, 201L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_ALREADY_SELECTED));
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
    }

    /**
     * The guarded update returning 0 with a professional now set means a concurrent selection
     * won the race — this is the two-taps-at-once case.
     */
    @Test
    void selectLosingTheRaceReportsAlreadySelected() {
        stubSelectableRequest();
        when(sosRequestRepository.selectProfessional(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(0);
        SosRequest afterRace = request(SosRequestStatus.PROFESSIONAL_SELECTED);
        setField(afterRace, "selectedProfessionalId", 4L);
        setField(afterRace, "selectedOfferId", 201L);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(selectableRequest()))
                .thenReturn(Optional.of(afterRace));

        assertThatThrownBy(() -> service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_ALREADY_SELECTED));
    }

    @Test
    void selectRefusesACandidateWhoBecameIneligibleSinceDispatch() {
        // MS1 (D-B): the last check before an order and a priced commitment exist. Minutes can
        // pass between dispatch and selection -- long enough for an operator to reject this
        // professional, or for them to clear their working hours. Mapped onto the EXISTING
        // SOS_CANDIDATE_NOT_AVAILABLE, which is exactly what happened and what the customer's
        // client already knows how to render: this one cannot be taken, the others still can.
        stubSelectableRequest();
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.SOS_CANDIDATE_NOT_AVAILABLE));
    }

    @Test
    void selectRefusingAnIneligibleCandidateCreatesNoOrderAndClaimsNothing() {
        // The refusal must land before any of the state changes selection makes -- no order row,
        // no claim on the request, no closing of the other candidates' offers. The customer's
        // remaining options have to survive intact.
        stubSelectableRequest();
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID))
                .isInstanceOf(ApiException.class);

        verify(orderRepository, never()).saveAndFlush(any(Order.class));
        verify(sosRequestRepository, never()).selectProfessional(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(sosOfferRepository, never()).markSelected(anyLong(), any());
        verify(sosOfferRepository, never()).closeLosingOffers(anyLong(), anyLong(), any());
    }

    @Test
    void selectStillWorksForACandidateWhoIsStillEligible() {
        stubSelectableRequest();
        when(professionalRepository.existsEligibleById(PROFESSIONAL_ID)).thenReturn(true);

        service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID);

        verify(orderRepository).saveAndFlush(any(Order.class));
    }

    /** 0 rows with nobody selected means the deadline lapsed between the read and the write. */
    @Test
    void selectAfterTheWindowClosedReportsWindowExpired() {
        stubSelectableRequest();
        when(sosRequestRepository.selectProfessional(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(0);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(selectableRequest()))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION)));

        assertThatThrownBy(() -> service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_WINDOW_EXPIRED));
    }

    private static SosRequest selectableRequest() {
        SosRequest request = request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(request, "selectionExpiresAt", Instant.now().plusSeconds(60));
        return request;
    }

    // ------------------------------------------------------------------
    // "סרוק שוב" — manual, bounded search expansion
    // ------------------------------------------------------------------

    /**
     * The base case: a request still waiting on responses widens, dispatches a further wave, and
     * records it — all on the same {@code sos_requests} row, with no status change.
     */
    @Test
    void scanAgainWidensTheSameRequestAndDispatchesAFurtherWave() {
        SosRequest searching = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        SosRequest expanded = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(expanded, "searchExpansions", (short) 1);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(searching))
                .thenReturn(Optional.of(expanded));
        when(sosRequestRepository.expandSearch(eq(REQUEST_ID), eq((short) 0), eq((short) 1), eq((short) 2),
                any(), any(), any())).thenReturn(1);
        when(sosDispatchService.expand(any(), any())).thenReturn(4);

        SosRequestResponse response = service.expandSearch(CUSTOMER_ID, REQUEST_ID);

        assertThat(response.id()).isEqualTo(REQUEST_ID);
        var scope = org.mockito.ArgumentCaptor.forClass(SosSearchScope.class);
        verify(sosDispatchService).expand(any(), scope.capture());
        assertThat(scope.getValue().level()).isEqualTo(1);
        // Level 1 = the base pool plus one increment, as a running total across every wave.
        assertThat(scope.getValue().poolSize())
                .isEqualTo(properties.getCandidatePoolSize() + properties.getExpansionPoolIncrement());
        verify(sosEventService).recordCustomer(eq(REQUEST_ID), eq(CUSTOMER_ID),
                eq(SosEventType.SEARCH_EXPANDED), any(), any(), any());
    }

    /** A customer already choosing may still widen — that is the whole point of the control. */
    @Test
    void scanAgainIsAllowedWhileTheSelectionWindowIsOpen() {
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(selectableRequest()));
        when(sosRequestRepository.expandSearch(anyLong(), Mockito.anyShort(), Mockito.anyShort(),
                Mockito.anyShort(), any(), any(), any())).thenReturn(1);

        service.expandSearch(CUSTOMER_ID, REQUEST_ID);

        verify(sosDispatchService).expand(any(), any());
    }

    /**
     * <b>Selection always wins over an in-flight expansion.</b> Nothing is dispatched, and the
     * customer is told the specific reason rather than a generic conflict.
     */
    @Test
    void scanAgainAfterAProfessionalWasSelectedIsRefusedAndDispatchesNothing() {
        SosRequest selected = request(SosRequestStatus.PROFESSIONAL_SELECTED);
        setField(selected, "selectedProfessionalId", PROFESSIONAL_ID);
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(selected));

        assertThatThrownBy(() -> service.expandSearch(CUSTOMER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_ALREADY_SELECTED));
        verify(sosDispatchService, never()).expand(any(), any());
        verify(sosRequestRepository, never()).expandSearch(anyLong(), Mockito.anyShort(), Mockito.anyShort(),
                Mockito.anyShort(), any(), any(), any());
    }

    /** The bound is real: at the configured maximum there is no further expansion to be had. */
    @Test
    void scanAgainAtTheConfiguredMaximumIsRefused() {
        SosRequest maxed = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(maxed, "searchExpansions", (short) 2);
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(maxed));

        assertThatThrownBy(() -> service.expandSearch(CUSTOMER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.SOS_EXPANSION_LIMIT_REACHED));
        verify(sosDispatchService, never()).expand(any(), any());
    }

    /**
     * A double-tap. Both calls read {@code searchExpansions = 0}; the compare-and-set means only
     * one writes, and the loser returns the state the winner produced rather than an error for
     * something the customer wanted anyway. <b>Exactly one expansion, one dispatch wave.</b>
     */
    @Test
    void aDoubleTappedScanAgainExpandsOnceAndDispatchesOnce() {
        SosRequest searching = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        SosRequest expandedOnce = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(expandedOnce, "searchExpansions", (short) 1);
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(searching));
        // The CAS succeeds once and then loses, exactly as two racing callers would see it.
        when(sosRequestRepository.expandSearch(anyLong(), Mockito.anyShort(), Mockito.anyShort(),
                Mockito.anyShort(), any(), any(), any())).thenReturn(1, 0);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(searching))
                .thenReturn(Optional.of(expandedOnce))
                .thenReturn(Optional.of(expandedOnce));

        service.expandSearch(CUSTOMER_ID, REQUEST_ID);
        SosRequestResponse second = service.expandSearch(CUSTOMER_ID, REQUEST_ID);

        assertThat(second.id()).isEqualTo(REQUEST_ID);
        verify(sosDispatchService, times(1)).expand(any(), any());
        verify(sosEventService, times(1)).recordCustomer(eq(REQUEST_ID), eq(CUSTOMER_ID),
                eq(SosEventType.SEARCH_EXPANDED), any(), any(), any());
    }

    /**
     * A customer who asks to keep looking must not be expired seconds later by a clock set before
     * they asked. Both deadlines are pushed out in the same guarded write.
     */
    @Test
    void scanAgainExtendsTheDeadlineItIsSearchingAgainst() {
        properties.setMatchingWindowSeconds(150);
        properties.setSelectionWindowSeconds(120);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosRequestRepository.expandSearch(anyLong(), Mockito.anyShort(), Mockito.anyShort(),
                Mockito.anyShort(), any(), any(), any())).thenReturn(1);

        service.expandSearch(CUSTOMER_ID, REQUEST_ID);

        var matching = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var selection = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var now = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(sosRequestRepository).expandSearch(eq(REQUEST_ID), Mockito.anyShort(), Mockito.anyShort(),
                Mockito.anyShort(), matching.capture(), selection.capture(), now.capture());
        assertThat(matching.getValue()).isEqualTo(now.getValue().plusSeconds(150));
        assertThat(selection.getValue()).isEqualTo(now.getValue().plusSeconds(120));
    }

    @Test
    void scanAgainRejectsANonOwningCustomer() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));

        assertThatThrownBy(() -> service.expandSearch(OTHER_CUSTOMER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    /** An expired request is not something to widen; the reason is specific, not generic. */
    @Test
    void scanAgainOnAnExpiredRequestReportsWindowExpired() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.EXPIRED)));

        assertThatThrownBy(() -> service.expandSearch(CUSTOMER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_WINDOW_EXPIRED));
    }

    /** Disabling expansion entirely is a legal deployment, and it must actually disable it. */
    @Test
    void scanAgainIsRefusedWhenExpansionIsConfiguredOff() {
        properties.setMaxSearchExpansions(0);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));

        assertThatThrownBy(() -> service.expandSearch(CUSTOMER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.SOS_EXPANSION_LIMIT_REACHED));
    }

    @Test
    void selectRejectsAnOfferThatWasNeverAccepted() {
        SosRequest request = selectableRequest();
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.REJECTED)));

        assertThatThrownBy(() -> service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.SOS_CANDIDATE_NOT_AVAILABLE));
    }

    /** An offer belonging to a different SOS request must never be selectable here. */
    @Test
    void selectRejectsAnOfferFromAnotherRequest() {
        SosRequest request = selectableRequest();
        SosOffer foreign = offer(SosOfferStatus.ACCEPTED);
        setField(foreign, "sosRequestId", 999L);
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.SOS_CANDIDATE_NOT_AVAILABLE));
    }

    @Test
    void selectIsRefusedWhenTheRequestIsNotAwaitingSelection() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));

        assertThatThrownBy(() -> service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_INVALID_STATE));
    }

    // ------------------------------------------------------------------
    // Deadlines
    // ------------------------------------------------------------------

    /**
     * The backend, not a frontend timer, is the source of truth: a request read after its
     * selection deadline is expired on that read.
     */
    @Test
    void readingAnOverdueSelectionWindowExpiresTheRequest() {
        SosRequest overdue = request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(overdue, "selectionExpiresAt", Instant.now().minus(1, ChronoUnit.MINUTES));
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(overdue))
                .thenReturn(Optional.of(request(SosRequestStatus.EXPIRED)));
        when(sosRequestRepository.expireIfStatus(eq(REQUEST_ID),
                eq(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION), any())).thenReturn(1);

        SosRequestResponse response = service.getRequest(CUSTOMER_ID, UserRole.CUSTOMER.name(), REQUEST_ID);

        assertThat(response.status()).isEqualTo(SosRequestStatus.EXPIRED);
        verify(sosOfferRepository).closeAllOpenOffers(eq(REQUEST_ID), any());
        verify(issueRepository).revertToOpen(eq(ISSUE_ID), any());
        verify(notificationService).recordSosNotification(REQUEST_ID, CUSTOMER_ID,
                NotificationMessageType.SOS_EXPIRED);
    }

    /**
     * Selecting after the deadline is refused by the lazy expiry before any write is attempted,
     * and reported as {@code SOS_WINDOW_EXPIRED} (410) rather than a generic conflict — this is
     * the common real failure and the customer deserves to be told what actually happened.
     */
    @Test
    void selectAfterTheDeadlineIsRefusedAsWindowExpired() {
        SosRequest overdue = request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(overdue, "selectionExpiresAt", Instant.now().minus(1, ChronoUnit.MINUTES));
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(overdue))
                .thenReturn(Optional.of(request(SosRequestStatus.EXPIRED)));
        when(sosRequestRepository.expireIfStatus(anyLong(), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.selectProfessional(CUSTOMER_ID, REQUEST_ID, OFFER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_WINDOW_EXPIRED));
        verify(sosRequestRepository, never()).selectProfessional(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
    }

    /**
     * An elapsed response window with acceptances in hand opens the selection window rather than
     * expiring a request that has usable candidates.
     */
    @Test
    void overdueMatchingWindowWithAcceptancesOpensSelectionInstead() {
        SosRequest overdue = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(overdue, "matchingExpiresAt", Instant.now().minusSeconds(5));
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(overdue))
                .thenReturn(Optional.of(overdue))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION)));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(1L);
        when(sosRequestRepository.openSelectionWindow(eq(REQUEST_ID), any(), any())).thenReturn(1);

        service.getRequest(CUSTOMER_ID, UserRole.CUSTOMER.name(), REQUEST_ID);

        verify(sosRequestRepository).openSelectionWindow(eq(REQUEST_ID), any(), any());
        verify(sosRequestRepository, never()).expireIfStatus(anyLong(), any(), any());
    }

    @Test
    void overdueMatchingWindowWithNoAcceptancesExpiresTheRequest() {
        SosRequest overdue = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(overdue, "matchingExpiresAt", Instant.now().minusSeconds(5));
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(overdue))
                .thenReturn(Optional.of(overdue))
                .thenReturn(Optional.of(request(SosRequestStatus.EXPIRED)));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(0L);
        when(sosRequestRepository.expireIfStatus(eq(REQUEST_ID),
                eq(SosRequestStatus.WAITING_FOR_PROFESSIONALS), any())).thenReturn(1);

        service.getRequest(CUSTOMER_ID, UserRole.CUSTOMER.name(), REQUEST_ID);

        verify(sosRequestRepository).expireIfStatus(eq(REQUEST_ID),
                eq(SosRequestStatus.WAITING_FOR_PROFESSIONALS), any());
    }

    // ------------------------------------------------------------------
    // Selection window opening
    // ------------------------------------------------------------------

    /**
     * <b>The first acceptance is enough.</b> This is the rule the whole SOS screen turns on: one
     * professional has said they can come, so the customer may take them — no quota, no timer.
     */
    @Test
    void selectionWindowOpensOnTheVeryFirstAcceptance() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(1L);
        when(sosRequestRepository.openSelectionWindow(eq(REQUEST_ID), any(), any())).thenReturn(1);

        assertThat(service.maybeOpenSelectionWindow(REQUEST_ID, false)).isTrue();
        verify(sosEventService).recordSystem(eq(REQUEST_ID), eq(SosEventType.CANDIDATES_READY), any(), any(), any());
        verify(sosEventService).recordSystem(eq(REQUEST_ID), eq(SosEventType.CUSTOMER_SELECTION_STARTED),
                any(), any(), any());
        verify(notificationService).recordSosNotification(REQUEST_ID, CUSTOMER_ID,
                NotificationMessageType.SOS_CANDIDATES_READY);
    }

    @Test
    void selectionWindowOpensOnceTheTargetCountIsReachedToo() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(3L);
        when(sosRequestRepository.openSelectionWindow(eq(REQUEST_ID), any(), any())).thenReturn(1);

        assertThat(service.maybeOpenSelectionWindow(REQUEST_ID, false)).isTrue();
    }

    /** Zero is still zero — there is nothing to choose between, forced or not. */
    @Test
    void selectionWindowStaysShutWithNoAcceptancesAtAll() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(0L);

        assertThat(service.maybeOpenSelectionWindow(REQUEST_ID, false)).isFalse();
        assertThat(service.maybeOpenSelectionWindow(REQUEST_ID, true)).isFalse();
        verify(sosRequestRepository, never()).openSelectionWindow(anyLong(), any(), any());
    }

    @Test
    void forcedOpenAcceptsFewerThanTheTargetButNeverZero() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED))
                .thenReturn(1L, 0L);
        when(sosRequestRepository.openSelectionWindow(eq(REQUEST_ID), any(), any())).thenReturn(1);

        assertThat(service.maybeOpenSelectionWindow(REQUEST_ID, true)).isTrue();
        assertThat(service.maybeOpenSelectionWindow(REQUEST_ID, true)).isFalse();
    }

    @Test
    void selectionWindowUsesTheConfiguredDuration() {
        properties.setSelectionWindowSeconds(90);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(3L);
        when(sosRequestRepository.openSelectionWindow(eq(REQUEST_ID), any(), any())).thenReturn(1);

        service.maybeOpenSelectionWindow(REQUEST_ID, false);

        var now = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var expires = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(sosRequestRepository).openSelectionWindow(eq(REQUEST_ID), now.capture(), expires.capture());
        assertThat(expires.getValue()).isEqualTo(now.getValue().plusSeconds(90));
    }

    // ------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------

    @Test
    void candidatesAreCappedAtTheTargetCount() {
        properties.setTargetCandidateCount(3);
        SosRequest request = selectableRequest();
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(sosOfferRepository.findBySosRequestIdAndStatusOrderByIdAsc(REQUEST_ID, SosOfferStatus.ACCEPTED))
                .thenReturn(List.of(offer(SosOfferStatus.ACCEPTED), offer(SosOfferStatus.ACCEPTED),
                        offer(SosOfferStatus.ACCEPTED), offer(SosOfferStatus.ACCEPTED),
                        offer(SosOfferStatus.ACCEPTED)));

        SosCandidatesResponse response = service.getCandidates(CUSTOMER_ID, REQUEST_ID);

        assertThat(response.candidates()).hasSize(3);
        assertThat(response.selectionOpen()).isTrue();
    }

    /** Each expansion buys the shortlist one more slot, so a widened search has somewhere to put
     *  what it finds. */
    @Test
    void theCandidateCapGrowsWithEachExpansion() {
        properties.setTargetCandidateCount(3);
        SosRequest request = selectableRequest();
        setField(request, "searchExpansions", (short) 2);
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(sosOfferRepository.findBySosRequestIdAndStatusOrderByIdAsc(REQUEST_ID, SosOfferStatus.ACCEPTED))
                .thenReturn(List.of(offer(SosOfferStatus.ACCEPTED), offer(SosOfferStatus.ACCEPTED),
                        offer(SosOfferStatus.ACCEPTED), offer(SosOfferStatus.ACCEPTED),
                        offer(SosOfferStatus.ACCEPTED), offer(SosOfferStatus.ACCEPTED)));

        assertThat(service.getCandidates(CUSTOMER_ID, REQUEST_ID).candidates()).hasSize(5);
    }

    /**
     * The property the expansion flow depends on: <b>a candidate already on screen is never
     * pushed off by a faster newcomer.</b> The shortlist is filled first-come (ascending offer
     * id) and only then sorted by ETA for display — so the two early responders survive the cap
     * even though the late arrival has the best ETA of the three, and the display order still
     * leads with whoever gets there soonest.
     */
    @Test
    void anEarlierCandidateIsNeverEvictedByAFasterLaterOne() {
        properties.setTargetCandidateCount(2);
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(selectableRequest()));
        when(sosOfferRepository.findBySosRequestIdAndStatusOrderByIdAsc(REQUEST_ID, SosOfferStatus.ACCEPTED))
                .thenReturn(List.of(offerWith(201L, 40), offerWith(202L, 30), offerWith(203L, 5)));
        when(assembler.toCandidate(any())).thenAnswer(inv -> candidateOf(inv.getArgument(0)));

        SosCandidatesResponse response = service.getCandidates(CUSTOMER_ID, REQUEST_ID);

        assertThat(response.candidates()).extracting(SosCandidate::offerId).containsExactly(202L, 201L);
    }

    /** Polling before anyone has accepted is normal, not an error. */
    @Test
    void candidatesBeforeAnyoneAcceptsIsAnEmptyListNotAnError() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosOfferRepository.findBySosRequestIdAndStatusOrderByIdAsc(REQUEST_ID, SosOfferStatus.ACCEPTED))
                .thenReturn(List.of());

        SosCandidatesResponse response = service.getCandidates(CUSTOMER_ID, REQUEST_ID);

        assertThat(response.candidates()).isEmpty();
        assertThat(response.selectionOpen()).isFalse();
    }

    @Test
    void candidatesRejectsANonOwningCustomer() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION)));

        assertThatThrownBy(() -> service.getCandidates(OTHER_CUSTOMER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    // ------------------------------------------------------------------
    // Authorization on reads
    // ------------------------------------------------------------------

    @Test
    void anUnrelatedProfessionalCannotReadTheRequest() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(professional()));
        when(sosOfferRepository.findBySosRequestIdAndProfessionalId(REQUEST_ID, PROFESSIONAL_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRequest(PROFESSIONAL_USER_ID, UserRole.PROFESSIONAL.name(), REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    /** A professional who was offered the job may see it — visibility is not authority to change it. */
    @Test
    void anOfferedProfessionalCanReadTheRequest() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(professional()));
        when(sosOfferRepository.findBySosRequestIdAndProfessionalId(REQUEST_ID, PROFESSIONAL_ID))
                .thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));

        assertThat(service.getRequest(PROFESSIONAL_USER_ID, UserRole.PROFESSIONAL.name(), REQUEST_ID)).isNotNull();
    }

    // ------------------------------------------------------------------
    // Address privacy — availability is not assignment
    // ------------------------------------------------------------------
    //
    // The assembler is mocked here, so what these assert is the *decision*: which
    // SosAddressAccess SosService hands it. The redaction that decision drives is asserted
    // directly against the real assembler in SosResponseAssemblerTest. Together they cover
    // "the right call is made" and "the call does the right thing".

    /** The customer owns the problem and the address. They always see it in full. */
    @Test
    void theCustomerSeesTheExactAddress() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION)));

        service.getRequest(CUSTOMER_ID, UserRole.CUSTOMER.name(), REQUEST_ID);

        verify(assembler).toRequestResponse(any(SosRequest.class), eq(SosAddressAccess.FULL));
    }

    /**
     * Offers fan out to up to 15 professionals. None of them has any business holding a
     * stranger's street address on the strength of having been asked.
     */
    @Test
    void anOfferedProfessionalDoesNotSeeTheExactAddress() {
        assertOfferHolderIsRedacted(SosOfferStatus.OFFERED);
    }

    /** Opening the card is not a relationship either. */
    @Test
    void aProfessionalWhoOnlyViewedTheOfferDoesNotSeeTheExactAddress() {
        assertOfferHolderIsRedacted(SosOfferStatus.VIEWED);
    }

    /**
     * <b>The load-bearing case.</b> {@code ACCEPTED} means "I am available and can come" — it is
     * not an award, and the customer may still choose one of the other candidates. Until they
     * choose, an available professional gets exactly what an unanswered one gets.
     */
    @Test
    void anAvailableAcceptedProfessionalStillDoesNotSeeTheExactAddress() {
        assertOfferHolderIsRedacted(SosOfferStatus.ACCEPTED);
    }

    private void assertOfferHolderIsRedacted(SosOfferStatus offerStatus) {
        // selected_professional_id is null: nobody has been chosen yet.
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION)));
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(professional()));
        when(sosOfferRepository.findBySosRequestIdAndProfessionalId(REQUEST_ID, PROFESSIONAL_ID))
                .thenReturn(Optional.of(offer(offerStatus)));

        service.getRequest(PROFESSIONAL_USER_ID, UserRole.PROFESSIONAL.name(), REQUEST_ID);

        verify(assembler).toRequestResponse(any(SosRequest.class), eq(SosAddressAccess.STREET_AND_CITY));
    }

    /** Selection is what grants the address — and it grants it immediately, before confirmation. */
    @Test
    void theSelectedProfessionalSeesTheExactAddress() {
        SosRequest selected = request(SosRequestStatus.PROFESSIONAL_SELECTED);
        setField(selected, "selectedProfessionalId", PROFESSIONAL_ID);
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(selected));
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(professional()));
        when(sosOfferRepository.findBySosRequestIdAndProfessionalId(REQUEST_ID, PROFESSIONAL_ID))
                .thenReturn(Optional.of(offer(SosOfferStatus.SELECTED)));

        service.getRequest(PROFESSIONAL_USER_ID, UserRole.PROFESSIONAL.name(), REQUEST_ID);

        verify(assembler).toRequestResponse(any(SosRequest.class), eq(SosAddressAccess.FULL));
    }

    /**
     * A losing candidate on a decided request loses the address along with the job — the check is
     * against {@code selected_professional_id}, so it flips for everyone at the same instant.
     */
    @Test
    void aLosingCandidateOnADecidedRequestIsStillRedacted() {
        SosRequest selected = request(SosRequestStatus.PROFESSIONAL_SELECTED);
        setField(selected, "selectedProfessionalId", 999L);
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(selected));
        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(professional()));
        when(sosOfferRepository.findBySosRequestIdAndProfessionalId(REQUEST_ID, PROFESSIONAL_ID))
                .thenReturn(Optional.of(offer(SosOfferStatus.NOT_SELECTED)));

        service.getRequest(PROFESSIONAL_USER_ID, UserRole.PROFESSIONAL.name(), REQUEST_ID);

        verify(assembler).toRequestResponse(any(SosRequest.class), eq(SosAddressAccess.STREET_AND_CITY));
    }

    // ------------------------------------------------------------------
    // Retry — an SOS request is an attempt, not the problem
    // ------------------------------------------------------------------

    @Test
    void retryIsAllowedAfterAPreviousAttemptExpired() {
        assertRetryAllowed();
    }

    @Test
    void retryIsAllowedAfterAPreviousAttemptFailed() {
        assertRetryAllowed();
    }

    @Test
    void retryIsAllowedAfterAPreviousAttemptWasCancelled() {
        assertRetryAllowed();
    }

    /**
     * All three terminal cases are the same call from this service's point of view — what differs
     * is only which status the previous row holds, and {@code existsActiveByIssueId} answers
     * {@code false} for every one of them. That the query's terminal set is exactly the right one
     * is asserted in {@code SosSchemaConstraintTest} against V36's index, which is where the two
     * definitions could actually drift apart.
     */
    private void assertRetryAllowed() {
        when(issueRepository.findById(ISSUE_ID))
                .thenReturn(Optional.of(issue(IssueUrgencyType.SOS, com.pronto.issues.entity.IssueStatus.OPEN)));
        when(sosRequestRepository.existsActiveByIssueId(ISSUE_ID)).thenReturn(false);
        when(sosRequestRepository.saveAndFlush(any(SosRequest.class))).thenAnswer(inv -> {
            SosRequest saved = inv.getArgument(0);
            setField(saved, "id", REQUEST_ID);
            return saved;
        });
        when(sosRequestRepository.startMatching(eq(REQUEST_ID), any(), any())).thenReturn(1);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.MATCHING)));

        SosRequestResponse response = service.create(CUSTOMER_ID, createRequest());

        assertThat(response.id()).isEqualTo(REQUEST_ID);
        // The same issue, reused: no second issue is created and nothing on it is rewritten.
        assertThat(response.issueId()).isEqualTo(ISSUE_ID);
        verify(sosDispatchService).dispatch(any(SosRequest.class));
        verify(issueRepository, never()).save(any(Issue.class));
    }

    /** ...but only one attempt may be in flight. This is the case the retry change must not open up. */
    @Test
    void retryIsRefusedWhileThepreviousAttemptIsStillActive() {
        when(issueRepository.findById(ISSUE_ID))
                .thenReturn(Optional.of(issue(IssueUrgencyType.SOS, com.pronto.issues.entity.IssueStatus.OPEN)));
        when(sosRequestRepository.existsActiveByIssueId(ISSUE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, createRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.SOS_REQUEST_ALREADY_EXISTS));
        verify(sosRequestRepository, never()).saveAndFlush(any(SosRequest.class));
    }

    // ------------------------------------------------------------------
    // Individual offer expiry
    // ------------------------------------------------------------------

    /** {@code OFFERED -> EXPIRED}: history row, notification, and nothing for the customer. */
    @Test
    void expiringAnOfferedOfferRecordsAnEventAndNotifiesOnlyThatProfessional() {
        assertOfferExpiryIsRecorded(SosOfferStatus.OFFERED);
    }

    /** {@code VIEWED -> EXPIRED}: opening the card does not exempt it from the deadline. */
    @Test
    void expiringAViewedOfferRecordsAnEventAndNotifiesOnlyThatProfessional() {
        assertOfferExpiryIsRecorded(SosOfferStatus.VIEWED);
    }

    private void assertOfferExpiryIsRecorded(SosOfferStatus from) {
        SosOffer expiring = offer(from);
        when(sosOfferRepository.expireOfferIfOpen(eq(OFFER_ID), any())).thenReturn(1);
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(expiring));
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional()));

        assertThat(service.expireOffer(OFFER_ID)).isTrue();

        verify(sosEventService).record(eq(REQUEST_ID), eq(SosEventType.OFFER_EXPIRED), eq(SosActorType.SYSTEM),
                eq(null), eq(PROFESSIONAL_ID), eq(OFFER_ID), eq(SosRequestStatus.WAITING_FOR_PROFESSIONALS),
                eq(null), any());
        verify(notificationService).recordSosNotification(REQUEST_ID, PROFESSIONAL_USER_ID,
                NotificationMessageType.SOS_OFFER_EXPIRED);
        // The customer is told nothing: "professional X ignored you" is neither actionable nor
        // true to what happened, and their dispatch view is deliberately aggregate.
        verify(notificationService, never()).recordSosNotification(eq(REQUEST_ID), eq(CUSTOMER_ID), any());
    }

    /**
     * Idempotence, which is the whole reason expiry is a guarded per-offer update rather than the
     * bulk statement it used to be. The sweep runs every 15s and can overlap itself, and it also
     * races professionals answering — a second pass must produce no second event and no second
     * notification.
     */
    @Test
    void aSecondSweepOverTheSameOfferProducesNoDuplicateEventOrNotification() {
        when(sosOfferRepository.expireOfferIfOpen(eq(OFFER_ID), any())).thenReturn(0);

        assertThat(service.expireOffer(OFFER_ID)).isFalse();

        verify(sosEventService, never()).record(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(notificationService, never()).recordSosNotification(anyLong(), anyLong(), any());
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    @Test
    void customerCanCancelAndOffersAreClosed() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)))
                .thenReturn(Optional.of(request(SosRequestStatus.CANCELLED)));
        when(sosRequestRepository.cancelIfStatus(eq(REQUEST_ID), eq(SosRequestStatus.WAITING_FOR_PROFESSIONALS),
                eq(SosActorType.CUSTOMER), any())).thenReturn(1);

        service.cancel(CUSTOMER_ID, UserRole.CUSTOMER.name(), REQUEST_ID);

        verify(sosOfferRepository).closeAllOpenOffers(eq(REQUEST_ID), any());
        verify(issueRepository).revertToOpen(eq(ISSUE_ID), any());
        verify(sosEventService).record(eq(REQUEST_ID), eq(SosEventType.CANCELLED), eq(SosActorType.CUSTOMER),
                eq(CUSTOMER_ID), any(), any(), eq(SosRequestStatus.WAITING_FOR_PROFESSIONALS),
                eq(SosRequestStatus.CANCELLED), any());
    }

    @Test
    void cancellingAnAlreadyTerminalRequestIsRefused() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.COMPLETED)));

        assertThatThrownBy(() -> service.cancel(CUSTOMER_ID, UserRole.CUSTOMER.name(), REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_INVALID_STATE));
    }

    /** Holding an unselected offer confers no right to cancel the customer's request. */
    @Test
    void anUnselectedProfessionalCannotCancel() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));

        assertThatThrownBy(() ->
                service.cancel(PROFESSIONAL_USER_ID, UserRole.PROFESSIONAL.name(), REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void cancellingAfterSelectionAlsoCancelsTheOrder() {
        SosRequest selected = request(SosRequestStatus.CONFIRMED);
        setField(selected, "selectedProfessionalId", PROFESSIONAL_ID);
        setField(selected, "selectedOfferId", OFFER_ID);
        setField(selected, "orderId", ORDER_ID);
        Order order = new Order(ISSUE_ID, CUSTOMER_ID, PROFESSIONAL_ID, Instant.now(), null,
                new BigDecimal("300.00"), null, "Tel Aviv", "Dizengoff", "10", null, null, null, null,
                new BigDecimal("250.00"), new BigDecimal("50.00"));
        setField(order, "id", ORDER_ID);
        setField(order, "orderStatus", com.pronto.bookings.entity.OrderStatus.CONFIRMED);

        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(selected))
                .thenReturn(Optional.of(request(SosRequestStatus.CANCELLED)));
        when(sosRequestRepository.cancelIfStatus(eq(REQUEST_ID), eq(SosRequestStatus.CONFIRMED),
                eq(SosActorType.CUSTOMER), any())).thenReturn(1);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(professionalRepository.findById(PROFESSIONAL_ID)).thenReturn(Optional.of(professional()));

        service.cancel(CUSTOMER_ID, UserRole.CUSTOMER.name(), REQUEST_ID);

        verify(orderRepository).cancelIfStatus(eq(ORDER_ID),
                eq(com.pronto.bookings.entity.OrderStatus.CONFIRMED),
                eq(com.pronto.bookings.entity.CancelledBy.CUSTOMER), any());
        verify(notificationService).recordSosNotification(REQUEST_ID, PROFESSIONAL_USER_ID,
                NotificationMessageType.SOS_CANCELLED);
    }
}
