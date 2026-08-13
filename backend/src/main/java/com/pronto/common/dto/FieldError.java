package com.pronto.common.dto;

/**
 * One entry in a {@code VALIDATION_ERROR}'s {@code details} array. See
 * {@code docs/architecture/api-contract.md} §1.
 */
public record FieldError(String field, String message) {
}
