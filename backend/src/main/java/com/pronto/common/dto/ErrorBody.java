package com.pronto.common.dto;

/**
 * The {@code error} object nested inside {@link ErrorResponse}. See
 * {@code docs/architecture/api-contract.md} §1.
 *
 * @param code stable machine-readable code, one of {@code com.pronto.common.exception.ErrorCode}'s names
 * @param message human-readable (English) message, for logs/debugging
 * @param details nullable; shape depends on the error (e.g. a field-error array for
 *                {@code VALIDATION_ERROR}, retry-time info for {@code ACCOUNT_LOCKED})
 */
public record ErrorBody(String code, String message, Object details) {
}
