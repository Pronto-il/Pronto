import { Input } from './Input';
import { WEEKDAY_LABELS_HE } from './weeklyHoursTypes';
import type { WeeklyHoursRow } from './weeklyHoursTypes';
import styles from './WeeklyHoursFields.module.css';

export interface WeeklyHoursFieldsProps {
  /** Always 7 rows, weekday 0-6 — build them with `buildWeeklyHoursRows()`. */
  rows: WeeklyHoursRow[];
  onChange: (rows: WeeklyHoursRow[]) => void;
  /** Per-weekday error message, keyed by `weekday` — from `validateWeeklyHoursRows()`. */
  errors?: Record<number, string>;
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
 */
export function WeeklyHoursFields({ rows, onChange, errors }: WeeklyHoursFieldsProps) {
  function updateRow(weekday: number, patch: Partial<WeeklyHoursRow>) {
    onChange(rows.map((row) => (row.weekday === weekday ? { ...row, ...patch } : row)));
  }

  return (
    <div className={styles.rows}>
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
                <Input
                  label="משעה"
                  type="time"
                  value={row.startTime}
                  onChange={(event) => updateRow(row.weekday, { startTime: event.target.value })}
                  error={rowError && !row.startTime ? rowError : undefined}
                />
                <Input
                  label="עד שעה"
                  type="time"
                  value={row.endTime}
                  onChange={(event) => updateRow(row.weekday, { endTime: event.target.value })}
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
