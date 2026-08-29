package com.pronto.bookings.controller;

import com.pronto.bookings.dto.ProfessionalListingResponse;
import com.pronto.bookings.service.BookingsService;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.matching.ServiceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code GET /api/bookings/professionals}' service-address query parameters — the endpoint behind
 * the reported {@code 400 Bad Request}.
 *
 * <p><b>These tests exist to pin the 400, not to remove it.</b> The bug was a frontend screen
 * asking for a listing before it had an address; the request it sent
 * ({@code ?categoryId=2&city=&street=&houseNumber=}) is one this endpoint cannot answer honestly,
 * because service-area relevance, road distance and ETA are all derived from the address. So the
 * fix was made on the calling side and the validation here is asserted to be exactly as strict as
 * it was — plus the house-number shape rule the rest of the platform now applies, which this
 * endpoint would otherwise have been the one door around.
 *
 * <p>The handler method is called directly with a mocked {@link BookingsService}, matching this
 * codebase's plain-unit-test convention (there is no {@code MockMvc}/{@code @WebMvcTest} slice
 * anywhere in the suite). That is sufficient here because the parameters are bound as
 * {@code String} and parsed by hand inside the handler — precisely so this endpoint produces the
 * app's own error envelope rather than Spring's type-mismatch handling.
 */
class BookingsControllerLocationTest {

    private BookingsService bookingsService;
    private BookingsController controller;

    @BeforeEach
    void setUp() {
        bookingsService = Mockito.mock(BookingsService.class);
        controller = new BookingsController(bookingsService);
        Mockito.lenient().when(bookingsService.listProfessionals(any(), any(), any(), any(), any()))
                .thenReturn(new ProfessionalListingResponse(0L, 2L, List.of()));
    }

    private ApiException listing(String city, String street, String houseNumber) {
        return (ApiException) catchThrowable(() ->
                controller.listProfessionals(null, null, "2", city, street, houseNumber, null, null));
    }

    @SuppressWarnings("unchecked")
    private static List<String> fields(ApiException exception) {
        return ((List<FieldError>) exception.getDetails()).stream().map(FieldError::field).toList();
    }

    @Test
    void emptyCityStreetAndHouseNumber_isRefusedNamingAllThree() {
        // The exact request the console showed. One FieldError per missing field, so the client
        // learns everything wrong with it in one response.
        ApiException exception = listing("", "", "");

        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(fields(exception)).containsExactlyInAnyOrder("city", "street", "houseNumber");
        verify(bookingsService, never()).listProfessionals(any(), any(), any(), any(), any());
    }

    @Test
    void missingCity_aloneIsEnoughToRefuse() {
        ApiException exception = listing(null, "דיזנגוף", "100");

        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(fields(exception)).containsExactly("city");
    }

    @Test
    void blankStreet_isRefused() {
        assertThat(fields(listing("תל אביב-יפו", "   ", "100"))).containsExactly("street");
    }

    @Test
    void houseNumberWithALetter_isRefused() {
        // Digits only, matching every write path (maps.HouseNumbers). Without this, the listing
        // query would accept an address that order creation then rejects.
        ApiException exception = listing("תל אביב-יפו", "דיזנגוף", "12א");

        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(fields(exception)).containsExactly("houseNumber");
    }

    @Test
    void houseNumberWithASymbol_isRefused() {
        assertThat(fields(listing("תל אביב-יפו", "דיזנגוף", "12/3"))).containsExactly("houseNumber");
    }

    @Test
    void aCompleteAddress_reachesTheServiceUnchanged() {
        controller.listProfessionals(null, null, "2", "תל אביב-יפו", "דיזנגוף", "100", "4", null);

        ArgumentCaptor<ServiceLocation> captor = ArgumentCaptor.forClass(ServiceLocation.class);
        verify(bookingsService).listProfessionals(any(), any(), any(), captor.capture(), any());
        ServiceLocation location = captor.getValue();
        assertThat(location.city()).isEqualTo("תל אביב-יפו");
        assertThat(location.street()).isEqualTo("דיזנגוף");
        assertThat(location.houseNumber()).isEqualTo("100");
        assertThat(location.apartment()).as("optional, and passed through when present").isEqualTo("4");
    }

    @Test
    void anAddressWithNoApartment_isStillComplete() {
        controller.listProfessionals(null, null, "2", "חיפה", "הרצל", "5", null, null);

        verify(bookingsService).listProfessionals(any(), any(), any(), any(), any());
    }

