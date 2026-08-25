package com.pronto.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/phone/capture} — the legacy-account path.
 *
 * <p>Every account created before Production MS1 has an unverified phone (and most have none at
 * all: the column was only ever populated for {@code CUSTOMER} registrations). Those users can
 * still log in with email + password + email OTP, but the
 * {@code PHONE_VERIFICATION_REQUIRED} gate refuses their marketplace mutations until they supply
 * and prove a number. This is where they supply it.
 *
 * <p>Authenticated, unlike every other {@code /api/auth/*} route — see
 * {@code auth.config.SecurityConfig}, which matches it explicitly ahead of the {@code permitAll} on
 * the rest of the prefix. It has to be: the account is already known, and letting an unauthenticated
 * caller attach a phone number to somebody else's account by naming it would be the whole
 * vulnerability.
 *
 * <p>{@code @Size(max = 32)} bounds the input before normalization; the canonical result is what has
 * to fit {@code users.phone}'s {@code VARCHAR(20)}, and it always will, since E.164 tops out at 16
 * characters.
 */
public record CapturePhoneRequest(@NotBlank @Size(max = 32) String phone) {
}
