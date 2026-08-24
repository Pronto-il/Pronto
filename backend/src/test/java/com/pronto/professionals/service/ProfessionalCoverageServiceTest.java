package com.pronto.professionals.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.locations.entity.ServiceCity;
import com.pronto.locations.entity.ServiceRegion;
import com.pronto.locations.repository.ServiceCityRepository;
import com.pronto.locations.repository.ServiceRegionRepository;
import com.pronto.locations.service.ServiceCoverageValidator;
import com.pronto.professionals.entity.Category;
import com.pronto.professionals.entity.Professional;
import com.pronto.professionals.entity.ProfessionalCategory;
import com.pronto.professionals.entity.ProfessionalCategoryId;
import com.pronto.professionals.entity.ProfessionalServiceCity;
import com.pronto.professionals.entity.ProfessionalServiceCityId;
import com.pronto.professionals.repository.CategoryRepository;
import com.pronto.professionals.repository.ProfessionalCategoryRepository;
import com.pronto.professionals.repository.ProfessionalServiceCityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MS4 Part B — the one component that reads and writes a professional's categories and service
 * coverage, and therefore the one place the multi-category rules are actually enforced.
 *
 * <p>The real {@link ServiceCoverageValidator} is wired in rather than mocked (over mocked
 * repositories), for the same reason {@code AuthServiceTest} does it: a stubbed validator would
 * agree with anything, and "the profile edit validates coverage the way registration does" is
 * exactly the property worth testing.
 */
class ProfessionalCoverageServiceTest {

    private static final long PROFESSIONAL_ID = 7L;
    private static final Long PLUMBING = 1L;
    private static final Long ELECTRICAL = 2L;
    private static final Long HANDYMAN = 8L;
    private static final Long NO_SUCH_CATEGORY = 4242L;

    private static final Long REGION_ID = 4L;
    private static final Long TEL_AVIV = 40L;
    private static final Long RAMAT_GAN = 41L;

    private ProfessionalCategoryRepository professionalCategoryRepository;
    private ProfessionalServiceCityRepository professionalServiceCityRepository;
    private ServiceRegionRepository serviceRegionRepository;
    private ServiceCityRepository serviceCityRepository;
    private CategoryRepository categoryRepository;
    private ProfessionalCoverageService service;

    @BeforeEach
    void setUp() {
        professionalCategoryRepository = Mockito.mock(ProfessionalCategoryRepository.class);
        professionalServiceCityRepository = Mockito.mock(ProfessionalServiceCityRepository.class);
        serviceRegionRepository = Mockito.mock(ServiceRegionRepository.class);
        serviceCityRepository = Mockito.mock(ServiceCityRepository.class);
        categoryRepository = Mockito.mock(CategoryRepository.class);
        service = new ProfessionalCoverageService(professionalCategoryRepository,
                professionalServiceCityRepository, serviceRegionRepository, serviceCityRepository,
                categoryRepository, new ServiceCoverageValidator(serviceRegionRepository, serviceCityRepository));

        Mockito.lenient().when(serviceRegionRepository.existsById(REGION_ID)).thenReturn(true);
        Mockito.lenient().when(serviceCityRepository.findAllById(any())).thenAnswer(invocation -> {
            List<ServiceCity> found = new ArrayList<>();
            for (Long id : (Iterable<Long>) invocation.getArgument(0)) {
                if (TEL_AVIV.equals(id)) {
                    found.add(city(TEL_AVIV, REGION_ID, (short) 1, "תל אביב"));
                } else if (RAMAT_GAN.equals(id)) {
                    found.add(city(RAMAT_GAN, REGION_ID, (short) 2, "רמת גן"));
                }
            }
            return found;
        });
        Mockito.lenient().when(categoryRepository.findAllById(any())).thenAnswer(invocation -> {
            List<Category> found = new ArrayList<>();
            for (Long id : (Iterable<Long>) invocation.getArgument(0)) {
                if (PLUMBING.equals(id) || ELECTRICAL.equals(id) || HANDYMAN.equals(id)) {
                    found.add(category(id));
                }
            }
            return found;
        });
    }

    // ---- case 6: an invalid category is rejected ----

