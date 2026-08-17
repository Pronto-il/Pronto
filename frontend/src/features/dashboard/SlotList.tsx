import type { SlotListItem } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './SlotList.module.css';

export interface SlotListProps {
  slots: SlotListItem[];
}

/**
 * Read-only slot list, per this milestone's brief — no edit/delete controls (out of
 * scope; not stubbed as disabled buttons either, since that would imply a capability that
 * doesn't exist, FRONTEND_AGENT.md §53).
 */
export function SlotList({ slots }: SlotListProps) {
  if (slots.length === 0) {
    return <p className={styles.empty}>עוד לא נוספו זמנים פנויים.</p>;
  }

  return (
    <div className={styles.list}>
      {slots.map((slot) => (
        <div key={slot.id} className={styles.row}>
          <span className={styles.time}>
            {formatDateLabel(slot.startTime)}, {formatTimeLabel(slot.startTime)}–{formatTimeLabel(slot.endTime)}
          </span>
          <span className={`${styles.badge} ${slot.isAvailable ? styles.available : styles.booked}`}>
            {slot.isAvailable ? 'פנוי' : 'תפוס'}
          </span>
        </div>
      ))}
    </div>
  );
}
