package com.pronto.professionals;

/**
 * <b>The single definition of "this professional serves the category being asked about."</b>
 *
 * <p>MS4 Part C. Before MS4 this was a bare column comparison — {@code p.categoryId =
 * :categoryId} — written out separately in {@code bookings.repository
 * .ProfessionalListingRepository.listByCategory} (standard matching) and in
 * {@code sos.repository.SosCandidateRepository.findEligible} (the SOS hard filter). Two copies
 * of one rule was survivable while the rule was three tokens long. It stops being survivable the
 * moment the rule becomes a membership test over a relation, because then the two copies can
 * disagree about <em>how</em> membership is decided — and a standard search that finds a plumber
 * while SOS does not, for the same professional and the same category, is a defect no test
 * naturally looks for.
 *
 * <h2>Alias contract</h2>
 *
 * A bare boolean JPQL fragment, not a complete query, with the same contract as
 * {@link ProfessionalEligibility#ELIGIBLE_JPQL}: <b>the alias {@code p} must be bound to
 * {@link com.pronto.professionals.entity.Professional}</b> in the host query, and the host query
 * must bind a {@code :categoryId} parameter. Join it with {@code AND}.
 *
 * <p>The subquery alias is {@code pcMatch} rather than {@code pc} so a host query may safely
 * concatenate this fragment and {@link ProfessionalEligibility#ELIGIBLE_JPQL} — which uses
 * {@code pcOnboarding} — into the same {@code WHERE} clause without either shadowing the other.
 *
 * <h2>Semantics</h2>
 *
 * Membership, not position: a professional serving {@code [Plumbing, Handyman]} matches a
 * Handyman request exactly as well as a Plumbing one. There is no "primary" category and
 * nothing here ranks one of a professional's categories above another — see
 * {@link com.pronto.professionals.entity.ProfessionalCategory} for why no primary flag exists.
 *
 * <p>Index-anchored on {@code idx_professional_categories_category}, which
 * {@code V45__create_professional_categories.sql} adds for precisely these two queries: the
 * composite primary key is ordered {@code (professional_id, category_id)} and so cannot serve a
 * category-first lookup.
 */
public final class ProfessionalCategoryMatch {

    /**
     * "{@code p} serves {@code :categoryId}". See this class's Javadoc for the alias contract.
     */
    public static final String SERVES_CATEGORY_JPQL =
            "EXISTS (SELECT 1 FROM com.pronto.professionals.entity.ProfessionalCategory pcMatch "
            + "WHERE pcMatch.professionalId = p.id AND pcMatch.categoryId = :categoryId)";

    private ProfessionalCategoryMatch() {
    }
}
