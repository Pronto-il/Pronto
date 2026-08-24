/**
 * The polling keys for every server resource more than one component reads.
 *
 * `usePolling`'s `key` option is what makes two consumers share a timer and a response instead
 * of each opening their own (see `shared/hooks/pollingStore.ts`). A key must fully determine the
 * request, so anything that varies the URL — a status filter, an order id, a date range — varies
 * the key too.
 *
 * They live together in one file rather than next to each `httpClient` call because the sharing
 * itself is the thing that needs to be reviewable: this list *is* the answer to "which fetches
 * are owned once, and by whom". A resource only one component reads needs no entry here — an
 * un-keyed `usePolling` stays private to its caller.
 */

/** `GET /api/notifications?unreadOnly=true` — owned by `useNotifications` (the bell). */
export const NOTIFICATIONS_UNREAD_KEY = 'notifications:unread';

/** `GET /api/bookings/orders/me` — owned by `ActiveOrderProvider` for a CUSTOMER session. */
export const MY_ORDERS_KEY = 'bookings:orders:me';

/** `GET /api/bookings/orders/me?status=PENDING` — owned by `PendingRequestsProvider`, read by
 *  the sidebar badge, the command-center banner and the incoming-requests feed. */
export const MY_PENDING_ORDERS_KEY = 'bookings:orders:me?status=PENDING';

/** `GET /api/bookings/orders/{orderId}` — the tracking screen's own detail poll. Keyed (rather
 *  than left private) so two mounts of the same order, or a remount across a back/forward
 *  navigation, reuse one entry. */
export function orderDetailKey(orderId: number): string {
  return `bookings:order:${orderId}`;
}

/** `GET /api/availability/sos-availability` — read by `SosAvailabilityToggle` and
 *  `CommandCenterBanner`, written by the toggle's own `PUT`. */
export const SOS_AVAILABILITY_KEY = 'availability:sos-availability';

/** `GET /api/availability/working-hours` — read by `WeeklyAvailabilityPage` and (only when the
 *  account isn't bookable) `OnboardingStatusNotice`. */
export const WORKING_HOURS_KEY = 'availability:working-hours';

/** `GET /api/availability/calendar?from=&to=` — one entry per visible range, so the weekly grid
 *  and the command-center banner share a poll whenever they are looking at the same week. */
export function availabilityCalendarKey(from: string, to: string): string {
  return `availability:calendar:${from}..${to}`;
}

/** `GET /api/professionals/me` (+ the two follow-ups) — owned by `OnboardingStatusNotice`. */
export const ONBOARDING_STATUS_KEY = 'professionals:me:onboarding-status';

/** `GET /api/sos/offers?includeClosed=true` (+ the selected request) — owned by `ProSosProvider`. */
export const PRO_SOS_KEY = 'sos:offers:me';

/** `GET /api/sos/requests/{id}` + `GET /api/sos/requests/{id}/candidates` — owned by
 *  `useSosRequest`, one entry per tracked request. */
export function sosRequestKey(sosRequestId: number): string {
  return `sos:request:${sosRequestId}`;
}
