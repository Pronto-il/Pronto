/**
 * Pronto SOS — urgent, broadcast-and-choose dispatch.
 *
 * <p>The customer activates SOS on an existing issue and names nobody. The platform matches and
 * ranks eligible professionals, dispatches offers to a bounded pool of them, collects
 * acceptances, and presents the customer with up to three candidates and roughly two minutes to
 * choose. Selection atomically creates an {@code orders} row, after which the job runs through
 * confirm → on the way → arrived → completed.
 *
 * <p><b>This is the only SOS flow.</b> The earlier browse-and-pick path ({@code GET
 * /api/bookings/sos-professionals} + {@code POST /api/bookings/sos-orders}), where the customer
 * read a list and named a professional themselves, has been removed — routes, service methods,
 * DTO and frontend alike ({@code sos.SingleSosFlowTest} pins that). What it shared with this
 * package stayed: {@code sos_availability} (the professional's live on/off toggle),
 * {@code matching.DistanceEtaStrategy}, and the {@code orders} table.
 *
 * <p><b>An SOS request is an attempt, not the problem.</b> The problem is the {@code issues} row
 * and it survives every attempt intact. A customer whose SOS expired, failed or was cancelled may
 * activate another on the same issue without re-describing anything; only one attempt may be in
 * flight at a time ({@code ux_sos_requests_active_issue}).
 *
 * <p><b>Availability is not assignment.</b> {@code SosOfferStatus.ACCEPTED} means a professional
 * said they are available and can come; {@code SELECTED} means the customer chose them. Until
 * that happens no professional sees more of the address than its city — see
 * {@code sos.service.SosAddressAccess}.
 *
 * <p>Realtime delivery lives in {@code sos.realtime}: {@code sos.service.SosEventService} writes
 * an {@code sos_events} row and publishes a {@code sos.event.SosDomainEvent} for every
 * transition, and {@code sos.realtime.SosRealtimePublisher} forwards those to WebSocket
 * subscribers after commit. Transport is {@code com.pronto.realtime}.
 *
 * <p>See {@code README.md} in this package for the state machine, the ranking model, the
 * concurrency protections and the full endpoint list.
 */
package com.pronto.sos;
