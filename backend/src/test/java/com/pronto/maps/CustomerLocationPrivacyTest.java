package com.pronto.maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pronto.bookings.dto.OrderDetailResponse;
import com.pronto.bookings.dto.OrderResponse;
import com.pronto.bookings.dto.OrderSummaryResponse;
import com.pronto.bookings.dto.ProfessionalCard;
import com.pronto.favorites.dto.FavoriteProfessionalSummary;
import com.pronto.professionals.dto.ProfessionalProfileResponse;
import com.pronto.sos.dto.SosCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A professional's live position is never exposed to a customer.</b>
 *
 * <p>MS2 gave the platform, for the first time, continuously-updated GPS for every working
 * professional — which is exactly the kind of data that leaks by accident, one convenient field at
 * a time, because somebody needed "just the coordinates" for a map pin. This test is the structural
 * answer: it walks the actual record components of every customer-facing DTO and fails on any field
 * whose name suggests a raw position, an accuracy figure or a location timestamp.
 *
 * <p>It is deliberately a <b>name-based</b> check rather than a list of known-bad fields. A future
 * developer adding {@code professionalLat} to a card is not going to remember to update an
 * allow-list, but they cannot avoid this.
 *
 * <p>What customers legitimately receive is <b>derived</b>: {@code distanceKm} and
 * {@code etaMinutes}. Those are answers to "how far" and "how long", not "where" — you cannot
 * reconstruct a position from them, and they are the entire point of the milestone.
 */
class CustomerLocationPrivacyTest {

    /**
     * Field-name fragments that indicate raw position data. Lower-cased substring matching, so
     * {@code professionalLatitude}, {@code lat}, {@code currentLng} and {@code gpsAccuracy} are all
     * caught.
     */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "latitude", "longitude", "accuracy", "capturedat", "gps", "coordinate");

    /**
     * Names that contain a forbidden fragment but are legitimate, with the reason.
     *
     * <p>Kept deliberately tiny. Every entry here is a decision that a customer may see this, and
     * an entry added carelessly is how the check stops working.
     */
    private static final Set<String> ALLOWED = Set.of(
            // (empty -- no customer-facing DTO currently needs any position-shaped field at all)
    );

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    @ParameterizedTest
    @ValueSource(classes = {
            ProfessionalCard.class,          // GET /api/bookings/professionals
            OrderResponse.class,             // every order mutation
            OrderDetailResponse.class,       // GET /api/bookings/orders/{id}
            OrderSummaryResponse.class,      // GET /api/bookings/orders/me
            SosCandidate.class,              // the SOS candidate tray -- the closest call of all
            ProfessionalProfileResponse.class,
            FavoriteProfessionalSummary.class
    })
    void noCustomerFacingDtoCarriesRawPositionData(Class<?> dtoClass) {
        assertThat(dtoClass.isRecord())
                .as("%s is expected to be a record; adjust this test if that changes", dtoClass.getSimpleName())
                .isTrue();

        for (RecordComponent component : dtoClass.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            if (ALLOWED.contains(component.getName())) {
                continue;
            }
            assertThat(FORBIDDEN_FRAGMENTS)
                    .as("%s.%s looks like raw position data. A customer may receive DERIVED figures "
                                    + "(distanceKm, etaMinutes) but never a professional's coordinates, "
                                    + "GPS accuracy or location timestamp.",
                            dtoClass.getSimpleName(), component.getName())
                    .noneMatch(name::contains);
        }
    }

    /**
     * The positive half: a card carries exactly the derived figures, and they survive
     * serialisation as the frontend will read them.
     */
    @Test
    void aProfessionalCardCarriesDerivedTravelFiguresAndNothingPositional() throws Exception {
        ProfessionalCard card = new ProfessionalCard(1L, "פרו", "גוש דן", new BigDecimal("250.00"), null,
                "תל אביב", null, new BigDecimal("4.50"), 12L, false, List.of(3L),
                new BigDecimal("4.2"), 11, true, null);

        String json = MAPPER.writeValueAsString(card);

        assertThat(json).contains("\"distanceKm\":4.2").contains("\"etaMinutes\":11");
        assertThat(json.toLowerCase(Locale.ROOT))
                .doesNotContain("latitude").doesNotContain("longitude").doesNotContain("accuracy");
    }

    /**
     * The unavailable case serialises as explicit {@code null}s plus a reason code — never as
     * zeros, which a client would render as "0.0 ק״מ, 0 דקות".
     */
    @Test
    void anUnroutableCardSerialisesAsNullsAndAReasonNotAsZeros() throws Exception {
        ProfessionalCard card = new ProfessionalCard(1L, "פרו", "גוש דן", new BigDecimal("250.00"), null,
                "תל אביב", null, null, 0L, false, List.of(3L),
                null, null, false, RouteUnavailableReason.PROFESSIONAL_LOCATION_STALE.name());

        String json = MAPPER.writeValueAsString(card);

        assertThat(json).contains("\"distanceKm\":null").contains("\"etaMinutes\":null");
        assertThat(json).contains("\"etaUnavailableReason\":\"PROFESSIONAL_LOCATION_STALE\"");
        assertThat(json).doesNotContain("\"distanceKm\":0").doesNotContain("\"etaMinutes\":0");
    }

    /**
     * The order DTOs carry the human-readable service address (the professional has to find the
     * door) but not its coordinates. Storing coordinates in MS2 must not automatically put them on
     * the wire — see roadmap §26.
     */
    @Test
    void orderResponsesCarryTheAddressTextButNotItsCoordinates() throws Exception {
        OrderResponse response = new OrderResponse(1L, 2L, 3L, 4L,
                com.pronto.bookings.entity.OrderStatus.ON_THE_WAY, Instant.now(), null, null,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO,
                "תל אביב", "דיזנגוף", "10", "4", "2", "א", null, null, Instant.now(), Instant.now());

        String json = MAPPER.writeValueAsString(response);

        assertThat(json).contains("דיזנגוף");
        assertThat(json.toLowerCase(Locale.ROOT))
                .doesNotContain("latitude").doesNotContain("longitude");
    }

    /**
     * The arrival-evidence columns exist on the {@code orders} entity, and they are the
     * professional's own position — the most sensitive thing MS2 persists. No response DTO reads
     * them.
     */
    @Test
    void arrivalEvidenceIsPersistedButNeverProjectedIntoAnyResponse() {
        List<Class<?>> responses = List.of(OrderResponse.class, OrderDetailResponse.class,
                OrderSummaryResponse.class);

        for (Class<?> dto : responses) {
            List<String> names = java.util.Arrays.stream(dto.getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(n -> n.toLowerCase(Locale.ROOT))
                    .toList();
            assertThat(names)
                    .as("%s must not project the arrival evidence columns", dto.getSimpleName())
                    .noneMatch(n -> n.contains("arrivallat") || n.contains("arrivallon")
                            || n.contains("arrivalaccuracy") || n.contains("arrivaldistance"));
        }
    }
}
