package com.pronto.sos.service;

import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.professionals.repository.ReviewAggregateRepository;
import com.pronto.sos.dto.SosRequestResponse;
import com.pronto.sos.entity.SosRequest;
import com.pronto.sos.entity.SosUrgency;
import com.pronto.sos.repository.SosOfferRepository;
import com.pronto.storage.service.StorageService;
import com.pronto.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.math.BigDecimal;
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
        assembler = new SosResponseAssembler(professionalRepository, userRepository, reviewAggregateRepository,
                sosOfferRepository, storageService);

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

    @Test
    void cityOnlyAccessStripsEveryExactLocationField() {
        SosRequestResponse response =
                assembler.toRequestResponse(fullyAddressedRequest(), SosAddressAccess.CITY_ONLY);

        assertThat(response.serviceStreet()).isNull();
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
     * City survives redaction on purpose. It is what a professional needs to judge whether the job
     * is reachable at all, it is already on their offer card, and a response with no location
     * whatsoever would be useless to the screen that has to render it.
     */
    @Test
    void cityOnlyAccessStillReturnsTheCityAndTheJobContext() {
        SosRequestResponse response =
                assembler.toRequestResponse(fullyAddressedRequest(), SosAddressAccess.CITY_ONLY);

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
        SosRequestResponse redacted = assembler.toRequestResponse(request, SosAddressAccess.CITY_ONLY);

        assertThat(redacted).isNotEqualTo(full);
        assertThat(redacted.id()).isEqualTo(full.id());
        assertThat(redacted.issueId()).isEqualTo(full.issueId());
        assertThat(redacted.customerId()).isEqualTo(full.customerId());
        assertThat(redacted.status()).isEqualTo(full.status());
        assertThat(redacted.serviceCity()).isEqualTo(full.serviceCity());
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
