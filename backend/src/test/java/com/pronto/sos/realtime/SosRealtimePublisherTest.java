package com.pronto.sos.realtime;

import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.sos.entity.SosActorType;
import com.pronto.sos.entity.SosEvent;
import com.pronto.sos.entity.SosEventType;
import com.pronto.sos.entity.SosOffer;
import com.pronto.sos.entity.SosOfferStatus;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.event.SosDomainEvent;
import com.pronto.sos.repository.SosEventRepository;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.sos.repository.SosRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The realtime routing matrix. Every test here is ultimately asking one of two questions: does the
 * right person get told, and — more importantly — does the wrong person stay untold.
 *
 * <p>Assertions are deliberately written as "the complete set of recipients was exactly X" rather
 * than "X received something", because the failure mode that matters in this feature is an extra
 * recipient, not a missing one. A test that only checks the intended party would pass just as
 * happily while broadcasting a customer's SOS to every professional in the city.
 */
class SosRealtimePublisherTest {

    /** MS4: `professionals` no longer stores a category or free-text place -- see the entity. */
    private static final long SERVICE_REGION_ID = 4L;
    private static final long BASE_CITY_ID = 40L;

    private static final Long REQUEST_ID = 100L;
    private static final Long EVENT_ID = 900L;
    private static final Long CUSTOMER_USER_ID = 1L;

    // Three professionals: ids 10/20/30, whose user ids are 11/21/31.
    private static final Long PRO_A = 10L;
    private static final Long PRO_A_USER = 11L;
    private static final Long PRO_B = 20L;
    private static final Long PRO_B_USER = 21L;
    private static final Long PRO_C = 30L;
    private static final Long PRO_C_USER = 31L;

    private SosEventRepository sosEventRepository;
    private SosRequestRepository sosRequestRepository;
    private SosOfferRepository sosOfferRepository;
    private ProfessionalRepository professionalRepository;
    private SosRealtimeDelivery delivery;
    private SosRealtimePublisher publisher;

    @BeforeEach
    void setUp() {
        sosEventRepository = Mockito.mock(SosEventRepository.class);
        sosRequestRepository = Mockito.mock(SosRequestRepository.class);
        sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        professionalRepository = Mockito.mock(ProfessionalRepository.class);
        delivery = Mockito.mock(SosRealtimeDelivery.class);
        publisher = new SosRealtimePublisher(sosEventRepository, sosRequestRepository, sosOfferRepository,
                professionalRepository, delivery);

        when(professionalRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            List<Professional> result = new ArrayList<>();
            for (Long id : ids) {
                result.add(professional(id));
            }
            return result;
        });
        when(professionalRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.of(professional(inv.getArgument(0))));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static Professional professional(Long professionalId) {
        Professional professional = new Professional(professionalId + 1, SERVICE_REGION_ID, BASE_CITY_ID, new BigDecimal("250.00"));
        setField(professional, "id", professionalId);
        return professional;
    }

    private SosRequest request(SosRequestStatus status) {
        SosRequest request = new SosRequest(2L, CUSTOMER_USER_ID, 7L, null, "leak", SosUrgency.URGENT,
                "Tel Aviv", "Dizengoff", "10", "4", null, null, null, null, null);
        setField(request, "id", REQUEST_ID);
        setField(request, "status", status);
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        return request;
    }

    private SosOffer offer(Long id, Long professionalId, SosOfferStatus status) {
        SosOffer offer = new SosOffer(REQUEST_ID, professionalId, 1, new BigDecimal("0.8"),
                new BigDecimal("8.0"), 15, new BigDecimal("250.00"), new BigDecimal("50.00"),
                new BigDecimal("30.00"), Instant.now(), Instant.now().plusSeconds(120));
        setField(offer, "id", id);
        setField(offer, "status", status);
        when(sosOfferRepository.findById(id)).thenReturn(Optional.of(offer));
        return offer;
    }

    private void stubOffers(SosOffer... offers) {
        when(sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(REQUEST_ID)).thenReturn(List.of(offers));
    }

