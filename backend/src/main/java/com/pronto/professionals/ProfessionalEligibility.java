package com.pronto.professionals;

/**
 * <b>The single definition of "this professional may be discovered and given new work."</b>
 *
 * <p>Governing decision D4 (Playbook §0.1, MS1 "Required Onboarding &amp; Marketplace
 * Eligibility"): approval alone never makes anyone bookable. A professional is eligible only
 * when an operator has approved them <em>and</em> the onboarding they were approved on is
 * actually complete:
 *
 * <pre>
 *   eligible(p) :=  p.approval_status = 'APPROVED'
 *               AND p.verification_document_key IS NOT NULL
 *               AND EXISTS an enabled professional_working_hours row
 *               AND EXISTS a professional_sub_services row whose sub_service
 *                          belongs to one of p's own categories
 *               AND the owning users row has phone_verified = true      (Production MS1)
 * </pre>
 *
 * <p><b>MS4:</b> the last clause used to read "p's own category", singular, against
 * {@code professionals.category_id}. That column is gone — a professional now holds a set of
 * categories in {@code professional_categories} — so the clause is now a three-way existence
 * test over {@code professional_sub_services × sub_services × professional_categories}. The
 * rule is unchanged in intent (the professional has proven at least one concrete thing they
 * do, under a trade they actually claim) and unchanged in outcome for every single-category
 * professional, which after {@code V45}'s {@code X -> [X]} backfill is all of them.
 *
 * <h2>Alias contract</h2>
 *
 * {@link #ELIGIBLE_JPQL} is a bare boolean JPQL fragment, not a complete query. It assumes
 * exactly one thing about its host query: <b>the alias {@code p} is bound to
 * {@link com.pronto.professionals.entity.Professional}</b>. Concatenate it into a {@code WHERE}
 * clause that already has such an alias, always joined with {@code AND} and (because it is a
 * conjunction itself) never inside an {@code OR} without parentheses of the caller's own.
 *
 * <h2>Why computed, never stored</h2>
 *
 * A maintained {@code is_eligible} flag would have five writers — the sub-services update, the
 * working-hours update, registration, a future category change (which invalidates a sub-service
 * selection without touching either child table), and the approval transition itself — and its
 * failure mode is a stale {@code true}: an incomplete professional who is bookable, which is the
 * exact defect MS1 exists to close. There is no integration test able to catch that staleness in
 * this repository until MS5 builds one (D3). Recomputing costs two index-anchored semi-joins
 * ({@code idx_professional_working_hours_professional}; the {@code professional_sub_services}
 * primary-key prefix) over tables capped at 7 and 34 rows per professional, added to queries
 * already dominated by per-row correlated {@code AVG}/{@code COUNT} subqueries over
 * {@code reviews}.
 *
 * <p>The trade-off accepted in exchange: this is a string constant, so it is not
 * compile-time-checked. Hibernate parses every {@code @Query} while building the persistence
 * context, so a malformed fragment fails application startup rather than one request — a loud,
 * immediate failure, not a silent one.
 *
 * <h2>What is deliberately NOT in here</h2>
 *
 * <p><b>{@code users.deleted_at IS NULL}</b> stays <em>adjacent</em> to this predicate rather
 * than inside it. {@code ProfessionalListingRepository.listByCategory} and
 * {@code SosCandidateRepository.findEligible} already join {@code users} and already apply it;
 * {@code BookingsService.listAvailableWindows} does not join {@code users} at all. Folding the
 * join in would force every consumer to carry a join it may not want, to re-state a rule they
 * mostly already have. Each gated path applies it in its own idiom instead — see
 * {@code BookingsService#isProfessionalBookable}.
 *
 * <p><b>Sub-service-level matching.</b> This checks only that the professional has <em>some</em>
 * valid sub-service under their own category, not that it matches the customer's request:
 * {@code issues} has no {@code sub_service_id} column and {@code SosRequest.subServiceId} is
 * always {@code null}, so there is nothing to match against yet.
 *
 * <p>Everything derived from this rule — the six gated paths and the single-row
 * {@code ProfessionalRepository#existsEligibleById} check every service guard delegates to —
 * reads this one constant. Nothing re-implements it in Java.
 */
