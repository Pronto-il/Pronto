package com.pronto.sos.service;

import com.pronto.bookings.repository.OrderRepository;
import com.pronto.issues.repository.IssueRepository;
import com.pronto.notifications.service.NotificationService;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.sos.config.SosProperties;
import com.pronto.sos.dto.SosCandidate;
import com.pronto.sos.dto.SosCandidateState;
import com.pronto.sos.dto.SosCandidatesResponse;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.sos.repository.SosRequestRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.repository.UserRepository;
import com.pronto.users.service.ContactVerificationGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * <b>Who the customer can see during an SOS call, and what they are allowed to believe about them.</b>
 *
 * <p>Before this feature the candidate list contained accepted offers only, so a customer whose
 * request had gone out to eight plumbers saw an empty tray until one of them answered — the screen
 * could not distinguish "we have contacted eight people" from "we have found nobody", and during an
 * emergency an empty list reads as the second.
 *
 * <p>The change is <b>visibility only</b>, and these tests exist mainly to pin the things that did
 * <em>not</em> change: a contacted professional carries no ETA, does not open the selection window,
 * and cannot be selected. The real assembler is used rather than a stub, because the ETA rule this
 * class is most concerned with lives inside it.
 */
class SosCandidateVisibilityTest {

    private static final Long CUSTOMER_ID = 1L;
    private static final Long ISSUE_ID = 2L;
    private static final Long CATEGORY_ID = 7L;
    private static final Long REQUEST_ID = 100L;
    private static final Long PROFESSIONAL_ID = 3L;

    private SosRequestRepository sosRequestRepository;
    private SosOfferRepository sosOfferRepository;
    private SosService service;

    @BeforeEach
    void setUp() {
        sosRequestRepository = Mockito.mock(SosRequestRepository.class);
        sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        ProfessionalRepository professionalRepository = Mockito.mock(ProfessionalRepository.class);

        // The REAL assembler over mocked repositories. The rule under test -- "a contacted
        // professional shows no arrival time" -- is implemented there, in its choice of which
        // column to read, so mocking it would test the mock.
        SosResponseAssembler assembler = new SosResponseAssembler(professionalRepository,
                Mockito.mock(UserRepository.class),
                Mockito.mock(com.pronto.professionals.repository.ReviewAggregateRepository.class),
                sosOfferRepository, Mockito.mock(StorageService.class), new SosProperties(),
                Mockito.mock(com.pronto.professionals.service.ProfessionalCoverageService.class));

        service = new SosService(sosRequestRepository, sosOfferRepository,
                Mockito.mock(IssueRepository.class), Mockito.mock(OrderRepository.class),
                professionalRepository, Mockito.mock(SosDispatchService.class),
                Mockito.mock(SosEventService.class), assembler, Mockito.mock(NotificationService.class),
                new SosProperties(), Mockito.mock(ContactVerificationGuard.class),
                Mockito.mock(com.pronto.maps.service.ServiceAddressGeocoder.class),
                new com.pronto.maps.service.SelectedPlaceValidator(),
                Mockito.mock(UserRepository.class));

        Mockito.lenient().when(professionalRepository.findById(anyLong())).thenReturn(Optional.empty());
        Mockito.lenient().when(professionalRepository.existsEligibleById(anyLong())).thenReturn(true);
    }

    // ------------------------------------------------------------------
    // Before anybody accepts
    // ------------------------------------------------------------------

