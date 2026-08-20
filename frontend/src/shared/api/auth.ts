import { httpClient } from './httpClient';

export type UserRole = 'CUSTOMER' | 'PROFESSIONAL';

export interface UserSummary {
  id: number;
  fullName: string;
  email: string;
  role: UserRole;
}

/**
 * Matches `backend/src/main/java/com/pronto/auth/dto/DefaultAddressRequest.java` field
 * names exactly (`AddressFormFields`/`AddressValue` already use these names, see
 * `shared/components/addressTypes.ts`). `city`/`street`/`houseNumber` required;
 * the rest optional.
 */
export interface RegisterAddressPayload {
  city: string;
  street: string;
  houseNumber: string;
  apartment?: string;
  floor?: string;
  entrance?: string;
  addressNotes?: string;
}

export interface RegisterCustomerPayload {
  fullName: string;
  email: string;
  password: string;
  phone: string;
  address: RegisterAddressPayload;
}

export interface RegisterProfessionalPayload {
  fullName: string;
  email: string;
  password: string;
  categoryId: number;
  serviceArea: string;
  basePrice: number;
  /** Required — `POST /api/auth/register`'s `verificationDocument` multipart part. */
  verificationDocument: File;
  /** Optional — the `profilePhoto` multipart part. */
  profilePhoto?: File | null;
}

export interface RegisterResponse {
  userId: number;
  role: UserRole;
  email: string;
  emailVerified: boolean;
}

/**
 * The JSON shape sent as the `data` part of `POST /api/auth/register`'s
 * `multipart/form-data` body — mirrors `backend/.../auth/dto/RegisterRequest.java`
 * (`customer`/`professional` are mutually exclusive, nested, role-specific payloads, not
 * one flat object of nullable fields). See `docs/architecture/api-contract.md` §2.1.
 */
interface RegisterRequestData {
  role: UserRole;
  fullName: string;
  email: string;
  password: string;
  customer: { defaultAddress: RegisterAddressPayload; phone: string } | null;
  professional: { categoryId: number; serviceArea: string; basePrice: number } | null;
}

/** Empty/whitespace-only optional address fields are omitted from the JSON payload rather than sent as `''`. */
function undefinedIfBlank(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

/**
 * Builds the `multipart/form-data` body for `POST /api/auth/register`: a `data` part
 * (`Blob`, `application/json`, matching `RegisterRequestData`) plus optional
 * `verificationDocument`/`profilePhoto` file parts (professional-only). See
 * `docs/architecture/api-contract.md` §2.1's request-parts table.
 */
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

/** `POST /api/auth/register` for a customer — sends the collected default address. */
export function registerCustomer(payload: RegisterCustomerPayload): Promise<RegisterResponse> {
  const data: RegisterRequestData = {
    role: 'CUSTOMER',
    fullName: payload.fullName,
    email: payload.email,
    password: payload.password,
    customer: {
      defaultAddress: {
        city: payload.address.city,
        street: payload.address.street,
        houseNumber: payload.address.houseNumber,
        apartment: undefinedIfBlank(payload.address.apartment),
        floor: undefinedIfBlank(payload.address.floor),
        entrance: undefinedIfBlank(payload.address.entrance),
        addressNotes: undefinedIfBlank(payload.address.addressNotes),
      },
      phone: payload.phone,
    },
    professional: null,
  };
  return httpClient.post<RegisterResponse>(
    '/api/auth/register',
    buildRegisterFormData(data, {}),
    { auth: false },
  );
}

/**
 * `POST /api/auth/register` for a professional — sends the required verification document
 * and optional profile photo as separate multipart parts alongside the `data` part.
 */
export function registerProfessional(
  payload: RegisterProfessionalPayload,
): Promise<RegisterResponse> {
  const data: RegisterRequestData = {
    role: 'PROFESSIONAL',
    fullName: payload.fullName,
    email: payload.email,
    password: payload.password,
    customer: null,
    professional: {
      categoryId: payload.categoryId,
      serviceArea: payload.serviceArea,
      basePrice: payload.basePrice,
    },
  };
  return httpClient.post<RegisterResponse>(
    '/api/auth/register',
    buildRegisterFormData(data, {
      verificationDocument: payload.verificationDocument,
      profilePhoto: payload.profilePhoto,
    }),
    { auth: false },
  );
}

export interface VerifyPayload {
  email: string;
  code: string;
}

export interface VerifyResponse {
  userId: number;
  emailVerified: boolean;
}

/** `POST /api/auth/verify`. Does not issue a JWT — the caller must still log in after. */
export function verifyEmail(payload: VerifyPayload): Promise<VerifyResponse> {
  return httpClient.post<VerifyResponse>('/api/auth/verify', payload, { auth: false });
}

export interface LoginPayload {
  email: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  tokenType: string;
  expiresIn: number;
  user: UserSummary;
}

/** `POST /api/auth/login`. */
export function login(payload: LoginPayload): Promise<LoginResponse> {
  return httpClient.post<LoginResponse>('/api/auth/login', payload, { auth: false });
}
