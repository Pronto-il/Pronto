import { useId } from 'react';
import { ChevronDown } from 'lucide-react';
import styles from './TimeField.module.css';

/** Minute granularity offered in the dropdown. 5 minutes is fine-grained enough for working
 *  hours and a manual block, and keeps the list short enough to scroll on a phone. A value
 *  that isn't on this grid is never discarded — see `buildMinuteOptions`. */
const DEFAULT_MINUTE_STEP = 5;

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** `"HH:mm"` → `{ hour, minute }`, or `null` for an empty/unparsable value. Tolerates a
 *  `"HH:mm:ss"` value (what a backend `LocalTime` can serialize as) by ignoring the seconds. */
function parseTime(value: string): { hour: string; minute: string } | null {
  const match = /^(\d{1,2}):(\d{2})/.exec(value.trim());
  if (!match) {
    return null;
  }
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (!Number.isInteger(hour) || !Number.isInteger(minute) || hour > 23 || minute > 59) {
    return null;
  }
  return { hour: pad(hour), minute: pad(minute) };
}

const HOUR_OPTIONS = Array.from({ length: 24 }, (_, hour) => pad(hour));

/**
 * The step grid, plus the current minute value when it isn't on it — so opening an existing
 * `08:23` block in this editor and saving it back doesn't silently round the time.
 */
function buildMinuteOptions(currentMinute: string | null, step: number): string[] {
  const options = Array.from({ length: Math.ceil(60 / step) }, (_, index) => pad(index * step));
  if (currentMinute && !options.includes(currentMinute)) {
    options.push(currentMinute);
    options.sort();
  }
  return options;
}

export interface TimeFieldProps {
  label: string;
  /** `"HH:mm"`, or `''` when nothing is chosen yet. */
  value: string;
  /** Always emits `"HH:mm"` — never a partial value: picking one side of an empty field
   *  defaults the other to `00`. */
  onChange: (value: string) => void;
  error?: string;
  hint?: string;
  required?: boolean;
  disabled?: boolean;
  /** Minute granularity of the dropdown, in minutes. Defaults to 5. */
  minuteStep?: number;
  className?: string;
}

/**
 * A 24-hour `HH:mm` time picker: two `<select>`s (hours `00`-`23`, minutes on a fixed step),
 * styled to match `Input`/`Select`.
 *
 * **Why not `<input type="time">`** (which every availability surface used before): its
 * rendered format is decided by the *browser/OS* locale, not by the page — an en-US browser
 * shows a 12-hour field with an AM/PM segment even inside this Hebrew, RTL app, which is
 * exactly the AM/PM entry the professional availability experience is required not to have.
 * `lang`/`dir` do not override it, and there is no CSS or attribute that does. Two selects are
 * the only way to guarantee `HH:mm` everywhere, and they also give a better touch target on
 * mobile than a segmented time input does.
 *
 * The value contract (`"HH:mm"` strings, `''` for "not set") is unchanged from the
 * `<input type="time">` it replaces, so every caller's state, validation
 * (`validateWeeklyHoursRows`' string comparison) and request serialization keep working
 * as-is — `"08:00" < "17:30"` lexicographically is still `08:00` before `17:30`.
 */
export function TimeField({
  label,
  value,
  onChange,
  error,
  hint,
  required,
  disabled,
  minuteStep = DEFAULT_MINUTE_STEP,
  className,
}: TimeFieldProps) {
  const generatedId = useId();
  const hourId = `${generatedId}-hour`;
  const describedBy = error ? `${generatedId}-error` : hint ? `${generatedId}-hint` : undefined;

  const parsed = parseTime(value);
  const hour = parsed?.hour ?? '';
  const minute = parsed?.minute ?? '';
  const minuteOptions = buildMinuteOptions(parsed?.minute ?? null, minuteStep);

  function emit(nextHour: string, nextMinute: string) {
    if (!nextHour && !nextMinute) {
      onChange('');
      return;
    }
    onChange(`${nextHour || '00'}:${nextMinute || '00'}`);
  }

  return (
    <div className={[styles.field, error ? styles.hasError : '', className ?? ''].filter(Boolean).join(' ')}>
      {/* A `<label>` can only point at one control, so it points at the hour select and the
          group as a whole carries the same text for assistive tech. */}
      <label htmlFor={hourId} className={styles.label}>
        {label}
        {required && (
          <span className={styles.required} aria-hidden="true">
            {' '}
            *
          </span>
        )}
      </label>
      <div className={styles.controls} role="group" aria-label={label} aria-describedby={describedBy}>
        <div className={styles.selectWrapper}>
          <select
            id={hourId}
            className={styles.select}
            value={hour}
            onChange={(event) => emit(event.target.value, minute)}
            aria-label={`${label} — שעה`}
            aria-invalid={Boolean(error)}
            disabled={disabled}
          >
            <option value="" disabled>
              --
            </option>
            {HOUR_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
          <ChevronDown size={16} className={styles.chevron} aria-hidden="true" />
        </div>

        <span className={styles.separator} aria-hidden="true">
          :
        </span>

        <div className={styles.selectWrapper}>
          <select
            className={styles.select}
            value={minute}
            onChange={(event) => emit(hour, event.target.value)}
            aria-label={`${label} — דקות`}
            aria-invalid={Boolean(error)}
            disabled={disabled}
          >
            <option value="" disabled>
              --
            </option>
            {minuteOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
          <ChevronDown size={16} className={styles.chevron} aria-hidden="true" />
        </div>
      </div>
      {error ? (
        <p id={`${generatedId}-error`} className={styles.errorMessage} role="alert">
          {error}
        </p>
      ) : hint ? (
        <p id={`${generatedId}-hint`} className={styles.hint}>
          {hint}
        </p>
      ) : null}
    </div>
  );
}
