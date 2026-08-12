/**
 * Notification records and status polling endpoints, plus email dispatch.
 *
 * <p>Owns the {@code notifications} table (see {@code docs/architecture/data-model.md}
 * §2.9). Backs the short-polling real-time status updates described in
 * {@code docs/architecture/overview.md} §3.3 (in-app channel) and dispatches
 * verification/status emails (email channel). Consumed by {@code bookings} (status
 * transitions) and {@code auth} (verification codes). Stub only as of Milestone 0 —
 * implemented in Milestone 5 (Notifications & real-time status) per
 * {@code docs/architecture/implementation-plan.md}.
 */
package com.pronto.notifications;