    // --- the request-binding half of the reported 400 -----------------------------------------

    @Test
    void categoryIdWithNoIssueId_isAccepted() {
        // THE root cause. Deferred authentication made "a listing keyed on a category, with no
        // issue" the normal case for guests and signed-in customers alike, and the service was
        // rewritten for it — but the controller still parsed issueId as required, so every such
        // request died at the binding step with `400 issueId is required`, address or no address.
        controller.listProfessionals(null, null, "2", "תל אביב-יפו", "דיזנגוף", "100", null, null);

        verify(bookingsService).listProfessionals(null, null, 2L,
                new ServiceLocation("תל אביב-יפו", "דיזנגוף", "100", null), null);
    }

    @Test
    void issueIdWithNoCategoryId_isAlsoAccepted() {
        // The other half of "exactly one of the two": a customer returning to an issue they
        // created on an earlier pass sends only an issueId.
        controller.listProfessionals(null, "7", null, "חיפה", "הרצל", "5", null, "RECOMMENDED");

        verify(bookingsService).listProfessionals(null, 7L, null,
                new ServiceLocation("חיפה", "הרצל", "5", null), "RECOMMENDED");
    }

    @Test
    void neitherIssueIdNorCategoryId_reachesTheServiceWhichOwnsThatRule() {
        // "At least one" is the service's rule (`requireListingCategory`), deliberately not
        // duplicated in the controller — two copies of a cross-field rule are how the two come to
        // disagree. So the controller binds both as null and lets it through.
        controller.listProfessionals(null, null, null, "חיפה", "הרצל", "5", null, null);

        verify(bookingsService).listProfessionals(null, null, null,
                new ServiceLocation("חיפה", "הרצל", "5", null), null);
    }

    @Test
    void aMalformedIdIsStillRefused() {
        // Absent and malformed stay different outcomes. An unparsable id quietly becoming null
        // would turn "I asked about issue 4x" into "I asked about nothing in particular".
        ApiException exception = (ApiException) catchThrowable(() ->
                controller.listProfessionals(null, "4x", null, "חיפה", "הרצל", "5", null, null));

        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(fields(exception)).containsExactly("issueId");
        verify(bookingsService, never()).listProfessionals(any(), any(), any(), any(), any());
    }

    @Test
    void aNonPositiveCategoryIdIsStillRefused() {
        ApiException exception = (ApiException) catchThrowable(() ->
                controller.listProfessionals(null, null, "0", "חיפה", "הרצל", "5", null, null));

        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(fields(exception)).containsExactly("categoryId");
    }

    // --- the optional apartment query param (maps.AddressAccessFields) ------------------------

    @Test
    void anApartmentOfDigits_isAccepted() {
        controller.listProfessionals(null, null, "2", "חיפה", "הרצל", "5", "12", null);

        verify(bookingsService).listProfessionals(null, null, 2L,
                new ServiceLocation("חיפה", "הרצל", "5", "12"), null);
    }

    @Test
    void anAbsentOrBlankApartment_isAccepted() {
        // Optional means optional: the great majority of listings carry no apartment at all.
        controller.listProfessionals(null, null, "2", "חיפה", "הרצל", "5", null, null);
        controller.listProfessionals(null, null, "2", "חיפה", "הרצל", "5", "  ", null);

        verify(bookingsService, Mockito.times(2))
                .listProfessionals(any(), any(), any(), any(), any());
    }

    @Test
    void anApartmentWithALetter_isRefused() {
        // The last door. Without this a customer could get a full listing for an address whose
        // booking CreateOrderRequest is then going to refuse -- failing at the last step of the
        // flow instead of on the field they can still see.
        ApiException exception = (ApiException) catchThrowable(() ->
                controller.listProfessionals(null, null, "2", "חיפה", "הרצל", "5", "4א", null));

        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(fields(exception)).containsExactly("apartment");
        verify(bookingsService, never()).listProfessionals(any(), any(), any(), any(), any());
    }

    @Test
    void availableWindows_withoutAnIssueId_isAccepted() {
        // The same defect one step further along the same journey: `listAvailableWindows`
        // documents and implements "no issue" as supported, and the client omits the parameter
        // when it has none.
        controller.listAvailableWindows(null, "9", null);

        verify(bookingsService).listAvailableWindows(null, 9L, null);
    }
}
