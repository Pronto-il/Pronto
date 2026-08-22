package com.pronto.sos.controller;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.sos.dto.CreateSosRequestRequest;
import com.pronto.sos.dto.SelectProfessionalRequest;
import com.pronto.sos.dto.SosCandidatesResponse;
import com.pronto.sos.dto.SosRequestResponse;
import com.pronto.sos.dto.SosRequestsListResponse;
import com.pronto.sos.dto.SosTimelineResponse;
import com.pronto.sos.service.SosService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/sos/requests/*} — the customer's Pronto SOS flow, plus the two either-role reads.
 *
 * <p>Route-level role gating is registered in {@code sos.config.SosWebConfig}, not checked in
 * these method bodies — the same arrangement, and for the same ordering reason, as
 * {@code bookings.controller.BookingsController} (see {@code common.security
 * .RoleRequiredInterceptor}'s Javadoc). Per-resource authorization ("is this your request?")
 * happens in {@code SosService} once the row is loaded.
 *
 * <p>Path ids are parsed manually rather than via typed {@code @PathVariable Long}, so a
 * malformed value produces this app's standard error envelope instead of Spring's default
 * type-mismatch handling — the convention {@code BookingsController} already established.
 *
 * <p>The professional-facing routes live in {@code SosProfessionalController}, including the
 * {@code /api/sos/requests/{id}/*} operational transitions: they are sub-paths of a request but
 * are professional-only actions, and splitting by actor rather than by URL prefix keeps each
 * controller's authorization story singular.
 */
@RestController
@RequestMapping("/api/sos")
public class SosCustomerController {

    private final SosService sosService;

    public SosCustomerController(SosService sosService) {
        this.sosService = sosService;
    }

    /** Activate SOS. {@code CUSTOMER}-only. */
    @PostMapping("/requests")
    public ResponseEntity<SosRequestResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @Valid @RequestBody CreateSosRequestRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sosService.create(principal.id(), request));
    }

    /** The caller's own SOS requests. Either role. */
    @GetMapping("/requests/me")
    public ResponseEntity<SosRequestsListResponse> listMine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(sosService.listMine(principal.id(), principal.role()));
    }

    /** Current state. Either role — the customer, or a professional who was offered it. */
    @GetMapping("/requests/{sosRequestId}")
    public ResponseEntity<SosRequestResponse> get(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @PathVariable("sosRequestId") String sosRequestIdRaw) {
        Long sosRequestId = parsePathId(sosRequestIdRaw);
        return ResponseEntity.ok(sosService.getRequest(principal.id(), principal.role(), sosRequestId));
    }

    /** The chronological history. Either role. This is what the realtime timeline will replay. */
    @GetMapping("/requests/{sosRequestId}/events")
    public ResponseEntity<SosTimelineResponse> events(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @PathVariable("sosRequestId") String sosRequestIdRaw) {
        Long sosRequestId = parsePathId(sosRequestIdRaw);
        return ResponseEntity.ok(sosService.getTimeline(principal.id(), principal.role(), sosRequestId));
    }

    /** Up to 3 accepted professionals to choose between. {@code CUSTOMER}-only. */
    @GetMapping("/requests/{sosRequestId}/candidates")
    public ResponseEntity<SosCandidatesResponse> candidates(@AuthenticationPrincipal AuthenticatedUser principal,
                                                              @PathVariable("sosRequestId") String sosRequestIdRaw) {
        Long sosRequestId = parsePathId(sosRequestIdRaw);
        return ResponseEntity.ok(sosService.getCandidates(principal.id(), sosRequestId));
    }

    /**
     * "סרוק שוב" — widen the search on this same request. {@code CUSTOMER}-only.
     *
     * <p>Idempotent under a double-tap by construction: the expansion counter is advanced by a
     * compare-and-set, so the second of two racing calls changes nothing and returns the state
     * the first produced. Refused once a professional has been selected, and once the configured
     * expansion ceiling is reached ({@code SOS_EXPANSION_LIMIT_REACHED}).
     */
    @PostMapping("/requests/{sosRequestId}/scan-again")
    public ResponseEntity<SosRequestResponse> scanAgain(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @PathVariable("sosRequestId") String sosRequestIdRaw) {
        Long sosRequestId = parsePathId(sosRequestIdRaw);
        return ResponseEntity.ok(sosService.expandSearch(principal.id(), sosRequestId));
    }

    /** Choose one. {@code CUSTOMER}-only, one-shot, deadline-enforced. */
    @PostMapping("/requests/{sosRequestId}/select")
    public ResponseEntity<SosRequestResponse> select(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @PathVariable("sosRequestId") String sosRequestIdRaw,
                                                       @Valid @RequestBody SelectProfessionalRequest request) {
        Long sosRequestId = parsePathId(sosRequestIdRaw);
        return ResponseEntity.ok(sosService.selectProfessional(principal.id(), sosRequestId, request.offerId()));
    }

    /** Either role — but a professional may only cancel once they are the selected one. */
    @PostMapping("/requests/{sosRequestId}/cancel")
    public ResponseEntity<SosRequestResponse> cancel(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @PathVariable("sosRequestId") String sosRequestIdRaw) {
        Long sosRequestId = parsePathId(sosRequestIdRaw);
        return ResponseEntity.ok(sosService.cancel(principal.id(), principal.role(), sosRequestId));
    }

    /**
     * Path-referenced id: unparsable or non-positive → {@code 404 NOT_FOUND}, matching
     * {@code BookingsController}'s convention (a malformed path id is indistinguishable from a
     * nonexistent one, and reporting it as a validation error leaks the routing shape).
     */
    static Long parsePathId(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Not found.");
        }
    }
}
