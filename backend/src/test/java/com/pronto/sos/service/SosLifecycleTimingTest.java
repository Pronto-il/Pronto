package com.pronto.sos.service;

import com.pronto.users.service.ContactVerificationGuard;
import com.pronto.bookings.repository.OrderRepository;
import com.pronto.issues.entity.Issue;
import com.pronto.issues.entity.IssueStatus;
import com.pronto.issues.entity.IssueUrgencyType;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.notifications.service.NotificationService;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.CreateSosRequestRequest;
import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.dto.SosCandidatesResponse;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.sos.repository.SosRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>The three SOS timers, and the rule that they are independent.</b>
 *
 * <p>The MS3 lifecycle redesign replaced one coupled "matching window" with three clocks that
 * must not affect each other:
 *
 * <ol>
 *   <li><b>The scan window</b> (10 minutes from activation) — how long the platform keeps
 *       looking for new professionals. Its end stops dispatch and nothing else.</li>
 *   <li><b>Each professional's response window</b> (10 minutes from <em>their</em> offer) — so
 *       somebody contacted at minute 9 still has until minute 19.</li>
 *   <li><b>The customer's decision window</b> (10 minutes from the first acceptance) — which the
 *       scan ending must never close.</li>
 * </ol>
 *
 * <p>Every test below is written against a configured duration rather than a real wait: the
 * production semantics are "10 minutes", and the tests set two or three seconds and assert the
 * arithmetic and the state transitions. Nothing here sleeps.
 *
 * <p>Deliberately separate from {@code SosServiceTest} (which owns activation, selection and
 * authorization): these are the timing rules, and they are the part of this feature most likely
 * to be quietly re-coupled by a later change.
 */
class SosLifecycleTimingTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final Long ISSUE_ID = 2L;
    private static final Long CATEGORY_ID = 7L;
    private static final Long REQUEST_ID = 100L;
    private static final Long PROFESSIONAL_ID = 3L;

    private SosRequestRepository sosRequestRepository;
    private SosOfferRepository sosOfferRepository;
    private IssueRepository issueRepository;
    private SosDispatchService sosDispatchService;
    private SosEventService sosEventService;
    private SosResponseAssembler assembler;
    private SosProperties properties;
    private SosService service;

    @BeforeEach
    void setUp() {
        sosRequestRepository = Mockito.mock(SosRequestRepository.class);
        sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        issueRepository = Mockito.mock(IssueRepository.class);
        OrderRepository orderRepository = Mockito.mock(OrderRepository.class);
        ProfessionalRepository professionalRepository = Mockito.mock(ProfessionalRepository.class);
        sosDispatchService = Mockito.mock(SosDispatchService.class);
        sosEventService = Mockito.mock(SosEventService.class);
        assembler = Mockito.mock(SosResponseAssembler.class);
        NotificationService notificationService = Mockito.mock(NotificationService.class);
        properties = new SosProperties();
        service = new SosService(sosRequestRepository, sosOfferRepository, issueRepository, orderRepository,
                professionalRepository, sosDispatchService, sosEventService, assembler, notificationService,
                properties, Mockito.mock(ContactVerificationGuard.class));
        Mockito.lenient().when(assembler.toRequestResponse(any(), any())).thenReturn(null);
    }

    // ------------------------------------------------------------------
    // 1. The scan window
    // ------------------------------------------------------------------

    /**
     * Activation stamps both server-side clocks the rest of the flow runs on: when scanning
     * stops, and when the search first widens by itself. A browser is not consulted for either,
     * which is what makes a refresh a no-op.
     */
    @Test
    void activationStampsTheScanDeadlineAndTheFirstExpansionSchedule() {
        properties.setScanWindowSeconds(600);
        properties.setExpansionIntervalSeconds(120);
        givenActivatableIssue();

        service.create(CUSTOMER_ID, createRequest());

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> scanExpiresAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> nextExpansionAt = ArgumentCaptor.forClass(Instant.class);
        verify(sosRequestRepository).startMatching(eq(REQUEST_ID), now.capture(), scanExpiresAt.capture(),
                nextExpansionAt.capture());
        assertThat(scanExpiresAt.getValue()).isEqualTo(now.getValue().plusSeconds(600));
        assertThat(nextExpansionAt.getValue()).isEqualTo(now.getValue().plusSeconds(120));
    }

    /** Expansion off means no schedule at all, not a schedule nobody acts on. */
    @Test
    void activationSchedulesNoExpansionWhenTheFeatureIsDisabled() {
        properties.setMaxSearchExpansions(0);
        givenActivatableIssue();

        service.create(CUSTOMER_ID, createRequest());

        ArgumentCaptor<Instant> nextExpansionAt = ArgumentCaptor.forClass(Instant.class);
        verify(sosRequestRepository).startMatching(eq(REQUEST_ID), any(), any(), nextExpansionAt.capture());
        assertThat(nextExpansionAt.getValue()).isNull();
    }

    // ------------------------------------------------------------------
    // 2. Automatic expansion
    // ------------------------------------------------------------------

    /**
     * <b>Nobody presses anything.</b> The sweep finds a request whose widening is due and widens
     * it, recording the history row as a {@code SYSTEM} action because no customer asked.
     */
    @Test
    void anAutomaticExpansionWidensTheSearchAndIsRecordedAsASystemAction() {
        SosRequest searching = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        SosRequest widened = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(widened, "searchExpansions", (short) 1);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(searching))
                .thenReturn(Optional.of(widened));
        when(sosRequestRepository.expandSearch(eq(REQUEST_ID), eq((short) 0), eq((short) 1), anyShort(),
                any(), any())).thenReturn(1);
        when(sosDispatchService.expand(any(), any())).thenReturn(6);

        assertThat(service.expandSearchAutomatically(REQUEST_ID)).isTrue();

        ArgumentCaptor<SosSearchScope> scope = ArgumentCaptor.forClass(SosSearchScope.class);
        verify(sosDispatchService).expand(any(), scope.capture());
        assertThat(scope.getValue().level()).isEqualTo(1);
        assertThat(scope.getValue().poolSize())
                .isEqualTo(properties.getCandidatePoolSize() + properties.getExpansionPoolIncrement());
        verify(sosEventService).recordSystem(eq(REQUEST_ID), eq(SosEventType.SEARCH_EXPANDED), any(), any(), any());
        verify(sosEventService, never()).recordCustomer(anyLong(), anyLong(), any(), any(), any(), any());
    }

    /**
     * Two sweep passes overlapping — the compare-and-set means exactly one widens, and the loser
     * dispatches nothing at all rather than reporting an error to a background job.
     */
    @Test
    void twoOverlappingSweepsProduceExactlyOneExpansion() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosRequestRepository.expandSearch(anyLong(), anyShort(), anyShort(), anyShort(), any(), any()))
                .thenReturn(1, 0);

        assertThat(service.expandSearchAutomatically(REQUEST_ID)).isTrue();
        assertThat(service.expandSearchAutomatically(REQUEST_ID)).isFalse();

        verify(sosDispatchService, times(1)).expand(any(), any());
    }

    /**
     * <b>Nothing is contacted after the scan window closes.</b> The guard lives in the update's
     * {@code WHERE} clause, so the service's job on losing is to park the schedule and dispatch
     * nobody — which is what this asserts.
     */
    @Test
    void anExpansionThatLosesItsGuardDispatchesNobodyAndParksTheSchedule() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosRequestRepository.expandSearch(anyLong(), anyShort(), anyShort(), anyShort(), any(), any()))
                .thenReturn(0);

        assertThat(service.expandSearchAutomatically(REQUEST_ID)).isFalse();

        verify(sosDispatchService, never()).expand(any(), any());
        verify(sosRequestRepository).clearExpansionSchedule(eq(REQUEST_ID), any());
    }

    // ------------------------------------------------------------------
    // 3. Scan end vs. request end
    // ------------------------------------------------------------------

    /**
     * <b>The scan ending is not the request ending.</b> Nobody has accepted, but a professional
     * contacted late in the scan still holds an answerable offer — so the request stays alive and
     * keeps waiting for them, exactly as the product rule requires.
     */
    @Test
    void aClosedScanWithAnAnswerableOfferDoesNotExpireTheRequest() {
        SosRequest scanned = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(scanned, "matchingExpiresAt", Instant.now().minusSeconds(5));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(scanned));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(0L);
        when(sosOfferRepository.existsAnswerableOffer(eq(REQUEST_ID), any())).thenReturn(true);

        service.getRequest(CUSTOMER_ID, "CUSTOMER", REQUEST_ID);

        verify(sosRequestRepository, never()).expireIfStatus(anyLong(), any(), any());
        // ...and it stops asking the sweep to widen a search that can no longer widen.
        verify(sosRequestRepository).clearExpansionSchedule(eq(REQUEST_ID), any());
    }

    /** Once the scan is over and no offer can still be answered, there is genuinely nothing left. */
    @Test
    void aClosedScanWithNoAnswerableOfferAndNoAcceptancesExpiresTheRequest() {
        SosRequest scanned = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(scanned, "matchingExpiresAt", Instant.now().minusSeconds(5));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(scanned));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(0L);
        when(sosOfferRepository.existsAnswerableOffer(eq(REQUEST_ID), any())).thenReturn(false);
        when(sosRequestRepository.expireIfStatus(eq(REQUEST_ID), eq(SosRequestStatus.WAITING_FOR_PROFESSIONALS),
                any())).thenReturn(1);

        service.getRequest(CUSTOMER_ID, "CUSTOMER", REQUEST_ID);

        verify(sosRequestRepository).expireIfStatus(eq(REQUEST_ID),
                eq(SosRequestStatus.WAITING_FOR_PROFESSIONALS), any());
    }

    /**
     * A scan that ends with somebody available hands over to the customer's window rather than
     * expiring — the candidate is kept, not discarded.
     */
    @Test
    void aClosedScanWithAnAcceptanceOpensTheCustomersWindowInstead() {
        SosRequest scanned = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(scanned, "matchingExpiresAt", Instant.now().minusSeconds(5));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(scanned));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(1L);
        when(sosRequestRepository.openSelectionWindow(eq(REQUEST_ID), any())).thenReturn(1);

        service.getRequest(CUSTOMER_ID, "CUSTOMER", REQUEST_ID);

        verify(sosRequestRepository).openSelectionWindow(eq(REQUEST_ID), any());
        verify(sosRequestRepository, never()).expireIfStatus(anyLong(), any(), any());
    }

    // ------------------------------------------------------------------
    // 4. The customer's ability to choose — which no clock ends
    // ------------------------------------------------------------------

    /**
     * <b>Opening the choice sets no deadline.</b> The MS3 follow-up removed the customer-decision
     * window entirely, so there is nothing to write and nothing for a client to count down to.
     */
    @Test
    void openingTheChoiceWritesNoDeadline() {
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.WAITING_FOR_PROFESSIONALS)));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(1L);
        when(sosRequestRepository.openSelectionWindow(eq(REQUEST_ID), any())).thenReturn(1);

        service.maybeOpenSelectionWindow(REQUEST_ID, false);

        verify(sosRequestRepository).openSelectionWindow(eq(REQUEST_ID), any());
    }

    /**
     * <b>Example A from the brief.</b> Scan 12:00-12:10, one acceptance at 12:03, the customer
     * does nothing until 12:14 — four minutes after the scan closed and eleven after the
     * acceptance, which under the old ten-minute decision timer would have expired the request
     * and deleted a professional who had committed to come. They are still there, still
     * selectable.
     */
    @Test
    void anAcceptedProfessionalIsStillSelectableLongAfterTheScanAndTheAcceptance() {
        SosRequest choosing = request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(choosing, "matchingExpiresAt", Instant.now().minusSeconds(4 * 60));
        setField(choosing, "candidatesReadyAt", Instant.now().minusSeconds(11 * 60));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(choosing));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(1L);
        when(sosOfferRepository.findBySosRequestIdAndStatusOrderByIdAsc(REQUEST_ID, SosOfferStatus.ACCEPTED))
                .thenReturn(List.of(acceptedOffer(300L, 20)));

        SosCandidatesResponse response = service.getCandidates(CUSTOMER_ID, REQUEST_ID);

        assertThat(response.status()).isEqualTo(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        assertThat(response.selectionOpen()).isTrue();
        assertThat(response.candidates()).hasSize(1);
        verify(sosRequestRepository, never()).expireIfStatus(anyLong(), any(), any());
    }

    /**
     * The same request read hours later. Elapsed time is not an input to "is this still
     * actionable" at all — only what the offers say is.
     */
    @Test
    void timePassingAloneNeverExpiresARequestWithACandidate() {
        SosRequest choosing = request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(choosing, "matchingExpiresAt", Instant.now().minusSeconds(6 * 60 * 60));
        setField(choosing, "candidatesReadyAt", Instant.now().minusSeconds(6 * 60 * 60));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(choosing));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(2L);

        service.getRequest(CUSTOMER_ID, "CUSTOMER", REQUEST_ID);

        verify(sosRequestRepository, never()).expireIfStatus(anyLong(), any(), any());
    }

    /**
     * <b>Example C.</b> The scan has closed with nobody accepted, but one professional's own
     * response window is still open — the request waits for them rather than declaring failure.
     */
    @Test
    void aRequestWaitsWhileAnyProfessionalCanStillLegallyRespond() {
        SosRequest waiting = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(waiting, "matchingExpiresAt", Instant.now().minusSeconds(30));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(waiting));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(0L);
        when(sosOfferRepository.existsAnswerableOffer(eq(REQUEST_ID), any())).thenReturn(true);

        service.getRequest(CUSTOMER_ID, "CUSTOMER", REQUEST_ID);

        verify(sosRequestRepository, never()).expireIfStatus(anyLong(), any(), any());
    }

    /**
     * <b>The one way this flow ends by itself.</b> Scan closed, nothing accepted, and no offer
     * that could still be answered — now there is genuinely nothing left that can happen.
     */
    @Test
    void aRequestEndsOnlyWhenNothingCanHappenAtAll() {
        SosRequest waiting = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(waiting, "matchingExpiresAt", Instant.now().minusSeconds(30));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(waiting));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(0L);
        when(sosOfferRepository.existsAnswerableOffer(eq(REQUEST_ID), any())).thenReturn(false);
        when(sosRequestRepository.expireIfStatus(eq(REQUEST_ID), eq(SosRequestStatus.WAITING_FOR_PROFESSIONALS),
                any())).thenReturn(1);

        service.getRequest(CUSTOMER_ID, "CUSTOMER", REQUEST_ID);

        verify(sosRequestRepository).expireIfStatus(eq(REQUEST_ID),
                eq(SosRequestStatus.WAITING_FOR_PROFESSIONALS), any());
    }

    /**
     * The same rule reached from the choosing status: a degenerate but expressible shape where
     * every offer has gone and none was accepted. Nothing on screen is left to tap, so it ends.
     */
    @Test
    void aChoosingRequestWithNothingLeftToChooseFromAlsoEnds() {
        SosRequest choosing = request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(choosing, "matchingExpiresAt", Instant.now().minusSeconds(30));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(choosing));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(0L);
        when(sosOfferRepository.existsAnswerableOffer(eq(REQUEST_ID), any())).thenReturn(false);
        when(sosRequestRepository.expireIfStatus(eq(REQUEST_ID),
                eq(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION), any())).thenReturn(1);

        service.getRequest(CUSTOMER_ID, "CUSTOMER", REQUEST_ID);

        verify(sosRequestRepository).expireIfStatus(eq(REQUEST_ID),
                eq(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION), any());
    }

    /**
     * While the scan is still running the request always has a future — a later expansion may
     * contact somebody nobody has asked yet — so an empty moment is not an ending, and the
     * offer-state questions are not even asked.
     */
    @Test
    void anEmptyMomentDuringTheScanIsNotAnEnding() {
        SosRequest waiting = request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        setField(waiting, "matchingExpiresAt", Instant.now().plusSeconds(300));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(waiting));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(0L);

        service.getRequest(CUSTOMER_ID, "CUSTOMER", REQUEST_ID);

        verify(sosRequestRepository, never()).expireIfStatus(anyLong(), any(), any());
        verify(sosOfferRepository, never()).existsAnswerableOffer(anyLong(), any());
    }

    // ---- fixtures ----

    /** An accepted offer, for the "still selectable" reads above. */
    private static SosOffer acceptedOffer(long offerId, int etaMinutes) {
        SosOffer offer = new SosOffer(REQUEST_ID, PROFESSIONAL_ID, 1, new BigDecimal("0.8"),
                new BigDecimal("8.0"), etaMinutes, new BigDecimal("250.00"), new BigDecimal("50.00"),
                new BigDecimal("30.00"), Instant.now(), Instant.now().plusSeconds(600));
        setField(offer, "id", offerId);
        setField(offer, "status", SosOfferStatus.ACCEPTED);
        return offer;
    }

    private void givenActivatableIssue() {
        Issue issue = new Issue(CUSTOMER_ID, CATEGORY_ID, "leak", IssueUrgencyType.SOS);
        setField(issue, "id", ISSUE_ID);
        setField(issue, "status", IssueStatus.OPEN);
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(sosRequestRepository.existsActiveByIssueId(ISSUE_ID)).thenReturn(false);
        when(sosRequestRepository.saveAndFlush(any(SosRequest.class))).thenAnswer(inv -> {
            SosRequest saved = inv.getArgument(0);
            setField(saved, "id", REQUEST_ID);
            return saved;
        });
        when(sosRequestRepository.startMatching(eq(REQUEST_ID), any(), any(), any())).thenReturn(1);
        when(sosRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(request(SosRequestStatus.MATCHING)));
    }

    private static CreateSosRequestRequest createRequest() {
        return new CreateSosRequestRequest(ISSUE_ID, "Burst pipe", SosUrgency.URGENT,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null, null, null);
    }

    private static SosRequest request(SosRequestStatus status) {
        SosRequest request = new SosRequest(ISSUE_ID, CUSTOMER_ID, CATEGORY_ID, null, "leak", SosUrgency.URGENT,
                "Tel Aviv", "Dizengoff", "10", null, null, null, null, null, null);
        setField(request, "id", REQUEST_ID);
        setField(request, "status", status);
        return request;
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

}
