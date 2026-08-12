/**
 * Shared {@code User} entity/profile logic used by both customer and professional roles.
 *
 * <p>Owns the {@code users} table (see {@code docs/architecture/data-model.md} §2.2),
 * which both the {@code auth} package (registration/login) and the {@code professionals}
 * package (professional profile, which extends a user with role {@code PROFESSIONAL})
 * depend on. Stub only as of Milestone 0 — implemented in Milestone 1 (Auth & user
 * management) per {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.users;