    private SosDomainEvent stubEvent(SosEventType type, Long offerId, Long professionalId) {
        SosEvent event = new SosEvent(REQUEST_ID, type, SosActorType.SYSTEM, null, professionalId, offerId,
                null, null, "detail");
        setField(event, "id", EVENT_ID);
        setField(event, "createdAt", Instant.parse("2026-08-21T12:00:00Z"));
        when(sosEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        return new SosDomainEvent(REQUEST_ID, EVENT_ID, type, null);
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

    /** Every (recipientUserId, eventType) pair the publisher emitted, in order. */
    private List<Map.Entry<Long, SosRealtimeEventType>> captureSends() {
        ArgumentCaptor<Long> users = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<SosRealtimeMessage> messages = ArgumentCaptor.forClass(SosRealtimeMessage.class);
        verify(delivery, Mockito.atLeast(0)).sendToUser(users.capture(), messages.capture());
        List<Map.Entry<Long, SosRealtimeEventType>> sends = new ArrayList<>();
        for (int i = 0; i < users.getAllValues().size(); i++) {
            sends.add(Map.entry(users.getAllValues().get(i), messages.getAllValues().get(i).eventType()));
        }
        return sends;
    }

    private SosRealtimeMessage captureMessageTo(Long userId, SosRealtimeEventType type) {
        ArgumentCaptor<Long> users = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<SosRealtimeMessage> messages = ArgumentCaptor.forClass(SosRealtimeMessage.class);
        verify(delivery, Mockito.atLeast(0)).sendToUser(users.capture(), messages.capture());
        for (int i = 0; i < users.getAllValues().size(); i++) {
            if (users.getAllValues().get(i).equals(userId) && messages.getAllValues().get(i).eventType() == type) {
                return messages.getAllValues().get(i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 1. Customer receives events only for their own SOS
    // ------------------------------------------------------------------

    @Test
    void lifecycleEventsGoToTheOwningCustomerAndNobodyElse() {
        request(SosRequestStatus.CREATED);
        publisher.publish(stubEvent(SosEventType.SOS_CREATED, null, null));

        assertThat(captureSends()).containsExactly(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.SOS_CREATED));
    }

    @Test
    void matchingStartedGoesOnlyToTheCustomer() {
        request(SosRequestStatus.MATCHING);
        publisher.publish(stubEvent(SosEventType.MATCHING_STARTED, null, null));

        assertThat(captureSends()).containsExactly(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.MATCHING_STARTED));
    }

    /** The message is scoped to the request it belongs to — a client can always tell them apart. */
    @Test
    void everyMessageCarriesItsOwnRequestAndEventId() {
        request(SosRequestStatus.CREATED);
        publisher.publish(stubEvent(SosEventType.SOS_CREATED, null, null));

        SosRealtimeMessage message = captureMessageTo(CUSTOMER_USER_ID, SosRealtimeEventType.SOS_CREATED);
        assertThat(message.sosRequestId()).isEqualTo(REQUEST_ID);
        assertThat(message.eventId()).isEqualTo(EVENT_ID);
        assertThat(message.timestamp()).isEqualTo(Instant.parse("2026-08-21T12:00:00Z"));
    }

    // ------------------------------------------------------------------
    // 2. A professional receives only their own offer
    // ------------------------------------------------------------------

    @Test
    void eachProfessionalReceivesOnlyTheirOwnOffer() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        stubOffers(offer(201L, PRO_A, SosOfferStatus.OFFERED), offer(202L, PRO_B, SosOfferStatus.OFFERED));

        publisher.publish(stubEvent(SosEventType.OFFERS_SENT, null, null));

        assertThat(captureSends()).containsExactlyInAnyOrder(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.OFFERS_SENT),
                Map.entry(PRO_A_USER, SosRealtimeEventType.SOS_OFFER_RECEIVED),
                Map.entry(PRO_B_USER, SosRealtimeEventType.SOS_OFFER_RECEIVED));

        assertThat(captureMessageTo(PRO_A_USER, SosRealtimeEventType.SOS_OFFER_RECEIVED).data())
                .containsEntry("offerId", 201L);
        assertThat(captureMessageTo(PRO_B_USER, SosRealtimeEventType.SOS_OFFER_RECEIVED).data())
                .containsEntry("offerId", 202L);
    }

    /** The customer learns how many were contacted, never who they are. */
    @Test
    void theCustomerIsNotToldWhichProfessionalsWereOffered() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        stubOffers(offer(201L, PRO_A, SosOfferStatus.OFFERED), offer(202L, PRO_B, SosOfferStatus.OFFERED));

        publisher.publish(stubEvent(SosEventType.OFFERS_SENT, null, null));

        Map<String, Object> data = captureMessageTo(CUSTOMER_USER_ID, SosRealtimeEventType.OFFERS_SENT).data();
        assertThat(data).containsEntry("offerCount", 2);
        assertThat(data).doesNotContainKeys("professionalId", "offerId", "professionalIds");
    }

    /**
     * Private customer detail must not travel to a professional who has not been selected.
     *
     * <p>Street and city are the deliberate exception, and they must be here: this payload is what
     * a professional decides on, and the REST offer view discloses exactly the same two fields.
     * The privacy rule holding in one surface but not the other is the failure mode
     * {@code SosAddressAccess} exists to prevent, so both are asserted at the same line.
     */
    @Test
    void theOfferPayloadExposesStreetAndCityButNoPrivateCustomerDetail() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        stubOffers(offer(201L, PRO_A, SosOfferStatus.OFFERED));

        publisher.publish(stubEvent(SosEventType.OFFERS_SENT, null, null));

        Map<String, Object> data = captureMessageTo(PRO_A_USER, SosRealtimeEventType.SOS_OFFER_RECEIVED).data();
        assertThat(data).containsEntry("serviceCity", "Tel Aviv");
        assertThat(data).containsEntry("serviceStreet", "Dizengoff");
        assertThat(data).containsKeys("visitFee", "sosFee", "expiresAt", "urgency", "categoryId");
        assertThat(data).doesNotContainKeys(
                "serviceHouseNumber", "serviceApartment", "serviceFloor", "serviceEntrance",
                "serviceAddressNotes", "latitude", "longitude", "customerId", "customerName",
                "customerPhone");
    }

