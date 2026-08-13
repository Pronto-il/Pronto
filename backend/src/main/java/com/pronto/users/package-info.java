/**
 * Shared {@code User} entity/profile logic plus the self-service {@code /api/users/me}
 * endpoints (get own profile, soft-delete own account).
 *
 * <p>Owns the {@code users} table (see {@code docs/architecture/data-model.md} §2.2) and
 * implements {@code docs/architecture/api-contract.md} §2.4-2.5. Depended on by {@code
 * auth} (registration/login/JWT validation) and depends on {@code professionals} (to
 * populate the nested {@code professional} object for {@code GET /api/users/me}).
 *
 * <p>See {@code users/README.md} in this directory for the full class-level breakdown.
 * Implemented in Milestone 1 per {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.users;
