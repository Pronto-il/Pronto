package com.pronto.professionals.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * One sub-service a professional offers, and what they charge for it — the request-side item shared
 * by {@code PUT /api/professionals/me/sub-services} and the {@code professional} block of
 * {@code POST /api/auth/register}, so the two surfaces cannot drift on the shape or the rules.
 *
 * <p><b>{@code price} is optional</b>, and stays optional deliberately. Before this feature a
 * selection carried no price at all, so requiring one would have made every existing client's
 * payload invalid and every already-selected row unrepresentable. {@code null} means "not priced" —
 * an honest state that the customer-facing surfaces render as an absence, never as {@code 0}.
 * The frontend asks for a price on every ticked sub-service, which is the product behaviour; the
 * API permits omitting it, which is the compatibility guarantee. See
 * {@code V57__alter_professional_sub_services_add_price.sql} for why no backfill from
 * {@code professionals.base_price} was possible.
 *
 * <p>Value rules are enforced in {@code ProfessionalsService}/{@code AuthService} rather than by
 * Bean Validation annotations here, for the reason the whole registration payload already works
 * that way: the field errors have to name a path the client can map to a specific input
 * ({@code subServices[2].price}), and {@code @DecimalMin} produces a message that cannot say which
 * sub-service it meant.
 *
 * @param subServiceId an existing {@code sub_services} id belonging to one of the professional's own
 *                     categories — validated by
 *                     {@code professionals.service.SubServiceSelectionValidator}, the same component
 *                     the id-only path has always used
 * @param price        at least {@code 0}, at most two decimal places, or {@code null} for "no price
 *                     given". Never negative — that is refused with a field error, and
 *                     {@code ck_professional_sub_services_price} refuses it again at the database
 *                     for any writer that is not this API.
 */
public record SubServicePriceSelection(
        @NotNull Long subServiceId,
        BigDecimal price
) {
}
