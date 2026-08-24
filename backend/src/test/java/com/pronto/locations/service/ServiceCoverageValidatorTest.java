package com.pronto.locations.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.locations.entity.ServiceCity;
import com.pronto.locations.repository.ServiceCityRepository;
import com.pronto.locations.repository.ServiceRegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MS4 Part A — the closed region/city vocabulary, and the two cross-field rules the schema
 * cannot express on its own (a city must sit inside the declared region; the base city must be
 * one the professional actually serves).
 *
 * <p>Mockito over the two catalogue repositories, no Spring context, matching this codebase's
 * unit-test convention. What is <em>not</em> mocked is the rule itself: this is the real
 * validator, and it is the same instance registration and the profile edit both call — the whole
 * reason it was extracted.
 */
class ServiceCoverageValidatorTest {

    private static final Long REGION_ID = 4L;          // גוש דן
    private static final Long OTHER_REGION_ID = 2L;    // חיפה והקריות
    private static final Long TEL_AVIV = 40L;
    private static final Long RAMAT_GAN = 41L;
    private static final Long HAIFA = 17L;             // sits in OTHER_REGION_ID
    private static final Long NO_SUCH_CITY = 99_999L;

    private ServiceRegionRepository serviceRegionRepository;
    private ServiceCityRepository serviceCityRepository;
    private ServiceCoverageValidator validator;

    @BeforeEach
    void setUp() {
        serviceRegionRepository = Mockito.mock(ServiceRegionRepository.class);
        serviceCityRepository = Mockito.mock(ServiceCityRepository.class);
        validator = new ServiceCoverageValidator(serviceRegionRepository, serviceCityRepository);

        Mockito.lenient().when(serviceRegionRepository.existsById(REGION_ID)).thenReturn(true);
        Mockito.lenient().when(serviceRegionRepository.existsById(OTHER_REGION_ID)).thenReturn(true);
        Mockito.lenient().when(serviceCityRepository.findAllById(any())).thenAnswer(invocation -> {
            List<ServiceCity> found = new ArrayList<>();
            for (Long id : (Iterable<Long>) invocation.getArgument(0)) {
                if (TEL_AVIV.equals(id)) {
                    found.add(city(TEL_AVIV, REGION_ID, (short) 1, "תל אביב"));
                } else if (RAMAT_GAN.equals(id)) {
                    found.add(city(RAMAT_GAN, REGION_ID, (short) 2, "רמת גן"));
                } else if (HAIFA.equals(id)) {
                    found.add(city(HAIFA, OTHER_REGION_ID, (short) 1, "חיפה"));
                }
            }
            return found;
        });
    }

    // ---- the happy path (MS4 validation case 10: multiple cities persist and load) ----

    @Test
    void multipleCitiesInsideTheRegion_areAccepted() {
        assertThatCode(() -> validator.validate(REGION_ID, List.of(TEL_AVIV, RAMAT_GAN), TEL_AVIV, ""))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptedCities_comeBackInCatalogueOrder_notRequestOrder() {
        // Ramat Gan (display_order 2) sent first; Tel Aviv (1) must still lead, so the same
        // professional's city list renders identically wherever it is shown.
        assertThat(validator.validate(REGION_ID, List.of(RAMAT_GAN, TEL_AVIV), RAMAT_GAN, ""))
                .containsExactly(TEL_AVIV, RAMAT_GAN);
    }

    @Test
    void duplicateCityIds_areDeduplicatedRatherThanRejected() {
        // A client that sends the same id twice made a harmless mistake, not an illegal request;
        // what matters is that it cannot become a primary-key violation on the join table.
        assertThat(validator.validate(REGION_ID, List.of(TEL_AVIV, TEL_AVIV), TEL_AVIV, ""))
                .containsExactly(TEL_AVIV);
    }

    // ---- case 11: an uncontrolled city value cannot be persisted ----

    @Test
    void unknownCityId_isRejectedWithAFieldError() {
        assertThatThrownBy(() -> validator.validate(REGION_ID, List.of(TEL_AVIV, NO_SUCH_CITY), TEL_AVIV, ""))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class)).extracting(FieldError::field)
                            .contains("serviceCityIds");
                });
    }

    @Test
    void unknownRegionId_isRejected() {
        assertThatThrownBy(() -> validator.validate(777L, List.of(TEL_AVIV), TEL_AVIV, ""))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class)).extracting(FieldError::field)
                        .contains("serviceRegionId"));
    }

    @Test
    void emptyCitySelection_isRejected() {
        assertThatThrownBy(() -> validator.validate(REGION_ID, List.of(), null, ""))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class)).extracting(FieldError::field)
                        .contains("serviceCityIds"));
    }

    @Test
    void nullCityId_isRejectedRatherThanSilentlyDropped() {
        List<Long> withNull = new ArrayList<>();
        withNull.add(TEL_AVIV);
        withNull.add(null);

        assertThatThrownBy(() -> validator.validate(REGION_ID, withNull, TEL_AVIV, ""))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class)).extracting(FieldError::message)
                        .anyMatch(message -> message.contains("null city id")));
    }

    // ---- case 12: the region/city relationship is enforced ----

    @Test
    void cityFromAnotherRegion_isRejected() {
        assertThatThrownBy(() -> validator.validate(REGION_ID, List.of(TEL_AVIV, HAIFA), TEL_AVIV, ""))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class)).extracting(FieldError::message)
                        .anyMatch(message -> message.contains("does not belong to the selected service region")));
    }

    @Test
    void theSameCity_isAcceptedUnderItsOwnRegion() {
        // The mirror of the test above: Haifa is not an invalid city, it is a city in a different
        // region. Declare that region and it is perfectly legal — which is what makes the rule a
        // relationship check rather than a hidden second allow-list.
        assertThatCode(() -> validator.validate(OTHER_REGION_ID, List.of(HAIFA), HAIFA, ""))
                .doesNotThrowAnyException();
    }

    // ---- the base city has to be one they serve ----

    @Test
    void baseCityOutsideTheSelection_isRejected() {
        assertThatThrownBy(() -> validator.validate(REGION_ID, List.of(TEL_AVIV), RAMAT_GAN, ""))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class)).extracting(FieldError::field)
                        .contains("baseCityId"));
    }

    @Test
    void missingBaseCity_isRejected() {
        assertThatThrownBy(() -> validator.validate(REGION_ID, List.of(TEL_AVIV), null, ""))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class)).extracting(FieldError::field)
                        .contains("baseCityId"));
    }

    // ---- the field prefix, so registration's nested payload reports the path it actually sent ----

    @Test
    void fieldPrefix_isAppliedToEveryReportedPath() {
        assertThatThrownBy(() -> validator.validate(null, List.of(), null, "professional."))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class)).extracting(FieldError::field)
                        .allSatisfy(field -> assertThat(field).startsWith("professional.")));
    }

    @Test
    void everyProblemIsReportedAtOnce_notOneAtATime() {
        // A registrant who got three things wrong should be told three things, not made to
        // resubmit twice to discover the second and third.
        assertThatThrownBy(() -> validator.validate(777L, List.of(NO_SUCH_CITY), 12345L, ""))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class))
                        .hasSizeGreaterThanOrEqualTo(3));
    }

    /** {@code ServiceCity} is a read-only reference entity with no public constructor. */
    private static ServiceCity city(Long id, Long regionId, short displayOrder, String nameHe) {
        ServiceCity city;
        try {
            var constructor = ServiceCity.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            city = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        setField(city, "id", id);
        setField(city, "regionId", regionId);
        setField(city, "displayOrder", displayOrder);
        setField(city, "nameHe", nameHe);
        return city;
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
