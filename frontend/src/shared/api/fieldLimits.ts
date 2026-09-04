/**
 * Character limits for every user-editable free-text field, mirroring the `@Size` constraints on
 * the backend request DTOs one-for-one.
 *
 * **Why one file rather than a constant per feature.** The rule is only useful if the two ends
 * agree: the client stops the caret at the same number the server refuses past, so the customer
 * never types something that will be thrown away and never sees a validation error for a limit
 * nothing told them about. Keeping the numbers together makes a drift between them visible in one
 * diff, and gives the tests a single thing to assert against.
 *
 * Each entry names the DTO it mirrors. Changing a number here is only half a change — the other
 * half is the annotation named beside it. The client limit is a courtesy; the server's is the rule
 * (nothing here is trusted, and none of it is truncation: input is stopped, never silently cut).
 *
 * Counted in UTF-16 code units, which is what the DOM's own `maxLength` counts and what Java's
 * `String.length()` — and therefore `@Size` — counts.
 */

/** `issues.dto.ClassifyRequest#description`, `issues.dto.CreateIssueRequest#description`. */
export const ISSUE_DESCRIPTION_MIN_LENGTH = 10;
export const ISSUE_DESCRIPTION_MAX_LENGTH = 300;

/** `issues.dto.ClarificationAnswerRequest#answer`. */
export const CLARIFICATION_ANSWER_MAX_LENGTH = 200;

/** `reviews.dto.CreateReviewRequest#comment`, `reviews.dto.UpdateReviewRequest#comment`. */
export const REVIEW_COMMENT_MAX_LENGTH = 500;

/** `professionals.dto.UpdateProfessionalProfileRequest#bio`. */
export const BIO_MAX_LENGTH = 2000;

/** `auth.dto.RegisterRequest#fullName`, `users.dto.UpdateUserMeRequest#fullName`, and
 *  `professionals.dto.UpdateProfessionalProfileRequest#fullName` — all 150. */
export const FULL_NAME_MAX_LENGTH = 150;

/** `auth.dto.RegisterRequest#email` / `auth.dto.LoginRequest#identifier` / column width. */
export const EMAIL_MAX_LENGTH = 255;

/** `auth.dto.RegisterRequest#phone` and `auth.dto.CapturePhoneRequest#phone` bound the input
 *  *before* normalization, which is why they are wider than the stored value below. */
export const PHONE_INPUT_MAX_LENGTH = 32;

/** `users.dto.UpdateUserMeRequest#phone` — the already-normalized number, and a tighter bound. */
export const PHONE_STORED_MAX_LENGTH = 20;

/**
 * Address fields, mirroring `users.dto.CustomerAddressRequest` / `auth.dto.DefaultAddressRequest`
 * / `sos.dto.CreateSosRequestRequest` / `bookings.dto.CreateOrderRequest`, which all carry the
 * same widths as the `users.default_*` columns (V20).
 *
 * `apartment`/`floor`/`entrance` are additionally shape-constrained server-side
 * (`maps.AddressAccessFields`' `\d{0,20}`, `\d{0,20}`, `[\p{L}0-9]{0,2}`), so these lengths are
 * the same bound the pattern already carries — stated here so the input agrees with it.
 */
export const ADDRESS_MAX_LENGTHS = {
  city: 100,
  street: 150,
  houseNumber: 20,
  apartment: 20,
  floor: 20,
  entrance: 2,
  addressNotes: 500,
} as const;
