/**
 * {@code Orders} — Standard + SOS booking flows, accept/reject, and status transitions.
 *
 * <p>Owns the {@code orders} table (see {@code docs/architecture/data-model.md} §2.8),
 * covering both the Standard path (browse professionals, price offers, pick one) and the
 * SOS path (currently-available professionals, urgent request). Depends on
 * {@code professionals} and {@code availability} for matching, and triggers
 * {@code notifications} on every status transition. Stub only as of Milestone 0 —
 * implemented across Milestone 3 (Standard booking flow) and Milestone 4 (SOS booking
 * flow) per {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.bookings;
