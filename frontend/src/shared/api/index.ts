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
export type { ProfessionalInfo, UserMeResponse, UserMeDefaultAddress } from './users';

export { CATEGORIES, getCategoryNameHe } from './categories';
export type { Category } from './categories';

export { GENERIC_ERROR_MESSAGE, getFieldErrorMessages } from './errorMessages';

export { uploadImage } from './storage';
export type { UploadImageResponse } from './storage';

export { classifyIssue, createIssue, getIssue } from './issues';
export type {
  IssueUrgencyType,
  ClarificationAnswer,
  ClassifyIssueRequest,
  ClassifyQuestion,
  ClassifyIssueResponse,
  CreateIssueRequest,
  IssueImage,
  IssueResponse,
  LatestOrderSummary,
  IssueDetailResponse,
} from './issues';

export {
  getProfessionalsForIssue,
  getProfessionalSlots,
  createOrder,
  acceptOrder,
  rejectOrder,
  cancelOrder,
  getOrder,
  getMyOrders,
  getSosProfessionalsForIssue,
  createSosOrder,
} from './bookings';
export type {
  OrderStatus,
  CancelledBy,
  ProfessionalSort,
  ServiceLocation,
  ProfessionalCard,
  ProfessionalListingResponse,
  AvailabilitySlotItem,
  ProfessionalSlotsResponse,
  CreateOrderRequest,
  OrderResponse,
  OrderDetailResponse,
  OrderSummary,
  MyOrdersResponse,
  CreateSosOrderRequest,
} from './bookings';

export {
  createAvailabilitySlot,
  getMyAvailabilitySlots,
  getSosAvailability,
  updateSosAvailability,
} from './availability';
export type {
  CreateSlotRequest,
  SlotResponse,
  SlotListItem,
  SlotListResponse,
  SosAvailabilityResponse,
} from './availability';

export { createReview } from './reviews';
export type { CreateReviewRequest, ReviewResponse } from './reviews';

export { getNotifications, markNotificationRead, markAllNotificationsRead } from './notifications';
export type {
  NotificationMessageType,
  NotificationResponse,
  NotificationsListResponse,
  MarkAllReadResponse,
} from './notifications';
