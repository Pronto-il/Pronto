import type { ReactNode } from 'react';
import styles from './FilterChip.module.css';

export interface FilterChipProps {
  label: ReactNode;
  selected: boolean;
  onSelect: () => void;
  className?: string;
}

/**
 * Single filter chip, per DESIGN_SYSTEM.md §34. Meant to be used inside `FilterChipGroup`
 * (`role="radiogroup"`) — this component only renders `role="radio"` on itself and relies on
 * the group ancestor for the accessible group semantics. A real `<button>` so native Enter/
 * Space activation and Tab focus work with no extra wiring — per the MS1 plan's corrected
 * scope decision, this is deliberately *not* a roving-tabindex/arrow-key widget.
 *
 * 44px touch target via inset expansion (`::after`), not a literal 44px visible chip — the
 * chip itself stays visually compact (36px) to match DESIGN_SYSTEM.md §34's chip look, while
 * still satisfying §73's 44×44px minimum touch target.
 */
export function FilterChip({ label, selected, onSelect, className }: FilterChipProps) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      className={[styles.chip, selected ? styles.selected : '', className ?? ''].filter(Boolean).join(' ')}
      onClick={onSelect}
    >
      {label}
    </button>
  );
}
