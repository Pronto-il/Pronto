package com.pronto.common.security;

/**
 * The {@code Authentication} principal set by {@code auth}'s JWT filter for every
 * successfully authenticated request. Deliberately holds {@code role} as a plain
 * {@code String} (not the {@code users} package's {@code UserRole} enum) so this class —
 * living in {@code common}, meant to be depended on by every domain package — never has to
 * depend on a domain package itself (see {@code common}'s package doc).
 *
 * <p>Controllers resolve it via {@code @AuthenticationPrincipal AuthenticatedUser}.
 */
public record AuthenticatedUser(Long id, String role) {
}
