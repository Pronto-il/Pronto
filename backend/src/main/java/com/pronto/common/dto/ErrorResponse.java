package com.pronto.common.dto;

import java.time.Instant;

/**
 * The standard error response envelope used by every endpoint. See
 * {@code docs/architecture/api-contract.md} §1.
 */
public record ErrorResponse(Instant timestamp, String path, ErrorBody error) {
}
