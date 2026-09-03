import { useEffect, useMemo, useRef, useState } from 'react';
import type { AvailableWindow } from '../../shared/api';
import { EmptyState, Skeleton } from '../../shared/components';
import { deriveStartTimeCandidates } from '../../shared/utils/availability';
import { formatDateLabel, formatTimeLabel, dateKey } from '../../shared/utils/formatDateTime';
import styles from './StartTimePicker.module.css';

export interface StartTimePickerProps {
  windows: AvailableWindow[];
  /** Echoed from `GET .../available-windows`'s response — never hardcoded client-side. */
  defaultDurationMinutes: number;
  /**
   * The first start time a standard booking may take, from the same response. Chips before it are
   * rendered **disabled rather than hidden** — the professional genuinely is free then, and the
   * screen must not imply otherwise; see `earliestBookableAt` in `shared/api/bookings.ts`.
   *
   * Optional so the component still renders if a caller has not fetched it yet; when absent, no chip
   * is restricted and the backend remains the only gate (which it is regardless).
   */
  earliestBookableAt?: string | null;
  /** The rule, in minutes, for the explanatory line. Also from the server. */
  minLeadMinutes?: number;
  /**
   * Enters the existing SOS flow. Rendered as "צריך מישהו מוקדם יותר?" beside the disabled chips —
   * the honest alternative for a customer who needs somebody sooner than a standard booking allows.
   * Omitted, the explanation is still shown without a call to action.
   */
  onTrySos?: () => void;
  selectedStart: string | null;
  onSelect: (bookedStart: string) => void;
  isLoading?: boolean;
  /**
   * The selected start time dropped out of the candidate list because it slipped into the
   * past while the customer sat on this screen (see `CLOCK_TICK_MS`). The parent owns
   * `selectedStart`, so only it can clear the selection and explain why — this component
   * just reports the fact. Fired at most once per selected value.
   */
  onSelectedExpired?: () => void;
}

/**
 * How often the derived chips are re-evaluated against the wall clock. A customer who opens
 * this step and then takes a phone call must not be left holding a chip that has since
 * slipped into the past — the server rejects a non-future `bookedStart` outright, so a stale
 * chip is an unbookable chip (MS4 final corrections, item 1). 30s is well under the 30-minute
 * chip grid, so a chip never lingers meaningfully past its own start time.
 */
const CLOCK_TICK_MS = 30_000;

/** Time-of-day sections (`toHour` exclusive), so a day's chips scan as a short list per part
 *  of the day instead of one undifferentiated 20-chip grid — DESIGN_SYSTEM.md §46's
 *  "avoid desktop-style calendar complexity" applied to the time axis. */
const PERIODS: { key: string; label: string; fromHour: number; toHour: number }[] = [
  { key: 'morning', label: 'בוקר', fromHour: 0, toHour: 12 },
  { key: 'noon', label: 'צהריים', fromHour: 12, toHour: 17 },
  { key: 'evening', label: 'ערב', fromHour: 17, toHour: 24 },
];

/**
 * "מתי נוח לך?" — renamed from `SlotPicker.tsx`, professional weekly availability calendar
 * feature M6 (`docs/architecture/professional-weekly-calendar-design.md` §9.2.3/§7.6). The
 * date-chip-row + time-chip-grid UI follows DESIGN_SYSTEM.md §46-47; the source of the chips
 * is start-time candidates derived client-side (`deriveStartTimeCandidates`) from the
 * professional's derived `AVAILABLE` windows (`GET .../available-windows?issueId=`). No
 * candidate is ever shown as "unavailable" — the derivation only emits start times that fit
 * a full job before the window closes — so every chip here is clickable.
 *
 * **MS4 final corrections (2026-08-20)**: chips are now clock-aligned (`15:00`, not `14:32`),
 * grouped by part of day, headed by the step's own question + the real visit duration, and
 * re-derived against a live clock so a start time that has slipped into the past disappears
 * instead of failing at confirmation time.
 */
