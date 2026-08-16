import { forwardRef, useId } from 'react';
import type { TextareaHTMLAttributes } from 'react';
import styles from './Textarea.module.css';

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label: string;
  error?: string;
  hint?: string;
}

/**
 * Labeled multi-line input, per DESIGN_SYSTEM.md §24 (120px minimum height). Same label/
 * error/hint conventions as `Input`.
 */
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { label, error, hint, id, className, required, ...rest },
  ref,
) {
  const generatedId = useId();
  const textareaId = id ?? generatedId;
  const describedBy = error ? `${textareaId}-error` : hint ? `${textareaId}-hint` : undefined;

  return (
    <div className={[styles.field, error ? styles.hasError : '', className ?? ''].filter(Boolean).join(' ')}>
      <label htmlFor={textareaId} className={styles.label}>
        {label}
        {required && (
          <span className={styles.required} aria-hidden="true">
            {' '}
            *
          </span>
        )}
      </label>
      <textarea
        ref={ref}
        id={textareaId}
        className={styles.textarea}
        aria-invalid={Boolean(error)}
        aria-describedby={describedBy}
        required={required}
        {...rest}
      />
      {error ? (
        <p id={`${textareaId}-error`} className={styles.errorMessage} role="alert">
          {error}
        </p>
      ) : hint ? (
        <p id={`${textareaId}-hint`} className={styles.hint}>
          {hint}
        </p>
      ) : null}
    </div>
  );
});
