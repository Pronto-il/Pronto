import { useEffect, useState } from 'react';
import { getMyAvailabilitySlots, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { SlotListItem } from '../../shared/api';
import { SlotForm } from './SlotForm';
import { SlotList } from './SlotList';
import styles from './AvailabilityPage.module.css';

/**
 * "יומן זמינות" tab — add a Standard-booking slot (`SlotForm`) and see the caller's own
 * slots read-only (`SlotList`, `GET /api/availability/slots/me`).
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
      {error && (
        <div className={styles.banner} role="alert">
          <p>{error}</p>
        </div>
      )}

      <div>
        <p className={styles.sectionTitle}>הוספת זמן פנוי</p>
        <SlotForm onCreated={(slot) => setSlots((prev) => [...(prev ?? []), slot])} />
      </div>

      <div>
        <p className={styles.sectionTitle}>הזמנים שלי</p>
        {slots === null && !error ? <p>טוען…</p> : <SlotList slots={slots ?? []} />}
      </div>
    </div>
  );
}
