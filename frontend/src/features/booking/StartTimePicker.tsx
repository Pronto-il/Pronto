import { useMemo, useState } from 'react';
import type { AvailableWindow } from '../../shared/api';
import { Skeleton } from '../../shared/components';
import { deriveStartTimeCandidates } from '../../shared/utils/availability';
import { formatDateLabel, formatTimeLabel, dateKey } from '../../shared/utils/formatDateTime';
import styles from './StartTimePicker.module.css';

export interface StartTimePickerProps {
  windows: AvailableWindow[];
  /** Echoed from `GET .../available-windows`'s response — never hardcoded client-side. */
  defaultDurationMinutes: number;
  selectedStart: string | null;
  onSelect: (bookedStart: string) => void;
  isLoading?: boolean;
}

/**
 * "מתי נוח לך?" — renamed from `SlotPicker.tsx`, professional weekly availability calendar
 * feature M6 (`docs/architecture/professional-weekly-calendar-design.md` §9.2.3/§7.6). The
 * date-chip-row + time-chip-grid UI is unchanged from the original component; only the
 * source of the chips changed — from the API's own flat, discrete `availability_slots` rows
 * to start-time candidates derived client-side (`deriveStartTimeCandidates`) from the
 * professional's derived `AVAILABLE` windows (`GET .../available-windows?issueId=`). No
 * candidate is ever shown as "unavailable" — the derivation only emits start times that fit
 * a full job before the window closes — so every chip here is clickable, same as before.
 */
export function StartTimePicker({
  windows,
  defaultDurationMinutes,
  selectedStart,
  onSelect,
  isLoading,
}: StartTimePickerProps) {
  const candidates = useMemo(
    () => deriveStartTimeCandidates(windows, defaultDurationMinutes),
    [windows, defaultDurationMinutes],
  );

  const groups = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const candidate of candidates) {
      const key = dateKey(candidate);
      if (!map.has(key)) {
        map.set(key, []);
      }
      map.get(key)!.push(candidate);
    }
    return Array.from(map.entries()).map(([key, dayStarts]) => ({
      key,
      label: formatDateLabel(dayStarts[0]),
      starts: dayStarts,
    }));
  }, [candidates]);

  const [selectedDateKey, setSelectedDateKey] = useState<string | null>(groups[0]?.key ?? null);
  const activeDateKey = selectedDateKey && groups.some((g) => g.key === selectedDateKey) ? selectedDateKey : groups[0]?.key ?? null;
  const activeGroup = groups.find((group) => group.key === activeDateKey);

  if (isLoading) {
    return <Skeleton variant="rect" className={styles.skeleton} />;
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
        {activeGroup?.starts.map((start) => (
          <button
            key={start}
            type="button"
            className={`${styles.timeChip} ${selectedStart === start ? styles.timeChipSelected : ''}`}
            onClick={() => onSelect(start)}
          >
            {formatTimeLabel(start)}
          </button>
        ))}
      </div>
    </div>
  );
}
