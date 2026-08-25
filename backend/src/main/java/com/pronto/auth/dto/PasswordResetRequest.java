package com.pronto.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/password-reset/request}. Same dual-identifier shape as
 * {@link LoginRequest} — a user who signs in with their phone number should not have to remember
 * which email address they used.
 *
 * <p>The recovery code always goes to the account's <em>verified email address</em> regardless of
 * which identifier was submitted. Email is the one channel every account on this platform has
 * proved, including every pre-MS1 row; routing recovery to an unverified or absent phone would
 * simply fail for the accounts most likely to need recovery.
 */
public record PasswordResetRequest(@NotBlank @Size(max = 255) String identifier) {
}
