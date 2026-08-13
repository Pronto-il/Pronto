package com.pronto.bookings.controller;

import com.pronto.bookings.dto.CreateOrderRequest;
import com.pronto.bookings.dto.CreateSosOrderRequest;
import com.pronto.bookings.dto.OrderDetailResponse;
import com.pronto.bookings.dto.OrderResponse;
import com.pronto.bookings.dto.OrdersListResponse;
import com.pronto.bookings.dto.ProfessionalListingResponse;
import com.pronto.bookings.dto.SlotListingResponse;
import com.pronto.bookings.service.BookingsService;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /api/bookings/*} — Standard (Milestone 3) and SOS (Milestone 4) booking flows.
 * Route-level role gating ({@code CUSTOMER}-only / {@code PROFESSIONAL}-only) is enforced by
 * {@code bookings.config.BookingsWebConfig}'s {@code RoleRequiredInterceptor}
 * registrations, not in these method bodies — see that class's javadoc (§0.1). The
 * either-role routes ({@code cancel}, {@code GET .../{orderId}}, {@code GET .../me}) have no
 * route-level gate at all; authorization for those happens entirely in
 * {@link BookingsService} once the resource is loaded. See
 * {@code docs/architecture/api-contract-bookings.md} §2.2-2.9 (Standard) and §2.12-2.13 (SOS).
 *
 * <p>Path/query ids are parsed manually (not via typed {@code @PathVariable Long}/
 * {@code @RequestParam Long}) so a malformed value produces this app's standard error
 * envelope with the contract-mandated code (§0's path-vs-body-id convention), rather than
 * Spring's default type-mismatch handling.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingsController {

    private final BookingsService bookingsService;

    public BookingsController(BookingsService bookingsService) {
        this.bookingsService = bookingsService;
    }

    @GetMapping("/professionals")
    public ResponseEntity<ProfessionalListingResponse> listProfessionals(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(name = "issueId", required = false) String issueIdRaw) {
        Long issueId = parseQueryId(issueIdRaw, "issueId");
        return ResponseEntity.ok(bookingsService.listProfessionals(principal.id(), issueId));
    }

    @GetMapping("/professionals/{professionalId}/slots")
    public ResponseEntity<SlotListingResponse> listSlots(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("professionalId") String professionalIdRaw,
            @RequestParam(name = "issueId", required = false) String issueIdRaw) {
        Long professionalId = parsePathId(professionalIdRaw);
        Long issueId = parseQueryId(issueIdRaw, "issueId");
        return ResponseEntity.ok(bookingsService.listSlots(principal.id(), professionalId, issueId));
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = bookingsService.createOrder(principal.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/sos-professionals")
    public ResponseEntity<ProfessionalListingResponse> listSosProfessionals(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(name = "issueId", required = false) String issueIdRaw) {
        Long issueId = parseQueryId(issueIdRaw, "issueId");
        return ResponseEntity.ok(bookingsService.listSosProfessionals(principal.id(), issueId));
    }

    @PostMapping("/sos-orders")
    public ResponseEntity<OrderResponse> createSosOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @Valid @RequestBody CreateSosOrderRequest request) {
        OrderResponse response = bookingsService.createSosOrder(principal.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/orders/{orderId}/accept")
    public ResponseEntity<OrderResponse> accept(@AuthenticationPrincipal AuthenticatedUser principal,
                                                 @PathVariable("orderId") String orderIdRaw) {
        Long orderId = parsePathId(orderIdRaw);
        return ResponseEntity.ok(bookingsService.accept(principal.id(), orderId));
    }

    @PostMapping("/orders/{orderId}/reject")
    public ResponseEntity<OrderResponse> reject(@AuthenticationPrincipal AuthenticatedUser principal,
                                                 @PathVariable("orderId") String orderIdRaw) {
        Long orderId = parsePathId(orderIdRaw);
        return ResponseEntity.ok(bookingsService.reject(principal.id(), orderId));
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancel(@AuthenticationPrincipal AuthenticatedUser principal,
                                                 @PathVariable("orderId") String orderIdRaw) {
        Long orderId = parsePathId(orderIdRaw);
        return ResponseEntity.ok(bookingsService.cancel(principal.id(), principal.role(), orderId));
    }

    @GetMapping("/orders/me")
    public ResponseEntity<OrdersListResponse> listMine(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(bookingsService.listMine(principal.id(), principal.role(), status));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderDetailResponse> getOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @PathVariable("orderId") String orderIdRaw) {
        Long orderId = parsePathId(orderIdRaw);
        return ResponseEntity.ok(bookingsService.getOrderDetail(principal.id(), principal.role(), orderId));
    }

    /** Query-param id (e.g. {@code issueId}): missing/non-positive/unparsable → {@code 400 VALIDATION_ERROR}. */
    private Long parseQueryId(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request failed validation.",
                    List.of(new FieldError(fieldName, "is required")));
        }
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request failed validation.",
                    List.of(new FieldError(fieldName, "must be a positive integer")));
        }
    }

    /** Path-referenced id (e.g. {@code professionalId}/{@code orderId}): unparsable → {@code 404 NOT_FOUND} (§0). */
    private Long parsePathId(String raw) {
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
