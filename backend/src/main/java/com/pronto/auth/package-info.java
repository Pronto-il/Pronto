/**
 * Registration, login, email verification codes, password hashing, account lockout, JWT
 * issuance/validation, and the application's Spring Security wiring.
 *
 * <p>Implements {@code docs/architecture/api-contract.md} §2.1-2.3 (the three
 * {@code /api/auth/*} endpoints) and §3.1-3.3 (JWT, Spring Security, password hashing,
 * email delivery). Owns the {@code verification_codes} table exclusively (see
 * {@code docs/architecture/data-model.md} §2.3) and writes {@code users}/{@code
 * professionals} rows during registration/login via those packages' repositories.
 *
 * <p>See {@code auth/README.md} in this directory for the full class-level breakdown,
 * cross-package interactions, and flagged judgment calls. Implemented in Milestone 1 per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.auth;
