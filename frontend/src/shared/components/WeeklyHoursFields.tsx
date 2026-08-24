import { useState } from 'react';
import { Button } from './Button';
import { TimeField } from './TimeField';
import { WEEKDAY_LABELS_HE } from './weeklyHoursTypes';
import type { WeeklyHoursRow } from './weeklyHoursTypes';
import styles from './WeeklyHoursFields.module.css';

/** Seed for the apply-to-all pair, matching `WorkingHoursForm`'s own `UNCONFIGURED_TIMES`. */
const APPLY_ALL_DEFAULTS = { startTime: '08:00', endTime: '17:00' };

export interface WeeklyHoursFieldsProps {
  /** Always 7 rows, weekday 0-6 — build them with `buildWeeklyHoursRows()`. */
  rows: WeeklyHoursRow[];
  onChange: (rows: WeeklyHoursRow[]) => void;
  /** Per-weekday error message, keyed by `weekday` — from `validateWeeklyHoursRows()`. */
  errors?: Record<number, string>;
  /** Renders the "החל על הכל" bulk-set row above the weekdays. Off by default so registration
   *  keeps its current, deliberately blank-slate week (see `BuildWeeklyHoursRowsOptions`). */
  showApplyToAll?: boolean;
}

/**
 * The 7-row weekday editor (one row per weekday, Sunday first): an enable/disable switch plus
 * start/end time pickers, hidden while the day is off. Presentational and self-contained — it
 * owns no API call, no submit button and no validation trigger, so both consumers can wrap it
 * in whatever their surface needs.
 *
 * Extracted in MS1 from `features/dashboard/WorkingHoursForm.tsx` (markup and styles moved
 * verbatim) once professional registration had to collect the same week: the alternative was a
 * second copy of the weekday/time logic, and the registration copy would have been the one to
 * drift from `WorkingHoursItemRequest`. Lives here rather than in `features/dashboard` for the
 * same reason `AddressFormFields` does — a domain field group with two unrelated consumers.
 *
 * Overnight ranges are deliberately not expressible: `ck_professional_working_hours_times`
 * requires `end_time > start_time`, so a UI implying 22:00→02:00 would be offering something
 * the database refuses.
 *
 * Times are entered through `TimeField` (24-hour `HH:mm`, two selects) rather than
 * `<input type="time">`, whose AM/PM segment on a non-Israeli browser locale was the actual
 * source of 12-hour entry in this app — see that component's doc.
 *
 * `showApplyToAll` adds a "החל על הכל" row above the week: one start/end pair pushed onto every
 * relevant day at once, after which each day stays independently editable (this only writes
 * the rows' values; it changes nothing else about how the week is edited or saved).
 */
export function WeeklyHoursFields({ rows, onChange, errors, showApplyToAll = false }: WeeklyHoursFieldsProps) {
  const [bulkStart, setBulkStart] = useState(APPLY_ALL_DEFAULTS.startTime);
  const [bulkEnd, setBulkEnd] = useState(APPLY_ALL_DEFAULTS.endTime);
  const [bulkError, setBulkError] = useState<string | undefined>();
  const [appliedCount, setAppliedCount] = useState<number | null>(null);

  function updateRow(weekday: number, patch: Partial<WeeklyHoursRow>) {
    setAppliedCount(null);
    onChange(rows.map((row) => (row.weekday === weekday ? { ...row, ...patch } : row)));
  }

  /**
   * "Relevant working days" = the days that are currently on. A professional who has already
   * said "I don't work Saturday" must not have Saturday switched on by a bulk time change.
   * The one exception is a week with nothing on at all (a not-yet-configured professional):
   * there, applying to zero days would make the button look broken, so it enables the whole
   * week — which the professional can then switch days off from, one by one.
   */
  function handleApplyToAll() {
    if (!bulkStart || !bulkEnd) {
      setBulkError('יש לבחור שעת התחלה ושעת סיום.');
      return;
    }
    if (bulkEnd <= bulkStart) {
      setBulkError('שעת הסיום צריכה להיות אחרי שעת ההתחלה.');
      return;
    }
    setBulkError(undefined);

    const anyEnabled = rows.some((row) => row.enabled);
    const next = rows.map((row) =>
      anyEnabled && !row.enabled
        ? row
        : { ...row, enabled: true, startTime: bulkStart, endTime: bulkEnd },
    );
    setAppliedCount(next.filter((row) => row.enabled).length);
    onChange(next);
  }

  return (
    <div className={styles.rows}>
      {showApplyToAll && (
        <div className={styles.applyAll}>
          <p className={styles.applyAllTitle}>שעות קבועות לכל הימים</p>
          <div className={styles.applyAllControls}>
            <TimeField
              label="משעה"
              value={bulkStart}
              onChange={(value) => {
                setBulkStart(value);
                setAppliedCount(null);
              }}
              className={styles.applyAllField}
            />
            <TimeField
              label="עד שעה"
              value={bulkEnd}
              onChange={(value) => {
                setBulkEnd(value);
                setAppliedCount(null);
              }}
              className={styles.applyAllField}
            />
            <Button type="button" variant="secondary" onClick={handleApplyToAll} className={styles.applyAllButton}>
              החל על הכל
            </Button>
          </div>
          {bulkError ? (
            <p className={styles.applyAllError} role="alert">
              {bulkError}
            </p>
          ) : appliedCount !== null ? (
            <p className={styles.applyAllNotice} role="status">
              השעות הוחלו על {appliedCount} ימים. אפשר לשנות כל יום בנפרד למטה.
            </p>
          ) : (
            <p className={styles.applyAllHint}>
              קובעים שעות פעם אחת ומחילים על ימי העבודה. אפשר לערוך כל יום בנפרד אחר כך.
            </p>
          )}
        </div>
      )}

      {rows.map((row) => {
        const rowError = errors?.[row.weekday];
        return (
          <div key={row.weekday} className={styles.row}>
            <div className={styles.dayCell}>
              <button
                type="button"
                role="switch"
                aria-checked={row.enabled}
                aria-label={`${WEEKDAY_LABELS_HE[row.weekday]} — ${row.enabled ? 'עובד/ת' : 'לא עובד/ת'}`}
                className={`${styles.switch} ${row.enabled ? styles.switchOn : ''}`}
                onClick={() => updateRow(row.weekday, { enabled: !row.enabled })}
              >
                <span className={styles.knob} aria-hidden="true" />
              </button>
              <span className={styles.dayLabel}>{WEEKDAY_LABELS_HE[row.weekday]}</span>
            </div>

            {row.enabled ? (
              <div className={styles.timesCell}>
                <TimeField
                  label="משעה"
                  value={row.startTime}
                  onChange={(value) => updateRow(row.weekday, { startTime: value })}
                  error={rowError && !row.startTime ? rowError : undefined}
                />
                <TimeField
                  label="עד שעה"
                  value={row.endTime}
                  onChange={(value) => updateRow(row.weekday, { endTime: value })}
                  error={rowError && row.startTime ? rowError : undefined}
                />
              </div>
            ) : (
              <span className={styles.notWorking}>לא עובד/ת</span>
            )}
          </div>
        );
      })}
    </div>
  );
}
