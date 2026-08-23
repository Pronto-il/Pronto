package com.pronto.users.entity;

/**
 * Mirrors {@code users.role}'s
 * {@code CHECK (role IN ('CUSTOMER','PROFESSIONAL','ADMIN'))} constraint (see
 * {@code docs/architecture/data-model.md} §2.2 and {@code V40}).
 */
public enum UserRole {
    CUSTOMER,
    PROFESSIONAL,
    /**
     * Pronto operator — MS1's minimal approval capability
     * ({@code /api/admin/professionals/**}, gated by {@code RoleRequiredInterceptor} in
     * {@code professionals.config.ProfessionalsWebConfig}). MS7 owns the wider operations
     * surface.
     *
     * <p><b>Not self-registerable, by explicit guard.</b>
     * {@code POST /api/auth/register}'s body is typed with this enum, so the mere existence of
     * this constant would otherwise make an admin account creatable by anyone who can reach the
     * public registration endpoint. {@code auth.service.AuthService#register} rejects
     * {@code role = ADMIN} with a {@code 400 VALIDATION_ERROR} before any row is written; an
     * ADMIN row is created only by a deliberate operational step.
     *
     * <p>Every role-branching service in this codebase treats an ADMIN caller as neither a
     * customer nor a professional: an ADMIN has no orders, no issues, no favorites, no SOS
     * requests and no professional profile, so the domain endpoints refuse them rather than
     * quietly resolving them into one of the other two roles.
     */
    ADMIN
}
