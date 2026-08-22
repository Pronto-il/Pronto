import { useMemo, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Lock, CheckCircle2 } from 'lucide-react';
import { Button, StatusBadge } from '../../shared/components';
import { usePolling } from '../../shared/hooks';
import { getAvailabilityCalendar, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { CalendarResponse, CalendarSegment } from '../../shared/api';
import { formatTimeLabel } from '../../shared/utils/formatDateTime';
import { CalendarBlockModal } from './CalendarBlockModal';
import styles from './WeeklyCalendarGrid.module.css';

/** The one piece of state driving `CalendarBlockModal` (design §7.3/§10 M5): `null` when
 *  closed, `{ mode: 'create' }` pre-filled from a clicked `AVAILABLE` segment's own range,
 *  `{ mode: 'edit' }` pre-filled straight from a clicked `BLOCKED` segment's `blockId`/
 *  `startAt`/`endAt`/`reason` (already in the fetched calendar response — no extra `GET`). A
 *  `BOOKED` click never produces this state at all (§15's critical constraint) — see
 *  `handleSegmentClick`'s branch below, which routes `BOOKED` straight to `navigate(...)`
 *  before any modal state is ever touched. */
type BlockModalState =
  | { mode: 'create'; initialRange: { startAt: string; endAt: string } }
  | { mode: 'edit'; block: { id: number; startAt: string; endAt: string; reason: string | null } };

/** Sunday(0)-first, matching the working-hours row order (design §7.3: "matching the
 *  working-hours row order"). */
const WEEKDAY_LABELS = ['ראשון', 'שני', 'שלישי', 'רביעי', 'חמישי', 'שישי', 'שבת'];

/** Single-letter weekday labels for the mobile day-switcher, same Sunday(0)-first order.
 *  Deliberately a separate list rather than `WEEKDAY_LABELS[i][0]`: taking the first letter of
 *  the full names produces ר/ש/ש/ר/ח/ש/ש — four collisions across the week, so three different
 *  chips read "ש". The conventional Hebrew calendar letters (א-ו + ש for שבת) are unique. */
const MOBILE_WEEKDAY_LABELS = ['א', 'ב', 'ג', 'ד', 'ה', 'ו', 'ש'];

/** Design §7.3/§31: "a coarser interval than the 3-5s order-tracking polling — recommended
 *  20-30s." */
const CALENDAR_POLL_INTERVAL_MS = 25000;

/** Design §7.3: "fixed default visible range 06:00-23:00... vertical scroll beyond that." */
const DEFAULT_START_HOUR = 6;
const DEFAULT_END_HOUR = 23;
const PX_PER_MINUTE = 1.15;
const GRID_STEP_MINUTES = 30;

const WEEK_RANGE_LABEL_FORMATTER = new Intl.DateTimeFormat('he-IL', { day: 'numeric', month: 'long' });
const DAY_HEADER_DATE_FORMATTER = new Intl.DateTimeFormat('he-IL', { day: 'numeric', month: 'numeric' });

// ---- Date helpers (local calendar-day math, matching this codebase's existing
// `shared/utils/formatDateTime.ts` convention of formatting/bucketing in the browser's own
// local timezone rather than a separately-tracked business timezone — see this component's
// doc comment below for the explicit assumption this rests on). ----

function toDateKey(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** Sunday of the week containing `date` (`Date#getDay()` is already `0`-`6`, Sunday-first,
 *  matching `professional_working_hours.weekday`'s own convention). */
function startOfWeek(date: Date): Date {
  const d = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  d.setDate(d.getDate() - d.getDay());
  return d;
}

function addDays(date: Date, days: number): Date {
  const d = new Date(date);
  d.setDate(d.getDate() + days);
  return d;
}

function formatWeekRangeLabel(weekStart: Date): string {
  const weekEnd = addDays(weekStart, 6);
  return `${WEEK_RANGE_LABEL_FORMATTER.format(weekStart)} – ${WEEK_RANGE_LABEL_FORMATTER.format(weekEnd)}`;
}

/** Minutes since midnight of `dayDate`, clamping a boundary that lands on a different
 *  calendar day (e.g. a segment ending exactly at midnight) to that day's own edge instead of
 *  producing a negative/out-of-range value. */
function minutesOfDay(instant: Date, dayDate: Date, isEnd: boolean): number {
  if (toDateKey(instant) !== toDateKey(dayDate)) {
    return isEnd ? 24 * 60 : 0;
  }
  return instant.getHours() * 60 + instant.getMinutes();
}

interface DayColumnData {
  date: Date;
  dateKey: string;
  weekday: number;
  segments: CalendarSegment[];
}

function buildDays(weekStart: Date, segments: CalendarSegment[]): DayColumnData[] {
  return Array.from({ length: 7 }, (_, weekday) => {
    const date = addDays(weekStart, weekday);
    const dateKey = toDateKey(date);
    const daySegments = segments.filter((segment) => toDateKey(new Date(segment.startAt)) === dateKey);
    return { date, dateKey, weekday, segments: daySegments };
  });
}

function computeVisibleRange(days: DayColumnData[]): { startMinute: number; endMinute: number } {
  let startMinute = DEFAULT_START_HOUR * 60;
  let endMinute = DEFAULT_END_HOUR * 60;
  for (const day of days) {
    for (const segment of day.segments) {
      startMinute = Math.min(startMinute, minutesOfDay(new Date(segment.startAt), day.date, false));
      endMinute = Math.max(endMinute, minutesOfDay(new Date(segment.endAt), day.date, true));
    }
  }
  return { startMinute, endMinute };
}

/**
 * `/pro/availability` — the weekly calendar grid (professional weekly availability calendar
 * feature, M4 view-only pass + M5 click interactions). Consumes `GET
 * /api/availability/calendar?from=&to=` for the currently-visible week (Sunday-Saturday).
 * Every rendered segment is clickable (design §7.3/§35-36): `AVAILABLE` opens
 * `CalendarBlockModal` in create mode, `BLOCKED` opens it in edit mode (with a delete action),
 * `BOOKED` navigates straight to `/orders/{orderId}` and **never** opens any block-editing UI
 * (§15) — see `CalendarWeekView`'s `handleSegmentClick` for the exact branch-before-anything-else
 * routing. Time outside working hours has no segment at all, so it has no click affordance.
 * See `docs/architecture/professional-weekly-calendar-design.md` §7.3/§7.4.
 *
 * Week state lives in the `?week=` URL search param (an ISO date, normalized to that week's
 * Sunday) rather than local-only state, both so a reload/share preserves the visible week and
 * so a booked-block click-through's back navigation can return to this exact week (§43) —
 * `OrderTrackingPage` reads the `returnTo.weekStart` passed via router state on the `BOOKED`
 * navigation above and builds `/pro/availability?week=${weekStart}` from it.
 *
 * **Timezone assumption, stated explicitly**: segment timestamps are bucketed into day
 * columns and formatted using the browser's own local timezone (via `new Date(isoString)` +
 * this codebase's existing `formatDateTime.ts` helpers), not the fixed `Asia/Jerusalem`
 * business timezone the backend derivation uses internally. This matches every other
 * timestamp display already in this app (`SlotForm`/`OrderTrackingPage`/etc.) and is accurate
 * for a user physically in Israel (the expected v1.0 audience) — flagged here rather than
 * silently assumed, since a professional browsing from a different timezone would see the
 * grid shifted relative to their actual working hours.
 */
export function WeeklyCalendarGrid() {
  const [searchParams, setSearchParams] = useSearchParams();
  const weekParam = searchParams.get('week');

  const weekStart = useMemo(() => {
    if (weekParam) {
      const parsed = new Date(weekParam);
      if (!Number.isNaN(parsed.getTime())) {
        return startOfWeek(parsed);
      }
    }
    return startOfWeek(new Date());
  }, [weekParam]);

  const weekStartKey = toDateKey(weekStart);
  const isCurrentWeek = weekStartKey === toDateKey(startOfWeek(new Date()));

  function goToWeek(newWeekStart: Date) {
    const next = new URLSearchParams(searchParams);
    next.set('week', toDateKey(newWeekStart));
    setSearchParams(next);
  }

  return (
    <div className={styles.wrapper}>
      <div className={styles.nav}>
        <button
          type="button"
          className={styles.navButton}
          onClick={() => goToWeek(addDays(weekStart, -7))}
          aria-label="שבוע קודם"
        >
          <ChevronRight size={20} aria-hidden="true" />
        </button>
        <p className={styles.navLabel}>{formatWeekRangeLabel(weekStart)}</p>
        <button
          type="button"
          className={styles.navButton}
          onClick={() => goToWeek(addDays(weekStart, 7))}
          aria-label="שבוע הבא"
        >
          <ChevronLeft size={20} aria-hidden="true" />
        </button>
        {!isCurrentWeek && (
          <Button variant="ghost" onClick={() => goToWeek(startOfWeek(new Date()))}>
            היום
          </Button>
        )}
      </div>

      <Legend />

      <CalendarWeekView key={weekStartKey} weekStart={weekStart} />
    </div>
  );
}

function Legend() {
  return (
    <div className={styles.legend}>
      <span className={styles.legendItem}>
        <span className={`${styles.legendSwatch} ${styles.segmentAvailable}`} aria-hidden="true" />
        זמין
      </span>
      <span className={styles.legendItem}>
        <span className={`${styles.legendSwatch} ${styles.segmentBlocked}`} aria-hidden="true" />
        חסום
      </span>
      <span className={styles.legendItem}>
        <span className={`${styles.legendSwatch} ${styles.segmentBooked}`} aria-hidden="true" />
        תפוס (הזמנה)
      </span>
    </div>
  );
}

function CalendarWeekView({ weekStart }: { weekStart: Date }) {
  const navigate = useNavigate();
  const from = toDateKey(weekStart);
  const to = toDateKey(addDays(weekStart, 7));

  const { data, error, isLoading, refetch } = usePolling<CalendarResponse>(() => getAvailabilityCalendar(from, to), {
    intervalMs: CALENDAR_POLL_INTERVAL_MS,
  });

  const [selectedDayIndex, setSelectedDayIndex] = useState(() => {
    const today = new Date();
    const diffDays = Math.round((startOfWeek(today).getTime() - weekStart.getTime()) / (24 * 60 * 60 * 1000));
    return diffDays === 0 ? today.getDay() : 0;
  });

  const [blockModal, setBlockModal] = useState<BlockModalState | null>(null);

  /**
   * Click-routing (design §7.3/§35-36): branches on `segment.type` **before** any
   * modal/edit code path is reachable — the critical constraint that a `BOOKED` click must
   * never open block-editing UI (§15) is satisfied structurally here, not as a late filter,
   * since the `BOOKED` branch calls `navigate(...)` and returns without ever touching
   * `setBlockModal`.
   */
  function handleSegmentClick(segment: CalendarSegment) {
    if (segment.type === 'BOOKED') {
      // §43: preserve the currently-visible week so the back button on `/orders/:orderId`
      // can return here instead of resetting to the current week.
      navigate(`/orders/${segment.orderId}`, { state: { returnTo: { weekStart: toDateKey(weekStart) } } });
      return;
    }
    if (segment.type === 'BLOCKED') {
      setBlockModal({
        mode: 'edit',
        block: { id: segment.blockId as number, startAt: segment.startAt, endAt: segment.endAt, reason: segment.reason },
      });
      return;
    }
    // AVAILABLE — create mode, pre-filled from the clicked segment's own range (the
    // professional can narrow the two datetime-local inputs inside the modal before saving).
    setBlockModal({ mode: 'create', initialRange: { startAt: segment.startAt, endAt: segment.endAt } });
  }

  if (isLoading && !data) {
    return <p className={styles.statusText}>טוען את היומן…</p>;
  }

  if (!data && error) {
    return (
      <div className={styles.banner} role="alert">
        <p>לא הצלחנו לטעון את לוח הזמינות.</p>
        <p>אפשר לנסות שוב בעוד רגע.</p>
        <Button variant="secondary" onClick={refetch}>
          נסה שוב
        </Button>
      </div>
    );
  }

  if (!data) {
    return <p className={styles.statusText}>{GENERIC_ERROR_MESSAGE}</p>;
  }

  const days = buildDays(weekStart, data.segments);
  const { startMinute, endMinute } = computeVisibleRange(days);
  const totalHeight = (endMinute - startMinute) * PX_PER_MINUTE;

  const gridLines: number[] = [];
  for (let m = Math.ceil(startMinute / GRID_STEP_MINUTES) * GRID_STEP_MINUTES; m <= endMinute; m += GRID_STEP_MINUTES) {
    gridLines.push(m);
  }

  return (
    <div className={styles.calendar}>
      {/* Desktop: 7-column grid. Hidden below the mobile breakpoint (CSS), replaced by the
          single-day view below — design §7.4: "not a horizontally-shrunk 7-column grid." */}
      <div className={styles.desktopGrid}>
        <div className={styles.headerRow}>
          <div className={styles.axisHeaderCell} />
          {days.map((day) => (
            <DayHeader key={day.dateKey} day={day} />
          ))}
        </div>
        <div className={styles.bodyScroll}>
          <div className={styles.body} style={{ height: totalHeight }}>
            <AxisColumn gridLines={gridLines} startMinute={startMinute} height={totalHeight} />
            {days.map((day) => (
              <DayColumn
                key={day.dateKey}
                day={day}
                startMinute={startMinute}
                gridLines={gridLines}
                height={totalHeight}
                onSegmentClick={handleSegmentClick}
              />
            ))}
          </div>
        </div>
      </div>

      {/* Mobile: single-day focused view + day-switcher strip — design §7.4. */}
      <div className={styles.mobileView}>
        <div className={styles.daySwitcher}>
          {days.map((day, index) => (
            <button
              key={day.dateKey}
              type="button"
              className={`${styles.dayChip} ${index === selectedDayIndex ? styles.dayChipActive : ''}`}
              onClick={() => setSelectedDayIndex(index)}
              // The visible label is a single letter now, so the full weekday name moves to the
              // accessible name rather than being lost.
              aria-label={`${WEEKDAY_LABELS[day.weekday]} ${DAY_HEADER_DATE_FORMATTER.format(day.date)}`}
            >
              <span className={styles.dayChipWeekday}>{MOBILE_WEEKDAY_LABELS[day.weekday]}</span>
              <span className={styles.dayChipDate}>{DAY_HEADER_DATE_FORMATTER.format(day.date)}</span>
            </button>
          ))}
        </div>
        <div className={styles.bodyScroll}>
          <div className={styles.mobileBody} style={{ height: totalHeight }}>
            <AxisColumn gridLines={gridLines} startMinute={startMinute} height={totalHeight} />
            <DayColumn
              day={days[selectedDayIndex]}
              startMinute={startMinute}
              gridLines={gridLines}
              height={totalHeight}
              onSegmentClick={handleSegmentClick}
            />
          </div>
        </div>
      </div>

      {blockModal && (
        <CalendarBlockModal
          key={blockModal.mode === 'edit' ? `edit-${blockModal.block.id}` : `create-${blockModal.initialRange.startAt}`}
          isOpen
          onClose={() => setBlockModal(null)}
          block={blockModal.mode === 'edit' ? blockModal.block : null}
          initialRange={blockModal.mode === 'create' ? blockModal.initialRange : null}
          onSaved={() => {
            setBlockModal(null);
            refetch();
          }}
        />
      )}
    </div>
  );
}

function DayHeader({ day }: { day: DayColumnData }) {
  const isToday = toDateKey(new Date()) === day.dateKey;
  return (
    <div className={`${styles.dayHeaderCell} ${isToday ? styles.dayHeaderToday : ''}`}>
      <span className={styles.dayHeaderWeekday}>{WEEKDAY_LABELS[day.weekday]}</span>
      <span className={styles.dayHeaderDate}>{DAY_HEADER_DATE_FORMATTER.format(day.date)}</span>
    </div>
  );
}

function AxisColumn({
  gridLines,
  startMinute,
  height,
}: {
  gridLines: number[];
  startMinute: number;
  height: number;
}) {
  return (
    <div className={styles.axisColumn} style={{ height }}>
      {gridLines.map((minute) => (
        <div
          key={minute}
          className={styles.axisTick}
          style={{ top: (minute - startMinute) * PX_PER_MINUTE }}
        >
          {minute % 60 === 0 && (
            <span className={styles.axisLabel}>{String(Math.floor(minute / 60)).padStart(2, '0')}:00</span>
          )}
        </div>
      ))}
    </div>
  );
}

function DayColumn({
  day,
  startMinute,
  gridLines,
  height,
  onSegmentClick,
}: {
  day: DayColumnData;
  startMinute: number;
  gridLines: number[];
  height: number;
  onSegmentClick: (segment: CalendarSegment) => void;
}) {
  return (
    <div className={styles.dayColumn} style={{ height }}>
      {gridLines.map((minute) => (
        <div key={minute} className={styles.gridLine} style={{ top: (minute - startMinute) * PX_PER_MINUTE }} />
      ))}
      {day.segments.map((segment, index) => (
        <SegmentBlock
          key={`${segment.type}-${segment.startAt}-${index}`}
          segment={segment}
          day={day}
          startMinute={startMinute}
          onSegmentClick={onSegmentClick}
        />
      ))}
    </div>
  );
}

/** Hebrew accessible labels for each clickable segment type — design §35-36's click behavior,
 *  described here for assistive tech since the click affordance itself has no visible "button"
 *  chrome beyond the segment's own fill. */
const SEGMENT_CLICK_LABEL: Record<CalendarSegment['type'], string> = {
  AVAILABLE: 'חסימת זמן פנוי זה',
  BLOCKED: 'עריכת החסימה',
  BOOKED: 'מעבר לפרטי ההזמנה',
};

function SegmentBlock({
  segment,
  day,
  startMinute,
  onSegmentClick,
}: {
  segment: CalendarSegment;
  day: DayColumnData;
  startMinute: number;
  onSegmentClick: (segment: CalendarSegment) => void;
}) {
  const segStart = minutesOfDay(new Date(segment.startAt), day.date, false);
  const segEnd = minutesOfDay(new Date(segment.endAt), day.date, true);
  const top = (segStart - startMinute) * PX_PER_MINUTE;
  const height = Math.max((segEnd - segStart) * PX_PER_MINUTE, 20);
  const timeLabel = `${formatTimeLabel(segment.startAt)}–${formatTimeLabel(segment.endAt)}`;

  // Every segment type is clickable (design §35-36) — `AVAILABLE`/`BLOCKED` open
  // `CalendarBlockModal`, `BOOKED` navigates to the order. Shared interactive-div props
  // (role/tabIndex/keyboard activation) so every branch below gets the same affordance
  // without repeating it three times.
  const interactiveProps = {
    role: 'button' as const,
    tabIndex: 0,
    'aria-label': `${SEGMENT_CLICK_LABEL[segment.type]}, ${timeLabel}`,
    onClick: () => onSegmentClick(segment),
    onKeyDown: (event: KeyboardEvent) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        onSegmentClick(segment);
      }
    },
  };

  if (segment.type === 'AVAILABLE') {
    return (
      <div className={`${styles.segment} ${styles.segmentAvailable}`} style={{ top, height }} {...interactiveProps}>
        <span className={styles.segmentLabel}>
          <CheckCircle2 size={12} aria-hidden="true" />
          זמין
        </span>
        <span className={styles.segmentTime}>{timeLabel}</span>
      </div>
    );
  }

  if (segment.type === 'BLOCKED') {
    return (
      <div className={`${styles.segment} ${styles.segmentBlocked}`} style={{ top, height }} {...interactiveProps}>
        <span className={styles.segmentLabel}>
          <Lock size={12} aria-hidden="true" />
          חסום
        </span>
        <span className={styles.segmentTime}>{timeLabel}</span>
        {segment.reason && <span className={styles.segmentReason}>{segment.reason}</span>}
      </div>
    );
  }

  // BOOKED — sub-labeled by `orderStatus` via the shared `StatusBadge` (reuses its existing
  // color mapping, design §7.3: "do not invent new colors independently"). `COMPLETED` gets
  // its own muted treatment per §20's "consider a distinct completed visual state." Clicking
  // navigates straight to `/orders/{orderId}` (§35-36) — never opens block-editing UI (§15).
  const isCompleted = segment.orderStatus === 'COMPLETED';
  return (
    <div
      className={`${styles.segment} ${styles.segmentBooked} ${isCompleted ? styles.segmentCompleted : ''}`}
      style={{ top, height }}
      {...interactiveProps}
    >
      {segment.orderStatus && <StatusBadge status={segment.orderStatus} />}
      <span className={styles.segmentTime}>{timeLabel}</span>
    </div>
  );
}