    /**
     * The headline behaviour: professionals who have been asked are on screen while the request is
     * still gathering responses.
     */
    @Test
    void professionalsWhoReceivedTheRequestAreVisibleBeforeAnybodyAccepts() {
        givenRequest(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        givenOffers(offer(201L, SosOfferStatus.OFFERED, 1, null),
                offer(202L, SosOfferStatus.VIEWED, 2, null));

        SosCandidatesResponse response = service.getCandidates(CUSTOMER_ID, REQUEST_ID);

        assertThat(response.candidates()).extracting(SosCandidate::offerId).containsExactly(201L, 202L);
        assertThat(response.candidates()).extracting(SosCandidate::state)
                .containsOnly(SosCandidateState.REQUESTED);
    }

    /**
     * {@code OFFERED} and {@code VIEWED} are one state to a customer. "They opened your request" is
     * not progress and must not be rendered as any.
     */
    @Test
    void openedButUnansweredIsIndistinguishableFromNotYetOpened() {
        givenRequest(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        givenOffers(offer(201L, SosOfferStatus.OFFERED, 1, null),
                offer(202L, SosOfferStatus.VIEWED, 2, null));

        assertThat(service.getCandidates(CUSTOMER_ID, REQUEST_ID).candidates())
                .extracting(SosCandidate::state)
                .containsExactly(SosCandidateState.REQUESTED, SosCandidateState.REQUESTED);
    }

    /**
     * <b>No arrival time before somebody promises one.</b> The offer row carries the platform's own
     * dispatch-time estimate in {@code estimated_arrival_minutes} from the moment it is created, so
     * this is the assertion that stops that number being shown as though a human had committed to it.
     */
    @Test
    void aRequestedCandidateCarriesNoEtaEvenThoughTheOfferRowHasAPlatformEstimate() {
        givenRequest(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        SosOffer dispatched = offer(201L, SosOfferStatus.OFFERED, 1, null);
        setField(dispatched, "estimatedArrivalMinutes", (short) 18);   // the platform's guess
        givenOffers(dispatched);

        SosCandidate candidate = service.getCandidates(CUSTOMER_ID, REQUEST_ID).candidates().get(0);

        assertThat(candidate.estimatedArrivalMinutes()).isNull();
        assertThat(candidate.respondedAt()).isNull();
    }

    /**
     * Visible is not selectable. The selection window follows the request's status, which opens on a
     * genuine acceptance — so a tray full of contacted professionals offers nothing to press.
     */
    @Test
    void contactedProfessionalsDoNotOpenTheSelectionWindow() {
        givenRequest(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        givenOffers(offer(201L, SosOfferStatus.OFFERED, 1, null));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED))
                .thenReturn(0L);

        assertThat(service.getCandidates(CUSTOMER_ID, REQUEST_ID).selectionOpen()).isFalse();
    }

    // ------------------------------------------------------------------
    // After somebody accepts
    // ------------------------------------------------------------------

    /** An acceptance changes the state, brings the committed ETA with it, and opens the choice. */
    @Test
    void anAcceptedProfessionalIsMarkedAcceptedAndCarriesTheirCommittedEta() {
        givenRequest(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        givenOffers(offer(201L, SosOfferStatus.ACCEPTED, 1, (short) 20));

        SosCandidatesResponse response = service.getCandidates(CUSTOMER_ID, REQUEST_ID);

        assertThat(response.candidates()).singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.state()).isEqualTo(SosCandidateState.ACCEPTED);
                    assertThat(candidate.estimatedArrivalMinutes()).isEqualTo((short) 20);
                });
        assertThat(response.selectionOpen()).isTrue();
    }

