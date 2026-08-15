export { ApiError, setAuthTokenGetter, httpClient } from './httpClient';

export { registerCustomer, registerProfessional, verifyEmail, login } from './auth';
export type {
  UserRole,
  UserSummary,
  RegisterCustomerPayload,
  RegisterProfessionalPayload,
  RegisterResponse,
  VerifyPayload,
  VerifyResponse,
  LoginPayload,
  LoginResponse,
} from './auth';

export { getMe } from './users';
export type { ProfessionalInfo, UserMeResponse } from './users';

export { CATEGORIES, getCategoryNameHe } from './categories';
export type { Category } from './categories';

export { GENERIC_ERROR_MESSAGE, getFieldErrorMessages } from './errorMessages';
