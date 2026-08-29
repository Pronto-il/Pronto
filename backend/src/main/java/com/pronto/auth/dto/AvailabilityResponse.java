package com.pronto.auth.dto;

/**
 * The answer to {@code POST /api/auth/availability}: <b>one boolean, and the field it is about</b>.
 *
 * <p><b>What is deliberately absent is the whole design.</b> There is no account id, no role, no
 * name, no masked value, no created-at, no "verified" flag, no "deleted" flag, and no distinction
 * between an account that is active, one that never finished verifying and one that has been soft
 * deleted. Every one of those would turn a yes/no into a profile of a stranger, and this endpoint
 * exists only so a registration form can put "כתובת האימייל הזו כבר רשומה" under an input instead
 * of on the final screen.
 *
 * <p>{@code available = false} therefore means exactly one thing: <b>{@code POST
 * /api/auth/register} would refuse this value.</b> It is not a claim about who holds it or why.
 *
 * @param field the field the caller asked about, echoed so a client with two checks in flight can
 *              attribute the answer without tracking request order
 * @param available whether registration would accept this value
 */
public record AvailabilityResponse(ContactField field, boolean available) {
}
