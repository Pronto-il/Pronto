package com.pronto.professionals.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/professionals/me} (self view) and
 * {@code GET /api/professionals/{professionalId}} (detail view, either role).
 * {@code favorited} is only ever populated on the {@code {professionalId}} detail endpoint
 * for a {@code CUSTOMER} caller — always {@code null} on {@code /me} (a professional can't
 * favorite themself, and this field is meaningless for a self-view).
 *
 * @param approvalStatus <b>MS1 (D-G): self-view only</b>, {@code null} for every other caller.
 *                       This field used to be returned to any authenticated caller, which was
 *                       harmless only for as long as the column was permanently {@code APPROVED}.
 *                       Now that it carries a real decision, returning it to a browsing customer
 *                       would tell them "this professional was rejected" — a judgment about a
 *                       named person, disclosed to someone with no business knowing it. The
 *                       professional themselves must see it; nobody else needs to.
 * @param bookable       The neutral replacement everyone gets: is this professional
 *                       marketplace-eligible ({@code approvalStatus = APPROVED} <em>and</em>
 *                       onboarding complete — see
 *                       {@link com.pronto.professionals.ProfessionalEligibility}). Enough for the
 *                       UI to withhold a booking affordance it would otherwise offer into a dead
 *                       end, and it reveals nothing about which of the several possible reasons
 *                       applies.
 * @param categoryIds    <b>MS4</b>, replacing the single {@code categoryId}: every category this
 *                       professional serves, in {@code categories.display_order}. The first entry
 *                       is what compact surfaces show as the primary trade — an ordering
 *                       convention, not a stored flag (see
 *                       {@link com.pronto.professionals.entity.ProfessionalCategory}).
 * @param serviceRegionId <b>MS4</b>, replacing free-text {@code serviceArea}: a canonical
 *                       {@code service_regions} id, with {@code serviceRegionNameHe} alongside so
 *                       a client can render it without a second lookup. Both {@code null} for a
 *                       pre-MS4 professional {@code V44} could not place — the profile editor
 *                       then asks them to choose, which is the honest outcome.
 * @param serviceCityIds <b>MS4</b>: the canonical cities they serve, in catalogue order, with
 *                       {@code serviceCityNamesHe} alongside. {@code baseCityId} is always one of
 *                       them, and is the city ETA is measured from.
 * @param subServiceId   echoed back from the optional {@code ?subServiceId=} query parameter, or
 *                       {@code null} when the caller did not ask about a specific service. Present
 *                       so a client can tell "you did not ask" from "they have no price for it" —
 *                       both of which leave {@link #subServicePrice} null, and which mean entirely
 *                       different things.
 * @param subServicePrice <b>This professional's own price for that one sub-service</b>, or
 *                       {@code null} when they have not set one (or when no {@code subServiceId}
 *                       was supplied).
 *
 *                       <p>Deliberately one price rather than the professional's whole price list:
 *                       a customer viewing a professional for an already-classified problem needs
 *                       the figure for <em>that</em> problem, and shipping the other thirty-three
 *                       would be both wasteful and an invitation for the client to pick the wrong
 *                       one.
 *
 *                       <p><b>There is no fallback to {@link #basePrice}, deliberately.</b> They
 *                       are different claims — one is "what I charge to unblock a drain", the other
 *                       is a single figure covering the whole trade — and substituting the second
 *                       for the first would quote the customer a number the professional never
 *                       attached to this job. {@code basePrice} is on this same response and a
 *                       client that wants to show it as a general indication may, clearly labelled
 *                       as such; what it must not do is present it as the price for this service.
 */
public record ProfessionalProfileResponse(
        Long id,
        List<Long> categoryIds,
        String fullName,
        Long serviceRegionId,
        String serviceRegionNameHe,
        Long baseCityId,
        String city,
        List<Long> serviceCityIds,
        List<String> serviceCityNamesHe,
        String bio,
        BigDecimal basePrice,
        String profileImageUrl,
        BigDecimal averageRating,
        long reviewCount,
        String approvalStatus,
        boolean bookable,
        Boolean favorited,
        Long subServiceId,
        BigDecimal subServicePrice,
        Instant createdAt,
        Instant updatedAt
) {
}
