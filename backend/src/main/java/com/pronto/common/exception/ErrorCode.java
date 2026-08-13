package com.pronto.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable machine-readable error taxonomy, shared by every endpoint's error envelope.
 *
 * <p>See {@code docs/architecture/api-contract.md} §1. This enum is expected to grow as
 * later milestones add endpoints/error cases — never remove or repurpose an existing name
 * without a version bump, since the frontend branches on {@link #name()}.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT),
    INVALID_CODE(HttpStatus.BAD_REQUEST),
    CODE_EXPIRED(HttpStatus.GONE),
    CODE_ALREADY_CONSUMED(HttpStatus.CONFLICT),
    EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    // Milestone 2 additions (issues/ai/storage). See
    // docs/architecture/api-contract-issues.md §1.
    FORBIDDEN(HttpStatus.FORBIDDEN),
    IMAGE_KEY_INVALID(HttpStatus.BAD_REQUEST),
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.BAD_REQUEST),
    IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    AI_SERVICE_ERROR(HttpStatus.BAD_GATEWAY),
    STORAGE_SERVICE_ERROR(HttpStatus.BAD_GATEWAY),

    // Milestone 3 additions (bookings/availability, plus issues' GET /{id}). See
    // docs/architecture/api-contract-bookings.md §2.
    ISSUE_NOT_BOOKABLE(HttpStatus.CONFLICT),
    CATEGORY_MISMATCH(HttpStatus.BAD_REQUEST),
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT),
    ORDER_NOT_PENDING(HttpStatus.CONFLICT),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT),

    // Milestone 4 additions (SOS booking flow). See
    // docs/architecture/api-contract-bookings.md §2 "Milestone 4 additions".
    ISSUE_URGENCY_MISMATCH(HttpStatus.CONFLICT),
    SOS_PROFESSIONAL_UNAVAILABLE(HttpStatus.CONFLICT),

    // Milestone 6 additions (job-status progression). See
    // docs/architecture/api-contract-bookings.md §2 "Milestone 6 additions".
    ORDER_NOT_CONFIRMED(HttpStatus.CONFLICT),
    ORDER_NOT_ON_THE_WAY(HttpStatus.CONFLICT);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
