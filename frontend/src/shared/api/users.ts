import { httpClient } from './httpClient';
import type { UserRole } from './auth';

export interface ProfessionalInfo {
  categoryId: number;
  serviceArea: string;
  basePrice: number;
}

/**
 * `GET /api/users/me` response. `professional` is `null` for a `CUSTOMER` caller. Note
 * there is no `address` field yet, for either role — consistent with the register-payload
 * gap documented in `auth.ts`.
 */
export interface UserMeResponse {
  id: number;
  fullName: string;
  email: string;
  role: UserRole;
  emailVerified: boolean;
  professional: ProfessionalInfo | null;
}

export function getMe(): Promise<UserMeResponse> {
  return httpClient.get<UserMeResponse>('/api/users/me');
}