public final class ProfessionalEligibility {

    /**
     * The only {@code approval_status} value that can make a professional bookable. Every other
     * value — present or future, including the {@code DISABLED} MS7 will introduce — is
     * ineligible by construction, because this predicate is positive rather than a blacklist.
     */
    public static final String APPROVED = "APPROVED";

    /**
     * The onboarding half on its own: verification document present, at least one enabled
     * working-hours day, at least one sub-service under the professional's own category.
     *
     * <p>Split out so the operator review screen can answer "is this person actually ready?"
     * <em>before</em> a decision is spent on them — an operator who approves someone with
     * incomplete onboarding leaves them non-bookable, and the system will not invent the missing
     * data to rescue that. Exposed as a named piece of the one rule rather than re-derived in
     * Java for the display, so the screen and the gate cannot disagree.
     *
     * <p>Same alias contract as {@link #ELIGIBLE_JPQL}: {@code p} is a {@code Professional}.
     */
    public static final String ONBOARDING_COMPLETE_JPQL =
            "p.verificationDocumentKey IS NOT NULL "
            + "AND EXISTS (SELECT 1 FROM com.pronto.availability.entity.ProfessionalWorkingHours wh "
            + "WHERE wh.professionalId = p.id AND wh.enabled = true) "
            + "AND EXISTS (SELECT 1 FROM com.pronto.professionals.entity.ProfessionalSubService ps, "
            + "com.pronto.professionals.entity.SubService s, "
            + "com.pronto.professionals.entity.ProfessionalCategory pcOnboarding "
            + "WHERE s.id = ps.subServiceId AND ps.professionalId = p.id "
            + "AND pcOnboarding.professionalId = p.id AND pcOnboarding.categoryId = s.categoryId)";

    /**
     * The eligibility conjunction — approval <b>and</b> {@link #ONBOARDING_COMPLETE_JPQL} — for an
     * outer query with {@code Professional p} in scope. See this class's Javadoc for the alias
     * contract and the full rule.
     */
    /**
     * <b>Production MS1:</b> the professional's own phone number must be verified.
     *
     * <p>This is the "professional marketplace eligibility" half of MS1's contact-verification
     * gate. It belongs in the eligibility predicate rather than in a service-layer check for the
     * reason this whole class exists: there are six gated paths (listing, Standard matching,
     * booking windows, order creation, SOS candidate selection, SOS dispatch), and a rule enforced
     * in Java at each of them is a rule the seventh consumer will forget. Expressed here, an
     * unverified professional is simply not discoverable anywhere.
     *
     * <p>Written as an {@code EXISTS} over {@code User} rather than as a join, so the
     * {@link #ELIGIBLE_JPQL} alias contract is unchanged — a consumer that never joined
     * {@code users} ({@code BookingsService#listAvailableWindows}) still does not have to.
     *
     * <p><b>Consequence, stated plainly:</b> immediately after {@code V46} every professional in the
     * database is ineligible, because {@code phone_verified} defaults to {@code false} for existing
     * rows and MS1 grandfathers nobody. That is the intended direction for a platform that has not
     * launched — a professional who cannot be phoned should not be dispatched to a stranger's home
     * — and the path back is the same phone-capture flow every other account uses. The TEST/DEMO
     * dataset seeds its synthetic professionals as verified so demonstrations keep working.
     */
    public static final String PHONE_VERIFIED_JPQL =
            "EXISTS (SELECT 1 FROM com.pronto.users.entity.User uPhone "
            + "WHERE uPhone.id = p.userId AND uPhone.phoneVerified = true)";

    /**
     * The eligibility conjunction — approval <b>and</b> {@link #ONBOARDING_COMPLETE_JPQL} <b>and</b>
     * {@link #PHONE_VERIFIED_JPQL} — for an outer query with {@code Professional p} in scope. See
     * this class's Javadoc for the alias contract and the full rule.
     */
    public static final String ELIGIBLE_JPQL =
            "p.approvalStatus = '" + APPROVED + "' AND " + ONBOARDING_COMPLETE_JPQL
            + " AND " + PHONE_VERIFIED_JPQL;

    private ProfessionalEligibility() {
    }
}