    /** A re-dispatch wave must not re-notify an offer that has already been answered. */
    @Test
    void alreadyAnsweredOffersAreNotReNotifiedOnAnotherWave() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        stubOffers(offer(201L, PRO_A, SosOfferStatus.ACCEPTED), offer(202L, PRO_B, SosOfferStatus.OFFERED));

        publisher.publish(stubEvent(SosEventType.OFFERS_SENT, null, null));

        assertThat(captureSends()).containsExactlyInAnyOrder(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.OFFERS_SENT),
                Map.entry(PRO_B_USER, SosRealtimeEventType.SOS_OFFER_RECEIVED));
    }

    // ------------------------------------------------------------------
    // SEARCH_EXPANDED — "סרוק שוב"
    // ------------------------------------------------------------------

    /**
     * An expansion routes exactly like a dispatch wave, with a different word for the customer:
     * the newly-contacted professional is offered the job, and the customer is told the search
     * widened — not that somebody new is available, which has not happened.
     */
    @Test
    void anExpansionOffersTheNewProfessionalAndTellsTheCustomerTheSearchWidened() {
        SosRequest request = request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(request, "searchExpansions", (short) 1);
        stubOffers(offer(201L, PRO_A, SosOfferStatus.ACCEPTED), offer(202L, PRO_B, SosOfferStatus.OFFERED));

        publisher.publish(stubEvent(SosEventType.SEARCH_EXPANDED, null, null));

        assertThat(captureSends()).containsExactlyInAnyOrder(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.SEARCH_EXPANDED),
                Map.entry(PRO_B_USER, SosRealtimeEventType.SOS_OFFER_RECEIVED));
    }

    /**
     * The customer's expansion payload stays aggregate, and carries <b>no radius and no
     * distance</b> — there is no real geographic data behind this expansion yet, and a wire field
     * is exactly how invented precision reaches a UI.
     */
    @Test
    void theExpansionPayloadIsAggregateAndQuotesNoDistance() {
        SosRequest request = request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(request, "searchExpansions", (short) 2);
        stubOffers(offer(201L, PRO_A, SosOfferStatus.ACCEPTED), offer(202L, PRO_B, SosOfferStatus.OFFERED));
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(1L);

        publisher.publish(stubEvent(SosEventType.SEARCH_EXPANDED, null, null));

        Map<String, Object> data =
                captureMessageTo(CUSTOMER_USER_ID, SosRealtimeEventType.SEARCH_EXPANDED).data();
        assertThat(data).containsEntry("offerCount", 2);
        assertThat(data).containsEntry("availableCandidateCount", 1L);
        assertThat(data).containsEntry("searchExpansions", 2);
        assertThat(data).doesNotContainKeys("radiusKm", "maxRadiusKm", "distanceKm", "professionalId", "offerId");
    }

    // ------------------------------------------------------------------
    // 3./4. Availability response — updates the customer, awards nothing
    // ------------------------------------------------------------------

    @Test
    void aPositiveResponseTellsTheCustomerACandidateIsAvailable() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        offer(201L, PRO_A, SosOfferStatus.ACCEPTED);
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(2L);

        publisher.publish(stubEvent(SosEventType.PROFESSIONAL_RESPONDED, 201L, PRO_A));

        assertThat(captureSends()).containsExactlyInAnyOrder(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.PROFESSIONAL_AVAILABLE),
                Map.entry(PRO_A_USER, SosRealtimeEventType.OFFER_RESPONSE_RECORDED));
        assertThat(captureMessageTo(CUSTOMER_USER_ID, SosRealtimeEventType.PROFESSIONAL_AVAILABLE).data())
                .containsEntry("availableCandidateCount", 2L);
    }

    /**
     * The distinction the whole feature hinges on: saying "I'm available" is not being given the
     * job. No selection-shaped message may be emitted, to anyone.
     */
    @Test
    void aPositiveResponseNeverAwardsTheJob() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        offer(201L, PRO_A, SosOfferStatus.ACCEPTED);

        publisher.publish(stubEvent(SosEventType.PROFESSIONAL_RESPONDED, 201L, PRO_A));

        assertThat(captureSends())
                .extracting(Map.Entry::getValue)
                .doesNotContain(SosRealtimeEventType.SOS_SELECTED,
                        SosRealtimeEventType.PROFESSIONAL_SELECTED,
                        SosRealtimeEventType.SOS_NOT_SELECTED);
    }

    /** A decline changes nothing the customer can see, and naming the decliner would leak. */
    @Test
    void aDeclineIsAcknowledgedToTheProfessionalOnly() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        offer(201L, PRO_A, SosOfferStatus.REJECTED);

        publisher.publish(stubEvent(SosEventType.PROFESSIONAL_RESPONDED, 201L, PRO_A));

        assertThat(captureSends()).containsExactly(
                Map.entry(PRO_A_USER, SosRealtimeEventType.OFFER_RESPONSE_RECORDED));
    }

    /** After selection, an ETA revision is exactly what the customer tracking an arrival wants. */
    @Test
    void anEtaRevisionAfterSelectionReachesTheCustomer() {
        request(SosRequestStatus.CONFIRMED);
        offer(201L, PRO_A, SosOfferStatus.SELECTED);

        publisher.publish(stubEvent(SosEventType.ETA_UPDATED, 201L, PRO_A));

        assertThat(captureSends()).containsExactlyInAnyOrder(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.ETA_UPDATED),
                Map.entry(PRO_A_USER, SosRealtimeEventType.OFFER_RESPONSE_RECORDED));
    }

    /**
     * <b>The regression this whole event type was introduced for.</b> A professional who is
     * merely available — not chosen — revises their ETA, and the customer comparing candidates
     * must be told the true thing: this offer's number changed. It previously arrived as
     * {@code PROFESSIONAL_AVAILABLE}, i.e. "another professional is available", when no new
     * candidate existed at all.
     */
    @Test
    void anEtaRevisionBeforeSelectionReachesTheCustomerAsAnEtaUpdate() {
        request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        offer(201L, PRO_A, SosOfferStatus.ACCEPTED);

        publisher.publish(stubEvent(SosEventType.ETA_UPDATED, 201L, PRO_A));

        assertThat(captureSends()).containsExactlyInAnyOrder(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.ETA_UPDATED),
                Map.entry(PRO_A_USER, SosRealtimeEventType.OFFER_RESPONSE_RECORDED));
    }

    /** The payload has to name which candidate changed, and to what, or the customer cannot act. */
    @Test
    void anEtaUpdateNamesTheOfferAndTheNewFigure() {
        request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        offer(201L, PRO_A, SosOfferStatus.ACCEPTED);

        publisher.publish(stubEvent(SosEventType.ETA_UPDATED, 201L, PRO_A));

        assertThat(captureMessageTo(CUSTOMER_USER_ID, SosRealtimeEventType.ETA_UPDATED).data())
                .containsEntry("offerId", 201L)
                .containsEntry("professionalId", PRO_A)
                .containsEntry("estimatedArrivalMinutes", (short) 15);
    }

    /**
     * Availability and revision must stay distinguishable in both directions: an ETA revision is
     * never announced as a new candidate, so no candidate-arrival treatment fires on an edit.
     */
    @Test
    void anEtaRevisionIsNeverAnnouncedAsANewCandidate() {
        request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        offer(201L, PRO_A, SosOfferStatus.ACCEPTED);

        publisher.publish(stubEvent(SosEventType.ETA_UPDATED, 201L, PRO_A));

        assertThat(captureSends())
                .extracting(Map.Entry::getValue)
                .doesNotContain(SosRealtimeEventType.PROFESSIONAL_AVAILABLE,
                        SosRealtimeEventType.SOS_SELECTED,
                        SosRealtimeEventType.PROFESSIONAL_SELECTED);
    }

    /** An offer that is no longer live has no ETA worth pushing to the customer. */
    @Test
    void anEtaUpdateOnAClosedOfferTellsTheCustomerNothing() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        offer(201L, PRO_A, SosOfferStatus.EXPIRED);

        publisher.publish(stubEvent(SosEventType.ETA_UPDATED, 201L, PRO_A));

        assertThat(captureSends()).containsExactly(
                Map.entry(PRO_A_USER, SosRealtimeEventType.OFFER_RESPONSE_RECORDED));
    }

    /**
     * The availability message carries the ETA too. The customer's card renders it on arrival, and
     * making them wait for a follow-up refetch to learn the one number they are comparing on is a
     * gap the payload can simply close.
     */
    @Test
    void anAvailabilityMessageCarriesTheCommittedEta() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        offer(201L, PRO_A, SosOfferStatus.ACCEPTED);
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(1L);

        publisher.publish(stubEvent(SosEventType.PROFESSIONAL_RESPONDED, 201L, PRO_A));

        assertThat(captureMessageTo(CUSTOMER_USER_ID, SosRealtimeEventType.PROFESSIONAL_AVAILABLE).data())
                .containsEntry("estimatedArrivalMinutes", (short) 15)
                .containsEntry("offerId", 201L);
    }

    // ------------------------------------------------------------------
    // Candidates / selection window
    // ------------------------------------------------------------------

    @Test
    void candidatesReadyGoesToTheCustomerWithTheCount() {
        request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        when(sosOfferRepository.countBySosRequestIdAndStatus(REQUEST_ID, SosOfferStatus.ACCEPTED)).thenReturn(3L);

        publisher.publish(stubEvent(SosEventType.CANDIDATES_READY, null, null));

        assertThat(captureSends()).containsExactly(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.CANDIDATES_UPDATED));
        assertThat(captureMessageTo(CUSTOMER_USER_ID, SosRealtimeEventType.CANDIDATES_UPDATED).data())
                .containsEntry("availableCandidateCount", 3L);
    }

    /**
     * <b>The push carries a count, not a countdown.</b> It used to carry
     * {@code selectionExpiresAt} so the customer's screen could tick down to the moment their
     * options were deleted. There is no such moment any more (MS3 follow-up), so a client that
     * still expected one would be rendering a clock for a rule that no longer exists — the
     * absence of the key is the assertion here.
     */
    @Test
    void selectionStartedCarriesTheCandidateCountAndNoDeadline() {
        request(SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);

        publisher.publish(stubEvent(SosEventType.CUSTOMER_SELECTION_STARTED, null, null));

        assertThat(captureSends()).containsExactly(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.CUSTOMER_SELECTION_STARTED));
        assertThat(captureMessageTo(CUSTOMER_USER_ID, SosRealtimeEventType.CUSTOMER_SELECTION_STARTED).data())
                .containsKey("availableCandidateCount")
                .doesNotContainKey("selectionExpiresAt");
    }

    // ------------------------------------------------------------------
    // 5./6./7./8. Selection routing — the sharpest part of the matrix
    // ------------------------------------------------------------------

    /**
     * Three offers, three different outcomes, three different messages: the winner is told they
     * won, the runner-up who had responded is told they lost, and the professional who never
     * answered is told nothing at all.
     */
    @Test
    void selectionRoutesWinnerRunnerUpAndSilentProfessionalDifferently() {
        SosRequest request = request(SosRequestStatus.PROFESSIONAL_SELECTED);
        setField(request, "selectedProfessionalId", PRO_A);
        setField(request, "selectedOfferId", 201L);
        setField(request, "orderId", 500L);
        stubOffers(
                offer(201L, PRO_A, SosOfferStatus.SELECTED),
                offer(202L, PRO_B, SosOfferStatus.NOT_SELECTED),
                offer(203L, PRO_C, SosOfferStatus.EXPIRED));

        publisher.publish(stubEvent(SosEventType.PROFESSIONAL_SELECTED, 201L, PRO_A));

        assertThat(captureSends()).containsExactlyInAnyOrder(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.PROFESSIONAL_SELECTED),
                Map.entry(PRO_A_USER, SosRealtimeEventType.SOS_SELECTED),
                Map.entry(PRO_B_USER, SosRealtimeEventType.SOS_NOT_SELECTED));
    }

    /** Restated as its own test because it is the easiest thing to get wrong and the rudest to ship. */
    @Test
    void aProfessionalWhoNeverRespondedIsNeverToldTheyWereNotSelected() {
        SosRequest request = request(SosRequestStatus.PROFESSIONAL_SELECTED);
        setField(request, "selectedProfessionalId", PRO_A);
        setField(request, "selectedOfferId", 201L);
        stubOffers(
                offer(201L, PRO_A, SosOfferStatus.SELECTED),
                offer(203L, PRO_C, SosOfferStatus.EXPIRED));

        publisher.publish(stubEvent(SosEventType.PROFESSIONAL_SELECTED, 201L, PRO_A));

        assertThat(captureSends())
                .doesNotContain(Map.entry(PRO_C_USER, SosRealtimeEventType.SOS_NOT_SELECTED));
        verify(delivery, never()).sendToUser(eq(PRO_C_USER), any());
    }

    /** Likewise for someone who actively declined — they opted out, they are not a runner-up. */
    @Test
    void aProfessionalWhoDeclinedIsNotToldTheyWereNotSelected() {
        SosRequest request = request(SosRequestStatus.PROFESSIONAL_SELECTED);
        setField(request, "selectedProfessionalId", PRO_A);
        stubOffers(
                offer(201L, PRO_A, SosOfferStatus.SELECTED),
                offer(202L, PRO_B, SosOfferStatus.REJECTED));

        publisher.publish(stubEvent(SosEventType.PROFESSIONAL_SELECTED, 201L, PRO_A));

        verify(delivery, never()).sendToUser(eq(PRO_B_USER), any());
    }

    @Test
    void theWinnerIsToldTheJobIsTheirsWithTheOrderId() {
        SosRequest request = request(SosRequestStatus.PROFESSIONAL_SELECTED);
        setField(request, "selectedProfessionalId", PRO_A);
        setField(request, "selectedOfferId", 201L);
        setField(request, "orderId", 500L);
        stubOffers(offer(201L, PRO_A, SosOfferStatus.SELECTED));

        publisher.publish(stubEvent(SosEventType.PROFESSIONAL_SELECTED, 201L, PRO_A));

        assertThat(captureMessageTo(PRO_A_USER, SosRealtimeEventType.SOS_SELECTED).data())
                .containsEntry("offerId", 201L)
                .containsEntry("orderId", 500L);
    }

    @Test
    void theCustomerIsToldWhoWasSelected() {
        SosRequest request = request(SosRequestStatus.PROFESSIONAL_SELECTED);
        setField(request, "selectedProfessionalId", PRO_A);
        setField(request, "selectedOfferId", 201L);
        setField(request, "orderId", 500L);
        stubOffers(offer(201L, PRO_A, SosOfferStatus.SELECTED));

        publisher.publish(stubEvent(SosEventType.PROFESSIONAL_SELECTED, 201L, PRO_A));

        assertThat(captureMessageTo(CUSTOMER_USER_ID, SosRealtimeEventType.PROFESSIONAL_SELECTED).data())
                .containsEntry("professionalId", PRO_A)
                .containsEntry("orderId", 500L);
    }

    // ------------------------------------------------------------------
    // 9. Operational updates
    // ------------------------------------------------------------------

    @Test
    void operationalUpdatesReachTheCustomerAndTheSelectedProfessionalOnly() {
        for (SosEventType type : List.of(SosEventType.PROFESSIONAL_CONFIRMED, SosEventType.ON_THE_WAY,
                SosEventType.ARRIVED, SosEventType.COMPLETED)) {
            Mockito.reset(delivery);
            SosRequest request = request(SosRequestStatus.CONFIRMED);
            setField(request, "selectedProfessionalId", PRO_A);

            publisher.publish(stubEvent(type, null, PRO_A));

            assertThat(captureSends())
                    .as("%s recipients", type)
                    .extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder(CUSTOMER_USER_ID, PRO_A_USER);
        }
    }

    /** An operational update must never leak to a professional who lost the request. */
    @Test
    void operationalUpdatesDoNotReachLosingProfessionals() {
        SosRequest request = request(SosRequestStatus.ON_THE_WAY);
        setField(request, "selectedProfessionalId", PRO_A);
        stubOffers(offer(201L, PRO_A, SosOfferStatus.SELECTED), offer(202L, PRO_B, SosOfferStatus.NOT_SELECTED));

        publisher.publish(stubEvent(SosEventType.ON_THE_WAY, null, PRO_A));

        verify(delivery, never()).sendToUser(eq(PRO_B_USER), any());
    }

    // ------------------------------------------------------------------
    // 10. Cancel / expire routing
    // ------------------------------------------------------------------

    @Test
    void cancellationReachesTheCustomerAndEveryStillInvolvedProfessional() {
        request(SosRequestStatus.CANCELLED);
        stubOffers(
                offer(201L, PRO_A, SosOfferStatus.ACCEPTED),
                offer(202L, PRO_B, SosOfferStatus.OFFERED),
                offer(203L, PRO_C, SosOfferStatus.REJECTED));

        publisher.publish(stubEvent(SosEventType.CANCELLED, null, null));

        assertThat(captureSends())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(CUSTOMER_USER_ID, PRO_A_USER, PRO_B_USER);
        // The professional who declined already opted out.
        verify(delivery, never()).sendToUser(eq(PRO_C_USER), any());
    }

    @Test
    void expiryReachesTheCustomerAndTheProfessionalsWhoStillHeldOffers() {
        request(SosRequestStatus.EXPIRED);
        stubOffers(offer(201L, PRO_A, SosOfferStatus.EXPIRED));

        publisher.publish(stubEvent(SosEventType.EXPIRED, null, null));

        assertThat(captureSends())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(CUSTOMER_USER_ID, PRO_A_USER);
    }

    @Test
    void matchingFailureIsCustomerOnly() {
        request(SosRequestStatus.FAILED);

        publisher.publish(stubEvent(SosEventType.FAILED, null, null));

        assertThat(captureSends()).containsExactly(
                Map.entry(CUSTOMER_USER_ID, SosRealtimeEventType.SOS_FAILED));
    }

    /** Opening an offer is telemetry; nobody is woken up for it. */
    @Test
    void offerViewedPublishesNothing() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);

        publisher.publish(stubEvent(SosEventType.OFFER_VIEWED, 201L, PRO_A));

        verify(delivery, never()).sendToUser(any(), any());
    }

    // ------------------------------------------------------------------
    // Individual offer expiry
    // ------------------------------------------------------------------

    /**
     * One professional's window closed. Exactly one recipient — and, critically, <b>not the
     * customer</b>. "Professional X did not respond" is not actionable, names a stranger's
     * business decision, and reframes the most ordinary outcome in a fan-out of eight as a
     * failure. The customer's dispatch view stays aggregate.
     */
    @Test
    void anExpiredOfferIsAnnouncedOnlyToItsOwnProfessional() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        offer(201L, PRO_A, SosOfferStatus.EXPIRED);
        offer(202L, PRO_B, SosOfferStatus.ACCEPTED);

        publisher.publish(stubEvent(SosEventType.OFFER_EXPIRED, 201L, PRO_A));

        assertThat(captureSends()).containsExactly(
                Map.entry(PRO_A_USER, SosRealtimeEventType.SOS_OFFER_EXPIRED));
    }

    /**
     * A lapsed offer must not be confused with a terminated request: the other professionals are
     * still in the running and the customer may be about to choose between them.
     */
    @Test
    void anExpiredOfferDoesNotLookLikeTheWholeRequestExpiring() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        offer(201L, PRO_A, SosOfferStatus.EXPIRED);
        offer(202L, PRO_B, SosOfferStatus.ACCEPTED);

        publisher.publish(stubEvent(SosEventType.OFFER_EXPIRED, 201L, PRO_A));

        assertThat(captureSends())
                .extracting(Map.Entry::getValue)
                .doesNotContain(SosRealtimeEventType.EXPIRED, SosRealtimeEventType.SOS_FAILED);
        verify(delivery, never()).sendToUser(eq(PRO_B_USER), any());
        verify(delivery, never()).sendToUser(eq(CUSTOMER_USER_ID), any());
    }

    /** The payload the professional's inbox needs to retire the card without a refetch. */
    @Test
    void theExpiredOfferPayloadIdentifiesTheOffer() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);
        offer(201L, PRO_A, SosOfferStatus.EXPIRED);

        publisher.publish(stubEvent(SosEventType.OFFER_EXPIRED, 201L, PRO_A));

        ArgumentCaptor<SosRealtimeMessage> message = ArgumentCaptor.forClass(SosRealtimeMessage.class);
        verify(delivery).sendToUser(eq(PRO_A_USER), message.capture());
        assertThat(message.getValue().sosRequestId()).isEqualTo(REQUEST_ID);
        assertThat(message.getValue().data())
                .containsEntry("offerId", 201L)
                .containsEntry("requestStatus", SosRequestStatus.WAITING_FOR_PROFESSIONALS.name())
                .containsKey("expiredAt");
    }

    /** A malformed event (no offer named) must not fan out to anyone rather than guessing. */
    @Test
    void anOfferExpiryEventWithoutAnOfferIdPublishesNothing() {
        request(SosRequestStatus.WAITING_FOR_PROFESSIONALS);

        publisher.publish(stubEvent(SosEventType.OFFER_EXPIRED, null, PRO_A));

        verify(delivery, never()).sendToUser(any(), any());
    }

    // ------------------------------------------------------------------
    // 11. Publishing happens after commit
    // ------------------------------------------------------------------

    /**
     * Structural assertion, not a behavioural one: with no database available in this environment
     * a real commit cannot be driven, so this pins the annotations that produce the guarantee.
     * If someone removes the phase or switches it to {@code BEFORE_COMMIT}, this fails.
     */
    @Test
    void publishingIsWiredToRunAfterCommitInItsOwnTransaction() throws Exception {
        Method listener = SosRealtimePublisher.class.getMethod("onSosDomainEvent", SosDomainEvent.class);

        TransactionalEventListener eventListener = listener.getAnnotation(TransactionalEventListener.class);
        assertThat(eventListener).isNotNull();
        assertThat(eventListener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);

        Transactional transactional = listener.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactional.readOnly()).isTrue();
    }

    // ------------------------------------------------------------------
    // 12. Realtime failure never escapes
    // ------------------------------------------------------------------

    /**
     * An after-commit synchronization that throws propagates out of the transaction manager to the
     * original caller — so a delivery fault here would turn a committed, successful selection into
     * an HTTP 500. The listener must swallow everything.
     */
    @Test
    void aDeliveryFailureNeverEscapesTheListener() {
        request(SosRequestStatus.CREATED);
        SosDomainEvent event = stubEvent(SosEventType.SOS_CREATED, null, null);
        Mockito.doThrow(new IllegalStateException("broker down")).when(delivery).sendToUser(any(), any());

        assertThatCode(() -> publisher.onSosDomainEvent(event)).doesNotThrowAnyException();
    }

    /** Same guarantee for a routing/lookup fault, not just a broker fault. */
    @Test
    void aRepositoryFailureNeverEscapesTheListener() {
        when(sosEventRepository.findById(EVENT_ID)).thenThrow(new IllegalStateException("db gone"));

        assertThatCode(() -> publisher.onSosDomainEvent(
                new SosDomainEvent(REQUEST_ID, EVENT_ID, SosEventType.SOS_CREATED, null)))
                .doesNotThrowAnyException();
    }

    /** A vanished row (cascading delete between commit and delivery) is a no-op, not a crash. */
    @Test
    void aMissingRequestOrEventPublishesNothing() {
        when(sosEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());
        when(sosRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.empty());

        publisher.publish(new SosDomainEvent(REQUEST_ID, EVENT_ID, SosEventType.SOS_CREATED, null));

        verify(delivery, never()).sendToUser(any(), any());
    }
}
