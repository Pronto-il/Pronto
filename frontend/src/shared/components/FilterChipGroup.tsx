import { FilterChip } from './FilterChip';
import styles from './FilterChipGroup.module.css';

export interface FilterChipOption<T extends string = string> {
  value: T;
  label: string;
}

export interface FilterChipGroupProps<T extends string = string> {
  options: FilterChipOption<T>[];
  value: T;
  onChange: (value: T) => void;
  /** Accessible name for the `radiogroup` (e.g. "מיון תוצאות") — required since a radiogroup
   *  needs one. */
  'aria-label': string;
  className?: string;
}

/**
 * Horizontal, single-select filter-chip group, per DESIGN_SYSTEM.md §34 — `role="radiogroup"`
 * wrapping `FilterChip`s (`role="radio"`). Scrolls horizontally on narrow screens rather than
 * wrapping/squeezing labels. Keyboard support is Tab + native Enter/Space activation only
 * (every chip is individually tabbable) — deliberately not a roving-tabindex/arrow-key widget,
 * per the MS1 plan's corrected scope decision (the single current/near-term consumer,
 * `ProfessionalList`'s sort chips, doesn't need it).
 */
export function FilterChipGroup<T extends string = string>({
  options,
  value,
  onChange,
  className,
  ...rest
}: FilterChipGroupProps<T>) {
  return (
    <div role="radiogroup" className={[styles.group, className ?? ''].filter(Boolean).join(' ')} {...rest}>
      {options.map((option) => (
        <FilterChip
          key={option.value}
          label={option.label}
          selected={option.value === value}
          onSelect={() => onChange(option.value)}
        />
      ))}
    </div>
  );
}
