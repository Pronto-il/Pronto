import type { SubServicePriceSelection } from './professionals';
import { httpClient } from './httpClient';
import type { WorkingHoursItemRequest } from './availability';

/**
 * MS1 (D-F): `ADMIN` joins the two self-service roles — `ck_users_role` permits it as of `V40`
 * and it is what gates the operator verification surface (`/admin/professionals`). It is
 * deliberately **not** a role anyone can register as: `POST /api/auth/register` refuses
 * `role = ADMIN` outright, and an operator account is created by a documented operational step.
 */
export type UserRole = 'CUSTOMER' | 'PROFESSIONAL' | 'ADMIN';

/** The subset of {@link UserRole} a client may send to `POST /api/auth/register`. */
export type RegisterableRole = Exclude<UserRole, 'ADMIN'>;

export interface UserSummary {
  id: number;
  fullName: string;
  email: string;
  role: UserRole;
}

// ---------------------------------------------------------------------------
// Production MS1 — the auth state machine
// ---------------------------------------------------------------------------

/**
 * What the server says to do next. Registration and login are no longer single round trips
 * (registration is three, login is two), and either can be interrupted — a closed tab, an expired
 * code, an account that stopped halfway through registering last week. The client branches on this
 * rather than inferring a state from which endpoint it happened to call.
 *
 * Mirrors `backend/.../auth/dto/AuthNextStep.java`.
 */
export type AuthNextStep =
  | 'VERIFY_EMAIL'
  | 'VERIFY_PHONE'
  | 'LOGIN_OTP'
  | 'LOGIN'
  | 'AUTHENTICATED';

export type OtpChannel = 'EMAIL' | 'SMS';

/**
 * An issued OTP challenge — as much of it as a client is allowed to know.
 *
 * Note what is absent: the code, and the unmasked destination. Everything needed to render "we sent
 * a code to `d***@example.com`, it expires in 15 minutes" is here; nothing else is.
 */
export interface OtpChallenge {
  /** Opaque handle. The only thing ever sent back — never the email or phone. */
  challengeId: string;
  channel: OtpChannel;
  /** `d***@example.com` or `+9725*****67`. */
  destinationMasked: string;
  expiresInSeconds: number;
  /**
   * Whether the provider accepted the message. `false` means the challenge is live but nothing
   * arrived, so the UI should lead with "resend" rather than with a code field.
   */
  delivered: boolean;
}

/** An issued session. Same shape the old `LoginResponse` had. */
export interface AuthSession {
  token: string;
  tokenType: string;
  expiresIn: number;
  user: UserSummary;
}

/**
 * The single response shape for every step of registration and login. Exactly one of
 * `challenge`/`session` is populated: `AUTHENTICATED` carries a session, `LOGIN` carries neither,
 * every other step carries a challenge.
 */
export interface AuthStepResponse {
  nextStep: AuthNextStep;
  challenge: OtpChallenge | null;
  session: AuthSession | null;
  emailVerified: boolean;
  phoneVerified: boolean;
}

// ---------------------------------------------------------------------------
// Registration
// ---------------------------------------------------------------------------

/**
 * Matches `backend/.../auth/dto/DefaultAddressRequest.java` field names exactly.
 * `city`/`street`/`houseNumber` required; the rest optional.
 *
 * **No longer sent by {@link registerCustomer}** — registration does not collect an address (see
 * that function). Retained because the type still describes the endpoint's optional
 * `customer.defaultAddress` object, which the backend continues to accept from the seed/demo
 * paths that construct accounts directly.
 */
export interface RegisterAddressPayload {
  city: string;
  street: string;
  houseNumber: string;
  apartment?: string;
  floor?: string;
  entrance?: string;
  addressNotes?: string;
  /** The place the customer selected from autocomplete (`V55`). Required by the backend whenever
   *  this object is present at all — a brand-new address has never been confirmed by anybody, so
   *  there is nothing to grandfather. */
  placeId?: string;
  formattedAddress?: string;
  latitude?: number;
  longitude?: number;
}

export interface RegisterCustomerPayload {
  fullName: string;
  email: string;
  /** Production MS1: required, and now a top-level identity field rather than customer detail. */
  phone: string;
  password: string;
}

