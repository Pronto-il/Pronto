/**
 * Field names match `backend/src/main/java/com/pronto/auth/dto/DefaultAddressRequest.java`
 * exactly (`city`/`street`/`houseNumber` required, the rest optional), so a value of this
 * shape can be sent to the backend as-is without a translation step.
 *
 * ## The resolution fields
 *
 * `placeId`/`latitude`/`longitude`/`formattedAddress` identify the place the customer actually
 * **selected** from autocomplete, as opposed to the text they typed. They move together and are
 * always either all present or all `null` — `SelectedPlaceValidator` on the backend refuses a
 * partial claim, so producing one here would only turn a UI bug into a 400.
 *
 * **`placeId === null` is the single source of truth for "this address is not resolved".** Every
 * surface asks {@link isAddressResolved} rather than checking fields itself, because "can this be
 * submitted?" is one rule and four screens ask it.
 */
export interface AddressValue {
  city: string;
  street: string;
  houseNumber: string;
  apartment: string;
  floor: string;
  entrance: string;
  addressNotes: string;
  /** Google place id of the selected suggestion; `null` until one is chosen, and again the
   *  moment the text is edited afterwards. */
  placeId: string | null;
  formattedAddress: string | null;
  latitude: number | null;
  longitude: number | null;
}

export const EMPTY_ADDRESS: AddressValue = {
  city: '',
  street: '',
  houseNumber: '',
  apartment: '',
  floor: '',
  entrance: '',
  addressNotes: '',
  placeId: null,
  formattedAddress: null,
  latitude: null,
  longitude: null,
};

/**
 * The saved-default-address shape returned by `GET /api/users/me`
 * (`shared/api/users.ts`'s `UserMeDefaultAddress`) — nullable optional fields, unlike the
 * always-present strings an editable form needs. Typed structurally rather than importing the
 * API type, so this module stays free of any dependency on the api layer.
 */
export interface SavedDefaultAddress {
  city: string;
  street: string;
  houseNumber: string;
  apartment?: string | null;
  floor?: string | null;
  entrance?: string | null;
  addressNotes?: string | null;
  /** `null` for an address saved before address validation existed — see `toAddressValue`. */
  placeId?: string | null;
  formattedAddress?: string | null;
}

/** Normalises a saved default address into the form-shaped `AddressValue` every booking
 *  surface works with. Shared by `AddressSelectionStep` (default-address mode) and the
 *  profession-matching screen (which pre-loads the listing against the saved address).
 *
 *  A **legacy** saved address — one with no `placeId`, saved before autocomplete existed —
 *  comes back unresolved, and that is correct in both places it matters. Booking against it
 *  still works, because the backend recognises the caller's own saved default and grandfathers
 *  it. Editing it on the profile screen requires picking a real suggestion first, which is
 *  exactly the "heals on edit, never mid-flow" policy. `latitude`/`longitude` are always `null`
 *  here because `/me` deliberately does not return them; nothing needs them for a saved
 *  address, since the backend resolves it server-side. */
export function toAddressValue(defaultAddress: SavedDefaultAddress): AddressValue {
  return {
    city: defaultAddress.city,
    street: defaultAddress.street,
    houseNumber: defaultAddress.houseNumber,
    apartment: defaultAddress.apartment ?? '',
    floor: defaultAddress.floor ?? '',
    entrance: defaultAddress.entrance ?? '',
    addressNotes: defaultAddress.addressNotes ?? '',
    placeId: defaultAddress.placeId ?? null,
    formattedAddress: defaultAddress.formattedAddress ?? null,
    latitude: null,
    longitude: null,
  };
}

/**
 * A place the customer picked from the suggestion list, normalised out of the provider's own
 * shape by `googlePlaces.ts` so nothing above that module knows what a `google.maps.places.Place`
 * is.
 */
export interface ResolvedPlace {
  placeId: string;
  formattedAddress: string;
  city: string;
  street: string;
  houseNumber: string;
  latitude: number;
  longitude: number;
}

