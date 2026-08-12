/**
 * Registration, login, email verification codes, password hashing, account lockout, and
 * token issuance.
 *
 * <p>Owns the {@code users} and {@code verification_codes} tables' write path for
 * account creation and authentication (see {@code docs/architecture/data-model.md}
 * §2.2-2.3). Stub only as of Milestone 0 — implemented in Milestone 1 (Auth & user
 * management) per {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.auth;
