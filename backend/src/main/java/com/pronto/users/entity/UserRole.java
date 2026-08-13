package com.pronto.users.entity;

/**
 * Mirrors {@code users.role}'s {@code CHECK (role IN ('CUSTOMER','PROFESSIONAL'))}
 * constraint (see {@code docs/architecture/data-model.md} §2.2).
 */
public enum UserRole {
    CUSTOMER,
    PROFESSIONAL
}
