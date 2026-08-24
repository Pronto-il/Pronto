import { httpClient } from './httpClient';

/**
 * `/api/admin/professionals/**` — MS1's minimal **operator** surface
 * (`professionals.controller.AdminProfessionalsController`, design
 * `docs/architecture/ms1-professional-verification-design.md` D-F). Every shape below was read
 * off the backend records themselves (`professionals.dto.ProfessionalApprovalSummary`,
 * `ProfessionalReviewDetailResponse`, `VerificationDocumentUrlResponse`,
 * `RejectProfessionalRequest`), not from prose.
 *
 * Its own file rather than more functions in `professionals.ts`, mirroring the backend's own
 * prefix split: `professionals.ts` is the customer/professional-facing client, this is the
 * `ADMIN`-only one. Keeping them apart means "what can an operator call" is one file to read,
 * and no ordinary screen can reach for an operator call by autocomplete.
 *
 * **Authorization is the backend's.** Every route here is gated on `role = ADMIN` by
 * `professionals.config.ProfessionalsWebConfig`'s `RoleRequiredInterceptor`, which answers
 * `403 FORBIDDEN` before it resolves a request body. The `RequireAuth role="ADMIN"` route guard
 * in `app/router.tsx` is UX only — it keeps the wrong person off a screen they'd see nothing on,
 * and is not what protects the data.
 */

/**
 * The lifecycle values `ck_professionals_approval_status` permits after `V40`. `DISABLED` is
 * reserved for MS7 and unreachable in MS1 (nothing can write it) — it is listed because the
 * backend accepts it as a filter value, not because a screen should offer it.
 *
 * Note that the response fields below are typed `string`, **not** this union: they carry whatever
 * is in the column, and an operator screen must render an unrecognized value as "unknown" rather
 * than crash or leak the raw code. See `features/admin/approvalPresentation.ts`.
 */
export type ProfessionalApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'DISABLED';

/** One row of the operator queue. `fullName`/`email` are nullable — the backend leaves them
 *  `null` rather than failing the whole list if a professional's user row can't be loaded. */
export interface ProfessionalApprovalSummary {
  professionalId: number;
  userId: number;
  fullName: string | null;
  email: string | null;
  categoryIds: number[];
  serviceRegion: string | null;
  city: string | null;
  approvalStatus: string;
  /** Sub-services (category-valid), an enabled working-hours day, and a verification document
   *  are all present. Backend-computed (`ProfessionalEligibility.ONBOARDING_COMPLETE_JPQL`) —
   *  never re-derived here. */
  onboardingComplete: boolean;
  registeredAt: string;
  approvalReviewedAt: string | null;
}

export interface ProfessionalApprovalListResponse {
  professionals: ProfessionalApprovalSummary[];
}

/**
 * Everything the review screen gets. **No document key and no document URL** — the backend
 * deliberately keeps those off this response so a list-then-open traversal never mints a bearer
 * capability for a private compliance file; `getVerificationDocumentUrl()` mints one on demand.
 */
export interface ProfessionalReviewDetail {
  professionalId: number;
  userId: number;
  fullName: string;
  email: string;
  categoryIds: number[];
  serviceRegion: string | null;
  city: string | null;
  /** MS4: every canonical city this professional serves, in catalogue order. */
  serviceCityNamesHe: string[];
  bio: string | null;
  basePrice: number;
  approvalStatus: string;
  /** Marketplace-eligible right now: approved **and** onboarding complete. Backend-computed per
   *  request; `APPROVED` on its own does not imply it (D4). */
  bookable: boolean;
  hasVerificationDocument: boolean;
  /** Every sub-service row this professional has — **not** pre-filtered to their categories. */
  subServiceIds: number[];
  onboardingComplete: boolean;
  registeredAt: string;
  approvalReviewedAt: string | null;
  approvalReviewedBy: number | null;
  /** Non-null only while the decision in force is a rejection — the backend clears it on a later
   *  approval, so a stale reason can never be read as current. */
  approvalRejectionReason: string | null;
}

/**
 * `url` is a **bearer capability**: anyone holding it can fetch the document without
 * authenticating until it expires. Never log it, never persist it, never render it as a
 * link/`<img>`/`<iframe>` — hand it straight to a deliberate, user-initiated open and drop it.
 */
export interface VerificationDocumentUrlResponse {
  professionalId: number;
  url: string;
  expiresInSeconds: number;
}

/**
 * `GET /api/admin/professionals[?approvalStatus=…]` — oldest first. Omit `approvalStatus` for the
 * unfiltered list; an unrecognized value is a `400 VALIDATION_ERROR`, never a silent empty list.
 */
export function listProfessionalsForReview(
  approvalStatus?: ProfessionalApprovalStatus,
): Promise<ProfessionalApprovalListResponse> {
  const query = approvalStatus ? `?approvalStatus=${encodeURIComponent(approvalStatus)}` : '';
  return httpClient.get<ProfessionalApprovalListResponse>(`/api/admin/professionals${query}`);
}

/** `GET /api/admin/professionals/{id}`. */
export function getProfessionalReviewDetail(professionalId: number): Promise<ProfessionalReviewDetail> {
  return httpClient.get<ProfessionalReviewDetail>(`/api/admin/professionals/${professionalId}`);
}

/** `GET /api/admin/professionals/{id}/verification-document` — mints a short-lived URL. See
 *  `VerificationDocumentUrlResponse`. `404 NOT_FOUND` when no document was ever uploaded. */
export function getVerificationDocumentUrl(
  professionalId: number,
): Promise<VerificationDocumentUrlResponse> {
  return httpClient.get<VerificationDocumentUrlResponse>(
    `/api/admin/professionals/${professionalId}/verification-document`,
  );
}

/** `POST /api/admin/professionals/{id}/approve`. Legal from a pending or a rejected application;
 *  approving an already-approved one is `409 PROFESSIONAL_APPROVAL_INVALID_TRANSITION`. */
export function approveProfessional(professionalId: number): Promise<ProfessionalReviewDetail> {
  return httpClient.post<ProfessionalReviewDetail>(`/api/admin/professionals/${professionalId}/approve`);
}

/**
 * `POST /api/admin/professionals/{id}/reject`. `reason` is required server-side
 * (`@NotBlank @Size(max = 500)`). Legal only from a pending application — rejecting an approved
 * one is `409 PROFESSIONAL_APPROVAL_INVALID_TRANSITION` by design (suspension is MS7, not this
 * endpoint wearing a different hat).
 */
export function rejectProfessional(
  professionalId: number,
  reason: string,
): Promise<ProfessionalReviewDetail> {
  return httpClient.post<ProfessionalReviewDetail>(`/api/admin/professionals/${professionalId}/reject`, {
    reason,
  });
}

/** Mirrors `RejectProfessionalRequest`'s `@Size(max = 500)` / `professionals
 *  .approval_rejection_reason`'s column width, so the UI can stop a rejection the backend would
 *  refuse instead of letting the operator lose what they typed. */
export const REJECTION_REASON_MAX_LENGTH = 500;
