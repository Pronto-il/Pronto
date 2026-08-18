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
 */
public record OrderDetailResponse(
        Long id,
        Long issueId,
        Long customerId,
        String customerName,
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
