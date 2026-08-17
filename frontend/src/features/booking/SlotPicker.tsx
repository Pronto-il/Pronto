import { useMemo, useState } from 'react';
import type { AvailabilitySlotItem } from '../../shared/api';
import { formatDateLabel, formatTimeLabel, dateKey } from '../../shared/utils/formatDateTime';
import styles from './SlotPicker.module.css';

export interface SlotPickerProps {
  slots: AvailabilitySlotItem[];
  selectedSlotId: number | null;
  onSelect: (slot: AvailabilitySlotItem) => void;
  isLoading?: boolean;
}

/**
 * "מתי נוח לך?" — groups the API's flat, already-future-only, already-available-only
 * `AvailabilitySlotItem[]` by calendar day client-side (the API has no grouping of its
 * own), per DESIGN_SYSTEM.md §46-47: date as a horizontal chip row, times as selectable
 * chips beneath. No slot is ever shown as "unavailable" — the endpoint only ever returns
 * open, future slots — so every chip here is clickable.
 */
export function SlotPicker({ slots, selectedSlotId, onSelect, isLoading }: SlotPickerProps) {
  const groups = useMemo(() => {
    const map = new Map<string, AvailabilitySlotItem[]>();
    for (const slot of slots) {
      const key = dateKey(slot.startTime);
      if (!map.has(key)) {
        map.set(key, []);
      }
      map.get(key)!.push(slot);
    }
    return Array.from(map.entries()).map(([key, daySlots]) => ({
      key,
      label: formatDateLabel(daySlots[0].startTime),
      slots: daySlots,
    }));
  }, [slots]);

  const [selectedDateKey, setSelectedDateKey] = useState<string | null>(groups[0]?.key ?? null);
  const activeDateKey = selectedDateKey && groups.some((g) => g.key === selectedDateKey) ? selectedDateKey : groups[0]?.key ?? null;
  const activeGroup = groups.find((group) => group.key === activeDateKey);

  if (isLoading) {
    return <div className={styles.skeleton} />;
  }

  if (groups.length === 0) {
    return (
      <div className={styles.empty}>
        <p className={styles.emptyTitle}>אין זמנים פנויים כרגע</p>
        <p>לבעל המקצוע הזה אין תורים פתוחים כרגע.</p>
      </div>
    );
  }

  return (
    <div className={styles.wrapper}>
      <div className={styles.dateRow}>
        {groups.map((group) => (
          <button
            key={group.key}
            type="button"
            className={`${styles.dateChip} ${activeDateKey === group.key ? styles.dateChipActive : ''}`}
            onClick={() => setSelectedDateKey(group.key)}
          >
            {group.label}
          </button>
        ))}
      </div>
      <div className={styles.timeGrid}>
        {activeGroup?.slots.map((slot) => (
          <button
            key={slot.slotId}
            type="button"
            className={`${styles.timeChip} ${selectedSlotId === slot.slotId ? styles.timeChipSelected : ''}`}
            onClick={() => onSelect(slot)}
          >
            {formatTimeLabel(slot.startTime)}
          </button>
        ))}
      </div>
    </div>
  );
}
