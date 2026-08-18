package com.pronto.users.dto;

/**
 * The nested {@code defaultAddress} object in {@code GET /api/users/me}'s response for a
 * {@code CUSTOMER}-role caller with a saved default address. {@code null} for a
 * {@code PROFESSIONAL} caller (the {@code users.default_*} columns are always null for
 * that role), and also {@code null} for a {@code CUSTOMER} with no recorded default city
 * (pre-V20 accounts) — mirrors {@link ProfessionalInfo}'s "absent means no such object"
 * convention rather than returning a partially-empty shape.
 */
public record DefaultAddressInfo(
        String city,
        String street,
        String houseNumber,
        String apartment,
        String floor,
        String entrance,
        String addressNotes
) {
}
