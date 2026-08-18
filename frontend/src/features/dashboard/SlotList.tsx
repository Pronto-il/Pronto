import { useState } from 'react';
import { Pencil, Trash2 } from 'lucide-react';
import { deleteAvailabilitySlot, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { SlotListItem, SlotResponse } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import { SlotForm } from './SlotForm';
import styles from './SlotList.module.css';

export interface SlotListProps {
  slots: SlotListItem[];
  /** Fired after a successful edit or delete, so the parent (`AvailabilityPage`) can update
   *  its `slots` state. Edit passes the updated `SlotResponse`-shaped slot; delete passes the
   *  deleted slot's id. `SlotList` stays a controlled/presentational component — it doesn't
   *  hold its own copy of the list. */
  onSlotUpdated: (slot: SlotResponse) => void;
  onSlotDeleted: (slotId: number) => void;
  /** Fired after a `SLOT_IN_USE` (409) race-condition error from an edit or delete attempt
   *  (the slot was booked between render and click), so the parent can re-fetch
   *  `getMyAvailabilitySlots()` and correct the affected row's `isAvailable` state. */
  onRefreshNeeded: () => void;
}

/**
 * The professional's own slot list. A slot with `isAvailable === false` ("תפוס") shows only
 * the time range and badge — no edit/delete controls, since editing/deleting a booked slot is
 * a guaranteed-fail round trip (offering controls that always 409 implies a capability that
 * doesn't exist). A slot with `isAvailable === true` gets edit (pencil) and delete (trash)
 * icon buttons; edit swaps that row's static display for an inline `SlotForm` in edit mode.
 * Delete has no confirmation step (low-stakes, easily-recreated) but a booked slot can still
 * race a concurrent order between render and click, so `SLOT_IN_USE` is handled explicitly as
 * defense-in-depth on both paths (see `frontend-ms9-gap-fixes-design.md` §1b).
 */
export function SlotList({ slots, onSlotUpdated, onSlotDeleted, onRefreshNeeded }: SlotListProps) {
  const [editingSlotId, setEditingSlotId] = useState<number | null>(null);
  const [deletingSlotId, setDeletingSlotId] = useState<number | null>(null);
  const [bannerError, setBannerError] = useState<string | null>(null);

  if (slots.length === 0) {
    return <p className={styles.empty}>עוד לא נוספו זמנים פנויים.</p>;
  }

  async function handleDelete(slotId: number) {
    setBannerError(null);
    setDeletingSlotId(slotId);
    try {
      await deleteAvailabilitySlot(slotId);
      onSlotDeleted(slotId);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'SLOT_IN_USE') {
        setBannerError('לא ניתן למחוק את הזמן — הוא כבר משויך להזמנה קיימת.');
        onRefreshNeeded();
      } else {
        setBannerError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setDeletingSlotId(null);
    }
  }

  return (
    <div className={styles.list}>
      {bannerError && (
        <div className={styles.banner} role="alert">
          <p>{bannerError}</p>
        </div>
      )}
      {slots.map((slot) => {
        if (editingSlotId === slot.id) {
          return (
            <div key={slot.id} className={styles.editingRow}>
              <SlotForm
                slot={slot}
                onSaved={(updated) => {
                  onSlotUpdated(updated);
                  setEditingSlotId(null);
                }}
                onCancel={() => setEditingSlotId(null)}
                onConflict={(message) => {
                  setBannerError(message);
                  onRefreshNeeded();
                  setEditingSlotId(null);
                }}
              />
            </div>
          );
        }

        return (
          <div key={slot.id} className={styles.row}>
            <span className={styles.time}>
              {formatDateLabel(slot.startTime)}, {formatTimeLabel(slot.startTime)}–{formatTimeLabel(slot.endTime)}
            </span>
            <div className={styles.rowEnd}>
              <span className={`${styles.badge} ${slot.isAvailable ? styles.available : styles.booked}`}>
                {slot.isAvailable ? 'פנוי' : 'תפוס'}
              </span>
              {slot.isAvailable && (
                <div className={styles.rowActions}>
                  <button
                    type="button"
                    className={styles.iconButton}
                    onClick={() => setEditingSlotId(slot.id)}
                    aria-label="עריכת זמן פנוי"
                  >
                    <Pencil size={16} />
                  </button>
                  <button
                    type="button"
                    className={styles.iconButton}
                    onClick={() => handleDelete(slot.id)}
                    disabled={deletingSlotId === slot.id}
                    aria-label="מחיקת זמן פנוי"
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
