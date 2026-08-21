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
    ORDER_NOT_ON_THE_WAY(HttpStatus.CONFLICT),

    // Milestone 7 hardening addition (per-IP rate limiting on /api/auth/*). See
    // docs/architecture/hardening-plan.md §5.2.
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

    // Milestone 7 additions (slot edit/delete). See
    // docs/architecture/api-contract-bookings.md §2 "Milestone 7 additions".
    SLOT_IN_USE(HttpStatus.CONFLICT),

    // Reviews/favorites/matching additions (professional profile, reviews, favorites,
    // service address, SOS surcharge). Lead-approved design, pronto-planning.
    REVIEW_ORDER_NOT_COMPLETED(HttpStatus.CONFLICT),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT),

    // Backend registration flow separation (customer default address, professional
    // verification document upload as part of POST /api/auth/register).
    UNSUPPORTED_DOCUMENT_TYPE(HttpStatus.BAD_REQUEST),

    // Professional weekly availability calendar, M1 additions (block CRUD). See
    // docs/architecture/professional-weekly-calendar-design.md §4.7.
    BLOCK_OVERLAPS_EXISTING_BLOCK(HttpStatus.CONFLICT),
    BLOCK_OVERLAPS_BOOKING(HttpStatus.CONFLICT),

    // Professional weekly availability calendar, M2 addition (order-creation rework). See
    // docs/architecture/professional-weekly-calendar-design.md §9.2.2. SLOT_UNAVAILABLE
    // (above) becomes vestigial as of M2 -- kept in the enum, never returned by any code
    // path once no caller can supply a slotId anymore.
    BOOKING_TIME_UNAVAILABLE(HttpStatus.CONFLICT),

    // Pronto SOS (broadcast-and-choose urgent dispatch). See the sos package README.
    /** An SOS request already exists for this issue ({@code ux_sos_requests_issue}). */
    SOS_REQUEST_ALREADY_EXISTS(HttpStatus.CONFLICT),
    /** The requested operation is not legal from the request's current status. */
    SOS_INVALID_STATE(HttpStatus.CONFLICT),
    /** Matching found nobody eligible — terminal {@code FAILED}. */
    SOS_NO_PROFESSIONALS_AVAILABLE(HttpStatus.CONFLICT),
    /** The offer is no longer open (already responded to, expired, or superseded). */
    SOS_OFFER_NOT_OPEN(HttpStatus.CONFLICT),
    /** The professional-response window or the customer-selection window has elapsed. */
    SOS_WINDOW_EXPIRED(HttpStatus.GONE),
    /** The chosen offer is not one of the current candidates (not accepted, or not this request). */
    SOS_CANDIDATE_NOT_AVAILABLE(HttpStatus.CONFLICT),
    /** A professional has already been selected for this request — selection is one-shot. */
    SOS_ALREADY_SELECTED(HttpStatus.CONFLICT);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
