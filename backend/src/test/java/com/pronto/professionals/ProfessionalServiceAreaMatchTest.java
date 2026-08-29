package com.pronto.professionals;

import com.pronto.bookings.repository.ProfessionalListingRepository;
import com.pronto.sos.repository.SosCandidateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The Eilat bug, pinned at the only layer that can enforce it: the query.</b>
 *
 * <p>The defect was not a wrong filter — it was <em>no</em> filter. Both discovery queries selected
 * on category, approval, onboarding and phone verification, and on nothing geographic at all, so a
 * customer in Eilat was offered every eligible professional in the country and the only thing their
 * address influenced was the ETA number printed on each card afterwards.
 *
 * <p>These tests read the {@code @Query} annotations as text, the same technique and for the same
 * reason as {@link ProfessionalCategoryMatchTest} and {@link ProfessionalEligibilityTest}: the rule
 * lives in a JPQL string, so the thing worth asserting is that the string still contains it. A
 * service-layer test can only show that <em>a</em> repository method was called; it cannot show that
 * the SQL behind it filters on anything.
 *
 * <p>They are deliberately paired across both surfaces. A coverage rule enforced on the standard
 * listing but not on SOS would mean a professional a customer cannot find by browsing is still
 * dispatched to their emergency — the exact asymmetry {@link ProfessionalCategoryMatch} was
 * extracted to prevent, one rule later.
 */
class ProfessionalServiceAreaMatchTest {

    @Test
    void theStandardListingFiltersOnDeclaredServiceCoverage() {
        assertThat(queryOf(ProfessionalListingRepository.class, "listByCategoryAndServiceCity"))
                .contains(ProfessionalServiceAreaMatch.SERVES_CITY_JPQL);
    }

    @Test
    void theSosHardFilterFiltersOnDeclaredServiceCoverage() {
        assertThat(queryOf(SosCandidateRepository.class, "findEligible"))
                .contains(ProfessionalServiceAreaMatch.SERVES_CITY_JPQL);
    }

    @Test
    void bothSurfacesUseTheSameConstant_soTheyCannotDisagree() {
        // Not "both contain a coverage filter" — both contain THIS one. Two hand-written EXISTS
        // clauses could drift into disagreeing about what coverage means, which is precisely how
        // the platform would end up with a professional who is browsable but not dispatchable.
        String listing = queryOf(ProfessionalListingRepository.class, "listByCategoryAndServiceCity");
        String sos = queryOf(SosCandidateRepository.class, "findEligible");

        assertThat(listing).contains(ProfessionalServiceAreaMatch.SERVES_CITY_JPQL);
        assertThat(sos).contains(ProfessionalServiceAreaMatch.SERVES_CITY_JPQL);
    }

    @Test
    void coverageIsDecidedByTheJoinTable_notByTheBaseCityColumn() {
        // The rule the requirement is most explicit about: a Tel Aviv-based professional who lists
        // Eilat is eligible there, and one who lists only Gush Dan cities is not, however close
        // their base city is to anything. base_city_id answers "where are they based" and must
        // never leak into "where do they work".
        assertThat(ProfessionalServiceAreaMatch.SERVES_CITY_JPQL)
                .contains("ProfessionalServiceCity")
                .contains("pscMatch.cityId = :serviceCityId")
                .doesNotContain("baseCityId")
                .doesNotContain("p.baseCityId");
    }

    @Test
    void coverageIsNotExpressedAsDistanceOrEta() {
        // Distance is measured from a live device position that may be anywhere, and degrades to
        // "unavailable" whenever the routing provider is down. It is a range concern layered on
        // top of eligibility, never a replacement for it.
        assertThat(ProfessionalServiceAreaMatch.SERVES_CITY_JPQL)
                .doesNotContain("distance")
                .doesNotContain("latitude")
                .doesNotContain("longitude")
                .doesNotContain("eta");
    }

    @Test
    void theFragmentHonoursTheSharedAliasContract() {
        // `p` bound by the host query, and a subquery alias distinct from the two sibling
        // fragments' (`pcMatch`, `pcOnboarding`) so all three can be concatenated into one WHERE.
        assertThat(ProfessionalServiceAreaMatch.SERVES_CITY_JPQL)
                .contains("pscMatch.professionalId = p.id")
                .doesNotContain(" pcMatch")
                .doesNotContain(" pcOnboarding");
    }

    @Test
    void bothQueriesStillApplyEveryRuleTheyAlreadyHad() {
        // A coverage filter that quietly replaced the category or eligibility filter would pass
        // every test above and be a far worse bug than the one being fixed.
        String listing = queryOf(ProfessionalListingRepository.class, "listByCategoryAndServiceCity");
        String sos = queryOf(SosCandidateRepository.class, "findEligible");

        assertThat(listing)
                .contains(ProfessionalCategoryMatch.SERVES_CATEGORY_JPQL)
                .contains(ProfessionalEligibility.ELIGIBLE_JPQL)
                .contains("u.deletedAt IS NULL");
        assertThat(sos)
                .contains(ProfessionalCategoryMatch.SERVES_CATEGORY_JPQL)
                .contains(ProfessionalEligibility.ELIGIBLE_JPQL)
                .contains("s.isAvailable = true")
                .contains("p.id NOT IN :excludedProfessionalIds");
    }

    /** The single {@code @Query} on the named method. */
    private static String queryOf(Class<?> repository, String methodName) {
        for (Method method : repository.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Query query = method.getAnnotation(Query.class);
                assertThat(query).as("@Query on %s.%s", repository.getSimpleName(), methodName).isNotNull();
                return query.value();
            }
        }
        throw new AssertionError("No method " + methodName + " on " + repository.getName());
    }
}
