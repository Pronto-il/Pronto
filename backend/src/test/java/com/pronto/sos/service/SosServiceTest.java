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

        when(assembler.toRequestResponse(any())).thenAnswer(inv -> stubResponse(inv.getArgument(0)));
    }

    // ---- fixtures ----

    private static SosRequestResponse stubResponse(SosRequest request) {
        return new SosRequestResponse(request.getId(), request.getIssueId(), request.getCustomerId(),
                request.getCategoryId(), null, null, request.getUrgency(), request.getStatus(),
                null, null, null, null, null, null, null, null, null,
                request.getSelectedProfessionalId(), null, request.getSelectedOfferId(), request.getOrderId(),
                request.getCancelledBy(), 0, 0, request.getMatchingExpiresAt(), request.getSelectionExpiresAt(),
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
        when(sosRequestRepository.existsByIssueId(ISSUE_ID)).thenReturn(false);
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

    /** One SOS request per issue — no double-activation. */
    @Test
    void createRejectsASecondSosRequestForTheSameIssue() {
        when(issueRepository.findById(ISSUE_ID))
                .thenReturn(Optional.of(issue(IssueUrgencyType.SOS, com.pronto.issues.entity.IssueStatus.OPEN)));
        when(sosRequestRepository.existsByIssueId(ISSUE_ID)).thenReturn(true);

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
        when(sosRequestRepository.existsByIssueId(ISSUE_ID)).thenReturn(false);
        when(sosRequestRepository.saveAndFlush(any(SosRequest.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("ux_sos_requests_issue"));

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

    /** Three acceptances means the customer chooses now, not after waiting out the timer. */
    @Test
    void selectionWindowOpensEarlyOnceTheTargetCountIsReached() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(3L);
        when(sosRequestRepository.openSelectionWindow(eq(REQUEST_ID), any(), any())).thenReturn(1);

        assertThat(service.maybeOpenSelectionWindow(REQUEST_ID, false)).isTrue();
        verify(sosEventService).recordSystem(eq(REQUEST_ID), eq(SosEventType.CANDIDATES_READY), any(), any(), any());
        verify(sosEventService).recordSystem(eq(REQUEST_ID), eq(SosEventType.CUSTOMER_SELECTION_STARTED),
                any(), any(), any());
        verify(notificationService).recordSosNotification(REQUEST_ID, CUSTOMER_ID,
                NotificationMessageType.SOS_CANDIDATES_READY);
    }

    @Test
    void selectionWindowStaysShutBelowTheTargetUnlessForced() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(1L);

        assertThat(service.maybeOpenSelectionWindow(REQUEST_ID, false)).isFalse();
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
        when(sosOfferRepository.findBySosRequestIdAndStatusOrderByEstimatedArrivalMinutesAsc(
                REQUEST_ID, SosOfferStatus.ACCEPTED))
                .thenReturn(List.of(offer(SosOfferStatus.ACCEPTED), offer(SosOfferStatus.ACCEPTED),
                        offer(SosOfferStatus.ACCEPTED), offer(SosOfferStatus.ACCEPTED),
                        offer(SosOfferStatus.ACCEPTED)));

        SosCandidatesResponse response = service.getCandidates(CUSTOMER_ID, REQUEST_ID);

        assertThat(response.candidates()).hasSize(3);
        assertThat(response.selectionOpen()).isTrue();
    }

    /** Polling before anyone has accepted is normal, not an error. */
    @Test
    void candidatesBeforeAnyoneAcceptsIsAnEmptyListNotAnError() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosOfferRepository.findBySosRequestIdAndStatusOrderByEstimatedArrivalMinutesAsc(
                REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(List.of());

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
