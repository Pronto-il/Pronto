import { useState } from 'react';
import type { FormEvent } from 'react';
import { Button, Input } from '../../shared/components';
import { updateWorkingHours, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { WorkingHoursItem } from '../../shared/api';
import styles from './WorkingHoursForm.module.css';

/** Sunday(0)-first, matching `professional_working_hours.weekday`'s own convention and the
 *  product spec's own example (design §2.1/§7.2). */
const WEEKDAY_LABELS = ['ראשון', 'שני', 'שלישי', 'רביעי', 'חמישי', 'שישי', 'שבת'];

const DEFAULT_START_TIME = '08:00';
const DEFAULT_END_TIME = '18:00';

interface RowState {
  weekday: number;
  enabled: boolean;
  startTime: string;
  endTime: string;
}

export interface WorkingHoursFormProps {
  /** Current values from `GET /api/availability/working-hours` — 0-7 entries (fewer than 7
   *  only before first-time setup completes). */
  workingHours: WorkingHoursItem[];
  onSaved: (workingHours: WorkingHoursItem[]) => void;
  /** Edit-mode only — first-time setup has nothing to revert to, so it has no cancel action. */
  onCancel?: () => void;
}

/** Builds exactly 7 rows (weekday 0-6), seeding any weekday the server hasn't configured yet
 *  with `enabled: false` and a sensible default time range (so toggling a fresh row on
 *  doesn't leave blank time inputs). */
function buildRows(workingHours: WorkingHoursItem[]): RowState[] {
  const byWeekday = new Map(workingHours.map((wh) => [wh.weekday, wh]));
  return Array.from({ length: 7 }, (_, weekday) => {
    const existing = byWeekday.get(weekday);
    return {
      weekday,
      enabled: existing?.enabled ?? false,
      startTime: existing?.startTime ?? DEFAULT_START_TIME,
      endTime: existing?.endTime ?? DEFAULT_END_TIME,
    };
  });
}

/**
 * "שעות עבודה שבועיות" — a 7-row form (one row per weekday, Sunday first), each row: an
 * enable/disable toggle plus start/end time pickers (hidden when disabled). Calls `PUT
 * /api/availability/working-hours` with the full week on save — a full replace, not a partial
 * patch, matching the backend's own "resend the whole editable shape" contract. Used both for
 * first-time setup (an empty/incomplete week, rendered full-page by `WeeklyAvailabilityPage`)
 * and later edits (rendered inline, with a cancel action) — see
 * `docs/architecture/professional-weekly-calendar-design.md` §7.2.
 */
export function WorkingHoursForm({ workingHours, onSaved, onCancel }: WorkingHoursFormProps) {
  const [rows, setRows] = useState<RowState[]>(() => buildRows(workingHours));
  const [fieldErrors, setFieldErrors] = useState<Record<number, string>>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  function updateRow(weekday: number, patch: Partial<RowState>) {
    setRows((prev) => prev.map((row) => (row.weekday === weekday ? { ...row, ...patch } : row)));
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);

    const errors: Record<number, string> = {};
    for (const row of rows) {
      if (!row.enabled) {
        continue;
      }
      if (!row.startTime || !row.endTime) {
        errors[row.weekday] = 'יש להזין שעת התחלה ושעת סיום.';
      } else if (row.endTime <= row.startTime) {
        errors[row.weekday] = 'שעת הסיום צריכה להיות אחרי שעת ההתחלה.';
      }
    }
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) {
      return;
    }

    setIsSubmitting(true);
    try {
      const payload = rows.map((row) => ({
        weekday: row.weekday,
        enabled: row.enabled,
        startTime: row.enabled ? row.startTime : null,
        endTime: row.enabled ? row.endTime : null,
      }));
      const result = await updateWorkingHours(payload);
      onSaved(result.workingHours);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'VALIDATION_ERROR') {
        setBannerError('יש לבדוק את שעות העבודה שהוזנו.');
      } else {
        setBannerError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit} noValidate>
      {bannerError && (
        <div className={styles.banner} role="alert">
          <p>{bannerError}</p>
        </div>
      )}

      <div className={styles.rows}>
        {rows.map((row) => {
          const rowError = fieldErrors[row.weekday];
          return (
            <div key={row.weekday} className={styles.row}>
              <div className={styles.dayCell}>
                <button
                  type="button"
                  role="switch"
                  aria-checked={row.enabled}
                  aria-label={`${WEEKDAY_LABELS[row.weekday]} — ${row.enabled ? 'עובד/ת' : 'לא עובד/ת'}`}
                  className={`${styles.switch} ${row.enabled ? styles.switchOn : ''}`}
                  onClick={() => updateRow(row.weekday, { enabled: !row.enabled })}
                >
                  <span className={styles.knob} aria-hidden="true" />
                </button>
                <span className={styles.dayLabel}>{WEEKDAY_LABELS[row.weekday]}</span>
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

      <div className={styles.actionsRow}>
        <Button type="submit" loading={isSubmitting}>
          שמירת שעות עבודה
        </Button>
        {onCancel && (
          <Button type="button" variant="secondary" onClick={onCancel}>
            ביטול
          </Button>
        )}
      </div>
    </form>
  );
}
