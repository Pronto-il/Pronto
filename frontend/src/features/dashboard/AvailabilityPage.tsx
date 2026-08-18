import { useEffect, useState } from 'react';
import { getMyAvailabilitySlots, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { SlotListItem } from '../../shared/api';
import { SlotForm } from './SlotForm';
import { SlotList } from './SlotList';
import { SosAvailabilityToggle } from './SosAvailabilityToggle';
import styles from './AvailabilityPage.module.css';

/**
 * "יומן זמינות" tab — the professional's SOS-availability toggle (`SosAvailabilityToggle`,
 * Frontend Milestone 4) at the top, then the pre-existing Standard-booking calendar: add a
 * slot (`SlotForm`) and manage the caller's own slots (`SlotList`, `GET
 * /api/availability/slots/me`, plus inline edit/delete for not-yet-booked slots as of
 * Frontend Milestone 9). The SOS toggle lives here rather than a new dashboard tab — both are
 * the `availability` domain, same `/api/availability/*` backend package, and
 * `ProDashboardLayout`'s three tabs already avoid dead/thin nav items, so a fourth tab for a
 * single toggle would contradict that convention.
 */
export default function AvailabilityPage() {
  const [slots, setSlots] = useState<SlotListItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  function loadSlots() {
    getMyAvailabilitySlots()
      .then((result) => setSlots(result.slots))
      .catch(() => setError(GENERIC_ERROR_MESSAGE));
  }

  useEffect(() => {
    loadSlots();
  }, []);

  return (
    <div className={styles.wrapper}>
      <div>
        <p className={styles.sectionTitle}>עבודות דחופות (SOS)</p>
        <SosAvailabilityToggle />
      </div>

      {error && (
        <div className={styles.banner} role="alert">
          <p>{error}</p>
        </div>
      )}

      <div>
        <p className={styles.sectionTitle}>הוספת זמן פנוי</p>
        <SlotForm onSaved={(slot) => setSlots((prev) => [...(prev ?? []), slot])} />
      </div>

      <div>
        <p className={styles.sectionTitle}>הזמנים שלי</p>
        {slots === null && !error ? (
          <p>טוען…</p>
        ) : (
          <SlotList
            slots={slots ?? []}
            onSlotUpdated={(updated) =>
              setSlots((prev) => prev?.map((s) => (s.id === updated.id ? { ...s, ...updated } : s)) ?? prev)
            }
            onSlotDeleted={(slotId) => setSlots((prev) => prev?.filter((s) => s.id !== slotId) ?? prev)}
            onRefreshNeeded={loadSlots}
          />
        )}
      </div>
    </div>
  );
}