/**
 * ## House number: digits only, and enforced in three places
 *
 * The number is typed by hand — it is the one part of an address Google cannot be asked to
 * choose from a list, because a street's suggestion list has one entry per street, not per
 * building. So it is the one part that has to be constrained by rule rather than by selection.
 *
 * Digits only, deliberately: it is concatenated into the query used to verify the complete
 * address, it is stored in a 20-character column, and it is what a professional reads at the
 * door. `12א` and `12/3` are real Israeli spellings and are nonetheless refused, because the
 * product decision is that the numeric part alone locates the building and everything else
 * belongs in the apartment/entrance/notes fields that exist for it.
 *
 * Three enforcement points, none of which trusts the others: {@link sanitizeHouseNumber} stops
 * a non-digit ever reaching the field, {@link validateAddressTextOnly} refuses to submit one
 * that did, and the backend refuses a request carrying one (`DefaultAddressRequest`,
 * `CreateOrderRequest`, `BookingsController#parseServiceLocation`). A `curl` does not run any
 * of the first two.
 */
export const HOUSE_NUMBER_PATTERN = /^\d{1,20}$/;

export const HOUSE_NUMBER_INVALID_MESSAGE = 'מספר הבית חייב להכיל ספרות בלבד.';

/** Strips everything that is not a digit — used as the input filter, so an invalid character
 *  never appears in the field rather than appearing and then being complained about. */
export function sanitizeHouseNumber(raw: string): string {
  return raw.replace(/\D+/g, '').slice(0, 20);
}

export function isValidHouseNumber(houseNumber: string): boolean {
  return HOUSE_NUMBER_PATTERN.test(houseNumber.trim());
}

/**
 * ## Apartment, floor and entrance: optional, but not unconstrained
 *
 * These three are the "how do I get in" fields, and they are typed rather than selected for the
 * reason the house number is — no geocoder resolves "דירה 4, קומה 2". Left as free text they
 * quietly become a second address line: the place where `12א` reappears after the house number
 * refused it, and where a whole sentence lands in a field the professional's app renders as a
 * two-character chip.
 *
 * * **Apartment** — digits only.
 * * **Floor** — digits only. **A negative floor is not accepted**, and that is a decision rather
 *   than an omission: nothing here ever supported one (the column was 20 characters of anything,
 *   which took `-1` exactly as it took `"ליד המעלית"`), so there is no behaviour to preserve, and
 *   digits-only was the rule asked for. A basement belongs in the access-notes field, which exists
 *   for what a structured field cannot hold.
 * * **Entrance** — at most two characters, each a letter of any script or an ASCII digit.
 *   `A`, `ב`, `1`, `12`, `A1`, `ב2` pass; `ABC`, `123`, `A-1`, `א ב`, `@1` do not. `\p{L}` rather
 *   than `[A-Za-z]` because an Israeli entrance is labelled `א`/`ב`/`ג` far more often than
 *   `A`/`B`/`C`.
 *
 * Each pattern admits the empty string — all three fields stay genuinely optional.
 *
 * Enforced in the same three-point way the house number is, and for the same reason: the
 * `sanitize*` functions below stop a bad character ever landing in the field,
 * {@link validateAddressTextOnly} refuses to submit one that did (a restored draft or a saved
 * legacy address can carry text this form never produced), and the backend refuses the request
 * outright — `maps.AddressAccessFields`, applied by `DefaultAddressRequest`,
 * `CustomerAddressRequest` and `CreateOrderRequest`. `curl` runs none of the first two.
 */
export const APARTMENT_PATTERN = /^\d{0,20}$/;
export const FLOOR_PATTERN = /^\d{0,20}$/;
export const ENTRANCE_PATTERN = /^[\p{L}0-9]{0,2}$/u;

