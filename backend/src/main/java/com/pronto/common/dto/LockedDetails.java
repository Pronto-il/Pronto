package com.pronto.common.dto;

import java.time.Instant;

/**
 * The {@code details} payload for an {@code ACCOUNT_LOCKED} error. See
 * {@code docs/architecture/api-contract.md} §2.3.
 */
public record LockedDetails(Instant lockedUntil, long retryAfterSeconds) {
}
