/**
 * Small Hebrew date/time formatting helpers shared by every screen that renders an
 * `AvailabilitySlotItem`/`OrderResponse` timestamp (`features/booking`'s `SlotPicker`,
 * `BookingSummary`, `OrderTrackingPage`, `MyOrdersPage`; `features/dashboard`'s
 * `IncomingRequestCard`) — extracted here rather than reimplemented per screen
 * (FRONTEND_AGENT.md §40).
 */

const DATE_FORMATTER = new Intl.DateTimeFormat('he-IL', { weekday: 'long', day: 'numeric', month: 'long' });
const TIME_FORMATTER = new Intl.DateTimeFormat('he-IL', { hour: '2-digit', minute: '2-digit' });

/** e.g. "יום ראשון, 16 באוגוסט" — but Intl doesn't add the "ב" prefix, so build it manually. */
export function formatDateLabel(isoString: string): string {
  const date = new Date(isoString);
  const today = new Date();
  const tomorrow = new Date();
  tomorrow.setDate(today.getDate() + 1);

  if (isSameDay(date, today)) {
    return 'היום';
  }
  if (isSameDay(date, tomorrow)) {
    return 'מחר';
  }
  return DATE_FORMATTER.format(date);
}

/** e.g. "14:30" */
export function formatTimeLabel(isoString: string): string {
  return TIME_FORMATTER.format(new Date(isoString));
}

/** e.g. "יום ראשון, 16 באוגוסט · 14:30" */
export function formatDateTimeLabel(isoString: string): string {
  return `${formatDateLabel(isoString)} · ${formatTimeLabel(isoString)}`;
}

/** Grouping key (calendar day, not full timestamp) — local time, not UTC-truncated. */
export function dateKey(isoString: string): string {
  const date = new Date(isoString);
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

function isSameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}
