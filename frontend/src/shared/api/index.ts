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
  checkContactAvailability,
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
  ContactField,
  AvailabilityResponse,
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

export { getMe, deleteMe, updateMe, saveDefaultAddress, toCustomerAddressPayload } from './users';
export type {
  ProfessionalInfo,
  UserMeResponse,
  UserMeDefaultAddress,
  UpdateUserMeRequest,
  CustomerAddressPayload,
} from './users';

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
/** Guest upload session — see `guestSessionStore.ts`. `clearGuestSession` has exactly one caller
 *  (`BookingDraftProvider.clearDraft`); the token is otherwise attached by `httpClient` and minted
 *  by `uploadImage`, so no screen ever handles it. */
export { clearGuestSession, getGuestSessionToken } from './guestSession';
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
  IncompleteServiceLocationError,
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
export type { CreateReviewRequest, ReviewResponse, PublicReviewResponse, ReviewListResponse } from './reviews';

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
  MySubServiceItem,
  SubServicePriceSelection,
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
  SosCandidateState,
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

// Free-text field limits, mirrored from the backend request DTOs — see `fieldLimits.ts`.
export {
  ISSUE_DESCRIPTION_MIN_LENGTH,
  ISSUE_DESCRIPTION_MAX_LENGTH,
  CLARIFICATION_ANSWER_MAX_LENGTH,
  REVIEW_COMMENT_MAX_LENGTH,
  BIO_MAX_LENGTH,
  FULL_NAME_MAX_LENGTH,
  EMAIL_MAX_LENGTH,
  PHONE_INPUT_MAX_LENGTH,
  PHONE_STORED_MAX_LENGTH,
  ADDRESS_MAX_LENGTHS,
} from './fieldLimits';