export const APARTMENT_INVALID_MESSAGE = 'מספר הדירה חייב להכיל ספרות בלבד.';
export const FLOOR_INVALID_MESSAGE = 'מספר הקומה חייב להכיל ספרות בלבד.';
export const ENTRANCE_INVALID_MESSAGE = 'הכניסה חייבת להיות עד 2 תווים — אותיות או ספרות בלבד.';

/** Input filters. Like {@link sanitizeHouseNumber}, these stop a disallowed character appearing
 *  at all rather than letting it appear and then complaining about it. */
export function sanitizeApartment(raw: string): string {
  return raw.replace(/\D+/g, '').slice(0, 20);
}

export function sanitizeFloor(raw: string): string {
  return raw.replace(/\D+/g, '').slice(0, 20);
}

export function sanitizeEntrance(raw: string): string {
  return raw.replace(/[^\p{L}0-9]+/gu, '').slice(0, 2);
}

/** Blank is valid: the field is optional. */
export function isValidApartment(apartment: string): boolean {
  return APARTMENT_PATTERN.test(apartment);
}

/** Blank is valid: the field is optional. */
export function isValidFloor(floor: string): boolean {
  return FLOOR_PATTERN.test(floor);
}

/** Blank is valid: the field is optional. */
export function isValidEntrance(entrance: string): boolean {
  return ENTRANCE_PATTERN.test(entrance);
}

/**
 * **Is there enough here to ask the backend for professionals?**
 *
 * `GET /api/bookings/professionals` takes `city`/`street`/`houseNumber` as *required* query
 * params and answers `400 VALIDATION_ERROR` when any of them is blank. This is the predicate
 * every screen that can trigger that request asks first — and it exists as a function because
 * the bug it fixes was four screens each asking a slightly different question, one of which was
 * merely `draft.address != null`. An `AddressValue` object full of empty strings is a perfectly
 * non-null object, so that check passed and the request went out as `city=&street=&houseNumber=`.
 *
 * `apartment` is in here for the same reason and only that reason: it is an *optional* query
 * param on that same endpoint, and `BookingsController` now refuses a malformed one alongside the
 * house number. A predicate that exists to predict whether the request will be accepted has to
 * track what the endpoint actually rejects — otherwise a legacy saved `4א` sails past this and
 * fails as a `400` mid-flow, instead of as a field error on a screen that can still fix it.
 * `floor`/`entrance` are deliberately absent: they are not sent to this endpoint at all, and are
 * checked at the point they *are* sent (`validateAddressTextOnly`, order creation).
 *
 * Deliberately does NOT require {@link isAddressResolved}: the caller's own saved default address
 * is grandfathered by the backend and may predate address validation, so demanding a place id
 * here would block a listing the server would have served.
 */
export function isAddressComplete(value: AddressValue | null | undefined): boolean {
  return (
    value != null &&
    value.city.trim() !== '' &&
    value.street.trim() !== '' &&
    isValidHouseNumber(value.houseNumber) &&
    isValidApartment(value.apartment)
  );
}

/**
 * Has the customer selected a real place, and does the text still describe it?
 *
 * <p>Both halves matter. `placeId` alone would keep saying "resolved" after the customer edited
 * the street, which is precisely the state the requirement calls out — so every edit path clears
 * it through {@link withEditedAddressText} rather than leaving this function to guess.
 */
export function isAddressResolved(value: AddressValue): boolean {
  return (
    value.placeId !== null &&
    value.latitude !== null &&
    value.longitude !== null &&
    value.city.trim() !== '' &&
    value.street.trim() !== ''
  );
}

/**
 * Apply a selection: the normalised address text, and the identity that makes it validated.
 *
 * House number is a deliberate special case. Google does not return a street number for every
 * place — a numberless street, a named building, a junction — and blanking a house number the
 * customer had already typed would be actively worse than keeping it. So a resolved
 * `houseNumber` wins, and an empty one leaves whatever was there.
 */