export interface RegisterProfessionalPayload {
  fullName: string;
  email: string;
  /**
   * Production MS1 — **new, and required**. Professionals had no phone number at all before this
   * milestone; they now supply one on the same field a customer does, and verify it the same way.
   */
  phone: string;
  password: string;
  /** MS4 — at least one; a professional may register for several trades. */
  categoryIds: number[];
  /** MS4 — a canonical `service_regions` id, never free text. */
  serviceRegionId: number;
  /** MS4 — at least one canonical city, every one inside `serviceRegionId`. */
  serviceCityIds: number[];
  /** MS4 — must be one of `serviceCityIds`; the city ETA is measured from. */
  baseCityId: number;
  basePrice: number;
  /**
   * MS1 (D4/D7) — required, at least one, every id belonging to one of `categoryIds`.
   * A cross-category id is refused by the backend with `400 CATEGORY_MISMATCH`.
   */
  subServiceIds?: number[];
  /**
   * The priced form of the same choice: each selected sub-service with what the professional
   * charges for it. Sent instead of `subServiceIds`, never alongside it — the server treats this as
   * authoritative when both are present, so sending both would create two sources of truth for one
   * list. A null/omitted `price` means "not stated", which is legal: a registrant may finish signing
   * up and price their services later from their profile.
   */
  subServices?: SubServicePriceSelection[];
  /**
   * MS1 (D4/D7) — required. Exactly 7 entries (weekday `0`-`6`, no duplicates/gaps) with at
   * least one `enabled` day, validated server-side by `WorkingHoursValidator`.
   */
  workingHours: WorkingHoursItemRequest[];
  /** Required — `POST /api/auth/register`'s `verificationDocument` multipart part. */
  verificationDocument: File;
  /** Optional — the `profilePhoto` multipart part. */
  profilePhoto?: File | null;
}

/**
 * The JSON shape sent as the `data` part of `POST /api/auth/register`'s `multipart/form-data`
 * body — mirrors `backend/.../auth/dto/RegisterRequest.java`.
 *
 * `phone` sits alongside `email` and `password`, not inside `customer`: as of Production MS1 it is
 * an identity every account of every role has, not a customer contact detail.
 */
interface RegisterRequestData {
  role: RegisterableRole;
  fullName: string;
  email: string;
  phone: string;
  password: string;
  customer: { defaultAddress: RegisterAddressPayload } | null;
  professional: {
    categoryIds: number[];
    serviceRegionId: number;
    serviceCityIds: number[];
    baseCityId: number;
    basePrice: number;
    subServiceIds?: number[];
    subServices?: SubServicePriceSelection[];
    workingHours: WorkingHoursItemRequest[];
  } | null;
}

function buildRegisterFormData(
  data: RegisterRequestData,
  files: { verificationDocument?: File; profilePhoto?: File | null },
): FormData {
  const formData = new FormData();
  formData.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }));
  if (files.verificationDocument) {
    formData.append('verificationDocument', files.verificationDocument);
  }
  if (files.profilePhoto) {
    formData.append('profilePhoto', files.profilePhoto);
  }
  return formData;
}

/**
 * `POST /api/auth/register` for a customer.
 *
 * Returns `nextStep: 'VERIFY_EMAIL'` and a challenge — **never a token**. The account exists but is
 * unverified until both the email and the phone code are redeemed.
 *
 * **`customer` is now `null`**: an address is no longer part of opening an account. It is asked
 * for in the booking flow, after AI classification and immediately before it is needed, and saved
 * to the profile only if the customer asks for that ("הפוך את זה לכתובת הבית") or edits it on the
 * profile screen. The backend treats `customer.defaultAddress` as optional, so this is a smaller
 * valid payload rather than a payload with holes in it.
 */
export function registerCustomer(payload: RegisterCustomerPayload): Promise<AuthStepResponse> {
  const data: RegisterRequestData = {
    role: 'CUSTOMER',
    fullName: payload.fullName,
    email: payload.email,
    phone: payload.phone,
    password: payload.password,
    customer: null,
    professional: null,
  };
  return httpClient.post<AuthStepResponse>(
    '/api/auth/register',
    buildRegisterFormData(data, {}),
    { auth: false },
  );
}

/**
 * `POST /api/auth/register` for a professional — sends the required verification document and
 * optional profile photo as separate multipart parts alongside the `data` part.
 *
 * MS1: a successful response means the application was **submitted for review**
 * (`approval_status = PENDING`), not that the professional is now listed to customers.
 */
export function registerProfessional(
  payload: RegisterProfessionalPayload,
): Promise<AuthStepResponse> {
  const data: RegisterRequestData = {
    role: 'PROFESSIONAL',
    fullName: payload.fullName,
    email: payload.email,
    phone: payload.phone,
    password: payload.password,
    customer: null,
    professional: {
      categoryIds: payload.categoryIds,
      serviceRegionId: payload.serviceRegionId,
      serviceCityIds: payload.serviceCityIds,
      baseCityId: payload.baseCityId,
      basePrice: payload.basePrice,
      subServiceIds: payload.subServiceIds,
      subServices: payload.subServices,
      workingHours: payload.workingHours,
    },
  };
  return httpClient.post<AuthStepResponse>(
    '/api/auth/register',
    buildRegisterFormData(data, {
      verificationDocument: payload.verificationDocument,
      profilePhoto: payload.profilePhoto,
    }),
    { auth: false },
  );
}

// ---------------------------------------------------------------------------
// Contact availability
// ---------------------------------------------------------------------------

/** The two identities `users` holds unique. Mirrors `backend/.../auth/dto/ContactField.java`. */
export type ContactField = 'EMAIL' | 'PHONE';

/** Mirrors `backend/.../auth/dto/AvailabilityResponse.java` — one boolean, and the field it is
 *  about. Deliberately carries no account information of any kind. */
export interface AvailabilityResponse {
  field: ContactField;
  available: boolean;
}

