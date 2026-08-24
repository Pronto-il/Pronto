import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { CalendarClock } from 'lucide-react';
import { Modal, Button, Input, TimeField } from '../../shared/components';
import {
  getAvailabilityBlock,
  createAvailabilityBlock,
  updateAvailabilityBlock,
  deleteAvailabilityBlock,
  ApiError,
  GENERIC_ERROR_MESSAGE,
} from '../../shared/api';
import type { BlockResponse } from '../../shared/api';
import styles from './CalendarBlockModal.module.css';

/** Hebrew messages for the two 409 overlap codes `POST`/`PATCH /api/availability/blocks*` can
 *  return (design §4.3/§4.4/§4.7), same "known-error-code-map" convention `SlotForm.tsx`/
 *  `OrderTrackingPage.tsx`'s `ORDER_ACTION_ERROR_MESSAGES` already use. */
const BLOCK_ERROR_MESSAGES: Record<string, string> = {
  BLOCK_OVERLAPS_EXISTING_BLOCK: 'הזמן שנבחר חופף לחסימה קיימת אחרת ביומן שלך.',
  BLOCK_OVERLAPS_BOOKING: 'לא ניתן לחסום את הזמן הזה — יש הזמנה קיימת שחופפת אליו.',
};

/** One `FieldError` entry of the backend's `VALIDATION_ERROR` envelope. */
interface BackendFieldError {
  field?: string;
  message?: string;
}

