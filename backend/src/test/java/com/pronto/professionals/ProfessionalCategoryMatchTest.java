package com.pronto.professionals;

import com.pronto.bookings.repository.ProfessionalListingRepository;
import com.pronto.sos.repository.SosCandidateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MS4 Part C — standard matching and SOS matching must agree, exactly, about which professionals
 * serve a category.
 *
 * <p><b>The bug this exists to catch.</b> Before MS4 the rule was three tokens
 * ({@code p.categoryId = :categoryId}) written out separately in the two queries; MS4 makes it a
 * membership test over {@code professional_categories}. The realistic regression is that somebody
 * later edits one query — adds a condition, "optimises" the subquery, reintroduces a column
 * comparison — and the other keeps the old semantics. The symptom would be a professional a
 * customer can find by browsing but SOS will never dispatch to (or the reverse), for the same
 * category. No unit test of either service notices that, because both mock their repository.
 *
 * <p>So this asserts the structural property directly: both {@code @Query} strings are built from
 * {@link ProfessionalCategoryMatch#SERVES_CATEGORY_JPQL}, and neither still speaks of a category
 * column on {@code professionals}. It is the same technique, and the same reasoning, as
 * {@code sos.SosSchemaConstraintTest} reading migrations off the classpath.
 *
 * <p>What it cannot do is prove the SQL returns the right rows — that needs a live database, and
 * is covered by the MS4 matching QA run recorded in the milestone report.
 */
class ProfessionalCategoryMatchTest {

    @Test
    void standardListing_filtersByCategoryMembership_fromTheSharedConstant() {
        assertThat(queryOf(ProfessionalListingRepository.class, "listByCategoryAndServiceCity"))
                .contains(ProfessionalCategoryMatch.SERVES_CATEGORY_JPQL);
    }

    @Test
    void sosHardFilter_filtersByCategoryMembership_fromTheSameSharedConstant() {
        assertThat(queryOf(SosCandidateRepository.class, "findEligible"))
                .contains(ProfessionalCategoryMatch.SERVES_CATEGORY_JPQL);
    }

    @Test
    void neitherQueryStillReadsACategoryColumnOffTheProfessional() {
        // professionals.category_id was dropped by V45. A query still naming it would not even
        // start (Hibernate parses every @Query at boot), but the failure would be at startup of
        // whichever environment ran it next -- this says so at build time instead.
        assertThat(queryOf(ProfessionalListingRepository.class, "listByCategoryAndServiceCity"))
                .doesNotContain("p.categoryId");
        assertThat(queryOf(SosCandidateRepository.class, "findEligible"))
                .doesNotContain("p.categoryId");
    }

    @Test
    void theFragmentIsAMembershipTest_notAnOrderedOrPositionalOne() {
        // "Serves handyman work" must not depend on handyman being their first trade -- the
        // brief's core requirement. An EXISTS over the relation is what makes that true; a
        // MIN/FIRST/ORDER BY here would quietly reintroduce a primary category.
        assertThat(ProfessionalCategoryMatch.SERVES_CATEGORY_JPQL)
                .startsWith("EXISTS (SELECT 1 FROM com.pronto.professionals.entity.ProfessionalCategory")
                .contains("pcMatch.professionalId = p.id")
                .contains("pcMatch.categoryId = :categoryId")
                .doesNotContain("ORDER BY")
                .doesNotContain("MIN(");
    }

    @Test
    void itsSubqueryAlias_cannotShadowTheEligibilityFragmentsAlias() {
        // Both fragments are concatenated into the same WHERE clause by both repositories. Two
        // subqueries sharing an alias is legal JPQL and silently wrong here -- the inner one would
        // win, and the eligibility check would start testing the requested category instead of
        // the professional's own.
        assertThat(ProfessionalCategoryMatch.SERVES_CATEGORY_JPQL).contains("pcMatch");
        assertThat(ProfessionalEligibility.ONBOARDING_COMPLETE_JPQL).contains("pcOnboarding");
        assertThat(ProfessionalEligibility.ONBOARDING_COMPLETE_JPQL).doesNotContain("pcMatch");
    }

    @Test
    void eligibilityAcceptsASubServiceUnderAnyOfTheProfessionalsCategories() {
        // MS4 widened this clause from "= p.categoryId" to a join through professional_categories.
        // For a single-category professional -- every row, after V45's X -> [X] backfill -- the
        // outcome is identical; for a multi-category one it stops demanding that the sub-service
        // sit under a category they no longer have to have been given first.
        assertThat(ProfessionalEligibility.ONBOARDING_COMPLETE_JPQL)
                .contains("pcOnboarding.professionalId = p.id")
                .contains("pcOnboarding.categoryId = s.categoryId")
                .doesNotContain("p.categoryId");
    }

    @Test
    void bothQueriesAlsoStillApplyTheFullEligibilityRule() {
        // Category membership is one hard filter among several. Losing the eligibility conjunction
        // while adding the category one would be a very quiet way to un-gate discovery.
        assertThat(queryOf(ProfessionalListingRepository.class, "listByCategoryAndServiceCity"))
                .contains(ProfessionalEligibility.ELIGIBLE_JPQL);
        assertThat(queryOf(SosCandidateRepository.class, "findEligible"))
                .contains(ProfessionalEligibility.ELIGIBLE_JPQL);
    }

    @Test
    void sosStillAppliesItsOwnHardFiltersOnTopOfCategory() {
        String sos = queryOf(SosCandidateRepository.class, "findEligible");
        assertThat(sos).contains("s.isAvailable = true");
        assertThat(sos).contains("u.deletedAt IS NULL");
        assertThat(sos).contains("p.id NOT IN :excludedProfessionalIds");
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
