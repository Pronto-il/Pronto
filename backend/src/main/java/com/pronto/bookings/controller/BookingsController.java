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
import com.pronto.maps.AddressAccessFields;
import com.pronto.maps.HouseNumbers;
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

    /**
     * The caller's id, or {@code null} when nobody is signed in.
     *
     * <p>Only the guest-reachable read routes may receive a null principal — every write route in
     * this controller is behind a {@code RoleRequiredInterceptor}, which rejects an anonymous
     * request before the handler runs. Written as a helper rather than an inline
     * {@code principal == null ? null : principal.id()} at each call site so that "this route
     * tolerates a guest" is a visible, greppable decision instead of a null check somebody could
     * copy onto a route where it is wrong.
     */
    private static Long callerId(AuthenticatedUser principal) {
        return principal == null ? null : principal.id();
    }

    @GetMapping("/professionals")
    public ResponseEntity<ProfessionalListingResponse> listProfessionals(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(name = "issueId", required = false) String issueIdRaw,
            @RequestParam(name = "categoryId", required = false) String categoryIdRaw,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "street", required = false) String street,
            @RequestParam(name = "houseNumber", required = false) String houseNumber,
            @RequestParam(name = "apartment", required = false) String apartment,
            @RequestParam(name = "sort", required = false) String sort) {
        // BOTH are optional, and exactly one is expected -- see BookingsService#listProfessionals,
        // which takes `issueId` in preference and falls back to `categoryId` for the guest journey.
        //
        // This is the bug behind the reported 400. Deferred authentication (a48f324) moved issue
        // creation to the booking commit, so a listing is now keyed on a category for guests AND
        // for signed-in customers who have not committed yet; the service was rewritten for that
        // and the frontend sends one id or the other. This line was not: it kept demanding an
        // issueId, so every `?categoryId=...` listing -- which is now the normal case -- was
        // answered `400 VALIDATION_ERROR: issueId is required`, no matter how good the address was.
        //
        // "At least one of them" is deliberately NOT re-implemented here: the service already
        // reports it (`requireListingCategory`), and a second copy of a cross-field rule is how
        // the two come to disagree.
        Long issueId = parseOptionalQueryId(issueIdRaw, "issueId");
        Long categoryId = parseOptionalQueryId(categoryIdRaw, "categoryId");
        ServiceLocation location = parseServiceLocation(city, street, houseNumber, apartment);
        // principal is null for a guest -- this route is permitAll (see auth.config.SecurityConfig's
        // "guest journey" block). callerId flows through as null and every consumer of it in
        // BookingsService already treats "no caller" as a real state.
        return ResponseEntity.ok(bookingsService.listProfessionals(callerId(principal), issueId,
                categoryId, location, sort));
    }

    @GetMapping("/professionals/{professionalId}/available-windows")
    public ResponseEntity<AvailableWindowsResponse> listAvailableWindows(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("professionalId") String professionalIdRaw,
            @RequestParam(name = "issueId", required = false) String issueIdRaw) {
        Long professionalId = parsePathId(professionalIdRaw);
        // Optional, for the same reason and by the same oversight as the listing route above:
        // `listAvailableWindows` documents and implements "no issue" as a supported state (a
        // professional's free windows come from their own published hours), the client omits the
        // parameter when it has no issue, and this line used to reject that with
        // `400 issueId is required` -- turning the step immediately after the listing into the
        // next dead end of the same journey.
        Long issueId = parseOptionalQueryId(issueIdRaw, "issueId");
        return ResponseEntity.ok(bookingsService.listAvailableWindows(callerId(principal), professionalId, issueId));
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
     *
     * <p><b>This is the check that answered the reported 400.</b> It was doing its job: a client
     * asked for professionals with {@code city=&street=&houseNumber=}, and an empty address is not
     * something this endpoint can honestly answer — service-area relevance, road distance and ETA
     * are all derived from it, so "no address" would mean inventing all three. The fix belongs on
     * the calling side (a screen must not ask for a listing before it has an address), and this
     * check stays exactly as strict, because a frontend fix protects only the frontend.
     *
     * <p>{@code houseNumber} additionally has to be <b>digits only</b>, matching every other write
     * path (see {@code maps.HouseNumbers}), and {@code apartment} <b>digits only</b> when present
     * (see {@code maps.AddressAccessFields}). Validating presence but not shape here would leave
     * this the one door through which an address the rest of the platform refuses could still be
     * used to rank and route professionals — and, for the apartment specifically, the one place a
     * customer could get a full listing for an address whose booking {@code CreateOrderRequest}
     * is then going to refuse. Failing on the field they can still see beats failing at the last
     * step of the flow.
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
        } else if (!HouseNumbers.isValid(houseNumber)) {
            errors.add(new FieldError("houseNumber", HouseNumbers.MESSAGE));
        }
        if (apartment != null && !apartment.isBlank() && !AddressAccessFields.isValidApartment(apartment)) {
            errors.add(new FieldError("apartment", AddressAccessFields.APARTMENT_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request failed validation.", errors);
        }
        return new ServiceLocation(city, street, houseNumber, apartment);
    }

    /**
     * Query-param id that may legitimately be absent: {@code null} when missing or blank,
     * otherwise validated exactly as a required one ({@code 400 VALIDATION_ERROR} for
     * non-positive or unparsable).
     *
     * <p>"Absent" and "malformed" are deliberately different outcomes. Absent is a state these
     * routes support, and answering it with a validation error is what produced the reported 400.
     * Malformed is a client bug and still fails loudly — an unparsable id silently becoming
     * {@code null} would turn "I asked about issue 4x" into "I asked about nothing in particular"
     * and quietly return a different customer's listing shape.
     */
    private Long parseOptionalQueryId(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
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
