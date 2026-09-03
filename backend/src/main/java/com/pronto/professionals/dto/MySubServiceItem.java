package com.pronto.professionals.dto;

import java.math.BigDecimal;

/**
 * One of the caller's own selected sub-services, with its price and enough catalogue context to
 * render it — inside {@link MySubServicesResponse}.
 *
 * <p><b>Why the labels are included rather than left to the client to join.</b> The id-only
 * response this extends assumed the frontend had already fetched {@code GET /api/categories} and
 * could resolve ids itself, which is true on the profile screen and is why that response stays as
 * it is. It stops being true for anything that renders a professional's services without the whole
 * catalogue in hand, and the failure mode of a missing join is a raw {@code sub_service_id} shown
 * to a user — the one thing the brief for this surface explicitly forbids. Sending the Hebrew label
 * costs a few bytes and removes the possibility.
 *
 * <p>{@code code} is included for the frontend to key on (stable across renames, unlike the label)
 * and must never be displayed: {@code plumbing_unclog} is an internal identifier, and
 * {@code nameHe} is the only string a professional or customer may see.
 *
 * @param price {@code null} when the professional has not priced this service. Render as an empty
 *              input or a dash — never as {@code 0}, which would advertise free work nobody offered.
 */
public record MySubServiceItem(
        Long subServiceId,
        Long categoryId,
        String code,
        String nameHe,
        BigDecimal price
) {
}
