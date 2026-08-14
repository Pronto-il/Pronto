package com.pronto.common.dto;

/**
 * The {@code details} payload for a {@code RATE_LIMITED} error. See
 * {@code docs/architecture/hardening-plan.md} §5.2. Mirrors {@link LockedDetails}'
 * {@code retryAfterSeconds} field; the same value is also set as the response's
 * {@code Retry-After} header (RFC 9110 §10.2.3) so both a machine-readable header and the
 * body's {@code error.details} carry it.
 */
public record RateLimitDetails(long retryAfterSeconds) {
}
