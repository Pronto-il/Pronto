export {
  ApiError,
  setAuthTokenGetter,
  setUnauthorizedHandler,
  setPhoneVerificationRequiredHandler,
  httpClient,
  API_BASE_URL,
} from './httpClient';

export {
  registerCustomer,
  registerProfessional,
  verifyEmail,
  verifyPhone,
  resendOtp,
  login,
  loginOtp,
  capturePhone,
  requestPasswordReset,
  confirmPasswordReset,
} from './auth';
export type { ListingSubject } from './bookings';
export type {
  UserRole,
  RegisterableRole,
  UserSummary,
  RegisterCustomerPayload,
  RegisterProfessionalPayload,
  LoginPayload,
  AuthNextStep,
  AuthSession,
  AuthStepResponse,
  OtpChallenge,
  OtpChannel,
  OtpSubmission,
  PasswordResetConfirmPayload,
} from './auth';

export * from './resourceKeys';

export { getMe, deleteMe, updateMe } from './users';
export type { ProfessionalInfo, UserMeResponse, UserMeDefaultAddress, UpdateUserMeRequest } from './users';

export {
  CATEGORIES,
  getCategoryNameHe,
  getProfessionalNameHe,
  getCategoryNamesHe,
  formatCategorySummary,
} from './categories';
export type { Category } from './categories';

export {
  getServiceAreas,
  citiesForRegion,
  allCities,
  regionForCity,
  cityNames,
} from './serviceAreas';
export type { ServiceRegionResponse, ServiceCityResponse } from './serviceAreas';

export { GENERIC_ERROR_MESSAGE, getFieldErrorMessages } from './errorMessages';

export { uploadImage, getPresignedImageUrls } from './storage';
export type { UploadImageResponse, PresignedImageUrlEntry, PresignedImageUrlsResponse } from './storage';

export { classifyIssue, createIssue, getIssue, updateIssueCategory } from './issues';
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
  markArrived,
  completeOrder,
  getOrder,
  getMyOrders,
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
  ArrivalRequest,
  OrderDetailResponse,
  OrderSummary,
  MyOrdersResponse,
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
  getAvailabilityBlock,
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
  updateMyLocation,
  getMyLocationStatus,
} from './professionals';
export type {
  ProfessionalProfileResponse,
  UpdateProfessionalProfileRequest,
  ProfileImageUploadResponse,
  SubServiceResponse,
  CategoryWithSubServicesResponse,
  MySubServicesResponse,
  UpdateProfessionalLocationRequest,
  ProfessionalLocationStatusResponse,
} from './professionals';

export {
  createSosRequest,
  getMySosRequests,
  getSosRequest,
  getSosCandidates,
  getSosTimeline,
  expandSosSearch,
  selectSosProfessional,
  cancelSosRequest,
  getMySosOffers,
  getSosOffer,
  acceptSosOffer,
  rejectSosOffer,
  confirmSosRequest,
  markSosOnTheWay,
  markSosArrived,
  completeSosRequest,
  isSosTerminalStatus,
  hasSosSelection,
  isSosSearching,
  isSosOfferOpen,
  isSosOfferResolved,
  SOS_ETA_MIN_MINUTES,
  SOS_ETA_MAX_MINUTES,
} from './sos';
export type {
  SosRequestStatus,
  SosOfferStatus,
  SosUrgency,
  SosActorType,
  CreateSosRequestPayload,
  SosRequestResponse,
  SosCandidate,
  SosCandidatesResponse,
  SosOfferResponse,
  SosOffersListResponse,
  SosEventType,
  SosEventResponse,
  SosTimelineResponse,
  SosRequestsListResponse,
  SosRealtimeEventType,
  SosRealtimeMessage,
} from './sos';

export { getNotifications, markNotificationRead, markAllNotificationsRead } from './notifications';
export type {
  NotificationMessageType,
  NotificationResponse,
  NotificationsListResponse,
  MarkAllReadResponse,
} from './notifications';

export {
  listProfessionalsForReview,
  getProfessionalReviewDetail,
  getVerificationDocumentUrl,
  approveProfessional,
  rejectProfessional,
  REJECTION_REASON_MAX_LENGTH,
} from './adminProfessionals';
export type {
  ProfessionalApprovalStatus,
  ProfessionalApprovalSummary,
  ProfessionalApprovalListResponse,
  ProfessionalReviewDetail,
  VerificationDocumentUrlResponse,
} from './adminProfessionals';
