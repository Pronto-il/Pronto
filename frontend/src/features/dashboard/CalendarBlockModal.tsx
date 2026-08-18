import { useState } from 'react';
import type { FormEvent } from 'react';
import { Modal, Button, Input } from '../../shared/components';
import {
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

/** `YYYY-MM-DDTHH:mm` — the inverse of `new Date(value).toISOString()`, same helper
 *  `SlotForm.tsx` already established for pre-filling a `datetime-local` input from an ISO
 *  string. */
function toDateTimeLocalValue(isoString: string): string {
  const date = new Date(isoString);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export interface CalendarBlockModalProps {
  isOpen: boolean;
  onClose: () => void;
  /**
   * Presence of `block` decides create vs. edit mode (design §7.3): create mode is opened from
   * an `AVAILABLE` segment click with `initialRange` pre-filling the clicked date/time range;
   * edit mode is opened from a `BLOCKED` segment click, pre-filled straight from that segment's
   * `blockId`/`startAt`/`endAt`/`reason` — no extra `GET` needed, the already-fetched calendar
   * response carries everything this modal needs.
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
 * Two `datetime-local` inputs + an optional short `reason` text field, mirroring
 * `SlotForm.tsx`'s exact input/validation shape for the closest existing precedent in this
 * codebase. Edit mode adds a destructive "מחיקת חסימה" action in the footer, calling `DELETE
 * /api/availability/blocks/{blockId}` directly (no confirmation step — same low-stakes,
 * easily-recreated reasoning `SlotList.tsx`'s own slot-delete already uses).
 */
export function CalendarBlockModal({ isOpen, onClose, block, initialRange, onSaved }: CalendarBlockModalProps) {
  const isEditMode = block != null;
  const seed = block ?? initialRange ?? null;

  const [startAt, setStartAt] = useState(seed ? toDateTimeLocalValue(seed.startAt) : '');
  const [endAt, setEndAt] = useState(seed ? toDateTimeLocalValue(seed.endAt) : '');
  const [reason, setReason] = useState(block?.reason ?? '');
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);

  // Re-seed local state whenever the modal is (re)opened for a different segment — `key`-based
  // remount at the call site (see `WeeklyCalendarGrid.tsx`) is simpler than syncing via effects
  // here, matching this codebase's existing `key={weekStartKey}`-remount convention.

  function resetAndClose() {
    setFieldError(undefined);
    setBannerError(null);
    onClose();
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);
    setFieldError(undefined);

    if (!startAt || !endAt) {
      setFieldError('יש למלא תאריך ושעת התחלה וסיום.');
      return;
    }
    const start = new Date(startAt);
    const end = new Date(endAt);
    if (end <= start) {
      setFieldError('שעת הסיום צריכה להיות אחרי שעת ההתחלה.');
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
        setFieldError('יש לבדוק את התאריכים שהוזנו.');
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

        <div className={styles.fields}>
          <Input
            label="התחלה"
            type="datetime-local"
            value={startAt}
            onChange={(event) => setStartAt(event.target.value)}
            error={fieldError && !endAt ? fieldError : undefined}
            required
          />
          <Input
            label="סיום"
            type="datetime-local"
            value={endAt}
            onChange={(event) => setEndAt(event.target.value)}
            error={fieldError && endAt ? fieldError : undefined}
            required
          />
        </div>

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
