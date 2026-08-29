/**
 * Per-field registration rules the browser can decide **on its own**, extracted from
 * `CustomerRegisterForm` so each one can be unit-tested and so the wizard's "may I advance?"
 * question has a single answer rather than one inline `if` per field.
 *
 * ## The line this module draws
 *
 * Everything here is answerable without the network: a shape, a length, two values matching.
 * Deliberately **not** here: whether an address or a number is already registered, which no
 * client can know, and which `useContactAvailability` asks the backend about instead. Keeping
 * those apart is what stops the form guessing — a field shows a local error, or a server answer,
 * never a locally-invented one about a fact only the server holds.
 */

import { ApiError } from '../../shared/api';

export const FULL_NAME_MIN_LENGTH = 2;
export const PASSWORD_MIN_LENGTH = 8;

export const FULL_NAME_INVALID_MESSAGE = 'יש להזין שם מלא (לפחות 2 תווים).';
export const EMAIL_INVALID_MESSAGE = 'יש להזין כתובת אימייל תקינה.';
export const EMAIL_TAKEN_MESSAGE = 'כתובת האימייל הזו כבר רשומה במערכת.';
export const PHONE_REQUIRED_MESSAGE = 'יש להזין מספר טלפון.';
export const PHONE_INVALID_MESSAGE = 'יש להזין מספר טלפון נייד תקין, למשל 050-1234567.';
export const PHONE_TAKEN_MESSAGE = 'מספר הטלפון הזה כבר רשום במערכת.';
export const PASSWORD_TOO_SHORT_MESSAGE = 'הסיסמה חייבת להכיל לפחות 8 תווים.';
export const CONFIRM_PASSWORD_MISMATCH_MESSAGE = 'אימות הסיסמה אינו תואם לסיסמה שהוזנה.';

/**
 * The same shape test the form has always used, kept as a constant so the tests can name it.
 * Deliberately loose — a browser cannot know whether a syntactically fine address exists, and a
 * stricter local regex only ever produces false rejections of valid, unusual addresses. The
 * backend applies its own `@Email` constraint on both the availability check and registration.
 */
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Characters a phone field may contain at all: digits, a leading `+`, and the separators people
 *  actually type. Anything else is a typo rather than a formatting choice. */
const PHONE_ALLOWED_CHARACTERS = /^[+\d\s()-]+$/;

/**
 * A phone number with the separators taken out — `050-123 4567` and `0501234567` are the same
 * number and must not produce different validation results.
 */
export function stripPhoneSeparators(raw: string): string {
  return raw.replace(/[\s()-]/g, '');
}

/**
 * **Shape only, and that is the whole design.**
 *
 * `+972501234567`, `00972501234567` and `0501234567` are accepted; so, for now, is a foreign
 * number in full international form, because the backend accepts one too.
 *
 * What this deliberately does **not** test is whether the number is a real, assignable, SMS-capable
 * line — that is a question about a numbering plan, the backend answers it with libphonenumber
 * (`auth.service.PhoneNumberNormalizer`), and a second copy of the Israeli mobile prefix list
 * living in this file would start silently rejecting legitimate customers the day a new prefix is
 * allocated. The precise verdict arrives from `POST /api/auth/availability` on blur, which is
 * still well before the confirmation screen — so nothing is deferred to submit, it is simply
 * answered by the component that knows.
 */
export const PHONE_SHAPE_PATTERN = /^(?:\+\d{8,15}|00\d{8,15}|0\d{8,9})$/;

export function validateFullName(fullName: string): string | undefined {
  return fullName.trim().length < FULL_NAME_MIN_LENGTH ? FULL_NAME_INVALID_MESSAGE : undefined;
}

export function validateEmail(email: string): string | undefined {
  return EMAIL_PATTERN.test(email.trim()) ? undefined : EMAIL_INVALID_MESSAGE;
}

export function validatePhone(phone: string): string | undefined {
  const trimmed = phone.trim();
  if (!trimmed) {
    return PHONE_REQUIRED_MESSAGE;
  }
  if (!PHONE_ALLOWED_CHARACTERS.test(trimmed)) {
    return PHONE_INVALID_MESSAGE;
  }
  return PHONE_SHAPE_PATTERN.test(stripPhoneSeparators(trimmed)) ? undefined : PHONE_INVALID_MESSAGE;
}

export function validatePassword(password: string): string | undefined {
  return password.length < PASSWORD_MIN_LENGTH ? PASSWORD_TOO_SHORT_MESSAGE : undefined;
}

export function validateConfirmPassword(
  password: string,
  confirmPassword: string,
): string | undefined {
  return confirmPassword === password ? undefined : CONFIRM_PASSWORD_MISMATCH_MESSAGE;
}

/**
 * The registration field a backend duplicate conflict belongs to, and the copy to show there.
 *
 * ## Why this is a function and not two `if`s in each form
 *
 * It was two `if`s in each form, and the professional one was missing a branch. `DUPLICATE_EMAIL`
 * was handled; `DUPLICATE_PHONE` was not, so it fell through to `getFieldErrorMessages` — which
 * returns `null` for anything that is not `VALIDATION_ERROR` — and ended up as the catch-all
 * *"משהו השתבש, נסו שוב"* banner. A registrant who had just completed six stages was told nothing
 * about what was actually wrong, and the one piece of information they needed (which field, and
 * why) was the one thing discarded.
 *
 * A shared mapper makes "which codes are expected validation outcomes rather than surprises"
 * answerable by reading one list, and makes it impossible for one of the two registration forms to
 * quietly know about fewer of them than the other.
 *
 * ## These are expected outcomes, not failures
 *
 * Both codes mean the customer's input was refused for a stated, actionable reason. The generic
 * banner is reserved for genuinely unexpected failures — a 500, a network drop, an unrecognised
 * code — where there is nothing specific to say.
 *
 * @returns the field and message, or `null` when this is not a duplicate conflict and the caller
 *          should fall through to its own handling (`VALIDATION_ERROR` field errors, then generic)
 */
export function mapDuplicateContactError(
  error: unknown,
): { field: 'email' | 'phone'; message: string } | null {
  if (!(error instanceof ApiError)) {
    return null;
  }
  if (error.code === 'DUPLICATE_EMAIL') {
    return { field: 'email', message: EMAIL_TAKEN_MESSAGE };
  }
  if (error.code === 'DUPLICATE_PHONE') {
    return { field: 'phone', message: PHONE_TAKEN_MESSAGE };
  }
  return null;
}
