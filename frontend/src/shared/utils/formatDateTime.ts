/**
 * Small Hebrew date/time formatting helpers shared by every screen that renders an
 * `AvailabilitySlotItem`/`OrderResponse` timestamp (`features/booking`'s `SlotPicker`,
 * `BookingSummary`, `OrderTrackingPage`, `MyOrdersPage`; `features/dashboard`'s
 * `IncomingRequestCard`; `features/professionals`'s `ReviewList`) — extracted here rather
 * than reimplemented per screen (FRONTEND_AGENT.md §40).
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

/**
 * Relative age label for a past timestamp, e.g. "4 ימים" — DESIGN_SYSTEM.md §45's review-card
 * format (`frontend-ms8-design.md` §4.5, granularity beyond that single example left to
 * reasonable judgment, §6 Risk 4). Extends this file's existing "היום/מחר" precedent onto the
 * past axis ("היום"/"אתמול" for the two most recent calendar days) rather than inventing a
 * separate convention, then falls back to day/month/year counts for anything older.
 */
export function formatRelativeAgeLabel(isoString: string): string {
  const date = new Date(isoString);
  const now = new Date();

  if (isSameDay(date, now)) {
    return 'היום';
  }
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (isSameDay(date, yesterday)) {
    return 'אתמול';
  }

  const diffDays = Math.floor((now.getTime() - date.getTime()) / (24 * 60 * 60 * 1000));
  if (diffDays < 30) {
    return `${diffDays} ימים`;
  }
  const diffMonths = Math.floor(diffDays / 30);
  if (diffMonths < 12) {
    return diffMonths === 1 ? 'חודש' : `${diffMonths} חודשים`;
  }
  const diffYears = Math.floor(diffDays / 365);
  return diffYears === 1 ? 'שנה' : `${diffYears} שנים`;
}

/** Grouping key (calendar day, not full timestamp) — local time, not UTC-truncated. */
export function dateKey(isoString: string): string {
  const date = new Date(isoString);
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

function isSameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}
