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
  /**
   * MS1 (D-G): the professional's **self-view only** — `null` for every other caller, so a
   * browsing customer can't learn that a named professional was rejected.
   */
  approvalStatus: string | null;
  /**
   * MS1 (D-G): the neutral flag everyone gets — is this professional marketplace-eligible
   * (`approvalStatus === 'APPROVED'` **and** onboarding complete: category-valid sub-services,
   * an enabled working-hours day, verification document). Backend-computed per request
   * (`professionals.ProfessionalEligibility`); it says "not bookable right now", never why.
   */
  bookable: boolean;
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

/**
 * MS11 — Services & Sub-services (`docs/architecture/product-ms11-sub-services-design.md`
 * §3.1-§3.2). Shapes verified directly against
 * `professionals.dto.CategoryWithSubServicesResponse`/`SubServiceResponse`/
 * `MySubServicesResponse`/`UpdateSubServicesRequest`.
 */
export interface SubServiceResponse {
  id: number;
  code: string;
  nameHe: string;
  nameEn: string;
  displayOrder: number;
}

export interface CategoryWithSubServicesResponse {
  id: number;
  code: string;
  nameHe: string;
  nameEn: string;
  displayOrder: number;
  subServices: SubServiceResponse[];
}

export interface MySubServicesResponse {
  subServiceIds: number[];
}

/** GET /api/categories — public, no auth required (called authenticated here like every other call on this page). */
export function getCategoriesWithSubServices(): Promise<CategoryWithSubServicesResponse[]> {
  return httpClient.get<CategoryWithSubServicesResponse[]>('/api/categories');
}

/** GET /api/professionals/me/sub-services — PROFESSIONAL only. */
export function getMySubServices(): Promise<MySubServicesResponse> {
  return httpClient.get<MySubServicesResponse>('/api/professionals/me/sub-services');
}

/** PUT /api/professionals/me/sub-services — PROFESSIONAL only, full-replace, empty list allowed. */
export function updateMySubServices(subServiceIds: number[]): Promise<MySubServicesResponse> {
  return httpClient.put<MySubServicesResponse>('/api/professionals/me/sub-services', { subServiceIds });
}
