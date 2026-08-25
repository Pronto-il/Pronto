package com.pronto.sos.service;

import com.pronto.bookings.repository.OrderRepository;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.notifications.entity.NotificationMessageType;
import com.pronto.notifications.service.NotificationService;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.sos.repository.SosRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Professional-side responses and the operational transitions, including their authorization. */
class SosOfferServiceTest {

    /** MS4: `professionals` no longer stores a category or free-text place -- see the entity. */
    private static final long SERVICE_REGION_ID = 4L;
    private static final long BASE_CITY_ID = 40L;

    private static final Long REQUEST_ID = 100L;
    private static final Long OFFER_ID = 200L;
    private static final Long CUSTOMER_ID = 1L;
    private static final Long ISSUE_ID = 2L;
    private static final Long PROFESSIONAL_ID = 3L;
    private static final Long PROFESSIONAL_USER_ID = 33L;
    private static final Long OTHER_PROFESSIONAL_ID = 4L;
    private static final Long OTHER_PROFESSIONAL_USER_ID = 44L;
    private static final Long ORDER_ID = 500L;

    private SosOfferRepository sosOfferRepository;
    private SosRequestRepository sosRequestRepository;
    private ProfessionalRepository professionalRepository;
    private OrderRepository orderRepository;
    private IssueRepository issueRepository;
    private SosService sosService;
    private SosEventService sosEventService;
    private SosResponseAssembler assembler;
    private NotificationService notificationService;
    private SosOfferService service;

    @BeforeEach
    void setUp() {
        sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        sosRequestRepository = Mockito.mock(SosRequestRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        orderRepository = Mockito.mock(OrderRepository.class);
        issueRepository = Mockito.mock(IssueRepository.class);
        sosService = Mockito.mock(SosService.class);
        sosEventService = Mockito.mock(SosEventService.class);
        assembler = Mockito.mock(SosResponseAssembler.class);
        notificationService = Mockito.mock(NotificationService.class);
        service = new SosOfferService(sosOfferRepository, sosRequestRepository, professionalRepository,
                orderRepository, issueRepository, sosService, sosEventService, assembler, notificationService,
                Mockito.mock(com.pronto.maps.service.ArrivalVerifier.class));

        when(professionalRepository.findByUserId(PROFESSIONAL_USER_ID)).thenReturn(Optional.of(professional()));
        when(professionalRepository.findByUserId(OTHER_PROFESSIONAL_USER_ID))
                .thenReturn(Optional.of(otherProfessional()));
    }

    /**
     * Production MS2: the SOS arrival action now carries the professional's device position,
     * because the transition is geofence-verified. These tests mock {@code ArrivalVerifier}, so
     * the fix itself is inert here -- the geofence rule has its own tests
     * ({@code BookingsArrivalTest}, {@code maps.GeoDistanceTest}) and re-asserting it through
     * every consumer would only couple those tests to this flow.
     */
    private static com.pronto.bookings.dto.ArrivalRequest arrivalFix() {
        return new com.pronto.bookings.dto.ArrivalRequest(new java.math.BigDecimal("32.077000"),
                new java.math.BigDecimal("34.773900"), new java.math.BigDecimal("12"), java.time.Instant.now());
    }

    // ---- fixtures ----

    private static SosRequest request(SosRequestStatus status) {
        SosRequest request = new SosRequest(ISSUE_ID, CUSTOMER_ID, 7L, null, "leak", SosUrgency.URGENT,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null, null, null);
        setField(request, "id", REQUEST_ID);
        setField(request, "status", status);
        return request;
    }

    private static SosRequest selectedRequest(SosRequestStatus status) {
        SosRequest request = request(status);
        setField(request, "selectedProfessionalId", PROFESSIONAL_ID);
        setField(request, "selectedOfferId", OFFER_ID);
        setField(request, "orderId", ORDER_ID);
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
        Professional professional = new Professional(PROFESSIONAL_USER_ID, SERVICE_REGION_ID, BASE_CITY_ID, new BigDecimal("250.00"));
        setField(professional, "id", PROFESSIONAL_ID);
        return professional;
    }

    private static Professional otherProfessional() {
        Professional professional = new Professional(OTHER_PROFESSIONAL_USER_ID, SERVICE_REGION_ID, BASE_CITY_ID,
                new BigDecimal("250.00"));
        setField(professional, "id", OTHER_PROFESSIONAL_ID);
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

    // ------------------------------------------------------------------
    // Accept
    // ------------------------------------------------------------------

    @Test
    void acceptRecordsTheProfessionalsOwnEta() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS));
        when(sosOfferRepository.accept(eq(OFFER_ID), eq((short) 25), any())).thenReturn(1);

