package com.pronto.locations.service;

import com.pronto.locations.entity.ServiceCity;
import com.pronto.locations.repository.ServiceCityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The one place customer address text becomes a canonical {@code service_cities} id.
 *
 * <p><b>Why the failure cases matter more than the happy path.</b> A resolver that is too strict
 * shows an empty listing to a customer we could have served; one that is too loose places them in
 * the wrong city and dispatches a professional hours away — which is the bug the coverage filter
 * exists to fix, reintroduced one layer down. So the tests that pin what must <em>not</em> match are
 * doing the load-bearing work here.
 */
class ServiceCityResolverTest {

    private static final ServiceCity TEL_AVIV = city(1L, 10L, "tel_aviv", "תל אביב");
    private static final ServiceCity RAMAT_GAN = city(2L, 10L, "ramat_gan", "רמת גן");
    private static final ServiceCity EILAT = city(3L, 70L, "eilat", "אילת");
    private static final ServiceCity KIRYAT_GAT = city(4L, 70L, "kiryat_gat", "קרית גת");
    private static final ServiceCity BEER_SHEVA = city(5L, 70L, "beer_sheva", "באר שבע");

    private ServiceCityRepository repository;
    private ServiceCityResolver resolver;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ServiceCityRepository.class);
        when(repository.findAll())
                .thenReturn(List.of(TEL_AVIV, RAMAT_GAN, EILAT, KIRYAT_GAT, BEER_SHEVA));
        resolver = new ServiceCityResolver(repository);
    }

    // ---- what resolves --------------------------------------------------------------------

    @Test
    void anExactCatalogueNameResolves() {
        assertThat(resolver.resolveId("אילת")).contains(3L);
    }

    @Test
    void surroundingAndInternalWhitespaceIsIgnored() {
        assertThat(resolver.resolveId("  אילת  ")).contains(3L);
        assertThat(resolver.resolveId("רמת   גן")).contains(2L);
    }

    @Test
    void aMergedMunicipalityNameResolvesToItsCatalogueHalf() {
        // The case that would otherwise break Tel Aviv, the platform's densest market: Google
        // answers "תל אביב-יפו" for a Tel Aviv address and the catalogue row is "תל אביב".
        assertThat(resolver.resolveId("תל אביב-יפו")).contains(1L);
    }

    @Test
    void hyphenSpacingVariantsResolve() {
        assertThat(resolver.resolveId("תל אביב - יפו")).contains(1L);
        assertThat(resolver.resolveId("תל-אביב-יפו")).contains(1L);
    }

    @Test
    void quotationMarksAreIgnored() {
        assertThat(resolver.resolveId("\"אילת\"")).contains(3L);
    }

    // ---- what must NOT resolve ------------------------------------------------------------

    @Test
    void aHyphenatedCatalogueNameMatchesTheSpacedCustomerSpelling() {
        // Both sides are flattened to the same canonical form, so it does not matter which side
        // spells the hyphen -- the same normalization V44's backfill applied to professionals.
        assertThat(resolver.resolveId("קרית-גת")).contains(4L);
    }

    @Test
    void aPrefixOfARealCityDoesNotResolve() {
        // The single most dangerous loosening available. "קרית" is a prefix of several real
        // municipalities; matching it would place the customer in whichever one happened to sort
        // first and dispatch a professional to a different town.
        assertThat(resolver.resolveId("קרית")).isEmpty();
    }

    @Test
    void spaceSeparatedWordsAreNeverDropped() {
        // The safety property that keeps this from degenerating into prefix matching: only
        // HYPHEN components are dropped. "קרית גת" is one component and can never fall back to
        // "קרית", so a customer in one town is never quietly placed in another.
        assertThat(resolver.resolveId("קרית")).isEmpty();
        assertThat(resolver.resolveId("באר")).isEmpty();
    }

    @Test
    void aCityNameContainingARealOneDoesNotResolve() {
        assertThat(resolver.resolveId("קרית גת עילית")).isEmpty();
        assertThat(resolver.resolveId("אילת הדרומית")).isEmpty();
    }

    @Test
    void aPlaceOutsideTheCatalogueDoesNotResolve() {
        // The honest answer for a locality this platform has not seeded. Callers must treat it as
        // "we do not cover this place" and show an empty result -- never as "no filter", which is
        // exactly how a Gush Dan professional ended up on an Eilat customer's screen.
        assertThat(resolver.resolveId("כפר ורדים")).isEmpty();
    }

    @Test
    void blankAndNullDoNotResolve() {
        assertThat(resolver.resolveId(null)).isEmpty();
        assertThat(resolver.resolveId("")).isEmpty();
        assertThat(resolver.resolveId("   ")).isEmpty();
    }

    @Test
    void anEmptyCatalogueResolvesNothingRatherThanThrowing() {
        when(repository.findAll()).thenReturn(List.of());

        assertThat(resolver.resolveId("אילת")).isEmpty();
    }

    // ---- the resolved value is a canonical row, not a string --------------------------------

    @Test
    void resolvingReturnsTheCatalogueRowItself() {
        // The whole point: downstream matching binds an id into a query against
        // professional_service_cities, and never compares city names again.
        assertThat(resolver.resolve("תל אביב-יפו"))
                .hasValueSatisfying(city -> {
                    assertThat(city.getId()).isEqualTo(1L);
                    assertThat(city.getCode()).isEqualTo("tel_aviv");
                    assertThat(city.getRegionId()).isEqualTo(10L);
                });
    }

    private static ServiceCity city(Long id, Long regionId, String code, String nameHe) {
        // The entity's no-arg constructor is `protected` for JPA, so it is instantiated the way
        // JPA itself does rather than by widening the production class's visibility for a test.
        ServiceCity city = newServiceCity();
        set(city, "id", id);
        set(city, "regionId", regionId);
        set(city, "code", code);
        set(city, "nameHe", nameHe);
        set(city, "nameEn", code);
        return city;
    }

    private static ServiceCity newServiceCity() {
        try {
            java.lang.reflect.Constructor<ServiceCity> constructor =
                    ServiceCity.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
