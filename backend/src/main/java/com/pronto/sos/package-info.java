/**
 * Pronto SOS — urgent, broadcast-and-choose dispatch.
 *
 * <p>The customer activates SOS on an existing issue and names nobody. The platform matches and
 * ranks eligible professionals, dispatches offers to a bounded pool of them, collects
 * acceptances, and presents the customer with up to three candidates and roughly two minutes to
 * choose. Selection atomically creates an {@code orders} row, after which the job runs through
 * confirm → on the way → arrived → completed.
 *
 * <p><b>Distinct from the pre-existing SOS booking path</b> ({@code GET
 * /api/bookings/sos-professionals} + {@code POST /api/bookings/sos-orders}), which is
 * browse-and-pick: there the customer reads a list and names a professional themselves. Both
 * coexist; this package adds nothing to and removes nothing from that one. They share
 * {@code sos_availability} (the professional's live on/off toggle),
 * {@code matching.DistanceEtaStrategy}, and the {@code orders} table.
 *
 * <p><b>Realtime is deliberately not implemented here.</b> The seam for it exists:
 * {@code sos.service.SosEventService} writes an {@code sos_events} row and publishes a
 * {@code sos.event.SosDomainEvent} for every transition, so the next phase adds a
 * {@code @TransactionalEventListener} that forwards to WebSocket subscribers without any
 * business logic moving or changing.
 *
 * <p>See {@code README.md} in this package for the state machine, the ranking model, the
 * concurrency protections and the full endpoint list.
 */
package com.pronto.sos;
