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
  if (!value.houseNumber.trim()) errors.houseNumber = 'יש להזין מספר בית.';
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