    @Test
    void unknownCategoryId_isRejectedWithAFieldError() {
        assertThatThrownBy(() -> service.validateCategories(List.of(PLUMBING, NO_SUCH_CATEGORY), "categoryIds"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class))
                            .extracting(FieldError::message)
                            .anyMatch(message -> message.contains("unknown category id " + NO_SUCH_CATEGORY));
                });
    }

    @Test
    void emptyCategorySelection_isRejected() {
        assertThatThrownBy(() -> service.validateCategories(List.of(), "categoryIds"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).asInstanceOf(list(FieldError.class))
                        .extracting(FieldError::field).contains("categoryIds"));
    }

    @Test
    void anInvalidCategory_isRejectedBeforeAnythingIsWritten() {
        when(professionalCategoryRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(new ProfessionalCategory(PROFESSIONAL_ID, PLUMBING)));

        assertThatThrownBy(() -> service.replaceCategories(PROFESSIONAL_ID,
                List.of(PLUMBING, NO_SUCH_CATEGORY), "categoryIds"))
                .isInstanceOf(ApiException.class);

        // The point of validating first: a partially-applied edit would silently drop a category
        // the professional still holds.
        verify(professionalCategoryRepository, never()).save(any());
        verify(professionalCategoryRepository, never()).deleteById(any());
    }

    // ---- cases 1/2/5: one category, several categories, and changing them ----

    @Test
    void aSingleCategory_isAccepted() {
        assertThat(service.validateCategories(List.of(PLUMBING), "categoryIds")).containsExactly(PLUMBING);
    }

    @Test
    void severalCategories_areAccepted() {
        assertThat(service.validateCategories(List.of(PLUMBING, HANDYMAN), "categoryIds"))
                .containsExactlyInAnyOrder(PLUMBING, HANDYMAN);
    }

    @Test
    void duplicateCategoryIds_areDeduplicated() {
        assertThat(service.validateCategories(List.of(PLUMBING, PLUMBING), "categoryIds"))
                .containsExactly(PLUMBING);
    }

    @Test
    void replaceCategories_isDiffBased_touchingOnlyWhatChanged() {
        when(professionalCategoryRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of(
                new ProfessionalCategory(PROFESSIONAL_ID, PLUMBING),
                new ProfessionalCategory(PROFESSIONAL_ID, ELECTRICAL)));

        // Plumbing stays, Electrical goes, Handyman arrives.
        service.replaceCategories(PROFESSIONAL_ID, List.of(PLUMBING, HANDYMAN), "categoryIds");

        verify(professionalCategoryRepository).deleteById(new ProfessionalCategoryId(PROFESSIONAL_ID, ELECTRICAL));
        verify(professionalCategoryRepository, never())
                .deleteById(new ProfessionalCategoryId(PROFESSIONAL_ID, PLUMBING));
        // Exactly one insert: the row that survived the edit is not rewritten, so its created_at
        // still records when the professional actually took that trade on.
        verify(professionalCategoryRepository, Mockito.times(1)).save(any(ProfessionalCategory.class));
    }

    // ---- ordering: what makes "primary category" meaningful without a primary flag ----

    @Test
    void categoriesComeBackInCatalogueDisplayOrder() {
        when(professionalCategoryRepository.findCategoryIdsInDisplayOrder(PROFESSIONAL_ID))
                .thenReturn(List.of(PLUMBING, HANDYMAN));

        assertThat(service.categoryIds(PROFESSIONAL_ID)).containsExactly(PLUMBING, HANDYMAN);
    }

    @Test
    void batchCategoryLookup_returnsAnEntryForEveryProfessional_evenOnesWithNone() {
        List<Object[]> rows = List.of(
                new Object[]{1L, PLUMBING}, new Object[]{1L, HANDYMAN}, new Object[]{2L, ELECTRICAL});
        when(professionalCategoryRepository.findCategoryIdsInDisplayOrder(List.of(1L, 2L, 3L)))
                .thenReturn(rows);

        Map<Long, List<Long>> byProfessional = service.categoryIdsByProfessional(List.of(1L, 2L, 3L));

        assertThat(byProfessional.get(1L)).containsExactly(PLUMBING, HANDYMAN);
        assertThat(byProfessional.get(2L)).containsExactly(ELECTRICAL);
        // Present-but-empty, not absent: a listing must not have to null-check every card.
        assertThat(byProfessional).containsKey(3L);
        assertThat(byProfessional.get(3L)).isEmpty();
    }

    @Test
    void batchCategoryLookup_withNoProfessionals_doesNotQuery() {
        assertThat(service.categoryIdsByProfessional(List.of())).isEmpty();
        verify(professionalCategoryRepository, never()).findCategoryIdsInDisplayOrder(Mockito.anyList());
    }

    // ---- case 10: service cities persist, and load back resolved ----

    @Test
    void replaceCoverage_setsRegionAndBaseCityOnTheEntity_andDiffsTheCityRows() {
        Professional professional = professional();
        when(professionalServiceCityRepository.findByProfessionalId(PROFESSIONAL_ID))
                .thenReturn(List.of(new ProfessionalServiceCity(PROFESSIONAL_ID, TEL_AVIV)));

        service.replaceCoverage(professional, REGION_ID, List.of(TEL_AVIV, RAMAT_GAN), TEL_AVIV, "");

        assertThat(professional.getServiceRegionId()).isEqualTo(REGION_ID);
        assertThat(professional.getBaseCityId()).isEqualTo(TEL_AVIV);
        verify(professionalServiceCityRepository, Mockito.times(1)).save(any(ProfessionalServiceCity.class));
        verify(professionalServiceCityRepository, never()).deleteById(any());
    }

    @Test
    void replaceCoverage_removingACity_deletesOnlyThatRow() {
        Professional professional = professional();
        when(professionalServiceCityRepository.findByProfessionalId(PROFESSIONAL_ID)).thenReturn(List.of(
                new ProfessionalServiceCity(PROFESSIONAL_ID, TEL_AVIV),
                new ProfessionalServiceCity(PROFESSIONAL_ID, RAMAT_GAN)));

        service.replaceCoverage(professional, REGION_ID, List.of(TEL_AVIV), TEL_AVIV, "");

        verify(professionalServiceCityRepository)
                .deleteById(new ProfessionalServiceCityId(PROFESSIONAL_ID, RAMAT_GAN));
        verify(professionalServiceCityRepository, never()).save(any());
    }

    @Test
    void replaceCoverage_withAnInvalidSelection_writesNothing() {
        Professional professional = professional();

        assertThatThrownBy(() -> service.replaceCoverage(professional, REGION_ID, List.of(TEL_AVIV),
                RAMAT_GAN, ""))   // base city not among the selected cities
                .isInstanceOf(ApiException.class);

        assertThat(professional.getServiceRegionId()).isNull();
        assertThat(professional.getBaseCityId()).isNull();
        verify(professionalServiceCityRepository, never()).save(any());
    }

    @Test
    void load_resolvesHebrewLabelsAlongsideTheIds() {
        Professional professional = professional();
        professional.setServiceRegionId(REGION_ID);
        professional.setBaseCityId(TEL_AVIV);
        when(professionalServiceCityRepository.findCityIdsInDisplayOrder(PROFESSIONAL_ID))
                .thenReturn(List.of(TEL_AVIV, RAMAT_GAN));
        when(serviceCityRepository.findById(TEL_AVIV))
                .thenReturn(Optional.of(city(TEL_AVIV, REGION_ID, (short) 1, "תל אביב")));
        when(serviceRegionRepository.findById(REGION_ID)).thenReturn(Optional.of(region(REGION_ID, "גוש דן")));
        when(professionalCategoryRepository.findCategoryIdsInDisplayOrder(PROFESSIONAL_ID))
                .thenReturn(List.of(PLUMBING, HANDYMAN));

        ProfessionalCoverageService.CoverageView view = service.load(professional);

        assertThat(view.serviceRegionId()).isEqualTo(REGION_ID);
        assertThat(view.serviceRegionNameHe()).isEqualTo("גוש דן");
        assertThat(view.baseCityNameHe()).isEqualTo("תל אביב");
        assertThat(view.serviceCityIds()).containsExactly(TEL_AVIV, RAMAT_GAN);
        assertThat(view.serviceCityNamesHe()).containsExactly("תל אביב", "רמת גן");
        assertThat(view.categoryIds()).containsExactly(PLUMBING, HANDYMAN);
    }

    @Test
    void load_forAPreMs4ProfessionalWithNoCanonicalPlace_reportsNothingRatherThanGuessing() {
        // V44 leaves both ids null when the old free text named no recognisable region. The
        // honest answer is "not set"; a fabricated city would be worse than an empty one.
        Professional professional = professional();
        when(professionalServiceCityRepository.findCityIdsInDisplayOrder(PROFESSIONAL_ID)).thenReturn(List.of());
        when(professionalCategoryRepository.findCategoryIdsInDisplayOrder(PROFESSIONAL_ID))
                .thenReturn(List.of(PLUMBING));

        ProfessionalCoverageService.CoverageView view = service.load(professional);

        assertThat(view.serviceRegionId()).isNull();
        assertThat(view.serviceRegionNameHe()).isNull();
        assertThat(view.baseCityNameHe()).isNull();
        assertThat(view.serviceCityNamesHe()).isEmpty();
        // ...and their trade is untouched, which is the migration's actual promise.
        assertThat(view.categoryIds()).containsExactly(PLUMBING);
    }

    @Test
    void baseCityName_forAProfessionalWithNoBaseCity_isNullNotAnError() {
        // matching.ApproximateDistanceEtaStrategy treats an unknown city as "not the customer's
        // city", so an unplaced professional gets the different-city estimate rather than a crash.
        assertThatCode(() -> assertThat(service.baseCityName(professional())).isNull())
                .doesNotThrowAnyException();
    }

    // ---- helpers ----

    private static Professional professional() {
        Professional professional = new Professional(99L, null, null, new BigDecimal("250.00"));
        setField(professional, "id", PROFESSIONAL_ID);
        return professional;
    }

    private static <T> T readOnlyEntity(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Category category(Long id) {
        Category category = readOnlyEntity(Category.class);
        setField(category, "id", id);
        return category;
    }

    private static ServiceCity city(Long id, Long regionId, short displayOrder, String nameHe) {
        ServiceCity city = readOnlyEntity(ServiceCity.class);
        setField(city, "id", id);
        setField(city, "regionId", regionId);
        setField(city, "displayOrder", displayOrder);
        setField(city, "nameHe", nameHe);
        return city;
    }

    private static ServiceRegion region(Long id, String nameHe) {
        ServiceRegion region = readOnlyEntity(ServiceRegion.class);
        setField(region, "id", id);
        setField(region, "nameHe", nameHe);
        return region;
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