const DAY_MS = 24 * 60 * 60 * 1000;
const DATE_LABEL_FORMATTER = new Intl.DateTimeFormat('he-IL', { day: 'numeric', month: 'long' });

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** `YYYY-MM-DD` in local time — what `<input type="date">` binds to. */
function toDateValue(isoString: string): string {
  const date = new Date(isoString);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** `HH:mm` in local time — what `TimeField` binds to. */
function toTimeValue(isoString: string): string {
  const date = new Date(isoString);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** The inverse: a local `YYYY-MM-DD` + `HH:mm` pair back into a real `Date`. Returns `null`
 *  when either half is missing or unparsable, so the caller can show one clear field error
 *  instead of sending an `Invalid Date` to the API. */
function toDate(dateValue: string, timeValue: string): Date | null {
  if (!dateValue || !timeValue) {
    return null;
  }
  const [year, month, day] = dateValue.split('-').map(Number);
  const [hour, minute] = timeValue.split(':').map(Number);
  if ([year, month, day, hour, minute].some((part) => !Number.isFinite(part))) {
    return null;
  }
  const date = new Date(year, month - 1, day, hour, minute, 0, 0);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** "3 ימים" / "יומיים" — how many calendar days a multi-day range covers, for the summary line. */
function spannedDays(start: Date, end: Date): number {
  const startDay = new Date(start.getFullYear(), start.getMonth(), start.getDate()).getTime();
  const endDay = new Date(end.getFullYear(), end.getMonth(), end.getDate()).getTime();
  return Math.round((endDay - startDay) / DAY_MS) + 1;
}

export interface CalendarBlockModalProps {
  isOpen: boolean;
  onClose: () => void;
  /**
   * Presence of `block` decides create vs. edit mode (design §7.3): create mode is opened from
   * an `AVAILABLE` segment click with `initialRange` pre-filling the clicked date/time range;
   * edit mode is opened from a `BLOCKED` segment click.
   *
   * In edit mode the passed `startAt`/`endAt` are the **clicked segment's** range, which for a
   * block spanning more than one day is only that day's clipped slice of it (the calendar's
   * segments are derived per day — see `AvailabilityDerivationService`). They're used as an
   * immediate seed so the form is never blank, then replaced by the block's real range as soon
   * as `GET /api/availability/blocks/{id}` answers.
   */
  block?: { id: number; startAt: string; endAt: string; reason: string | null } | null;
  initialRange?: { startAt: string; endAt: string } | null;
  /** Fired after a successful create/update/delete so the parent can refresh the calendar
   *  (refetch, same pattern `AvailabilityPage` already uses for `SlotList`'s `onRefreshNeeded`). */
  onSaved: () => void;
}

/**
 * Create/edit/delete a manual availability block (design §7.3/§13, M5). Reuses the shared
 * `Modal` primitive as-is (auto dialog-on-desktop/sheet-on-mobile, no `variant` prop needed).
 * Edit mode adds a destructive "מחיקת חסימה" action in the footer, calling `DELETE
 * /api/availability/blocks/{blockId}` directly (no confirmation step — same low-stakes,
 * easily-recreated reasoning `SlotList.tsx`'s own slot-delete already uses).
 *
 * **Layout (redesign).** The two ends of the range are now stacked vertically as two labelled
 * groups — "מתאריך ושעה" above "עד תאריך ושעה" — each a date field plus a 24-hour `TimeField`,
 * instead of the previous pair of side-by-side `datetime-local` inputs. Three reasons, all of
 * them things the old form got wrong: a `datetime-local` control renders AM/PM on any browser
 * whose locale isn't Israeli (the 24-hour requirement); two of them side by side inside a
 * mobile bottom sheet left each one about 130px wide, i.e. a truncated, effectively unusable
 * segmented control; and nothing on screen communicated that the two fields form a *range*.
 *
 * **Multi-day ranges** are a first-class case: the two dates are independent, the only rule is
 * that the end instant is after the start one, and a summary line spells the range back out
 * ("‎25.08 08:00 ← 28.08 18:00, 4 ימים"). The backend already stored and derived these
 * correctly (`professional_availability_blocks` is a plain `[start_at, end_at)` range, and the
 * calendar clips it per day); what did not work was editing one, which is why edit mode reloads
 * the block's true range instead of trusting the clicked day-slice.
 */
export function CalendarBlockModal({ isOpen, onClose, block, initialRange, onSaved }: CalendarBlockModalProps) {
  const isEditMode = block != null;
  const seed = block ?? initialRange ?? null;

  const [startDate, setStartDate] = useState(seed ? toDateValue(seed.startAt) : '');
  const [startTime, setStartTime] = useState(seed ? toTimeValue(seed.startAt) : '');
  const [endDate, setEndDate] = useState(seed ? toDateValue(seed.endAt) : '');
  const [endTime, setEndTime] = useState(seed ? toTimeValue(seed.endAt) : '');
  const [reason, setReason] = useState(block?.reason ?? '');
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);

  // Re-seed local state whenever the modal is (re)opened for a different segment — `key`-based
  // remount at the call site (see `WeeklyCalendarGrid.tsx`) is simpler than syncing via effects
  // here, matching this codebase's existing `key={weekStartKey}`-remount convention.

  // Edit mode only: replace the clicked segment's (possibly day-clipped) range with the block's
  // real one. Keyed on the block id alone, and best-effort — a failure leaves the seeded values
  // in place, and the save below is still validated server-side either way.
  const blockId = block?.id;
  useEffect(() => {
    if (blockId == null) {
      return;
    }
    let cancelled = false;
    getAvailabilityBlock(blockId)
      .then((loaded) => {
        if (cancelled) {
          return;
        }
        setStartDate(toDateValue(loaded.startAt));
        setStartTime(toTimeValue(loaded.startAt));
        setEndDate(toDateValue(loaded.endAt));
        setEndTime(toTimeValue(loaded.endAt));
        setReason(loaded.reason ?? '');
      })
      .catch(() => {
        // Non-blocking: the seeded segment range stays, same "best-effort enrichment" pattern
        // `OrderTrackingPage`'s issue fetch uses.
      });
    return () => {
      cancelled = true;
    };
  }, [blockId]);

  function resetAndClose() {
    setFieldError(undefined);
    setBannerError(null);
    onClose();
  }

  const start = toDate(startDate, startTime);
  const end = toDate(endDate, endTime);
  const isValidRange = start !== null && end !== null && end > start;
  const daysSpanned = isValidRange ? spannedDays(start, end) : 0;

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);
    setFieldError(undefined);

    if (!start || !end) {
      setFieldError('יש לבחור תאריך ושעה להתחלה ולסיום.');
      return;
    }
    if (end <= start) {
      setFieldError('מועד הסיום צריך להיות אחרי מועד ההתחלה.');
      return;
    }

    setIsSubmitting(true);
    try {
      const payload = {
        startAt: start.toISOString(),
        endAt: end.toISOString(),
        reason: reason.trim() ? reason.trim() : null,
      };
      const saved: BlockResponse = isEditMode
        ? await updateAvailabilityBlock(block.id, payload)
        : await createAvailabilityBlock(payload);
      void saved;
      onSaved();
      resetAndClose();
    } catch (error) {
      if (error instanceof ApiError && error.code === 'VALIDATION_ERROR') {
        // The backend refuses a `startAt` in the past (`AvailabilityService#validateBlockTimes`)
        // — reachable when editing a block that has already begun. Say which rule was broken
        // instead of the old catch-all "check the dates".
        const details = Array.isArray(error.details) ? (error.details as BackendFieldError[]) : [];
        const startAtRejected = details.some((detail) => detail.field === 'startAt');
        setFieldError(
          startAtRejected
            ? 'לא ניתן לקבוע התחלה שכבר עברה. יש לבחור מועד התחלה מעכשיו והלאה.'
            : 'יש לבדוק את התאריכים והשעות שהוזנו.',
        );
      } else if (error instanceof ApiError && BLOCK_ERROR_MESSAGES[error.code]) {
        setBannerError(BLOCK_ERROR_MESSAGES[error.code]);
      } else {
        setBannerError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleDelete() {
    if (!isEditMode) {
      return;
    }
    setBannerError(null);
    setIsDeleting(true);
    try {
      await deleteAvailabilityBlock(block.id);
      onSaved();
      resetAndClose();
    } catch {
      setBannerError(GENERIC_ERROR_MESSAGE);
    } finally {
      setIsDeleting(false);
    }
  }

  return (
    <Modal isOpen={isOpen} onClose={resetAndClose} title={isEditMode ? 'עריכת חסימה' : 'חסימת זמן'}>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        {bannerError && (
          <div className={styles.banner} role="alert">
            <p>{bannerError}</p>
          </div>
        )}

        <fieldset className={styles.rangeGroup}>
          <legend className={styles.rangeLegend}>מתאריך ושעה</legend>
          <div className={styles.rangeFields}>
            <Input
              label="תאריך"
              type="date"
              value={startDate}
              onChange={(event) => setStartDate(event.target.value)}
              className={styles.rangeField}
              required
            />
            <TimeField
              label="שעה"
              value={startTime}
              onChange={setStartTime}
              className={styles.rangeField}
              required
            />
          </div>
        </fieldset>

        <fieldset className={styles.rangeGroup}>
          <legend className={styles.rangeLegend}>עד תאריך ושעה</legend>
          <div className={styles.rangeFields}>
            <Input
              label="תאריך"
              type="date"
              value={endDate}
              // A multi-day block is expected, not exceptional — the end date is free to be any
              // day at or after the start date, and `min` only keeps the obviously-invalid
              // "ends before it starts" out of the picker.
              min={startDate || undefined}
              onChange={(event) => setEndDate(event.target.value)}
              className={styles.rangeField}
              required
            />
            <TimeField label="שעה" value={endTime} onChange={setEndTime} className={styles.rangeField} required />
          </div>
        </fieldset>

        {isValidRange && (
          <p className={styles.summary}>
            <CalendarClock size={16} aria-hidden="true" />
            <span>
              {DATE_LABEL_FORMATTER.format(start)} {startTime} — {DATE_LABEL_FORMATTER.format(end)} {endTime}
              {daysSpanned > 1 ? ` · ${daysSpanned} ימים` : ''}
            </span>
          </p>
        )}

        {fieldError && (
          <p className={styles.fieldError} role="alert">
            {fieldError}
          </p>
        )}

        <Input
          label="סיבה (אופציונלי)"
          type="text"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          maxLength={255}
          placeholder="לדוגמה: פגישה אישית"
        />

        <div className={styles.actionsRow}>
          <Button type="submit" loading={isSubmitting} disabled={isDeleting}>
            {isEditMode ? 'עדכון חסימה' : 'חסימת הזמן'}
          </Button>
          <Button type="button" variant="secondary" onClick={resetAndClose} disabled={isSubmitting || isDeleting}>
            ביטול
          </Button>
        </div>

        {isEditMode && (
          <Button
            type="button"
            variant="destructive"
            onClick={handleDelete}
            loading={isDeleting}
            disabled={isSubmitting}
            fullWidth
          >
            מחיקת חסימה
          </Button>
        )}
      </form>
    </Modal>
  );
}
