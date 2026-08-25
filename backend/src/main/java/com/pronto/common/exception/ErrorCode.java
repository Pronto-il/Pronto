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
    /**
     * The requested resource does not exist. Deliberately shared by two callers: domain code
     * raising {@code ApiException(NOT_FOUND, ...)} for a missing entity, and
     * {@code GlobalExceptionHandler}'s unmatched-route handler. Both mean "what you asked for
     * isn't here" at the same status, and the frontend branches only on the code — so a
     * second, near-identical code would split the taxonomy without telling any caller
     * anything new.
     */
    NOT_FOUND(HttpStatus.NOT_FOUND),
    /**
     * The request body's {@code Content-Type} is not one this endpoint can read (e.g.
     * {@code text/plain} posted to a JSON endpoint). Like {@link #METHOD_NOT_ALLOWED} below,
     * a framework-level failure kept out of {@link #INTERNAL_ERROR}.
     */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    /**
     * The path exists but not for the HTTP method used (e.g. {@code GET /api/auth/login},
     * which is {@code POST}-only). A framework-level routing failure, deliberately NOT
     * folded into {@link #INTERNAL_ERROR} — nothing unexpected happened server-side, the
     * caller simply used the wrong verb, and a 500 both misleads the caller and hides the
     * real 4xx in monitoring.
     */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
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
    //
    // SOS_PROFESSIONAL_UNAVAILABLE was removed here along with the browse-and-pick SOS flow:
    // it meant "the professional you picked toggled their SOS availability off between the
    // listing and your order", which is only expressible in a flow where the customer picks a
    // professional by name. Pronto SOS has no such moment -- availability is an eligibility
    // filter at dispatch, and the customer only ever chooses from professionals who have
    // actively said yes to this specific job. Deleted rather than kept vestigial (unlike
    // BOOKING_TIME_UNAVAILABLE below) because the endpoint that raised it no longer exists.
    //
    // ISSUE_URGENCY_MISMATCH is NOT legacy -- sos.service.SosService still raises it when an
    // SOS request is activated on a STANDARD issue, and BookingsService raises it in reverse.
    ISSUE_URGENCY_MISMATCH(HttpStatus.CONFLICT),

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

    // Pronto SOS (broadcast-and-choose urgent dispatch) — the only SOS flow. See the sos README.
    /**
     * An SOS attempt is <b>already in progress</b> for this issue
     * ({@code ux_sos_requests_active_issue}, V36). Deliberately not "an SOS request has ever
     * existed": a previous attempt that expired, failed or was cancelled is history and must not
     * block a retry on the same issue.
     */
    SOS_REQUEST_ALREADY_EXISTS(HttpStatus.CONFLICT),
    /** The requested operation is not legal from the request's current status. */
    SOS_INVALID_STATE(HttpStatus.CONFLICT),
    /**
     * Vestigial, like {@link #BOOKING_TIME_UNAVAILABLE} above — never returned by any code path.
     * "Nobody eligible" is not an error the activating customer sees: the request is created
     * successfully and lands in terminal {@code FAILED}, which the client reads off
     * {@code status} (and hears about as realtime {@code SOS_FAILED}). Kept because a future
     * synchronous variant of matching would want exactly this code.
     */
    SOS_NO_PROFESSIONALS_AVAILABLE(HttpStatus.CONFLICT),
    /** The offer is no longer open (already responded to, expired, or superseded). */
    SOS_OFFER_NOT_OPEN(HttpStatus.CONFLICT),
    /** The professional-response window or the customer-selection window has elapsed. */
    SOS_WINDOW_EXPIRED(HttpStatus.GONE),
    /** The chosen offer is not one of the current candidates (not accepted, or not this request). */
    SOS_CANDIDATE_NOT_AVAILABLE(HttpStatus.CONFLICT),
    /** A professional has already been selected for this request — selection is one-shot. */
    SOS_ALREADY_SELECTED(HttpStatus.CONFLICT),
    /**
     * "סרוק שוב" was asked for on a request already at {@code pronto.sos.max-search-expansions}.
     *
     * <p>Its own code rather than a generic {@code SOS_INVALID_STATE} because it is the one
     * refusal here that is neither an error nor a race: the search really is as wide as this
     * platform will take it, every candidate found so far is still selectable, and the customer
     * needs to be told that specific thing rather than "something went wrong".
     */
    SOS_EXPANSION_LIMIT_REACHED(HttpStatus.CONFLICT),
    /**
     * An attempt to change an ETA a professional has already committed to (MS3).
     *
     * <p><b>A rule, not a race.</b> The customer chose — or is choosing — partly on that number,
     * so a professional who could revise it afterwards could win the job with an unrealistically
     * short promise and then take as long as they liked. The commitment is therefore final from
     * the moment acceptance persists, enforced in the domain: there is no repository statement
     * that writes an ETA outside {@code accept}. The endpoint survives only so a stale client
     * gets this explanation instead of a {@code 404}.
     */
    SOS_ETA_LOCKED(HttpStatus.CONFLICT),

    // MS1 (professional verification & approval). See
    // docs/architecture/ms1-professional-verification-design.md D-F.
    /**
     * The requested approval decision is not legal from the professional's current status —
     * approving someone already {@code APPROVED}, or rejecting someone who is not {@code PENDING}.
     *
     * <p>Its own code rather than a generic {@code VALIDATION_ERROR}, for the reason every other
     * {@code *_INVALID_STATE} code here exists: nothing about the request was malformed, the world
     * simply moved. The realistic cause is a double-submitted decision or two operators opening the
     * same queue, and an operator UI needs to tell those apart from "you sent nonsense."
     */
    PROFESSIONAL_APPROVAL_INVALID_TRANSITION(HttpStatus.CONFLICT),

    /**
     * The issue is past the point where the customer may still correct what they reported —
     * anything other than {@code OPEN}: booked, completed, cancelled. Raised by
     * {@code PATCH /api/issues/{id}/category}.
     *
     * <p>Its own code rather than {@code ISSUE_NOT_BOOKABLE} (which answers a different question,
     * "can an order be created against this issue") and rather than {@code VALIDATION_ERROR}: the
     * request is well-formed and the caller does own the issue, the issue has simply moved on. The
     * realistic cause is a stale tab — a professional accepted while the customer was still looking
     * at the classification screen — and the UI needs to tell that apart from a bad request.
     */
    ISSUE_NOT_EDITABLE(HttpStatus.CONFLICT),

    // ---------------------------------------------------------------------------------
    // Production MS1 (Authentication & Contact Verification).
    // See docs/production-roadmap/reports/prod-MS1-report.md §7.
    // ---------------------------------------------------------------------------------

    /**
     * The submitted phone number already belongs to another account.
     *
     * <p>Its own code rather than a reuse of {@link #DUPLICATE_EMAIL}: the two are different fields
     * on the same form, and a client that cannot tell them apart cannot highlight the right one.
     * Returned both by the pre-insert check and by the {@code ux_users_phone} race — see
     * {@code GlobalExceptionHandler}'s constraint-violation handler, which exists so that losing
     * that race is a 409 rather than a 500.
     */
    DUPLICATE_PHONE(HttpStatus.CONFLICT),

    /**
     * The account's phone number has not been verified, and the requested operation is one this
     * platform will not perform for an unverified contact channel: creating an issue, creating an
     * order, activating SOS, or being listed to customers.
     *
     * <p>Deliberately NOT {@link #FORBIDDEN}. A generic 403 tells a client "you may not do this",
     * which is untrue and unactionable — the caller may do this, as soon as they finish a step that
     * takes thirty seconds. This code is what lets the frontend route straight to phone capture
     * instead of showing a dead end, and it is the mechanism by which pre-MS1 accounts keep working
     * without being handed marketplace access they never verified a phone for.
     */
    PHONE_VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN),

    /** Phone verification was attempted on an account whose phone is already verified. */
    PHONE_ALREADY_VERIFIED(HttpStatus.CONFLICT),

    /**
     * The challenge is out of guesses ({@code OtpService.MAX_ATTEMPTS}) and is now dead.
     *
     * <p>Separate from {@link #INVALID_CODE} because the required user action differs: "you typed
     * it wrong, try again" versus "this code is finished, request a new one". Separate from
     * {@link #RATE_LIMITED} because nothing here is time-based — waiting does not help, and a
     * client that showed a countdown would be lying.
     */
    OTP_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),

    /**
     * The Email or SMS provider would not accept the message, so no code was delivered.
     *
     * <p>{@code 502} rather than {@code 500}: nothing went wrong in this application, an upstream
     * dependency failed, and the distinction is what keeps a provider outage visible as a provider
     * outage in monitoring instead of being buried in the generic error rate. The challenge itself
     * may still exist — the client's recovery is a resend, not a restart.
     */
    OTP_DELIVERY_FAILED(HttpStatus.BAD_GATEWAY);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