/**
 * `POST /api/auth/availability` — would `POST /api/auth/register` accept this email or phone?
 *
 * Exists so a registration form can put "already registered" under the field instead of on the
 * final summary screen. Three things to know before calling it:
 *
 * 1. **It is advisory, never permission.** The answer is true when given and can be false a
 *    moment later; registration performs its own duplicate checks and the unique indexes settle
 *    the race. A caller must still handle `DUPLICATE_EMAIL`/`DUPLICATE_PHONE` at submit.
 * 2. **It is rate limited more tightly than anything else on the registration path** (20 per 10
 *    minutes per client, versus registration's own 10), because it is the cheapest form of the
 *    account-existence disclosure `register` already makes. Call it on blur, not on keystroke —
 *    a debounced-per-character caller would burn the budget of a customer who simply types
 *    slowly, and then the real check would 429.
 * 3. **A malformed value is a `400 VALIDATION_ERROR`, not `available: false`.** The backend
 *    applies the same email constraint and the same libphonenumber rule registration applies, so
 *    this is also how a client learns that `03-1234567` is not a number that can receive an SMS.
 */
export function checkContactAvailability(
  field: ContactField,
  value: string,
): Promise<AvailabilityResponse> {
  return httpClient.post<AvailabilityResponse>(
    '/api/auth/availability',
    { field, value },
    { auth: false },
  );
}

// ---------------------------------------------------------------------------
// OTP redemption
// ---------------------------------------------------------------------------

/** Every OTP submission is the same shape: a challenge handle and six digits. */
export interface OtpSubmission {
  challengeId: string;
  code: string;
}

/** `POST /api/auth/verify-email`. On success the phone code is dispatched automatically. */
export function verifyEmail(payload: OtpSubmission): Promise<AuthStepResponse> {
  return httpClient.post<AuthStepResponse>('/api/auth/verify-email', payload, { auth: false });
}

/** `POST /api/auth/verify-phone` — registration completes here, and a session is issued. */
export function verifyPhone(payload: OtpSubmission): Promise<AuthStepResponse> {
  return httpClient.post<AuthStepResponse>('/api/auth/verify-phone', payload, { auth: false });
}

/** `POST /api/auth/otp/resend`. Subject to a 60s cooldown and an hourly ceiling per purpose. */
export function resendOtp(challengeId: string): Promise<OtpChallenge> {
  return httpClient.post<OtpChallenge>('/api/auth/otp/resend', { challengeId }, { auth: false });
}

// ---------------------------------------------------------------------------
// Login
// ---------------------------------------------------------------------------

export interface LoginPayload {
  /** Email address **or** phone number. The server decides which, and both reach the same account. */
  identifier: string;
  password: string;
}

/**
 * `POST /api/auth/login` — password check only.
 *
 * Returns a challenge and **never a token**. The code goes to the channel matching the identifier
 * that was used: an email address gets an email, a phone number gets an SMS.
 */
export function login(payload: LoginPayload): Promise<AuthStepResponse> {
  return httpClient.post<AuthStepResponse>('/api/auth/login', payload, { auth: false });
}

/** `POST /api/auth/login/otp` — the only endpoint besides `verify-phone` that issues a session. */
export function loginOtp(payload: OtpSubmission): Promise<AuthStepResponse> {
  return httpClient.post<AuthStepResponse>('/api/auth/login/otp', payload, { auth: false });
}

// ---------------------------------------------------------------------------
// Phone capture (legacy accounts) and password recovery
// ---------------------------------------------------------------------------

/**
 * `POST /api/auth/phone/capture` — **authenticated**, unlike every other `/api/auth/*` route.
 *
 * The way out of the legacy cohort: an account created before Production MS1 has no verified phone,
 * so the backend refuses its bookings, SOS activations and marketplace listing with
 * `PHONE_VERIFICATION_REQUIRED` until it supplies one here and redeems the code at
 * {@link verifyPhone}.
 */
export function capturePhone(phone: string): Promise<OtpChallenge> {
  return httpClient.post<OtpChallenge>('/api/auth/phone/capture', { phone });
}

/**
 * `POST /api/auth/password-reset/request`.
 *
 * Always resolves with a well-formed challenge, whether or not the account exists — the response is
 * deliberately identical either way, so this endpoint cannot be used to discover who has an
 * account. A challenge for an address nobody registered simply fails at confirm, exactly as a wrong
 * code does.
 */
export function requestPasswordReset(identifier: string): Promise<OtpChallenge> {
  return httpClient.post<OtpChallenge>(
    '/api/auth/password-reset/request',
    { identifier },
    { auth: false },
  );
}

export interface PasswordResetConfirmPayload {
  challengeId: string;
  code: string;
  newPassword: string;
}

/** `POST /api/auth/password-reset/confirm`. Issues no session — the user signs in normally after. */
export function confirmPasswordReset(payload: PasswordResetConfirmPayload): Promise<void> {
  return httpClient.post<void>('/api/auth/password-reset/confirm', payload, { auth: false });
}
