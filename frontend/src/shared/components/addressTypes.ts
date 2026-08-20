/**
 * Field names match `backend/src/main/java/com/pronto/auth/dto/DefaultAddressRequest.java`
 * exactly (`city`/`street`/`houseNumber` required, the rest optional), so a value of this
 * shape can be sent to the backend as-is without a translation step.
 */
export interface AddressValue {
  city: string;
  street: string;
  houseNumber: string;
  apartment: string;
  floor: string;
  entrance: string;
  addressNotes: string;
}

export const EMPTY_ADDRESS: AddressValue = {
  city: '',
  street: '',
  houseNumber: '',
  apartment: '',
  floor: '',
  entrance: '',
  addressNotes: '',
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
}

/** Normalises a saved default address into the form-shaped `AddressValue` every booking
 *  surface works with. Shared by `AddressSelectionStep` (default-address mode) and the
 *  profession-matching screen (which pre-loads the listing against the saved address). */
export function toAddressValue(defaultAddress: SavedDefaultAddress): AddressValue {
  return {
    city: defaultAddress.city,
    street: defaultAddress.street,
    houseNumber: defaultAddress.houseNumber,
    apartment: defaultAddress.apartment ?? '',
    floor: defaultAddress.floor ?? '',
    entrance: defaultAddress.entrance ?? '',
    addressNotes: defaultAddress.addressNotes ?? '',
  };
}
