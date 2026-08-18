package com.pronto.bookings.dto;

import com.pronto.bookings.entity.CancelledBy;
import com.pronto.bookings.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response body for {@code GET /api/bookings/orders/{orderId}} — the tracking/status
 * endpoint, enriched with display-friendly names so the client needs no follow-up calls. See
 * {@code docs/architecture/api-contract-bookings.md} §2.8.
 *
 * <p>{@code basePriceSnapshot}/{@code sosSurcharge} are the SOS-surcharge line-item split
 * (§1 classification item 10). {@code serviceCity}/{@code serviceStreet}/
 * {@code serviceHouseNumber}/{@code serviceApartment}/{@code serviceFloor}/
 * {@code serviceEntrance}/{@code serviceAddressNotes} are the service-address snapshot (§1
 * classification item 5).
 *
 * <p>{@code customerPhone} — new, professional weekly availability calendar design §9.1 —
 * is populated by this same endpoint's existing, unmodified party-to-order authorization
 * check (no new authorization branch, no {@code order_status} gating): visible to the
 * order's own customer and to the assigned professional starting the moment the order is
 * created ({@code PENDING} onward), the same access-scoping the service-address snapshot
 * above already uses.
 */
public record OrderDetailResponse(
        Long id,
        Long issueId,
        Long customerId,
        String customerName,
        String customerPhone,
        Long professionalId,
        String professionalName,
        OrderStatus orderStatus,
        Instant bookedStart,
        Instant bookedEnd,
        Instant expectedArrivalAt,
        BigDecimal finalPrice,
        BigDecimal basePriceSnapshot,
        BigDecimal sosSurcharge,
        String serviceCity,
        String serviceStreet,
        String serviceHouseNumber,
        String serviceApartment,
        String serviceFloor,
        String serviceEntrance,
        String serviceAddressNotes,
        CancelledBy cancelledBy,
        Instant createdAt,
        Instant updatedAt
) {
}
