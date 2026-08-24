import { useState } from 'react';
import type { FormEvent } from 'react';
import { Button, WeeklyHoursFields, buildWeeklyHoursRows, validateWeeklyHoursRows, toWeeklyHoursRequest } from '../../shared/components';
import type { WeeklyHoursRow } from '../../shared/components';
import { updateWorkingHours, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { WorkingHoursItem } from '../../shared/api';
import styles from './WorkingHoursForm.module.css';

/** Seeded into a weekday the server hasn't configured, so toggling a fresh row on doesn't
 *  leave two blank time inputs. Edit-surface behavior only — professional *registration*
 *  deliberately starts from a blank week (Playbook MS1: no invented default hours). */
const UNCONFIGURED_TIMES = { startTime: '08:00', endTime: '18:00' };

export interface WorkingHoursFormProps {
  /** Current values from `GET /api/availability/working-hours` — 0-7 entries (fewer than 7
   *  only before first-time setup completes). */
  workingHours: WorkingHoursItem[];
  onSaved: (workingHours: WorkingHoursItem[]) => void;
  /** Edit-mode only — first-time setup has nothing to revert to, so it has no cancel action. */
  onCancel?: () => void;
}

/**
 * "שעות עבודה שבועיות" — the shared `WeeklyHoursFields` 7-row weekday editor plus this
 * surface's own save action. Calls `PUT /api/availability/working-hours` with the full week on
 * save — a full replace, not a partial patch, matching the backend's own "resend the whole
 * editable shape" contract. Used both for first-time setup (an empty/incomplete week, rendered
 * full-page by `WeeklyAvailabilityPage`) and later edits (rendered inline, with a cancel
 * action) — see `docs/architecture/professional-weekly-calendar-design.md` §7.2.
 *
 * **MS1**: the weekday rows, their validation and the request serialization moved into
 * `shared/components/WeeklyHoursFields`/`weeklyHoursTypes` so professional registration
 * collects the identical week through the identical code. Behavior here is unchanged.
 */
export function WorkingHoursForm({ workingHours, onSaved, onCancel }: WorkingHoursFormProps) {
  const [rows, setRows] = useState<WeeklyHoursRow[]>(() =>
    buildWeeklyHoursRows(workingHours, { unconfiguredTimes: UNCONFIGURED_TIMES }),
  );
  const [fieldErrors, setFieldErrors] = useState<Record<number, string>>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);

    const errors = validateWeeklyHoursRows(rows);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) {
      return;
    }

    setIsSubmitting(true);
    try {
      const result = await updateWorkingHours(toWeeklyHoursRequest(rows));
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

      {/* Apply-to-all is enabled on this surface only: an existing professional's week is
          usually the same hours on most days, so entering them seven times was busywork.
          Registration keeps the plain editor (see `WeeklyHoursFields`' prop doc). */}
      <WeeklyHoursFields rows={rows} onChange={setRows} errors={fieldErrors} showApplyToAll />

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
