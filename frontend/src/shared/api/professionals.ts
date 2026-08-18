import { httpClient } from './httpClient';

/**
 * `professionals` domain types/functions (`backend/src/main/java/com/pronto/professionals/`)
 * — this package's first frontend client, mirroring `bookings.ts`'s pattern of a dedicated
 * file per backend package. Shapes verified directly against
 * `professionals.dto.ProfessionalProfileResponse`/`UpdateProfessionalProfileRequest`/
 * `ProfileImageUploadResponse`, not copied from prose.
 */

export interface ProfessionalProfileResponse {
  id: number;
  categoryId: number;
  fullName: string;
  serviceArea: string;
  city: string;
  bio: string | null;
  basePrice: number;
  profileImageUrl: string | null;
  averageRating: number | null;
  reviewCount: number;
  approvalStatus: string;
  /** Populated only on getProfessionalProfile() for a CUSTOMER caller; null everywhere else. */
  favorited: boolean | null;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateProfessionalProfileRequest {
  fullName: string;
  serviceArea: string;
  city: string;
  /** Optional, <=2000 chars server-side. */
  bio?: string;
  basePrice: number;
}

export interface ProfileImageUploadResponse {
  imageKey: string;
  imageUrl: string;
  contentType: string;
  sizeBytes: number;
}

/** GET /api/professionals/me — PROFESSIONAL only. */
export function getMyProfessionalProfile(): Promise<ProfessionalProfileResponse> {
  return httpClient.get<ProfessionalProfileResponse>('/api/professionals/me');
}

/** PUT /api/professionals/me — PROFESSIONAL only, allowlist DTO (no categoryId/id/etc). */
export function updateMyProfessionalProfile(
  payload: UpdateProfessionalProfileRequest,
): Promise<ProfessionalProfileResponse> {
  return httpClient.put<ProfessionalProfileResponse>('/api/professionals/me', payload);
}

/** POST /api/professionals/me/profile-image — PROFESSIONAL only, multipart field "file". */
export function uploadProfessionalProfileImage(file: File): Promise<ProfileImageUploadResponse> {
  const formData = new FormData();
  formData.append('file', file);
  return httpClient.post<ProfileImageUploadResponse>('/api/professionals/me/profile-image', formData);
}

/** GET /api/professionals/{id} — either role, no route gate. Public detail view. */
export function getProfessionalProfile(professionalId: number): Promise<ProfessionalProfileResponse> {
  return httpClient.get<ProfessionalProfileResponse>(`/api/professionals/${professionalId}`);
}
