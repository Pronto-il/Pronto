import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Card, Badge } from '../../shared/components';
import { useAuth, usePendingRequests, usePolling } from '../../shared/hooks';
import { getAvailabilityCalendar, getSosAvailability } from '../../shared/api';
import { SOS_AVAILABILITY_KEY, availabilityCalendarKey } from '../../shared/api/resourceKeys';
import type { CalendarResponse, SosAvailabilityResponse, CalendarSegment } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './CommandCenterBanner.module.css';

/** A summary banner doesn't need a faster cadence than the calendar it sits above (MS6 design
 *  doc §3.3), and 25s was faster than a day's job list ever changes. */
const BANNER_POLL_INTERVAL_MS = 60_000;

function toDateKey(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function addDays(date: Date, days: number): Date {
  const d = new Date(date);
  d.setDate(d.getDate() + days);
  return d;
}

function getTimeOfDayGreeting(): string {
  const hour = new Date().getHours();
  if (hour < 5) return 'לילה טוב';
  if (hour < 12) return 'בוקר טוב';
  if (hour < 18) return 'צהריים טובים';
  return 'ערב טוב';
}

/**
 * Command-center summary banner (MS6 design doc §3) — composed at the top of
 * `WeeklyAvailabilityPage`, above the existing `SosAvailabilityToggle`/calendar content, not a
 * separate `/pro` route (§3.1). One restrained `Card` (§3.5), not a grid of stat tiles: a
 * greeting, three `Badge`s (pending-request count, today's job count, SOS state), and an
 * optional "next appointment" line.
 *
 * Data sources, each already verified real (§3.2): the pending-request count comes from
 * `PendingRequestsContext` (shared with the sidebar badge, §3.3) rather than its own fetch;
 * today's job count/next appointment come from a narrow, single-day
 * `GET /api/availability/calendar?from=&to=`; SOS state from
 * `GET /api/availability/sos-availability`. Earnings are deliberately omitted — no backend field
 * exists (§3.4).
 *
 * **Both of those reads are keyed, and that is the point.** `SosAvailabilityToggle` sits directly
 * below this banner on the same screen and reads the same SOS-availability resource; sharing
 * `SOS_AVAILABILITY_KEY` means the two of them make one request between them, and the toggle's
 * own `PUT` publishes its response into the same entry, so flipping the switch updates this
 * badge without either component asking the server again. The calendar read is keyed by its
 * date range for the same reason — when `WeeklyCalendarGrid` below is showing the week that
 * contains today (its default, and where a professional spends nearly all of their time), the
 * ranges differ so the keys differ; sharing happens whenever the ranges actually coincide, and
 * the banner never silently pins the grid to a range it didn't choose.
 *
 * Motion: CSS-only mount transition (`.banner`'s own `.module.css`) — this is a static,
 * non-interactive-on-mount informational card, not a mount/exit-driven surface, per the
 * CSS-vs-framer-motion split in `shared/motion/README.md` (§3.5).
 */
export function CommandCenterBanner() {
  const { user } = useAuth();
  const { count: pendingCount } = usePendingRequests();

  const today = useMemo(() => new Date(), []);
  const from = toDateKey(today);
  const to = toDateKey(addDays(today, 1));

  const { data: calendarData } = usePolling<CalendarResponse>(() => getAvailabilityCalendar(from, to), {
    key: availabilityCalendarKey(from, to),
    intervalMs: BANNER_POLL_INTERVAL_MS,
  });
  const { data: sosData } = usePolling<SosAvailabilityResponse>(() => getSosAvailability(), {
    key: SOS_AVAILABILITY_KEY,
    intervalMs: BANNER_POLL_INTERVAL_MS,
  });

  const todaysBookedSegments: CalendarSegment[] = (calendarData?.segments ?? []).filter(
    (segment) => segment.type === 'BOOKED',
  );
  const todaysJobCount = todaysBookedSegments.length;

  const nowMs = Date.now();
  const nextAppointment = [...todaysBookedSegments]
    .filter((segment) => new Date(segment.startAt).getTime() >= nowMs)
    .sort((a, b) => a.startAt.localeCompare(b.startAt))[0];

  const firstName = user?.fullName?.trim().split(/\s+/)[0];

  return (
    // `motion-list-item` (global, `styles/motion.css`) gives this static, once-per-mount card
    // a simple CSS opacity/translateY rise — the CSS tier per `shared/motion/README.md` (§3.5),
    // reused rather than a bespoke keyframe.
    <Card className={`${styles.banner} motion-list-item`}>
      <p className={styles.greeting}>
        {getTimeOfDayGreeting()}
        {firstName ? `, ${firstName}` : ''}
      </p>
      <div className={styles.badgeRow}>
        <Link to="/pro/requests" className={styles.badgeLink}>
          <Badge tone={pendingCount > 0 ? 'primary' : 'neutral'}>{pendingCount} בקשות חדשות</Badge>
        </Link>
        <Badge tone="neutral">{todaysJobCount} עבודות היום</Badge>
        {sosData && <Badge tone="info">SOS: {sosData.isAvailable ? 'פעיל' : 'כבוי'}</Badge>}
      </div>
      {nextAppointment && (
        <p className={styles.nextAppointment}>
          העבודה הבאה: {formatDateLabel(nextAppointment.startAt)}, {formatTimeLabel(nextAppointment.startAt)}
        </p>
      )}
    </Card>
  );
}
