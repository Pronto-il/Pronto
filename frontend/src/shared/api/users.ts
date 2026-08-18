import { httpClient } from './httpClient';
import type { UserRole } from './auth';

export interface ProfessionalInfo {
  categoryId: number;
  serviceArea: string;
  basePrice: number;
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
}

/** `GET /api/users/me` — either role, the caller's own profile. */
export function getMe(): Promise<UserMeResponse> {
  return httpClient.get<UserMeResponse>('/api/users/me');
}

/** `DELETE /api/users/me` — either role. Soft-deletes the caller's account server-side. */
export function deleteMe(): Promise<void> {
  return httpClient.delete<void>('/api/users/me');
}
