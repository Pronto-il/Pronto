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

export { getMe, deleteMe, updateMe } from './users';
export type { ProfessionalInfo, UserMeResponse, UserMeDefaultAddress, UpdateUserMeRequest } from './users';

export { CATEGORIES, getCategoryNameHe } from './categories';
export type { Category } from './categories';

export { GENERIC_ERROR_MESSAGE, getFieldErrorMessages } from './errorMessages';

export { uploadImage, getPresignedImageUrls } from './storage';
export type { UploadImageResponse, PresignedImageUrlEntry, PresignedImageUrlsResponse } from './storage';

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
  ClarificationEntry,
  ProntoAnalysis,
} from './issues';

export {
  getProfessionalsForIssue,
  prefetchProfessionalListing,
  getAvailableWindows,
  createOrder,
  acceptOrder,
  rejectOrder,
  cancelOrder,
  markOnTheWay,
  completeOrder,
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
  AvailableWindow,
  AvailableWindowsResponse,
  CreateOrderRequest,
  OrderResponse,
  OrderDetailResponse,
  OrderSummary,
  MyOrdersResponse,
  CreateSosOrderRequest,
} from './bookings';

export {
  createAvailabilitySlot,
  updateAvailabilitySlot,
  deleteAvailabilitySlot,
  getMyAvailabilitySlots,
  getSosAvailability,
  updateSosAvailability,
  getWorkingHours,
  updateWorkingHours,
  getAvailabilityCalendar,
  createAvailabilityBlock,
  updateAvailabilityBlock,
  deleteAvailabilityBlock,
} from './availability';
export type {
  CreateSlotRequest,
  SlotResponse,
  SlotListItem,
  SlotListResponse,
  SosAvailabilityResponse,
  WorkingHoursItem,
  WorkingHoursItemRequest,
  WorkingHoursListResponse,
  SegmentType,
  CalendarSegment,
  CalendarResponse,
  CreateBlockRequest,
  BlockResponse,
} from './availability';

export { createReview, getReviews } from './reviews';
export type { CreateReviewRequest, ReviewResponse, ReviewListResponse } from './reviews';

export { addFavorite, removeFavorite, getFavorites } from './favorites';
export type { FavoriteProfessionalSummary, FavoritesListResponse } from './favorites';

export {
  getMyProfessionalProfile,
  updateMyProfessionalProfile,
  uploadProfessionalProfileImage,
  getProfessionalProfile,
  getCategoriesWithSubServices,
  getMySubServices,
  updateMySubServices,
} from './professionals';
export type {
  ProfessionalProfileResponse,
  UpdateProfessionalProfileRequest,
  ProfileImageUploadResponse,
  SubServiceResponse,
  CategoryWithSubServicesResponse,
  MySubServicesResponse,
} from './professionals';

export { getNotifications, markNotificationRead, markAllNotificationsRead } from './notifications';
export type {
  NotificationMessageType,
  NotificationResponse,
  NotificationsListResponse,
  MarkAllReadResponse,
} from './notifications';