export function withSelectedPlace(value: AddressValue, place: ResolvedPlace): AddressValue {
  return {
    ...value,
    city: place.city || value.city,
    street: place.street || value.street,
    houseNumber: place.houseNumber || value.houseNumber,
    placeId: place.placeId,
    formattedAddress: place.formattedAddress,
    latitude: place.latitude,
    longitude: place.longitude,
  };
}

/**
 * **Editing the address text invalidates the previous selection.**
 *
 * The requirement in one function, and the reason it is a function rather than four inline
 * spread-updates: a screen that forgot to clear `placeId` would submit last week's coordinates
 * with this week's street, and it would look completely fine on screen.
 *
 * Only the three *locating* fields do this. Apartment, floor, entrance and access notes describe
 * how to get into a building rather than which building it is — no geocoder resolves "דירה 4" —
 * so editing them must not throw away a perfectly good selection and force the customer to pick
 * their address again.
 */
export function withEditedAddressText(
  value: AddressValue,
  field: keyof AddressValue,
  fieldValue: string,
): AddressValue {
  const next = { ...value, [field]: fieldValue };
  if (field === 'city' || field === 'street' || field === 'houseNumber') {
    return { ...next, placeId: null, formattedAddress: null, latitude: null, longitude: null };
  }
  return next;
}

/** Shown when the customer typed an address but never picked one from the list. */
export const ADDRESS_NOT_SELECTED_MESSAGE = 'יש לבחור כתובת מתוך הרשימה המוצעת.';

/**
 * Required text fields only, WITHOUT the "must have been selected" rule.
 *
 * For exactly one case: the customer booking to their own saved default address. That address may
 * predate autocomplete, and the backend grandfathers it by recognising the caller's own stored
 * address — so demanding a re-selection here would block a booking the server would have accepted,
 * over an address that has been working fine. Every other surface uses {@link validateAddress}.
 */
export function validateAddressTextOnly(
  value: AddressValue,
): Partial<Record<keyof AddressValue, string>> {
  const errors: Partial<Record<keyof AddressValue, string>> = {};
  if (!value.city.trim()) errors.city = 'יש להזין עיר.';
  if (!value.street.trim()) errors.street = 'יש להזין רחוב.';
  if (!value.houseNumber.trim()) {
    errors.houseNumber = 'יש להזין מספר בית.';
  } else if (!isValidHouseNumber(value.houseNumber)) {
    // Reachable even though the input filters non-digits: a saved address, a restored draft or
    // a hand-edited localStorage entry can all carry text this form never produced.
    errors.houseNumber = HOUSE_NUMBER_INVALID_MESSAGE;
  }
  // Optional fields: an empty value is fine, a malformed one is not. Checked here rather than
  // only at the keystroke for the reason above, and in this function rather than in
  // `validateAddress` so the rule reaches the saved-default-address path too — that address is
  // sent to `POST /api/bookings/orders`, whose DTO now applies the same three patterns, so a
  // legacy value that skipped this check would be a 400 in the middle of a booking instead of a
  // field error the customer can fix.
  if (!isValidApartment(value.apartment)) errors.apartment = APARTMENT_INVALID_MESSAGE;
  if (!isValidFloor(value.floor)) errors.floor = FLOOR_INVALID_MESSAGE;
  if (!isValidEntrance(value.entrance)) errors.entrance = ENTRANCE_INVALID_MESSAGE;
  return errors;
}

/**
 * The one required-field rule for an address, shared by customer registration, the profile
 * screen, the booking flow and the SOS entry screen — all four of which previously carried their
 * own copy of the same three `if` statements, and only one of which would have gained the
 * selection rule if it had stayed that way.
 */
export function validateAddress(value: AddressValue): Partial<Record<keyof AddressValue, string>> {
  const errors = validateAddressTextOnly(value);
  if (Object.keys(errors).length === 0 && !isAddressResolved(value)) {
    errors.placeId = ADDRESS_NOT_SELECTED_MESSAGE;
  }
  return errors;
}
