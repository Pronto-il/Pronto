import { forwardRef, useId } from 'react';
import type { SelectHTMLAttributes } from 'react';
import { ChevronDown } from 'lucide-react';
import styles from './Select.module.css';

export interface SelectOption {
  value: string;
  label: string;
}

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label: string;
  options: SelectOption[];
  placeholder?: string;
  error?: string;
  hint?: string;
}

/** Labeled select, same states as `Input` (§22-24), used for service category. */
export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { label, options, placeholder, error, hint, id, className, required, ...rest },
  ref,
) {
  const generatedId = useId();
  const selectId = id ?? generatedId;
  const describedBy = error ? `${selectId}-error` : hint ? `${selectId}-hint` : undefined;

  return (
    <div className={[styles.field, error ? styles.hasError : '', className ?? ''].filter(Boolean).join(' ')}>
      <label htmlFor={selectId} className={styles.label}>
        {label}
        {required && (
          <span className={styles.required} aria-hidden="true">
            {' '}
            *
          </span>
        )}
      </label>
      <div className={styles.selectWrapper}>
        <select
          ref={ref}
          id={selectId}
          className={styles.select}
          aria-invalid={Boolean(error)}
          aria-describedby={describedBy}
          required={required}
          {...rest}
        >
          {placeholder && (
            <option value="" disabled>
              {placeholder}
            </option>
          )}
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <ChevronDown size={18} className={styles.chevron} aria-hidden="true" />
      </div>
      {error ? (
        <p id={`${selectId}-error`} className={styles.errorMessage} role="alert">
          {error}
        </p>
      ) : hint ? (
        <p id={`${selectId}-hint`} className={styles.hint}>
          {hint}
        </p>
      ) : null}
    </div>
  );
});
