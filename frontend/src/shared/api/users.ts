import { httpClient } from './httpClient';
import type { UserRole } from './auth';

export interface ProfessionalInfo {
  /** MS4: every trade this professional serves, in catalogue display order. */
  categoryIds: number[];
  /** MS4: canonical service-region label; `null` when the account predates MS4 with no match. */
  serviceRegion: string | null;
  basePrice: number;
  /** Added MS10 profile redesign §6, so a professional's own photo can be shown read-only
   *  on the shared `/profile` page. `null` when no photo has been uploaded (mirrors
   *  `professionals.dto.ProfessionalProfileResponse.profileImageUrl`'s own nullability). */
  profileImageUrl: string | null;
}

/**
 * Nested `defaultAddress` object on `GET /api/users/me`'s response for a `CUSTOMER` caller
 * with a saved default address. `null` for a `PROFESSIONAL` caller, and also `null` for a
 * `CUSTOMER` with no recorded default city (pre-V20 accounts) — mirrors `ProfessionalInfo`'s
 * "absent means no such object" convention.
 */
export interface UserMeDefaultAddress {
  city: string;
  street: string;
  houseNumber: string;
  apartment: string | null;
  floor: string | null;
  entrance: string | null;
  addressNotes: string | null;
  /** Google place id of the suggestion this address was selected from (`V55`). `null` for an
   *  address saved before address validation existed — such an address is grandfathered for
   *  booking and must be re-selected the next time it is edited. */
  placeId: string | null;
  formattedAddress: string | null;
}

/**
 * `GET /api/users/me` response. `professional` is `null` for a `CUSTOMER` caller.
 * `defaultAddress` is `null` for a `PROFESSIONAL` caller or a pre-V20 `CUSTOMER` with no
 * recorded default address.
 */
export interface UserMeResponse {
  id: number;
  fullName: string;
  email: string;
  role: UserRole;
  emailVerified: boolean;
  professional: ProfessionalInfo | null;
  defaultAddress: UserMeDefaultAddress | null;
  /**
   * Canonical E.164, e.g. `+972501234567`. Production MS1: returned for **every** role — it used to
   * be blanked for a `PROFESSIONAL`, which was right while this was customer contact detail and
   * wrong now that it is the account's second identity. `null` only on a legacy row that has never
   * supplied one.
   */
  phone: string | null;
  /**
   * Production MS1. Drives the phone-capture prompt **together with
   * {@link phoneVerificationRequired}**, so a user is asked before they run into
   * `PHONE_VERIFICATION_REQUIRED` rather than after. The prompt is UX; the rule itself is enforced
   * by the backend (`users.service.ContactVerificationGuard`).
   *
   * Reports the stored column and is never adjusted by policy: it answers "was this number
   * proved", which stays `false` for an account created while OTP verification was switched off.
   */
  phoneVerified: boolean;
  /**
   * Whether this deployment asks accounts to prove their phone number at all — the backend's
   * `VerificationPolicy`, itself gated by `OTP_VERIFICATION_ENABLED`.
   *
   * Exists so the client can tell **"unproved and being asked"** from **"unproved and nobody is
   * asking"**. Without it the only available signal was `phoneVerified: false`, which is identical
   * in both states — so a beta user with verification switched off would be shown a capture screen
   * offering to send a code that nothing would ever redeem. The alternative, making `phoneVerified`
   * report `true` under that policy, would have put a lie in the one record that decides who gets
   * asked to verify when verification is turned back on.
   */
  phoneVerificationRequired: boolean;
}

/** `GET /api/users/me` — either role, the caller's own profile. */
export function getMe(): Promise<UserMeResponse> {
  return httpClient.get<UserMeResponse>('/api/users/me');
}

/** `DELETE /api/users/me` — either role. Soft-deletes the caller's account server-side. */
export function deleteMe(): Promise<void> {
  return httpClient.delete<void>('/api/users/me');
}

