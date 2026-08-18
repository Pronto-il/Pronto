import { useState } from 'react';
import type { FormEvent } from 'react';
import { Input, Button } from '../../shared/components';
import { createAvailabilitySlot, updateAvailabilitySlot, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { SlotResponse, SlotListItem } from '../../shared/api';
import styles from './SlotForm.module.css';

export interface SlotFormProps {
  /** Present in edit mode (pre-fills startTime/endTime from this slot and calls
   *  `updateAvailabilitySlot(slot.id, ...)` on submit instead of `createAvailabilitySlot`).
   *  Absent in create mode (current/default behavior, unchanged). */
  slot?: SlotListItem;
  /** Renamed from `onCreated` — fires on a successful create OR update with the resulting
   *  `SlotResponse`. */
  onSaved: (slot: SlotResponse) => void;
  /** Edit-mode only: fired by a "ביטול" button to exit edit mode without saving. Create
   *  mode does not render a cancel button. Required when `slot` is provided; unused
   *  otherwise. */
  onCancel?: () => void;
  /** Edit-mode only: fired when the slot was booked between render and submit (`SLOT_IN_USE`),
   *  carrying the Hebrew error message so the owning `SlotList` can show it via its own
   *  persistent banner — this row collapses back to its read-only display in the same render
   *  as this call (via `onConflict`'s handler calling `setEditingSlotId(null)`), which unmounts
   *  this form before it could ever paint an in-form banner, so the message must travel up
   *  rather than be shown locally. Unused in create mode — `SLOT_IN_USE` can't happen before
   *  the slot exists. */
  onConflict?: (message: string) => void;
}

/** `YYYY-MM-DDTHH:mm` — the inverse of the `new Date(value).toISOString()` conversion used
 *  on submit below, so an existing slot's ISO timestamps can pre-fill a `datetime-local`
 *  input in edit mode. */
function toDateTimeLocalValue(isoString: string): string {
  const date = new Date(isoString);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * "הוספת זמן פנוי" (create mode) / inline row editor (edit mode, `slot` prop present) — two
 * `datetime-local` inputs + submit → `POST /api/availability/slots` or
 * `PUT /api/availability/slots/{slotId}`. `datetime-local` values have no timezone of their
 * own; `new Date(value)` interprets them in the browser's local timezone, which is the
 * professional's own timezone — the same assumption `Date#toISOString()` then encodes as the
 * UTC-offset ISO string the backend expects.
 */
export function SlotForm({ slot, onSaved, onCancel, onConflict }: SlotFormProps) {
  const isEditMode = slot !== undefined;
  const [startTime, setStartTime] = useState(slot ? toDateTimeLocalValue(slot.startTime) : '');
  const [endTime, setEndTime] = useState(slot ? toDateTimeLocalValue(slot.endTime) : '');
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);
    setFieldError(undefined);

    if (!startTime || !endTime) {
      setFieldError('יש למלא תאריך ושעת התחלה וסיום.');
      return;
    }
    const start = new Date(startTime);
    const end = new Date(endTime);
    if (end <= start) {
      setFieldError('שעת הסיום צריכה להיות אחרי שעת ההתחלה.');
      return;
    }

    setIsSubmitting(true);
    try {
      const payload = { startTime: start.toISOString(), endTime: end.toISOString() };
      const saved = isEditMode ? await updateAvailabilitySlot(slot.id, payload) : await createAvailabilitySlot(payload);
      onSaved(saved);
      if (!isEditMode) {
        setStartTime('');
        setEndTime('');
      }
    } catch (error) {
      if (error instanceof ApiError && error.code === 'VALIDATION_ERROR') {
        setFieldError('יש לבדוק את התאריכים שהוזנו.');
      } else if (isEditMode && error instanceof ApiError && error.code === 'SLOT_IN_USE') {
        // Not setting local bannerError here: the parent's onConflict handler collapses this
        // row (setEditingSlotId(null)) in the same render pass under React 18 batching, so
        // this form unmounts before an in-form banner could ever paint. The message travels up
        // to SlotList's own banner instead, which survives the collapse.
        onConflict?.('לא ניתן לעדכן את הזמן — הוא כבר משויך להזמנה קיימת.');
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
      <div className={styles.fields}>
        <Input
          label="התחלה"
          type="datetime-local"
          value={startTime}
          onChange={(event) => setStartTime(event.target.value)}
          error={fieldError && !endTime ? fieldError : undefined}
          required
        />
        <Input
          label="סיום"
          type="datetime-local"
          value={endTime}
          onChange={(event) => setEndTime(event.target.value)}
          error={fieldError && endTime ? fieldError : undefined}
          required
        />
      </div>
      <div className={styles.actionsRow}>
        <Button type="submit" loading={isSubmitting}>
          {isEditMode ? 'עדכון' : 'הוספת זמן פנוי'}
        </Button>
        {isEditMode && (
          <Button type="button" variant="secondary" onClick={onCancel}>
            ביטול
          </Button>
        )}
      </div>
    </form>
  );
}
