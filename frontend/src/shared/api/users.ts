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
   * Production MS1. Drives the phone-capture prompt, so a user is asked before they run into
   * `PHONE_VERIFICATION_REQUIRED` rather than after. The prompt is UX; the rule itself is enforced
   * by the backend (`users.service.ContactVerificationGuard`).
   */
  phoneVerified: boolean;
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
 * Request body for `PUT /api/users/me` (MS10 profile redesign §4/§4.5, CUSTOMER-only).
 * `defaultAddress` is always required in full (not partial-updatable) — same shape as
 * `AddressValue`/`DefaultAddressRequest`, `city`/`street`/`houseNumber` required, the rest
 * optional.
 */
export interface UpdateUserMeRequest {
  fullName: string;
  phone: string;
  defaultAddress: {
    city: string;
    street: string;
    houseNumber: string;
    apartment?: string;
    floor?: string;
    entrance?: string;
    addressNotes?: string;
  };
}

/** `PUT /api/users/me` — CUSTOMER only. Returns the same shape `getMe()` does. */
export function updateMe(payload: UpdateUserMeRequest): Promise<UserMeResponse> {
  return httpClient.put<UserMeResponse>('/api/users/me', payload);
}
