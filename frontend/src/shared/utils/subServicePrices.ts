import type { SubServicePriceSelection } from '../api';

/**
 * Shared parsing/validation for a professional's sub-service prices, so registration and the
 * profile editor cannot disagree about what a valid price is — the same reason the backend extracted
 * `SubServicePriceValidator` rather than writing the rule at both call sites.
 *
 * <p>The rules mirror that validator exactly: empty means "not stated" and is allowed, and anything
 * present must be a non-negative number with at most two decimals and below a fat-finger ceiling.
 * The frontend copy is Hebrew and per-field; the backend remains the enforcement point regardless.
 */

/** Matches the backend's `ck_professional_sub_services_price` upper bound. */
const MAX_PRICE = 1_000_000;

export const SUB_SERVICE_PRICE_ERRORS = {
  notANumber: 'יש להזין מחיר במספרים בלבד.',
  negative: 'מחיר לא יכול להיות שלילי.',
  tooManyDecimals: 'אפשר להזין עד שתי ספרות אחרי הנקודה.',
  tooLarge: 'המחיר גבוה מדי. אפשר שנפלה טעות בהקלדה.',
} as const;

/**
 * @returns a Hebrew error for the raw input, or `null` when it is acceptable. An empty/whitespace
 *          string is acceptable — it means the professional has not priced this service, which is a
 *          legal state both here and in the database.
 */
export function validateSubServicePrice(raw: string): string | null {
  const trimmed = raw.trim();
  if (trimmed === '') {
    return null;
  }
  // Deliberately stricter than Number(): Number('') is 0, Number('1e3') is 1000 and Number(' 12 ')
  // is 12, none of which anybody typed into a price field on purpose.
  if (!/^\d+(\.\d+)?$/.test(trimmed)) {
    // A leading '-' lands here too, but "not a number" is a poor description of it, so it is
    // checked separately for a message that says what is actually wrong.
    return /^-/.test(trimmed) ? SUB_SERVICE_PRICE_ERRORS.negative : SUB_SERVICE_PRICE_ERRORS.notANumber;
  }
  const decimals = trimmed.split('.')[1];
  if (decimals !== undefined && decimals.length > 2) {
    return SUB_SERVICE_PRICE_ERRORS.tooManyDecimals;
  }
  if (Number(trimmed) > MAX_PRICE) {
    return SUB_SERVICE_PRICE_ERRORS.tooLarge;
  }
  return null;
}

/** The API value for a raw input: `null` for "not stated", otherwise the number. */
export function toSubServicePrice(raw: string): number | null {
  const trimmed = raw.trim();
  return trimmed === '' ? null : Number(trimmed);
}

/**
 * The request body for a selection, in the order the ids were given.
 *
 * <p>Reads each price out of `pricesById` by id rather than by position — a selection is a set and
 * the two lists are maintained independently, so pairing them positionally is the kind of thing
 * that works until somebody unticks a middle row.
 */
export function toPriceSelections(
  selectedIds: readonly number[],
  pricesById: Readonly<Record<number, string>>,
): SubServicePriceSelection[] {
  return selectedIds.map((subServiceId) => ({
    subServiceId,
    price: toSubServicePrice(pricesById[subServiceId] ?? ''),
  }));
}

/**
 * First Hebrew error across the selected rows, or `null` when every price is acceptable.
 * Used to block submission with one message; the per-row messages come from
 * {@link validateSubServicePrice} on the same inputs.
 */
export function firstSubServicePriceError(
  selectedIds: readonly number[],
  pricesById: Readonly<Record<number, string>>,
): string | null {
  for (const id of selectedIds) {
    const error = validateSubServicePrice(pricesById[id] ?? '');
    if (error) {
      return error;
    }
  }
  return null;
}
