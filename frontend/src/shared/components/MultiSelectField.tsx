import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Check, ChevronDown, Search, X } from 'lucide-react';
import styles from './MultiSelectField.module.css';

export interface MultiSelectOption {
  value: number;
  label: string;
}

export interface MultiSelectFieldProps {
  label: string;
  options: MultiSelectOption[];
  selected: number[];
  onChange: (selected: number[]) => void;
  /** Shown in the closed control when nothing is selected. */
  placeholder?: string;
  /** Shown above the list when `options` is empty — e.g. "choose a region first". */
  emptyMessage?: string;
  /** Adds the filter box. Off for short lists (a search field over 7 options is noise). */
  searchable?: boolean;
  searchPlaceholder?: string;
  error?: string;
  hint?: string;
  required?: boolean;
  disabled?: boolean;
  className?: string;
}

/**
 * Multi-select with an optional filter box, a checkbox list and removable chips for what's
 * chosen. Built for MS4's service-city selector (≈16 cities per region, searchable) and reused
 * by the service-category selector (7 options, not searchable) — one component rather than two,
 * because they differ only in list length.
 *
 * Deliberately **not** a native `<select multiple>`: on touch it renders as an unlabeled
 * scrolling box with no indication that ctrl/cmd-click is what selects a second item, and it
 * cannot show what's currently chosen without the user scrolling the list. The chips do that.
 *
 * Values are numbers (canonical `service_cities.id` / `categories.id`), not display strings —
 * the whole point of MS4 Part A is that nothing persists a place or a trade as text.
 *
 * Presentational and self-contained: no API call, no validation, no submit. Both consumers wrap
 * it in whatever their surface needs, the same contract `WeeklyHoursFields` follows.
 */
export function MultiSelectField({
  label,
  options,
  selected,
  onChange,
  placeholder = 'בחירה',
  emptyMessage,
  searchable = false,
  searchPlaceholder = 'חיפוש…',
  error,
  hint,
  required,
  disabled,
  className,
}: MultiSelectFieldProps) {
  const generatedId = useId();
  const listId = `${generatedId}-list`;
  const [isOpen, setIsOpen] = useState(false);
  const [query, setQuery] = useState('');
  const containerRef = useRef<HTMLDivElement>(null);

  // Click-away closes the panel. Pointerdown rather than click so a mousedown that starts
  // outside and ends inside doesn't leave the panel open under the cursor.
  useEffect(() => {
    if (!isOpen) {
      return;
    }
    function handlePointerDown(event: PointerEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener('pointerdown', handlePointerDown);
    return () => document.removeEventListener('pointerdown', handlePointerDown);
  }, [isOpen]);

  const selectedSet = useMemo(() => new Set(selected), [selected]);
  const selectedOptions = useMemo(
    () => options.filter((option) => selectedSet.has(option.value)),
    [options, selectedSet],
  );

  const visibleOptions = useMemo(() => {
    const trimmed = query.trim();
    if (!trimmed) {
      return options;
    }
    return options.filter((option) => option.label.includes(trimmed));
  }, [options, query]);

  function toggle(value: number) {
    onChange(selectedSet.has(value) ? selected.filter((id) => id !== value) : [...selected, value]);
  }

  return (
    <div
      ref={containerRef}
      className={[styles.field, error ? styles.hasError : '', className ?? ''].filter(Boolean).join(' ')}
    >
      <span className={styles.label} id={`${generatedId}-label`}>
        {label}
        {required && (
          <span className={styles.required} aria-hidden="true">
            {' '}
            *
          </span>
        )}
      </span>

      <button
        type="button"
        className={styles.control}
        aria-expanded={isOpen}
        aria-controls={listId}
        aria-labelledby={`${generatedId}-label`}
        aria-invalid={Boolean(error)}
        disabled={disabled}
        onClick={() => setIsOpen((open) => !open)}
      >
        <span className={selectedOptions.length === 0 ? styles.placeholder : styles.summary}>
          {selectedOptions.length === 0 ? placeholder : `${selectedOptions.length} נבחרו`}
        </span>
        <ChevronDown size={18} className={styles.chevron} aria-hidden="true" />
      </button>

      {isOpen && (
        <div className={styles.panel} id={listId}>
          {searchable && options.length > 0 && (
            <div className={styles.searchRow}>
              <Search size={16} className={styles.searchIcon} aria-hidden="true" />
              <input
                type="text"
                className={styles.search}
                value={query}
                placeholder={searchPlaceholder}
                aria-label={searchPlaceholder}
                onChange={(event) => setQuery(event.target.value)}
              />
            </div>
          )}

          {options.length === 0 ? (
            <p className={styles.empty}>{emptyMessage ?? 'אין אפשרויות זמינות.'}</p>
          ) : visibleOptions.length === 0 ? (
            <p className={styles.empty}>לא נמצאו תוצאות עבור "{query.trim()}".</p>
          ) : (
            <ul className={styles.list} role="listbox" aria-multiselectable="true">
              {visibleOptions.map((option) => {
                const isSelected = selectedSet.has(option.value);
                return (
                  <li key={option.value}>
                    <button
                      type="button"
                      role="option"
                      aria-selected={isSelected}
                      className={`${styles.option} ${isSelected ? styles.optionSelected : ''}`}
                      onClick={() => toggle(option.value)}
                    >
                      <span className={styles.checkbox} aria-hidden="true">
                        {isSelected && <Check size={14} />}
                      </span>
                      <span className={styles.optionLabel}>{option.label}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}

      {selectedOptions.length > 0 && (
        <ul className={styles.chips}>
          {selectedOptions.map((option) => (
            <li key={option.value} className={styles.chip}>
              <span>{option.label}</span>
              <button
                type="button"
                className={styles.chipRemove}
                aria-label={`הסרה: ${option.label}`}
                onClick={() => toggle(option.value)}
              >
                <X size={14} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      )}

      {error ? (
        <p className={styles.errorMessage} role="alert">
          {error}
        </p>
      ) : hint ? (
        <p className={styles.hint}>{hint}</p>
      ) : null}
    </div>
  );
}
