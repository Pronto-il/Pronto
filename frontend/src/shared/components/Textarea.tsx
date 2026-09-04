import { forwardRef, useId } from 'react';
import type { TextareaHTMLAttributes } from 'react';
import styles from './Textarea.module.css';

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label: string;
  error?: string;
  hint?: string;
  /**
   * Guidance rendered directly *under the label*, above the field — for the case where the
   * sentence tells the customer what to write, and is therefore useless in `hint`'s position
   * below a box they have already filled in.
   */
  helperText?: string;
}

/**
 * Labeled multi-line input, per DESIGN_SYSTEM.md §24 (120px minimum height). Same label/
 * error/hint conventions as `Input`.
 */
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { label, error, hint, helperText, id, className, required, maxLength, value, ...rest },
  ref,
) {
  const generatedId = useId();
  const textareaId = id ?? generatedId;
  /**
   * Shown whenever the field has a limit, so "how much room is left" is answered before the
   * customer runs out of it rather than by the caret stopping. Counts the controlled `value`,
   * which is what will be sent — an uncontrolled field has nothing to count and gets no counter.
   */
  // `value.length` deliberately — the same UTF-16 code units the DOM's own `maxLength` counts and
  // the same unit Java's `@Size` counts on the other side, so the number shown is the number the
  // server will measure.
  const counter = maxLength !== undefined && typeof value === 'string' ? `${value.length}/${maxLength}` : null;
  const belowFieldId = error ? `${textareaId}-error` : hint ? `${textareaId}-hint` : undefined;
  const describedBy =
    [helperText ? `${textareaId}-helper` : undefined, belowFieldId].filter(Boolean).join(' ') || undefined;

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
      {helperText && (
        <p id={`${textareaId}-helper`} className={styles.helperText}>
          {helperText}
        </p>
      )}
      <textarea
        ref={ref}
        id={textareaId}
        className={styles.textarea}
        aria-invalid={Boolean(error)}
        aria-describedby={describedBy}
        required={required}
        maxLength={maxLength}
        value={value}
        {...rest}
      />
      {/* One row under the field, so adding the counter costs no vertical space: the existing
          error/hint keeps its place and the counter sits opposite it. */}
      {(error || hint || counter) && (
        <div className={styles.footer}>
          {error ? (
            <p id={`${textareaId}-error`} className={styles.errorMessage} role="alert">
              {error}
            </p>
          ) : hint ? (
            <p id={`${textareaId}-hint`} className={styles.hint}>
              {hint}
            </p>
          ) : (
            <span />
          )}
          {counter && (
            // Not a live region: it changes on every keystroke, and announcing each one would be
            // noise. The limit itself is on the element, where assistive tech reads it.
            <span className={styles.counter}>{counter}</span>
          )}
        </div>
      )}
    </div>
  );
});
