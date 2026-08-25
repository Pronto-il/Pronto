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
  /**
   * MS4: every category this professional serves, in the catalogue's own display order. The
   * first entry is what compact surfaces show as the primary trade — an ordering convention, not
   * a stored flag, so nothing has to keep a "primary" field correct across edits.
   */
  categoryIds: number[];
  fullName: string;
  /**
   * MS4: canonical `service_regions` id, replacing the old free-text `serviceArea`.
   * `null` for a professional who registered before MS4 and whose old text named no recognisable
   * region — the profile editor then asks them to choose rather than showing an invented one.
   */
  serviceRegionId: number | null;
  serviceRegionNameHe: string | null;
  /** MS4: the city ETA is measured from. Always one of `serviceCityIds`. */
  baseCityId: number | null;
  /** The base city's Hebrew name, resolved server-side. */
  city: string | null;
  /** MS4: every canonical city they serve, in catalogue order. */
  serviceCityIds: number[];
  serviceCityNamesHe: string[];
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

/**
 * MS4 §18: everything registration collects about coverage and trades is editable here too.
 * `categoryId` used to be excluded from this DTO precisely because a professional could not
 * change their single trade at all; that restriction is what MS4 lifts.
 */
export interface UpdateProfessionalProfileRequest {
  fullName: string;
  serviceRegionId: number;
  /** At least one; every one inside `serviceRegionId`. */
  serviceCityIds: number[];
  /** Must be one of `serviceCityIds`. */
  baseCityId: number;
  /** At least one; every one an existing category. */
  categoryIds: number[];
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

/** PUT /api/professionals/me — PROFESSIONAL only, allowlist DTO (no id/approvalStatus/etc). */
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

// ---------------------------------------------------------------------------------------
// Production MS2 -- the professional's current device position.
// ---------------------------------------------------------------------------------------

/**
 * Body of `PUT /api/professionals/me/location`, mirroring
 * `professionals.dto.UpdateProfessionalLocationRequest` exactly.
 *
 * There is deliberately no `professionalId`: the subject is always the caller. `accuracyMeters`
 * is required, because a fix with no accuracy figure cannot be quality-checked, and MS2's whole
 * position is that an unqualified fix must not be treated as a precise one.
 */
export interface UpdateProfessionalLocationRequest {
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  /** ISO-8601, device clock. The server stamps its own receive time and trusts the stricter. */
  capturedAt: string;
}

/**
 * Response of `PUT`/`GET /api/professionals/me/location` — the professional's own view of their
 * own position state.
 *
 * Carries **no coordinates**, by design: the client already knows where it is, so returning them
 * would add nothing while creating a response shape that a later change could widen. What is
 * useful is whether the platform currently considers them routable and, if not, why.
 */
export interface ProfessionalLocationStatusResponse {
  usable: boolean;
  /** Server receive time of the stored reading; `null` if none has ever been sent. */
  updatedAt: string | null;
  accuracyMeters: number | null;
  /**
   * A `maps.RouteUnavailableReason` name, or `null` when `usable`. Stable code — branch on it
   * rather than on the message, exactly as with `ApiError.code`.
   */
  reason: string | null;
  /**
   * How long a reading stays usable, from `updatedAt`. Read from the server so the client
   * schedules its next refresh from the server's own rule instead of hardcoding a duplicate.
   */
  staleAfterSeconds: number;
}

/** `PUT /api/professionals/me/location` — PROFESSIONAL only. Replace semantics, not append. */
export function updateMyLocation(
  request: UpdateProfessionalLocationRequest,
): Promise<ProfessionalLocationStatusResponse> {
  return httpClient.put<ProfessionalLocationStatusResponse>('/api/professionals/me/location', request);
}

/** `GET /api/professionals/me/location` — PROFESSIONAL only. */
export function getMyLocationStatus(): Promise<ProfessionalLocationStatusResponse> {
  return httpClient.get<ProfessionalLocationStatusResponse>('/api/professionals/me/location');
}
