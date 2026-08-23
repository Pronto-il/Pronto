/**
 * Form-shaped weekly working hours, shared by every surface that edits them: the professional
 * dashboard's `WorkingHoursForm` (`PUT /api/availability/working-hours`) and, since MS1,
 * professional registration (`POST /api/auth/register`'s `professional.workingHours`). Both
 * send the identical backend record (`availability.dto.WorkingHoursItemRequest`), so the
 * row/validation/serialization logic lives here once rather than being reimplemented per
 * screen — same split `addressTypes.ts` already applies to `AddressFormFields`.
 *
 * Kept free of any dependency on the api layer (`addressTypes.ts`'s own rule): the request
 * shape below is declared structurally, so `toWeeklyHoursRequest()`'s result is assignable to
 * `shared/api`'s `WorkingHoursItemRequest[]` without this module importing it.
 */

/** Sunday(0)-first, matching `professional_working_hours.weekday`'s own convention. */
export const WEEKDAY_LABELS_HE = ['ראשון', 'שני', 'שלישי', 'רביעי', 'חמישי', 'שישי', 'שבת'];

/**
 * One weekday's editable state. `startTime`/`endTime` are `"HH:mm"` strings, or `''` when the
 * professional hasn't chosen them yet — never `null`, since a controlled `<input type="time">`
 * needs a string.
 */
export interface WeeklyHoursRow {
  /** `0` (Sunday) through `6` (Saturday). */
  weekday: number;
  enabled: boolean;
  startTime: string;
  endTime: string;
}

/**
 * Structural mirror of `availability.dto.WorkingHoursItemRequest` — `startTime`/`endTime` are
 * `null` (not `''`) on a disabled day, which is what `ck_professional_working_hours_times`
 * requires.
 */
export interface WeeklyHoursRequestItem {
  weekday: number;
  enabled: boolean;
  startTime: string | null;
  endTime: string | null;
}

/** The already-saved shape (`GET /api/availability/working-hours`), typed structurally. */
export interface SavedWeeklyHoursItem {
  weekday: number;
  enabled: boolean;
  startTime: string | null;
  endTime: string | null;
}

export interface BuildWeeklyHoursRowsOptions {
  /**
   * Times to seed into a weekday the server hasn't configured, so toggling an existing
   * professional's fresh row on doesn't leave two blank time inputs. Deliberately **omitted at
   * registration** (Playbook MS1: "do not invent default working hours") — a registrant's week
   * starts fully blank and every enabled day must be filled in explicitly.
   */
  unconfiguredTimes?: { startTime: string; endTime: string };
}

/** Exactly 7 rows (weekday 0-6), whatever the server returned. */
export function buildWeeklyHoursRows(
  saved: SavedWeeklyHoursItem[],
  options: BuildWeeklyHoursRowsOptions = {},
): WeeklyHoursRow[] {
  const byWeekday = new Map(saved.map((item) => [item.weekday, item]));
  return Array.from({ length: 7 }, (_, weekday) => {
    const existing = byWeekday.get(weekday);
    return {
      weekday,
      enabled: existing?.enabled ?? false,
      startTime: existing?.startTime ?? options.unconfiguredTimes?.startTime ?? '',
      endTime: existing?.endTime ?? options.unconfiguredTimes?.endTime ?? '',
    };
  });
}

/**
 * Client-side mirror of `availability.service.WorkingHoursValidator#validateWeek`'s per-day
 * rules — times required on an enabled day, `endTime > startTime`. UX only: the backend stays
 * authoritative and re-runs the identical checks (an overnight range is also refused by
 * `ck_professional_working_hours_times`, which is why the editor offers no way to express one).
 *
 * @returns a `{ weekday: hebrewMessage }` map — empty when every enabled day is valid
 */
export function validateWeeklyHoursRows(rows: WeeklyHoursRow[]): Record<number, string> {
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
  return errors;
}

/** `WorkingHoursValidator#requireAtLeastOneEnabledDay`'s client-side mirror (registration only). */
export function hasEnabledWeekday(rows: WeeklyHoursRow[]): boolean {
  return rows.some((row) => row.enabled);
}

/** The full week, in `PUT`/register request shape — always 7 entries, never a partial patch. */
export function toWeeklyHoursRequest(rows: WeeklyHoursRow[]): WeeklyHoursRequestItem[] {
  return rows.map((row) => ({
    weekday: row.weekday,
    enabled: row.enabled,
    startTime: row.enabled ? row.startTime : null,
    endTime: row.enabled ? row.endTime : null,
  }));
}
