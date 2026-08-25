package com.pronto.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/login}. One entry point, two identifier kinds.
 *
 * <p>{@code identifier} is an email address <em>or</em> a phone number, and which one it is is
 * decided by the server ({@code AuthService} tries
 * {@code PhoneNumberNormalizer#tryNormalize} first, falling back to email) rather than by a
 * radio button the user has to get right. Both resolve to the same {@code users} row, and the
 * identifier the caller chose decides only which channel the second-factor code goes to.
 *
 * <p>Deliberately no {@code @Email} constraint any more — it was correct when email was the only
 * identifier and would now reject every phone login before the request reached any logic. There is
 * no {@code @Pattern} standing in for it either: the set of things that are "a valid identifier" is
 * exactly what identifier resolution already computes, and a second, looser copy of that rule here
 * would only ever disagree with it.
 */
public record LoginRequest(
        @NotBlank @Size(max = 255) String identifier,
        @NotBlank String password
) {
}
