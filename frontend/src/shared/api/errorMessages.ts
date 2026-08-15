import { ApiError } from './httpClient';

/**
 * Generic fallback for network failures and unrecognized error codes — never show a
 * stack trace, HTTP status, or the backend's English message to the user (FRONTEND_AGENT.md
 * §26 / §28).
 */
export const GENERIC_ERROR_MESSAGE = 'משהו השתבש, נסו שוב.';

/**
 * Human Hebrew copy per known `VALIDATION_ERROR` field name. Keyed by the *leaf* field
 * name (see `getFieldErrorMessages` below) — e.g. the register endpoint's nested
 * `customer.defaultAddress.city` Bean Validation path is looked up here as `city`.
 */
const FIELD_ERROR_MESSAGES: Record<string, string> = {
  fullName: 'יש להזין שם מלא (בין 2 ל-150 תווים).',
  email: 'יש להזין כתובת אימייל תקינה.',
  password: 'הסיסמה חייבת להכיל לפחות 8 תווים.',
  categoryId: 'יש לבחור תחום שירות.',
  serviceArea: 'יש להזין אזור שירות.',
  basePrice: 'יש להזין מחיר תקין.',
  city: 'יש להזין עיר.',
  street: 'יש להזין רחוב.',
  houseNumber: 'יש להזין מספר בית.',
};

function fieldMessage(field: string): string {
  return FIELD_ERROR_MESSAGES[field] ?? 'יש לבדוק את הערך שהוזן בשדה זה.';
}

/**
 * Maps a `400 VALIDATION_ERROR`'s `details` array (`[{ field, message }]`, per
 * `docs/architecture/api-contract.md` §1) to a `{ field: hebrewMessage }` map so a form can
 * attribute each entry to its matching input, instead of dumping the raw backend message
 * in a banner. Returns `null` if `error` isn't a validation error with a field-error array.
 *
 * The register endpoint's `data` part binds a nested object (`RegisterRequest`, with
 * `customer.defaultAddress.*` / `professional.*` children per
 * `docs/architecture/api-contract.md` §2.1), so Spring's Bean Validation reports nested
 * fields as dotted paths (e.g. `customer.defaultAddress.city`, `professional.categoryId`)
 * rather than the flat names the pre-multipart JSON contract used. The result is keyed by
 * the *leaf* segment only (text after the last `.`) so callers can keep matching against
 * plain input names (`city`, `categoryId`, ...) regardless of nesting depth. None of this
 * endpoint's leaf names collide across its nested objects, so this is lossless here.
 */
export function getFieldErrorMessages(error: unknown): Record<string, string> | null {
  if (!(error instanceof ApiError) || error.code !== 'VALIDATION_ERROR') {
    return null;
  }
  if (!Array.isArray(error.details)) {
    return null;
  }
  const result: Record<string, string> = {};
  for (const entry of error.details as Array<{ field?: string }>) {
    if (entry?.field) {
      const leaf = entry.field.includes('.') ? entry.field.split('.').pop()! : entry.field;
      result[leaf] = fieldMessage(leaf);
    }
  }
  return result;
}
