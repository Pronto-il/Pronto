package com.pronto.bookings.controller;

import com.pronto.bookings.dto.ArrivalRequest;
import com.pronto.bookings.dto.AvailableWindowsResponse;
import com.pronto.bookings.dto.CreateOrderRequest;
import com.pronto.bookings.dto.OrderDetailResponse;
import com.pronto.bookings.dto.OrderResponse;
import com.pronto.bookings.dto.OrdersListResponse;
import com.pronto.bookings.dto.ProfessionalListingResponse;
import com.pronto.bookings.service.BookingsService;
import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.matching.ServiceLocation;
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

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /api/bookings/*} — Standard (Milestone 3) and SOS (Milestone 4) booking flows. As of
 * the professional weekly availability calendar design (M2, §9.2.2), the Standard listing
 * endpoint is {@code GET .../professionals/{id}/available-windows?issueId=} (replaces the
 * retired {@code GET .../slots?issueId=} entirely).
 * Route-level role gating ({@code CUSTOMER}-only / {@code PROFESSIONAL}-only) is enforced by
 * {@code bookings.config.BookingsWebConfig}'s {@code RoleRequiredInterceptor}
 * registrations, not in these method bodies — see that class's javadoc (§0.1). The
 * either-role routes ({@code cancel}, {@code GET .../{orderId}}, {@code GET .../me}) have no
 * route-level gate at all; authorization for those happens entirely in
 * {@link BookingsService} once the resource is loaded. See
 * {@code docs/architecture/api-contract-bookings.md} §2.2-2.9 (Standard), §2.12-2.13 (SOS),
 * and §2.16-2.17 (job-status progression, Milestone 6).
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
            @RequestParam(name = "issueId", required = false) String issueIdRaw,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "street", required = false) String street,
            @RequestParam(name = "houseNumber", required = false) String houseNumber,
            @RequestParam(name = "apartment", required = false) String apartment,
            @RequestParam(name = "sort", required = false) String sort) {
        Long issueId = parseQueryId(issueIdRaw, "issueId");
        ServiceLocation location = parseServiceLocation(city, street, houseNumber, apartment);
        return ResponseEntity.ok(bookingsService.listProfessionals(principal.id(), issueId, location, sort));
    }

    @GetMapping("/professionals/{professionalId}/available-windows")
    public ResponseEntity<AvailableWindowsResponse> listAvailableWindows(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("professionalId") String professionalIdRaw,
            @RequestParam(name = "issueId", required = false) String issueIdRaw) {
        Long professionalId = parsePathId(professionalIdRaw);
        Long issueId = parseQueryId(issueIdRaw, "issueId");
        return ResponseEntity.ok(bookingsService.listAvailableWindows(principal.id(), professionalId, issueId));
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = bookingsService.createOrder(principal.id(), request);
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

    @PostMapping("/orders/{orderId}/on-the-way")
    public ResponseEntity<OrderResponse> onTheWay(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @PathVariable("orderId") String orderIdRaw) {
        Long orderId = parsePathId(orderIdRaw);
        return ResponseEntity.ok(bookingsService.onTheWay(principal.id(), orderId));
    }

    /**
     * <b>Production MS2</b> — {@code ON_THE_WAY -> ARRIVED}, gated on a backend proximity check.
     *
     * <p>The only order-action route on this controller that takes a body, because it is the only
     * one asserting a fact about the physical world rather than an intention. See
     * {@code ArrivalRequest} for why the fix is sent rather than read from the professional's
     * stored position, and {@code BookingsService#arrived} for the verification itself.
     */
    @PostMapping("/orders/{orderId}/arrived")
    public ResponseEntity<OrderResponse> arrived(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @PathVariable("orderId") String orderIdRaw,
                                                   @Valid @RequestBody ArrivalRequest request) {
        Long orderId = parsePathId(orderIdRaw);
        return ResponseEntity.ok(bookingsService.arrived(principal.id(), orderId, request));
    }

    @PostMapping("/orders/{orderId}/complete")
    public ResponseEntity<OrderResponse> complete(@AuthenticationPrincipal AuthenticatedUser principal,
                                                    @PathVariable("orderId") String orderIdRaw) {
        Long orderId = parsePathId(orderIdRaw);
        return ResponseEntity.ok(bookingsService.complete(principal.id(), orderId));
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

    /**
     * {@code city}/{@code street}/{@code houseNumber} are required (blank/missing →
     * {@code 400 VALIDATION_ERROR}, one {@link FieldError} per missing field so all three can
     * be reported in one response, same "collect every failure" spirit as {@code @Valid}
     * body validation); {@code apartment} is optional. See {@code matching.ServiceLocation}.
     */
    private ServiceLocation parseServiceLocation(String city, String street, String houseNumber, String apartment) {
        List<FieldError> errors = new ArrayList<>();
        if (city == null || city.isBlank()) {
            errors.add(new FieldError("city", "is required"));
        }
        if (street == null || street.isBlank()) {
            errors.add(new FieldError("street", "is required"));
        }
        if (houseNumber == null || houseNumber.isBlank()) {
            errors.add(new FieldError("houseNumber", "is required"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request failed validation.", errors);
        }
        return new ServiceLocation(city, street, houseNumber, apartment);
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
