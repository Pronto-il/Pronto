package com.pronto.sos.service;

import com.pronto.professionals.service.ProfessionalCoverageService;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.sos.dto.SosRequestResponse;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosRequestStatus;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The redaction itself, against the real assembler.
 *
 * <p>{@code SosServiceTest} covers which {@link SosAddressAccess} the service <em>decides</em> on
 * for each caller (its assembler is mocked). This covers what that decision actually does to the
 * bytes on the wire. Splitting them matters: a correct decision feeding a redaction that quietly
 * did nothing would pass every test in the other class.
 *
 * <p>The assertions are deliberately written as "no exact-location field survives" rather than
 * field-by-field equality, so that a future column added to {@code sos_requests} and forgotten in
 * the redaction branch is caught here rather than in production.
 */
class SosResponseAssemblerTest {

    private static final Long REQUEST_ID = 7L;
    private static final Long ISSUE_ID = 11L;
    private static final Long CUSTOMER_ID = 2L;
    private static final Long CATEGORY_ID = 1L;

    private SosOfferRepository sosOfferRepository;
    private SosResponseAssembler assembler;

    @BeforeEach
    void setUp() {
        ProfessionalRepository professionalRepository = Mockito.mock(ProfessionalRepository.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        ReviewAggregateRepository reviewAggregateRepository = Mockito.mock(ReviewAggregateRepository.class);
        sosOfferRepository = Mockito.mock(SosOfferRepository.class);
        StorageService storageService = Mockito.mock(StorageService.class);
        ProfessionalCoverageService professionalCoverageService =
                Mockito.mock(ProfessionalCoverageService.class);
        assembler = new SosResponseAssembler(professionalRepository, userRepository, reviewAggregateRepository,
                sosOfferRepository, storageService, new com.pronto.sos.config.SosProperties(),
                professionalCoverageService);

        when(sosOfferRepository.findBySosRequestIdOrderByMatchRankAsc(anyLong())).thenReturn(List.of());
    }

    /** Every exact-location field populated, so redaction has something to actually remove. */
    private static SosRequest fullyAddressedRequest() {
        SosRequest request = new SosRequest(ISSUE_ID, CUSTOMER_ID, CATEGORY_ID, null, "Burst pipe",
                SosUrgency.URGENT, "Tel Aviv", "Dizengoff", "10", "4B", "3", "A",
                "Gate code 1234, dog in the yard",
                new BigDecimal("32.075100"), new BigDecimal("34.775200"));
        setField(request, "id", REQUEST_ID);
        return request;
    }

    // ------------------------------------------------------------------
    // "is anybody new still being contacted" — canExpandSearch (MS3 follow-up)
    // ------------------------------------------------------------------

    /**
     * The flag the customer's screen uses to decide whether to say "we are still looking". It has
     * to go false when the scan window closes, or the screen claims to be searching after
     * dispatch has stopped — a lie told to somebody in an emergency.
     */
    @Test
    void searchIsNotExpandableOnceTheScanWindowHasClosed() {
        SosRequest request = fullyAddressedRequest();
        setField(request, "status", SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(request, "matchingExpiresAt", Instant.now().minusSeconds(30));

        SosRequestResponse response = assembler.toRequestResponse(request, SosAddressAccess.FULL);

        assertThat(response.canExpandSearch()).isFalse();
    }

    /** ...and true while the scan window is genuinely open with expansions left. */
    @Test
    void searchIsExpandableWhileTheScanWindowIsOpen() {
        SosRequest request = fullyAddressedRequest();
        setField(request, "status", SosRequestStatus.WAITING_FOR_CUSTOMER_SELECTION);
        setField(request, "matchingExpiresAt", Instant.now().plusSeconds(300));

        SosRequestResponse response = assembler.toRequestResponse(request, SosAddressAccess.FULL);

        assertThat(response.canExpandSearch()).isTrue();
    }

    /**
     * <b>No customer-decision deadline reaches the wire.</b> The record has no such component
     * since the MS3 follow-up; this asserts the shape itself, so re-adding one would fail here
     * rather than quietly reappear on a client.
     */
    @Test
    void theResponseCarriesNoCustomerDecisionDeadline() {
        assertThat(java.util.Arrays.stream(SosRequestResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .filter(name -> name.toLowerCase().contains("selection")))
                .as("SosRequestResponse components naming a selection deadline")
                .isEmpty();
    }

    @Test
    void fullAccessReturnsTheExactAddress() {
        SosRequestResponse response = assembler.toRequestResponse(fullyAddressedRequest(), SosAddressAccess.FULL);

        assertThat(response.serviceCity()).isEqualTo("Tel Aviv");
        assertThat(response.serviceStreet()).isEqualTo("Dizengoff");
        assertThat(response.serviceHouseNumber()).isEqualTo("10");
        assertThat(response.serviceApartment()).isEqualTo("4B");
        assertThat(response.serviceFloor()).isEqualTo("3");
        assertThat(response.serviceEntrance()).isEqualTo("A");
        assertThat(response.serviceAddressNotes()).isEqualTo("Gate code 1234, dog in the yard");
        assertThat(response.latitude()).isEqualByComparingTo("32.075100");
        assertThat(response.longitude()).isEqualByComparingTo("34.775200");
    }

    /**
     * The door-identifying fields, and only those. The street is deliberately <em>not</em> in this
     * list — see {@link #streetAndCityAccessDisclosesTheStreetSoAnEtaCanBeEstimated}.
     */
    @Test
    void streetAndCityAccessStripsEveryDoorIdentifyingField() {
        SosRequestResponse response =
                assembler.toRequestResponse(fullyAddressedRequest(), SosAddressAccess.STREET_AND_CITY);

        assertThat(response.serviceHouseNumber()).isNull();
        assertThat(response.serviceApartment()).isNull();
        assertThat(response.serviceFloor()).isNull();
        assertThat(response.serviceEntrance()).isNull();
        // The notes field is the easiest one to overlook and the most dangerous to leak: it is
        // free text and in practice holds gate codes and "the key is under the mat".
        assertThat(response.serviceAddressNotes()).isNull();
        assertThat(response.latitude()).isNull();
        assertThat(response.longitude()).isNull();
    }

    /**
     * Street and city survive redaction on purpose, and the street's presence is the point of this
     * access level rather than an oversight: a professional is being asked to commit to an arrival
     * time, and "Tel Aviv" spans an hour of driving. A house number would add nothing to that
     * estimate and everything to a stranger's ability to turn up uninvited, which is why the line
     * is drawn between the two.
     */
    @Test
    void streetAndCityAccessDisclosesTheStreetSoAnEtaCanBeEstimated() {
        SosRequestResponse response =
                assembler.toRequestResponse(fullyAddressedRequest(), SosAddressAccess.STREET_AND_CITY);

        assertThat(response.serviceStreet()).isEqualTo("Dizengoff");
        assertThat(response.serviceCity()).isEqualTo("Tel Aviv");
    }

    @Test
    void streetAndCityAccessStillReturnsTheJobContext() {
        SosRequestResponse response =
                assembler.toRequestResponse(fullyAddressedRequest(), SosAddressAccess.STREET_AND_CITY);

        assertThat(response.serviceCity()).isEqualTo("Tel Aviv");
        assertThat(response.id()).isEqualTo(REQUEST_ID);
        assertThat(response.categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(response.issueSummary()).isEqualTo("Burst pipe");
        assertThat(response.urgency()).isEqualTo(SosUrgency.URGENT);
    }

    /**
     * Redaction must not be able to drift into being a no-op through a copy-paste. Compares the
     * two shapes directly: everything non-locational must be identical, everything locational
     * must differ.
     */
    @Test
    void theTwoAccessLevelsDifferOnlyInTheLocationFields() {
        SosRequest request = fullyAddressedRequest();
        SosRequestResponse full = assembler.toRequestResponse(request, SosAddressAccess.FULL);
        SosRequestResponse redacted = assembler.toRequestResponse(request, SosAddressAccess.STREET_AND_CITY);

        assertThat(redacted).isNotEqualTo(full);
        assertThat(redacted.id()).isEqualTo(full.id());
        assertThat(redacted.issueId()).isEqualTo(full.issueId());
        assertThat(redacted.customerId()).isEqualTo(full.customerId());
        assertThat(redacted.status()).isEqualTo(full.status());
        assertThat(redacted.serviceCity()).isEqualTo(full.serviceCity());
        // Street is now shared by both levels — the difference between them is the door, not the
        // road. Asserted explicitly so a future change that re-redacts the street has to come
        // through here rather than silently narrowing what a professional can estimate from.
        assertThat(redacted.serviceStreet()).isEqualTo(full.serviceStreet());
        assertThat(redacted.offerCount()).isEqualTo(full.offerCount());
        assertThat(redacted.acceptedCandidateCount()).isEqualTo(full.acceptedCandidateCount());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