/**
 * A customer's home address, as `PUT /api/users/me` and `PUT /api/users/me/default-address` both
 * take it. Mirrors `users.dto.CustomerAddressRequest` — `city`/`street`/`houseNumber` required
 * (the house number digits-only), the rest optional.
 */
export interface CustomerAddressPayload {
  city: string;
  street: string;
  houseNumber: string;
  apartment?: string;
  floor?: string;
  entrance?: string;
  addressNotes?: string;
  /** The selected place (`V55`). Required by the backend on both endpoints: saving a home
   *  address is exactly the moment a legacy free-text one must become validated. Sent as
   *  `undefined` rather than `null` for an unresolved address so the request shape is the same
   *  "omit what you do not have" convention the optional text fields already use — the backend
   *  refuses it either way. */
  placeId?: string;
  formattedAddress?: string;
  latitude?: number;
  longitude?: number;
}

/**
 * The editable-form shape (`shared/components`' `AddressValue`) as this endpoint's wire shape:
 * blank optional fields omitted rather than sent as `''`, `null` resolution fields omitted rather
 * than sent as `null`.
 *
 * Typed structurally rather than importing `AddressValue`, so the api layer keeps no dependency
 * on the component layer — the same convention `addressTypes.ts`'s `SavedDefaultAddress` uses in
 * the other direction. Written once here because three screens send this exact body and the
 * previous two copies had already drifted (one trimmed, one did not).
 */
export function toCustomerAddressPayload(value: {
  city: string;
  street: string;
  houseNumber: string;
  apartment: string;
  floor: string;
  entrance: string;
  addressNotes: string;
  placeId: string | null;
  formattedAddress: string | null;
  latitude: number | null;
  longitude: number | null;
}): CustomerAddressPayload {
  const optional = (field: string) => (field.trim() ? field.trim() : undefined);
  return {
    city: value.city.trim(),
    street: value.street.trim(),
    houseNumber: value.houseNumber.trim(),
    apartment: optional(value.apartment),
    floor: optional(value.floor),
    entrance: optional(value.entrance),
    addressNotes: optional(value.addressNotes),
    placeId: value.placeId ?? undefined,
    formattedAddress: value.formattedAddress ?? undefined,
    latitude: value.latitude ?? undefined,
    longitude: value.longitude ?? undefined,
  };
}

/**
 * Request body for `PUT /api/users/me` (MS10 profile redesign §4/§4.5, CUSTOMER-only).
 *
 * **`defaultAddress` is optional as of the address-flow redesign**, and omitting it leaves the
 * saved home address exactly as it was. It became optional because registration no longer
 * collects an address at all: a customer can now legitimately have none, and requiring one here
 * would mean such a customer could not correct a typo in their own name without first inventing
 * a home address. Supplying one is unchanged — still required in full, still must be a selected
 * place.
 */
export interface UpdateUserMeRequest {
  fullName: string;
  phone: string;
  defaultAddress?: CustomerAddressPayload;
}

/** `PUT /api/users/me` — CUSTOMER only. Returns the same shape `getMe()` does. */
export function updateMe(payload: UpdateUserMeRequest): Promise<UserMeResponse> {
  return httpClient.put<UserMeResponse>('/api/users/me', payload);
}

/**
 * `PUT /api/users/me/default-address` — CUSTOMER only. The home address on its own.
 *
 * Exists for the booking flow's "הפוך את זה לכתובת הבית": at that moment the customer has an
 * address and nothing else, and `PUT /api/users/me` would demand their name and phone number
 * back as well — three fields resent from client-side state, two of which the customer did not
 * ask to touch and one of which (`phone`) drops its verified flag if it comes back changed.
 *
 * Returns the refreshed `/me`, so a caller can update its cached user without a second request.
 */
export function saveDefaultAddress(address: CustomerAddressPayload): Promise<UserMeResponse> {
  return httpClient.put<UserMeResponse>('/api/users/me/default-address', address);
}
