import { httpClient } from './httpClient';

/**
 * `bookings` domain types/functions, Frontend Milestone 3 (Standard booking flow).
 *
 * These shapes were verified directly against the real backend source (the `bookings`
 * package DTOs), not copied from `docs/architecture/api-contract-bookings.md`'s prose —
 * that doc's §2.1-§2.11 predates Milestone 8 (Professional Profiles, Reviews, Favorites &
 * Matching), which changed several of these DTOs in place without the doc being updated.
 * Divergences are called out per-type below.
 */

export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'ON_THE_WAY' | 'COMPLETED' | 'CANCELLED' | 'REJECTED' | 'EXPIRED';
export type CancelledBy = 'CUSTOMER' | 'PROFESSIONAL' | 'SYSTEM';
export type ProfessionalSort = 'CHEAPEST' | 'FASTEST';

export interface ServiceLocation {
  city: string;
  street: string;
  houseNumber: string;
  apartment?: string;
}

/**
 * Milestone 8 enrichment (distance/ETA/rating/favorites) added to the original
 * api-contract-bookings.md §2.2 shape (`professionalId`/`fullName`/`serviceArea`/
 * `basePrice`/`reliabilityScore` only) — `reliabilityScore` is now a legacy field, always
 * `null` in practice, superseded by `averageRating`/`reviewCount`.
 */
export interface ProfessionalCard {
  professionalId: number;
  fullName: string;
  serviceArea: string;
  basePrice: number;
  /** Legacy field, always null in practice — do not render. */
  reliabilityScore: number | null;
  city: string | null;
  profileImageUrl: string | null;
  /** Null when the professional has no reviews yet. */
  averageRating: number | null;
  reviewCount: number;
  /** Display only this pass — no favorite-toggle interaction built (needs POST/DELETE /api/favorites). */
  favorited: boolean;
  sameCity: boolean;
  distanceKm: number;
  baseTravelTimeMinutes: number;
  trafficAdjustmentMinutes: number;
  etaMinutes: number;
}

export interface ProfessionalListingResponse {
  issueId: number;
  categoryId: number;
  professionals: ProfessionalCard[];
}

/**
 * `GET /api/bookings/professionals?issueId=&city=&street=&houseNumber=&apartment=&sort=`
 * `city`/`street`/`houseNumber` are REQUIRED query params as of Milestone 8 (400
 * VALIDATION_ERROR, one FieldError per missing field) — NOT optional despite what
 * api-contract-bookings.md §2.2's original prose implies.
 */
export function getProfessionalsForIssue(
  issueId: number,
  location: ServiceLocation,
  sort?: ProfessionalSort,
): Promise<ProfessionalListingResponse> {
  const params = new URLSearchParams();
  params.set('issueId', String(issueId));
  params.set('city', location.city);
  params.set('street', location.street);
  params.set('houseNumber', location.houseNumber);
  if (location.apartment) {
    params.set('apartment', location.apartment);
  }
  if (sort) {
    params.set('sort', sort);
  }
  return httpClient.get<ProfessionalListingResponse>(`/api/bookings/professionals?${params.toString()}`);
}

export interface AvailabilitySlotItem {
  slotId: number;
  startTime: string;
  endTime: string;
}

export interface ProfessionalSlotsResponse {
  professionalId: number;
  slots: AvailabilitySlotItem[];
}

/**
 * `GET /api/bookings/professionals/{professionalId}/slots?issueId=` — unchanged from the
 * original api-contract-bookings.md §2.3, not affected by Milestone 8.
 */
export function getProfessionalSlots(professionalId: number, issueId: number): Promise<ProfessionalSlotsResponse> {
  return httpClient.get<ProfessionalSlotsResponse>(
    `/api/bookings/professionals/${professionalId}/slots?issueId=${issueId}`,
  );
}

/**
 * `serviceCity`/`serviceStreet`/`serviceHouseNumber` (+ optional `serviceApartment`) are a
 * real Milestone 8 addition to the request body (`orders.service_*` columns) — not present
 * in api-contract-bookings.md §2.4's original prose.
 */
export interface CreateOrderRequest {
  issueId: number;
  professionalId: number;
  slotId: number;
  serviceCity: string;
  serviceStreet: string;
  serviceHouseNumber: string;
  serviceApartment?: string;
}

export interface OrderResponse {
  id: number;
  issueId: number;
  customerId: number;
  professionalId: number;
  orderStatus: OrderStatus;
  bookedStart: string;
  bookedEnd: string | null;
  finalPrice: number;
  basePriceSnapshot: number;
  sosSurcharge: number;
  serviceCity: string;
  serviceStreet: string;
  serviceHouseNumber: string;
  serviceApartment: string | null;
  cancelledBy: CancelledBy | null;
  createdAt: string;
  updatedAt: string;
}

/** `POST /api/bookings/orders` — CUSTOMER only. */
export function createOrder(payload: CreateOrderRequest): Promise<OrderResponse> {
  return httpClient.post<OrderResponse>('/api/bookings/orders', payload);
}

/** `POST /api/bookings/orders/{orderId}/accept` — PROFESSIONAL only. */
export function acceptOrder(orderId: number): Promise<OrderResponse> {
  return httpClient.post<OrderResponse>(`/api/bookings/orders/${orderId}/accept`);
}

/** `POST /api/bookings/orders/{orderId}/reject` — PROFESSIONAL only. */
export function rejectOrder(orderId: number): Promise<OrderResponse> {
  return httpClient.post<OrderResponse>(`/api/bookings/orders/${orderId}/reject`);
}

/** `POST /api/bookings/orders/{orderId}/cancel` — either party, no body. */
export function cancelOrder(orderId: number): Promise<OrderResponse> {
  return httpClient.post<OrderResponse>(`/api/bookings/orders/${orderId}/cancel`);
}

/** Same fields as `OrderResponse` plus display-friendly names. */
export interface OrderDetailResponse extends OrderResponse {
  customerName: string;
  professionalName: string;
}

/** `GET /api/bookings/orders/{orderId}` — either party (ownership checked server-side). */
export function getOrder(orderId: number): Promise<OrderDetailResponse> {
  return httpClient.get<OrderDetailResponse>(`/api/bookings/orders/${orderId}`);
}

export interface OrderSummary {
  id: number;
  issueId: number;
  orderStatus: OrderStatus;
  bookedStart: string;
  bookedEnd: string | null;
  finalPrice: number;
  createdAt: string;
}

export interface MyOrdersResponse {
  orders: OrderSummary[];
}

/** `GET /api/bookings/orders/me?status=` — either party, own orders only. */
export function getMyOrders(status?: OrderStatus): Promise<MyOrdersResponse> {
  const query = status ? `?status=${status}` : '';
  return httpClient.get<MyOrdersResponse>(`/api/bookings/orders/me${query}`);
}
