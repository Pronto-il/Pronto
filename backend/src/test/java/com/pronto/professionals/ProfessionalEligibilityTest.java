package com.pronto.professionals;

import com.pronto.bookings.repository.ProfessionalListingRepository;
import com.pronto.professionals.repository.ProfessionalRepository;
import com.pronto.sos.repository.SosCandidateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MS1's <b>"pending professional is excluded from matching"</b> coverage, at the only level this
 * repository can currently assert it.
 *
 * <p><b>What this test can and cannot prove, stated plainly.</b> The eligibility rule is a JPQL
 * fragment evaluated by the database, and this backend has no {@code @SpringBootTest},
 * {@code @DataJpaTest}, Testcontainers or {@code @Sql} anywhere — MS0 recorded that gap and D3
 * assigns building the permanent DB/integration harness to MS5. So no test here can execute the
 * predicate against real rows. What it <em>can</em> prove, and what actually protects the
 * milestone, is structural: that there is exactly one definition of the rule and that every query
 * which must enforce it is literally built from that definition rather than from a copy someone
 * will forget to update. A hand-written second copy in one of these two {@code @Query} strings is
 * the realistic regression, and it is precisely what these assertions catch.
 *
 * <p>The runtime half is covered by the service-level guard tests
 * ({@code BookingsServiceTest}, {@code SosServiceTest}, {@code FavoritesServiceTest}), which
 * exercise the branch each guard takes on the answer, and by Hibernate itself: it parses every
 * {@code @Query} while building the persistence context, so a malformed fragment fails application
 * startup rather than a request.
 */
class ProfessionalEligibilityTest {

    @Test
    void eligibleIsApprovalAndCompletedOnboarding_notApprovalAlone() {
        // D4's whole point. If this ever collapses to just the status check, an APPROVED
        // professional with an empty calendar becomes bookable again.
        assertThat(ProfessionalEligibility.ELIGIBLE_JPQL)
                .contains("p.approvalStatus = 'APPROVED'")
                .contains(ProfessionalEligibility.ONBOARDING_COMPLETE_JPQL);
    }

    @Test
    void onboardingRequiresDocument_enabledWorkingHours_andOwnCategorySubService() {
        assertThat(ProfessionalEligibility.ONBOARDING_COMPLETE_JPQL)
                .contains("p.verificationDocumentKey IS NOT NULL")
                .contains("ProfessionalWorkingHours wh")
                .contains("wh.enabled = true")
                .contains("ProfessionalSubService ps")
                // The cross-category rule: a sub-service that belongs to somebody else's category
                // must not confer eligibility.
                .contains("s.categoryId = p.categoryId");
    }

    @Test
    void eligibilityIsAPositiveTest_soEveryFutureNonApprovedValueIsIneligibleByConstruction() {
        // Why DISABLED can be reserved in V40 with no enforcement work: the predicate names the
        // one status that qualifies rather than blacklisting the ones that do not.
        assertThat(ProfessionalEligibility.ELIGIBLE_JPQL)
                .doesNotContain("PENDING")
                .doesNotContain("REJECTED")
                .doesNotContain("DISABLED")
                .doesNotContain("<>")
                .doesNotContain("NOT IN");
    }

    @Test
    void standardListingQueryIsBuiltFromTheConstant() {
        assertThat(queryOf(ProfessionalListingRepository.class, "listByCategory"))
                .contains(ProfessionalEligibility.ELIGIBLE_JPQL);
    }

    @Test
    void sosCandidateQueryIsBuiltFromTheConstant() {
        assertThat(queryOf(SosCandidateRepository.class, "findEligible"))
                .contains(ProfessionalEligibility.ELIGIBLE_JPQL);
    }

    @Test
    void singleRowServiceGuardIsBuiltFromTheSameConstant() {
        // The one every service-level guard delegates to. If this drifted from the two listing
        // queries above, a professional could be invisible in search yet bookable by direct id --
        // or the reverse.
        assertThat(queryOf(ProfessionalRepository.class, "existsEligibleById"))
                .contains(ProfessionalEligibility.ELIGIBLE_JPQL);
    }

    @Test
    void operatorOnboardingCheckIsBuiltFromTheOnboardingHalfOnly() {
        String query = queryOf(ProfessionalRepository.class, "hasCompleteOnboarding");
        assertThat(query).contains(ProfessionalEligibility.ONBOARDING_COMPLETE_JPQL);
        // Deliberately NOT the approval half: this answers "would approving them make them
        // bookable", which is only useful before the approval exists.
        assertThat(query).doesNotContain("p.approvalStatus");
    }

    @Test
    void everyConsumerBindsTheFragmentsAliasP() {
        // The fragment's sole assumption about its host query. A consumer that aliased
        // Professional as anything else would fail at context startup, but naming the contract
        // here is what makes that failure legible instead of mysterious.
        assertThat(queryOf(ProfessionalListingRepository.class, "listByCategory"))
                .contains("FROM Professional p");
        assertThat(queryOf(SosCandidateRepository.class, "findEligible"))
                .contains("FROM Professional p");
        assertThat(queryOf(ProfessionalRepository.class, "existsEligibleById"))
                .contains("FROM Professional p");
        assertThat(queryOf(ProfessionalRepository.class, "hasCompleteOnboarding"))
                .contains("FROM Professional p");
    }

    private static String queryOf(Class<?> repository, String methodName) {
        for (Method method : repository.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                Query query = method.getAnnotation(Query.class);
                assertThat(query)
                        .as("%s#%s must carry an @Query", repository.getSimpleName(), methodName)
                        .isNotNull();
                return query.value();
            }
        }
        throw new AssertionError(repository.getSimpleName() + " has no method " + methodName);
    }
}
