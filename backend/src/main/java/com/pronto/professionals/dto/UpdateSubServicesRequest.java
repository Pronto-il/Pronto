package com.pronto.professionals.dto;

import java.util.List;

/**
 * Request body for {@code PUT /api/professionals/me/sub-services} -- a full-replace of the
 * caller's entire sub-service selection, same shape precedent as {@code
 * availability.dto.WorkingHoursUpdateRequest}. Deliberately no {@code @NotEmpty} -- an empty
 * selection is a valid, un-blocking state (design doc §6 item 2, lead-approved). See {@code
 * docs/architecture/product-ms11-sub-services-design.md} §3.2.
 *
 * <h2>Two shapes, one meaning</h2>
 *
 * {@link #subServices} carries a price per sub-service and is what the current frontend sends.
 * {@link #subServiceIds} is the original id-only form, kept working rather than removed: it is a
 * public API that existing clients (and this repository's own older tests) still speak, and a
 * selection without prices remains a legal state of the system, so there was nothing to force them
 * off it for.
 *
 * <p><b>{@code subServices} wins when both are present.</b> Not merged — the two are alternative
 * spellings of the same list, and merging them would make a sub-service silently keep an old price
 * because it happened to appear in the other field. Exactly one of them must be non-null;
 * {@code ProfessionalsService} reports both-null as a field error rather than treating it as "clear
 * my selection", because deleting every service a professional offers is too destructive to be the
 * meaning of an omitted field.
 *
 * @param subServiceIds id-only form. A selection sent this way is stored with {@code price = null}
 *                      for newly-added rows; <b>prices already stored for rows that stay selected
 *                      are preserved</b>, so an old client cannot wipe pricing it does not know
 *                      about simply by saving the profile.
 * @param subServices   id-and-price form. Full-replace semantics on both the membership and the
 *                      prices: a sub-service present with a {@code null} price has its price
 *                      cleared, which is how a professional withdraws one.
 */
public record UpdateSubServicesRequest(
        List<Long> subServiceIds,
        List<SubServicePriceSelection> subServices
) {

    /**
     * The request normalised to the id-and-price form, whichever shape the client used.
     *
     * <p>An id-only payload maps to selections with a {@code null} price, which
     * {@code ProfessionalsService} then treats as "leave whatever price is stored alone" rather than
     * "clear it" — see {@link #pricesAreAuthoritative()}, which is the flag that distinguishes the
     * two, because a {@code null} price means different things in the two payloads and the
     * difference cannot be recovered from the list itself.
     */
    public List<SubServicePriceSelection> selections() {
        if (subServices != null) {
            return subServices;
        }
        if (subServiceIds != null) {
            return subServiceIds.stream()
                    .map(id -> new SubServicePriceSelection(id, null))
                    .toList();
        }
        return List.of();
    }

    /**
     * Whether the prices in {@link #selections()} are the client's actual intent ({@code true}, the
     * {@code subServices} form) or an artefact of a payload that could not express prices at all
     * ({@code false}, the {@code subServiceIds} form).
     *
     * <p>This is what stops an older client from silently deleting pricing: it sends ids, this
     * returns {@code false}, and stored prices for still-selected sub-services are left untouched.
     */
    public boolean pricesAreAuthoritative() {
        return subServices != null;
    }

    /** Neither field supplied — reported as a validation error, never treated as "select nothing". */
    public boolean isEmptyPayload() {
        return subServices == null && subServiceIds == null;
    }
}