    /**
     * <b>Accepted above requested, always.</b> The professionals the customer can act on are at the
     * top; nobody is promoted past them on the strength of a number they never gave.
     */
    @Test
    void acceptedProfessionalsSortAboveContactedOnes() {
        givenRequest(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        givenOffers(
                offer(201L, SosOfferStatus.OFFERED, 1, null),
                offer(202L, SosOfferStatus.ACCEPTED, 5, (short) 40),
                offer(203L, SosOfferStatus.VIEWED, 2, null),
                offer(204L, SosOfferStatus.ACCEPTED, 6, (short) 15));

        assertThat(service.getCandidates(CUSTOMER_ID, REQUEST_ID).candidates())
                .extracting(SosCandidate::offerId)
                // Accepted first, soonest committed arrival leading; then the contacted ones in the
                // platform's own match-rank order.
                .containsExactly(204L, 202L, 201L, 203L);
    }

    // ------------------------------------------------------------------
    // Leaving the list
    // ------------------------------------------------------------------

    /**
     * A professional who declined disappears — not greyed out, not shown in red. Their decision is
     * their own, and an emergency screen must not fill up with rows nobody can act on.
     */
    @Test
    void aRejectedProfessionalIsRemovedFromTheActiveList() {
        givenRequest(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        givenOffers(offer(201L, SosOfferStatus.REJECTED, 1, null),
                offer(202L, SosOfferStatus.ACCEPTED, 2, (short) 25));

        assertThat(service.getCandidates(CUSTOMER_ID, REQUEST_ID).candidates())
                .extracting(SosCandidate::offerId).containsExactly(202L);
    }

    /** Likewise an offer whose own response window lapsed. */
    @Test
    void anExpiredOfferIsRemovedFromTheActiveList() {
        givenRequest(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        givenOffers(offer(201L, SosOfferStatus.EXPIRED, 1, null),
                offer(202L, SosOfferStatus.ACCEPTED, 2, (short) 25));

        assertThat(service.getCandidates(CUSTOMER_ID, REQUEST_ID).candidates())
                .extracting(SosCandidate::offerId).containsExactly(202L);
    }

    /**
     * The customer-visible vocabulary is total over the statuses the filter admits, and loudly
     * incomplete over the ones it does not — so a declined professional appearing on a customer's
     * screen fails here rather than rendering as "waiting for a response".
     */
    @Test
    void theCustomerVocabularyRefusesToDescribeAClosedOffer() {
        assertThat(SosCandidateState.fromOfferStatus(SosOfferStatus.OFFERED))
                .isEqualTo(SosCandidateState.REQUESTED);
        assertThat(SosCandidateState.fromOfferStatus(SosOfferStatus.VIEWED))
                .isEqualTo(SosCandidateState.REQUESTED);
        assertThat(SosCandidateState.fromOfferStatus(SosOfferStatus.ACCEPTED))
                .isEqualTo(SosCandidateState.ACCEPTED);

        for (SosOfferStatus closed : List.of(SosOfferStatus.REJECTED, SosOfferStatus.EXPIRED,
                SosOfferStatus.SELECTED, SosOfferStatus.NOT_SELECTED)) {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> SosCandidateState.fromOfferStatus(closed))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private void givenRequest(SosRequestStatus status) {
        SosRequest request = new SosRequest(ISSUE_ID, CUSTOMER_ID, CATEGORY_ID, null, "leak",
                SosUrgency.URGENT, "תל אביב-יפו", "דיזנגוף", "10", null, null, null, null, null, null);
        setField(request, "id", REQUEST_ID);
        setField(request, "status", status);
        // Far in the future, so enforceDeadlines never terminates the request mid-test: these tests
        // are about what the list contains, not about expiry (SosLifecycleTimingTest owns that).
        setField(request, "matchingExpiresAt", Instant.now().plusSeconds(600));
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
    }

    private void givenOffers(SosOffer... offers) {
        when(sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(REQUEST_ID))
                .thenReturn(List.of(offers));
        Mockito.lenient().when(sosOfferRepository.countBySosRequestIdAndStatus(any(), any()))
                .thenReturn(0L);
    }

    /**
     * @param promisedEta the figure the professional committed to on acceptance
     *                    ({@code promised_eta_minutes}), or {@code null} for an offer nobody has
     *                    answered. Deliberately the only ETA these fixtures set by default — see
     *                    {@link #aRequestedCandidateCarriesNoEtaEvenThoughTheOfferRowHasAPlatformEstimate}
     *                    for the one that also sets the platform's dispatch estimate.
     */
    private static SosOffer offer(long offerId, SosOfferStatus status, int matchRank, Short promisedEta) {
        SosOffer offer = new SosOffer(REQUEST_ID, PROFESSIONAL_ID, matchRank, new BigDecimal("0.800"),
                new BigDecimal("8.00"), null, new BigDecimal("250.00"), new BigDecimal("50.00"),
                new BigDecimal("30.00"), Instant.now(), Instant.now().plusSeconds(600));
        setField(offer, "id", offerId);
        setField(offer, "status", status);
        if (promisedEta != null) {
            setField(offer, "promisedEtaMinutes", promisedEta);
            setField(offer, "estimatedArrivalMinutes", promisedEta);
            setField(offer, "respondedAt", Instant.now());
            setField(offer, "acceptedAt", Instant.now());
        }
        return offer;
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