        service.accept(PROFESSIONAL_USER_ID, OFFER_ID, 25);

        verify(sosOfferRepository).accept(eq(OFFER_ID), eq((short) 25), any());
        verify(sosEventService).recordProfessional(eq(REQUEST_ID), eq(PROFESSIONAL_USER_ID), eq(PROFESSIONAL_ID),
                eq(OFFER_ID), eq(SosEventType.PROFESSIONAL_RESPONDED), any(), any(), any());
        verify(sosService).maybeOpenSelectionWindow(REQUEST_ID, false);
    }

    /**
     * <b>The selection window opening does not stop the search.</b> Selection opens on the first
     * acceptance, so if a second professional could not answer after that, a customer who was
     * given one option would be stuck with exactly one — and "סרוק שוב" would have nothing to
     * produce.
     */
    @Test
    void aProfessionalCanStillAcceptWhileTheCustomerIsChoosing() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION));
        when(sosOfferRepository.accept(anyLong(), any(), any())).thenReturn(1);

        service.accept(PROFESSIONAL_USER_ID, OFFER_ID, 20);

        verify(sosOfferRepository).accept(eq(OFFER_ID), eq((short) 20), any());
    }

    /**
     * <b>Accepting without an ETA is refused (MS3).</b> It used to fall back to the platform's
     * own dispatch-time estimate, which meant a candidate could reach the customer's decision
     * screen advertising a figure no professional had agreed to — and, since the ETA is now
     * locked at acceptance, would have locked in that invented figure. Nothing is written.
     */
    @Test
    void acceptingWithoutAnEtaIsRefused() {
        assertThatThrownBy(() -> service.accept(PROFESSIONAL_USER_ID, OFFER_ID, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(sosOfferRepository, never()).accept(anyLong(), any(), any());
    }

    /** The professional's own figure is what is stored — as both the live ETA and the promise. */
    @Test
    void acceptanceStoresTheProfessionalsOwnEta() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS));
        when(sosOfferRepository.accept(anyLong(), any(), any())).thenReturn(1);

        service.accept(PROFESSIONAL_USER_ID, OFFER_ID, 25);

        // One statement writes status, live ETA, promised ETA and accepted_at together, so there
        // is no window in which a candidate exists without the commitment beside them.
        verify(sosOfferRepository).accept(eq(OFFER_ID), eq((short) 25), any());
    }

    /**
     * The guarded update carries {@code expiresAt > now}, so 0 rows on a still-open offer means
     * it expired — the authoritative expiry check, not an application clock read.
     */
    @Test
    void acceptingAnExpiredOfferIsRefused() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS));
        when(sosOfferRepository.accept(anyLong(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.accept(PROFESSIONAL_USER_ID, OFFER_ID, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_WINDOW_EXPIRED));
    }

    @Test
    void acceptingAnAlreadyAnsweredOfferIsRefused() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.REJECTED)));
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS));

        assertThatThrownBy(() -> service.accept(PROFESSIONAL_USER_ID, OFFER_ID, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_OFFER_NOT_OPEN));
        verify(sosOfferRepository, never()).accept(anyLong(), any(), any());
    }

    /**
     * Once somebody has been chosen, late acceptances must not reopen the decision — the backend
     * half of "selection stops the search". No offer row moves, so no new candidate can appear
     * behind the customer's tracking screen.
     */
    @Test
    void acceptingAfterTheRequestMovedOnIsRefused() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(request(SosRequestStatus.PROFESSIONAL_SELECTED));

        assertThatThrownBy(() -> service.accept(PROFESSIONAL_USER_ID, OFFER_ID, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_INVALID_STATE));
        verify(sosOfferRepository, never()).accept(anyLong(), any(), any());
    }

    /**
     * <b>A closed scan does not close an offer (MS3).</b> The platform has stopped looking for
     * anybody new, but this professional's own response window is still open — the request is
     * still {@code WAITING_FOR_PROFESSIONALS}, so their acceptance lands and makes them a
     * candidate. Nothing here consults the scan window; the offer's own {@code expires_at} is
     * the only deadline that governs this call, which is what keeps the two timers independent.
     */
    @Test
    void acceptingAfterTheScanEndedButWithinTheOwnWindowStillCreatesACandidate() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));
        SosRequest scanClosed = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(scanClosed, "matchingExpiresAt", java.time.Instant.now().minusSeconds(60));
        when(sosService.loadRequest(REQUEST_ID)).thenReturn(scanClosed);
        when(sosOfferRepository.accept(anyLong(), any(), any())).thenReturn(1);

        service.accept(PROFESSIONAL_USER_ID, OFFER_ID, 20);

        verify(sosOfferRepository).accept(eq(OFFER_ID), eq((short) 20), any());
        // ...and the customer's window opens on it, so the candidate is immediately selectable.
        verify(sosService).maybeOpenSelectionWindow(REQUEST_ID, false);
    }

    /**
     * The same, once the customer already has somebody on screen: acceptances keep landing while
     * the customer is deciding, so candidates appear progressively rather than in one batch.
     */
    @Test
    void acceptingWhileTheCustomerIsAlreadyChoosingIsAllowed() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION));
        when(sosOfferRepository.accept(anyLong(), any(), any())).thenReturn(1);

        service.accept(PROFESSIONAL_USER_ID, OFFER_ID, 12);

        verify(sosOfferRepository).accept(eq(OFFER_ID), eq((short) 12), any());
    }

    /** A professional may only ever touch an offer that was sent to them. */
    @Test
    void anotherProfessionalCannotAcceptSomeoneElsesOffer() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));

        assertThatThrownBy(() -> service.accept(OTHER_PROFESSIONAL_USER_ID, OFFER_ID, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(sosOfferRepository, never()).accept(anyLong(), any(), any());
    }

    // ------------------------------------------------------------------
    // Reject / view / ETA
    // ------------------------------------------------------------------

    @Test
    void rejectMarksTheOfferAndRecordsAnEvent() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));
        when(sosOfferRepository.reject(eq(OFFER_ID), any())).thenReturn(1);
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS));

        service.reject(PROFESSIONAL_USER_ID, OFFER_ID);

        verify(sosOfferRepository).reject(eq(OFFER_ID), any());
        verify(sosEventService).recordProfessional(eq(REQUEST_ID), eq(PROFESSIONAL_USER_ID), eq(PROFESSIONAL_ID),
                eq(OFFER_ID), eq(SosEventType.PROFESSIONAL_RESPONDED), any(), any(), eq("Declined"));
    }

    @Test
    void rejectingAnAlreadyAnsweredOfferIsRefused() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.ACCEPTED)));
        when(sosOfferRepository.reject(anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reject(PROFESSIONAL_USER_ID, OFFER_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_OFFER_NOT_OPEN));
    }

    @Test
    void openingAnOfferMarksItViewed() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));
        when(sosOfferRepository.markViewed(eq(OFFER_ID), any())).thenReturn(1);
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS));

        service.getOffer(PROFESSIONAL_USER_ID, OFFER_ID);

        verify(sosOfferRepository).markViewed(eq(OFFER_ID), any());
        verify(sosEventService).recordProfessional(eq(REQUEST_ID), eq(PROFESSIONAL_USER_ID), eq(PROFESSIONAL_ID),
                eq(OFFER_ID), eq(SosEventType.OFFER_VIEWED), any(), any(), any());
    }

    /** Re-opening must be a silent no-op, never a second event or an error. */
    @Test
    void reopeningAnAnsweredOfferRecordsNoSecondViewEvent() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.ACCEPTED)));
        when(sosOfferRepository.markViewed(anyLong(), any())).thenReturn(0);
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS));

        service.getOffer(PROFESSIONAL_USER_ID, OFFER_ID);

        verify(sosEventService, never()).recordProfessional(anyLong(), anyLong(), anyLong(), anyLong(),
                eq(SosEventType.OFFER_VIEWED), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // ETA locking (MS3)
    // ------------------------------------------------------------------

    /**
     * <b>The commitment is final.</b> An ETA used to be revisable while {@code ACCEPTED} or
     * {@code SELECTED}, on the reasoning that traffic changes. The reasoning that beats it: the
     * customer picks a professional because of that number, so a revisable ETA is an invitation
     * to win the job with fifteen minutes and deliver fifty.
     */
    @Test
    void anAcceptedEtaCannotBeRevised() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.ACCEPTED)));

        assertThatThrownBy(() -> service.updateEta(PROFESSIONAL_USER_ID, OFFER_ID, 40))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_ETA_LOCKED));
    }

    /** Being chosen does not reopen it either — if anything, that is when it matters most. */
    @Test
    void aSelectedProfessionalCannotReviseTheirEtaEither() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.SELECTED)));

        assertThatThrownBy(() -> service.updateEta(PROFESSIONAL_USER_ID, OFFER_ID, 5))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_ETA_LOCKED));
    }

    /**
     * Enforced in the domain, not by hiding a control: <b>no ETA is written by any path other
     * than acceptance</b>, so a refused revision leaves the stored figure untouched — there is
     * nothing to write it with.
     */
    @Test
    void aRefusedEtaRevisionWritesNothingAtAll() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.ACCEPTED)));

        assertThatThrownBy(() -> service.updateEta(PROFESSIONAL_USER_ID, OFFER_ID, 40))
                .isInstanceOf(ApiException.class);

        verify(sosEventService, never()).recordProfessional(anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any());
        verify(sosOfferRepository, never()).saveAndFlush(any());
    }

    /** Authorization still outranks the rule: another professional's offer is a 403, not a 409. */
    @Test
    void anotherProfessionalsEtaAttemptIsForbiddenNotLocked() {
        SosOffer foreign = offer(SosOfferStatus.ACCEPTED);
        setField(foreign, "professionalId", 999L);
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.updateEta(PROFESSIONAL_USER_ID, OFFER_ID, 40))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    // ------------------------------------------------------------------
    // Operational transitions
    // ------------------------------------------------------------------

    @Test
    void confirmAlsoAcceptsTheLinkedOrder() {
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(selectedRequest(SosRequestStatus.PROFESSIONAL_SELECTED));
        when(sosRequestRepository.confirm(eq(REQUEST_ID), eq(PROFESSIONAL_ID), any())).thenReturn(1);
        when(sosService.reload(REQUEST_ID)).thenReturn(selectedRequest(SosRequestStatus.CONFIRMED));

        service.confirm(PROFESSIONAL_USER_ID, REQUEST_ID);

        verify(orderRepository).acceptIfPending(eq(ORDER_ID), any());
        verify(sosEventService).recordProfessional(eq(REQUEST_ID), eq(PROFESSIONAL_USER_ID), eq(PROFESSIONAL_ID),
                eq(OFFER_ID), eq(SosEventType.PROFESSIONAL_CONFIRMED), eq(SosRequestStatus.PROFESSIONAL_SELECTED),
                eq(SosRequestStatus.CONFIRMED), any());
        verify(notificationService).recordSosNotification(REQUEST_ID, CUSTOMER_ID,
                NotificationMessageType.SOS_PROFESSIONAL_CONFIRMED);
    }

    /** Only the selected professional may drive the job — a losing candidate must not. */
    @Test
    void anUnselectedProfessionalCannotConfirm() {
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(selectedRequest(SosRequestStatus.PROFESSIONAL_SELECTED));

        assertThatThrownBy(() -> service.confirm(OTHER_PROFESSIONAL_USER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(sosRequestRepository, never()).confirm(anyLong(), anyLong(), any());
    }

    @Test
    void anUnselectedProfessionalCannotMarkOnTheWayArrivedOrComplete() {
        when(sosService.loadRequest(REQUEST_ID)).thenReturn(selectedRequest(SosRequestStatus.CONFIRMED));

        assertThatThrownBy(() -> service.onTheWay(OTHER_PROFESSIONAL_USER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.arrived(OTHER_PROFESSIONAL_USER_ID, REQUEST_ID, arrivalFix()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.complete(OTHER_PROFESSIONAL_USER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class);

        verify(sosRequestRepository, never()).markOnTheWay(anyLong(), anyLong(), any());
        verify(sosRequestRepository, never()).markArrived(anyLong(), anyLong(), any());
        verify(sosRequestRepository, never()).markCompleted(anyLong(), anyLong(), any());
    }

    /** The state machine rejects the illegal jump before any write is attempted. */
    @Test
    void completingBeforeArrivingIsRejected() {
        when(sosService.loadRequest(REQUEST_ID)).thenReturn(selectedRequest(SosRequestStatus.ON_THE_WAY));

        assertThatThrownBy(() -> service.complete(PROFESSIONAL_USER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_INVALID_STATE));
        verify(sosRequestRepository, never()).markCompleted(anyLong(), anyLong(), any());
    }

    @Test
    void onTheWayIsRejectedBeforeConfirmation() {
        when(sosService.loadRequest(REQUEST_ID))
                .thenReturn(selectedRequest(SosRequestStatus.PROFESSIONAL_SELECTED));

        assertThatThrownBy(() -> service.onTheWay(PROFESSIONAL_USER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_INVALID_STATE));
    }

    @Test
    void onTheWayMirrorsOntoTheOrderWithTheCommittedEta() {
        when(sosService.loadRequest(REQUEST_ID)).thenReturn(selectedRequest(SosRequestStatus.CONFIRMED));
        when(sosRequestRepository.markOnTheWay(eq(REQUEST_ID), eq(PROFESSIONAL_ID), any())).thenReturn(1);
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.SELECTED)));
        when(sosService.reload(REQUEST_ID)).thenReturn(selectedRequest(SosRequestStatus.ON_THE_WAY));

        service.onTheWay(PROFESSIONAL_USER_ID, REQUEST_ID);

        var now = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var eta = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(orderRepository).onTheWayIfConfirmed(eq(ORDER_ID), now.capture(), eta.capture());
        // The offer's committed 15-minute ETA, not a recomputed estimate.
        assertThat(eta.getValue()).isEqualTo(now.getValue().plusSeconds(15 * 60L));
    }

    /** ARRIVED is SOS-only; the shared order state machine has no such status. */
    @Test
    void arrivedDoesNotTouchTheOrder() {
        when(sosService.loadRequest(REQUEST_ID)).thenReturn(selectedRequest(SosRequestStatus.ON_THE_WAY));
        when(sosRequestRepository.markArrived(eq(REQUEST_ID), eq(PROFESSIONAL_ID), any())).thenReturn(1);
        when(sosService.reload(REQUEST_ID)).thenReturn(selectedRequest(SosRequestStatus.ARRIVED));

        service.arrived(PROFESSIONAL_USER_ID, REQUEST_ID, arrivalFix());

        verify(sosRequestRepository).markArrived(eq(REQUEST_ID), eq(PROFESSIONAL_ID), any());
        Mockito.verifyNoInteractions(orderRepository);
        verify(notificationService).recordSosNotification(REQUEST_ID, CUSTOMER_ID,
                NotificationMessageType.SOS_ARRIVED);
    }

    /** Completing the order is what makes the job reviewable. */
    @Test
    void completeFinishesTheRequestOrderAndIssueTogether() {
        when(sosService.loadRequest(REQUEST_ID)).thenReturn(selectedRequest(SosRequestStatus.ARRIVED));
        when(sosRequestRepository.markCompleted(eq(REQUEST_ID), eq(PROFESSIONAL_ID), any())).thenReturn(1);
        when(sosService.reload(REQUEST_ID)).thenReturn(selectedRequest(SosRequestStatus.COMPLETED));

        service.complete(PROFESSIONAL_USER_ID, REQUEST_ID);

        verify(sosRequestRepository).markCompleted(eq(REQUEST_ID), eq(PROFESSIONAL_ID), any());
        verify(orderRepository).completeIfOnTheWay(eq(ORDER_ID), any());
        verify(issueRepository).completeIfBooked(eq(ISSUE_ID), any());
        verify(notificationService).recordSosNotification(REQUEST_ID, CUSTOMER_ID,
                NotificationMessageType.SOS_COMPLETED);
    }

    /** Losing the guarded-update race surfaces as a conflict, not a silent success. */
    @Test
    void losingTheTransitionRaceReportsInvalidState() {
        when(sosService.loadRequest(REQUEST_ID)).thenReturn(selectedRequest(SosRequestStatus.CONFIRMED));
        when(sosRequestRepository.markOnTheWay(anyLong(), anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.onTheWay(PROFESSIONAL_USER_ID, REQUEST_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.SOS_INVALID_STATE));
    }

    /** A user with no professional profile has no business in any of these paths. */
    @Test
    void aUserWithoutAProfessionalProfileIsForbidden() {
        when(professionalRepository.findByUserId(777L)).thenReturn(Optional.empty());
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer(SosOfferStatus.OFFERED)));

        assertThatThrownBy(() -> service.accept(777L, OFFER_ID, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void aMissingOfferIsNotFound() {
        when(sosOfferRepository.findById(OFFER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(PROFESSIONAL_USER_ID, OFFER_ID, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
