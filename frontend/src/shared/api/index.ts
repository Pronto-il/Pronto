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

export { uploadImage } from './storage';
export type { UploadImageResponse } from './storage';

export { classifyIssue, createIssue } from './issues';
export type {
  IssueUrgencyType,
  ClarificationAnswer,
  ClassifyIssueRequest,
  ClassifyQuestion,
  ClassifyIssueResponse,
  CreateIssueRequest,
  IssueImage,
  IssueResponse,
} from './issues';
