import { Checkbox } from './Checkbox';
import styles from './SubServicePriceRow.module.css';

export interface SubServicePriceRowProps {
  /** Human-readable Hebrew label. Never a taxonomy code — those are internal identifiers. */
  label: string;
  checked: boolean;
  onToggle: () => void;
  /**
   * The price as the professional has typed it, or `''` for "not stated". Kept as a string rather
   * than a number so a half-typed `"4"` or an emptied field survives a re-render — a numeric state
   * would coerce those to `4` and `0`, and `0` is a real, different price.
   */
  price: string;
  onPriceChange: (value: string) => void;
  /** Field-level message, shown under the input. */
  error?: string | null;
}

/**
 * One sub-service a professional offers, with what they charge for it.
 *
 * <p><b>The price input appears only when the sub-service is ticked</b>, which is the whole
 * interaction: an unticked row is a service they do not provide, and asking its price would be
 * asking about work they have not offered to do. Unticking hides the input; the caller decides
 * whether to keep the typed value around in case the customer changes their mind back (both callers
 * do — losing a price to a mis-tap is a needlessly annoying way to lose data).
 *
 * <p><b>Leaving the price empty is allowed</b> and means "not stated", which the API stores as null
 * and every customer-facing surface renders as an absence. It is deliberately not defaulted to 0:
 * that would advertise free work. The nudge below says so in one short line rather than blocking
 * the professional from finishing registration.
 *
 * <p>`inputMode="decimal"` rather than `type="number"`: a numeric input on mobile brings up the
 * right keypad either way, while `type="number"` also brings spinner arrows nobody wants on a price
 * and silently discards non-numeric text instead of letting the field show what was typed and be
 * validated against it.
 */
export function SubServicePriceRow({
  label,
  checked,
  onToggle,
  price,
  onPriceChange,
  error,
}: SubServicePriceRowProps) {
  return (
    <div className={`${styles.row} ${checked ? styles.rowSelected : ''}`}>
      <Checkbox label={label} checked={checked} onChange={onToggle} />

      {checked && (
        <div className={styles.priceField}>
          <label className={styles.priceLabel}>
            <span className={styles.priceLabelText}>מחיר</span>
            <span className={styles.priceInputWrap}>
              <input
                className={`${styles.priceInput} ${error ? styles.priceInputError : ''}`}
                type="text"
                inputMode="decimal"
                dir="ltr"
                value={price}
                placeholder="0"
                aria-label={`מחיר עבור ${label}`}
                aria-invalid={error ? true : undefined}
                onChange={(event) => onPriceChange(event.target.value)}
              />
              <span className={styles.currency} aria-hidden="true">
                ₪
              </span>
            </span>
          </label>
          {error ? (
            <p className={styles.priceError} role="alert">
              {error}
            </p>
          ) : (
            <p className={styles.priceHint}>אפשר להשלים מחיר גם מאוחר יותר</p>
          )}
        </div>
      )}
    </div>
  );
}
