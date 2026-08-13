/**
 * {@code Orders} — Standard + SOS booking flows, accept/reject, and status transitions.
 *
 * <p>Owns the {@code orders} table (see {@code docs/architecture/data-model.md} §2.9, as
 * amended by {@code V11}/{@code V12} — the genuine 7th {@code REJECTED} status and the
 * nullable {@code slot_id} FK, see {@code docs/architecture/api-contract-bookings.md} §1).
 * Depends on {@code issues} (an order is created against a persisted, confirmed issue —
 * and, since Milestone 3, {@code issues} also depends back on this package for its {@code
 * GET /api/issues/{id}} {@code latestOrder} field, a deliberate mutual dependency),
 * {@code professionals} and {@code availability} (matching/listing/slot claim-release), and
 * {@code users} (customer/professional display names).
 *
 * <p><b>Milestone 3 (Standard booking flow) — implemented</b>, per the full contract in
 * {@code docs/architecture/api-contract-bookings.md} §2.2-2.9: professional listing (§2.2),
 * slot listing (§2.3), create order (§2.4, atomic slot-claim + issue-transition), accept
 * (§2.5), reject (§2.6, releases the slot + reopens the issue), cancel (§2.7, either party,
 * state-dependent), get-by-id tracking endpoint (§2.8), and self-listing (§2.9). Every state
 * transition uses the same atomic {@code UPDATE ... WHERE <current-state-guard>} pattern
 * (§3.2 of that doc), not ad hoc per-endpoint locking.
 *
 * <p><b>Not built this milestone</b> (Milestone 4/5/6 scope, per §6/§7 of the contract doc):
 * SOS order creation, {@code ON_THE_WAY}/{@code COMPLETED} progression endpoints, the
 * {@code PENDING}-timeout expiry sweep, payment, GPS/live tracking.
 */
package com.pronto.bookings;