export function StartTimePicker({
  windows,
  defaultDurationMinutes,
  earliestBookableAt,
  minLeadMinutes,
  onTrySos,
  selectedStart,
  onSelect,
  isLoading,
  onSelectedExpired,
}: StartTimePickerProps) {
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNowMs(Date.now()), CLOCK_TICK_MS);
    return () => clearInterval(timer);
  }, []);

  const candidates = useMemo(
    () => deriveStartTimeCandidates(windows, defaultDurationMinutes, { notBeforeMs: nowMs }),
    [windows, defaultDurationMinutes, nowMs],
  );

  // The lead-time boundary, as epoch ms. Parsed once rather than inside the render loop, and
  // deliberately NOT advanced by the local clock tick above: the server computed it from its own
  // clock when the windows were fetched, and it is the server that will re-check it at the commit.
  // Sliding it forward here would grey out chips the backend would still accept.
  const earliestBookableMs = useMemo(
    () => (earliestBookableAt ? new Date(earliestBookableAt).getTime() : null),
    [earliestBookableAt],
  );

  /** A chip inside the lead-time window: visible, explained, and not clickable. */
  function isTooSoon(candidate: string): boolean {
    return earliestBookableMs !== null && new Date(candidate).getTime() < earliestBookableMs;
  }

  // Report an expired selection once per value — `onSelectedExpired` is typically an inline
  // arrow in the parent (new identity every render), so without this ref the effect would
  // re-fire on every render until the parent's state settled.
  const reportedExpiryFor = useRef<string | null>(null);
  useEffect(() => {
    if (isLoading || !selectedStart) {
      return;
    }
    if (candidates.includes(selectedStart)) {
      reportedExpiryFor.current = null;
      return;
    }
    if (reportedExpiryFor.current !== selectedStart) {
      reportedExpiryFor.current = selectedStart;
      onSelectedExpired?.();
    }
  }, [candidates, isLoading, onSelectedExpired, selectedStart]);

  const groups = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const candidate of candidates) {
      const key = dateKey(candidate);
      if (!map.has(key)) {
        map.set(key, []);
      }
      map.get(key)!.push(candidate);
    }
    return Array.from(map.entries()).map(([key, dayStarts]) => ({
      key,
      label: formatDateLabel(dayStarts[0]),
      starts: dayStarts,
    }));
  }, [candidates]);

  const [selectedDateKey, setSelectedDateKey] = useState<string | null>(groups[0]?.key ?? null);
  const activeDateKey = selectedDateKey && groups.some((g) => g.key === selectedDateKey) ? selectedDateKey : groups[0]?.key ?? null;
  const activeGroup = groups.find((group) => group.key === activeDateKey);

  const periodSections = useMemo(() => {
    if (!activeGroup) {
      return [];
    }
    return PERIODS.map((period) => ({
      ...period,
      starts: activeGroup.starts.filter((start) => {
        const hour = new Date(start).getHours();
        return hour >= period.fromHour && hour < period.toHour;
      }),
    })).filter((period) => period.starts.length > 0);
  }, [activeGroup]);

  // Only explain the rule when it is actually restricting something the customer can see. On a
  // calendar whose first opening is tomorrow, the notice would be answering a question nobody asked.
  const hasTooSoonChips = candidates.some(isTooSoon);

  /** "שעתיים וחצי" for the default 150; a plain minute count for any other configured value. */
  const leadNoticeLabel = (() => {
    if (minLeadMinutes === undefined || minLeadMinutes === null) {
      return 'זמן מה';
    }
    if (minLeadMinutes === 150) {
      return 'שעתיים וחצי';
    }
    if (minLeadMinutes % 60 === 0) {
      const hours = minLeadMinutes / 60;
      return hours === 1 ? 'שעה' : hours === 2 ? 'שעתיים' : `${hours} שעות`;
    }
    return `${minLeadMinutes} דקות`;
  })();

  if (isLoading) {
    return <Skeleton variant="rect" className={styles.skeleton} />;
  }

  if (groups.length === 0) {
    return (
      <EmptyState
        title="אין זמנים פנויים כרגע"
        description="לבעל המקצוע הזה אין תורים פתוחים כרגע. אפשר לחזור אחורה ולבחור בעל מקצוע אחר."
      />
    );
  }

  return (
    <div className={styles.wrapper}>
      <div className={styles.intro}>
        <h2 className={styles.question}>מתי נוח לך?</h2>
        <p className={styles.durationNote}>משך הביקור המשוער: {defaultDurationMinutes} דקות</p>
      </div>

      {hasTooSoonChips && (
        // The wording is load-bearing. It says the SLOT is not open for a standard booking, never
        // that the professional is busy -- they are not, and their calendar on this very screen
        // says so. Claiming otherwise would be a lie the customer can see through.
        <div className={styles.leadNotice}>
          <p className={styles.leadNoticeBody}>
            הזמנה רגילה נסגרת {leadNoticeLabel} מראש, כדי שלבעל המקצוע יהיה זמן להגיע אליך.
            המועדים המוקדמים יותר מוצגים באפור.
          </p>
          {onTrySos && (
            <div className={styles.leadNoticeCta}>
              <span className={styles.leadNoticeCtaText}>צריך מישהו מוקדם יותר?</span>
              <button type="button" className={styles.sosLink} onClick={onTrySos}>
                נסו SOS
              </button>
            </div>
          )}
        </div>
      )}

      <div className={styles.dateRow} role="tablist" aria-label="בחירת יום">
        {groups.map((group) => (
          <button
            key={group.key}
            type="button"
            role="tab"
            aria-selected={activeDateKey === group.key}
            className={`${styles.dateChip} ${activeDateKey === group.key ? styles.dateChipActive : ''}`}
            onClick={() => setSelectedDateKey(group.key)}
          >
            {group.label}
          </button>
        ))}
      </div>

      <div className={styles.periods}>
        {periodSections.map((period) => (
          <div key={period.key} className={styles.period}>
            <p className={styles.periodLabel}>{period.label}</p>
            <div className={styles.timeGrid}>
              {period.starts.map((start) => {
                const tooSoon = isTooSoon(start);
                return (
                  <button
                    key={start}
                    type="button"
                    disabled={tooSoon}
                    aria-pressed={!tooSoon && selectedStart === start}
                    // Spelled out for a screen reader, which cannot see the muted styling and
                    // otherwise hears only a disabled button with no reason.
                    aria-label={tooSoon ? `${formatTimeLabel(start)} — לא זמין להזמנה רגילה` : undefined}
                    className={[
                      styles.timeChip,
                      tooSoon ? styles.timeChipTooSoon : '',
                      !tooSoon && selectedStart === start ? styles.timeChipSelected : '',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                    onClick={() => onSelect(start)}
                  >
                    {formatTimeLabel(start)}
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
