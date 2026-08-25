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

/**
 * Production MS2 adds `ARRIVED` between `ON_THE_WAY` and `COMPLETED` — the professional is at
 * the customer's address and the **backend has verified it geographically**. It is optional:
 * `ON_THE_WAY -> COMPLETED` remains legal, so a professional whose device cannot get a usable
 * fix is never stranded mid-job.
 */
export type OrderStatus =
  | 'PENDING'
  | 'CONFIRMED'
  | 'ON_THE_WAY'
  | 'ARRIVED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REJECTED'
  | 'EXPIRED';
export type CancelledBy = 'CUSTOMER' | 'PROFESSIONAL' | 'SYSTEM';
export type ProfessionalSort = 'CHEAPEST' | 'RECOMMENDED' | 'FASTEST';

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
  /** MS4: the Hebrew label of the professional's canonical service region. */
  serviceRegion: string | null;
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
  /** MS4: every category this professional serves, in catalogue display order. */
  categoryIds: number[];
  /**
   * ## Production MS2 — these are now nullable, and that is the point
   *
   * The card used to carry `sameCity`, `baseTravelTimeMinutes`, `trafficAdjustmentMinutes` and a
   * non-nullable `etaMinutes`. All four were artefacts of a placeholder model: `sameCity` was a
   * string comparison between two city names, and the middle two were the halves of a hardcoded
   * peak-hour surcharge. The figures they produced were fixed — 8 or 35 km, 34/40/54/70 minutes —
   * regardless of where anybody actually was.
   *
   * They are replaced by real road distance and real driving duration, **or by `null`**, when the
   * professional's device position is missing, stale or too imprecise to route from, or when the
   * provider could not be reached. A card with `null` here must render honest degraded copy — see
   * `ProfessionalCard.tsx` — never `0.0 ק״מ` or `0 דקות`.
   */
  distanceKm: number | null;
  etaMinutes: number | null;
  /** Whether `etaMinutes` accounts for traffic. Carried from the provider, never assumed. */
  etaTrafficAware: boolean;
  /**
   * A `maps.RouteUnavailableReason` name when the figures are absent, `null` otherwise —
   * `PROFESSIONAL_LOCATION_MISSING`, `PROFESSIONAL_LOCATION_STALE`,
   * `PROFESSIONAL_LOCATION_INACCURATE`, `DESTINATION_UNKNOWN`, `PROVIDER_UNAVAILABLE`,
   * `NO_ROUTE`. A stable code, branched on rather than displayed raw.
   */
  etaUnavailableReason: string | null;
}

export interface ProfessionalListingResponse {
  issueId: number;
  categoryId: number;
  professionals: ProfessionalCard[];
}

/**
 * Single-entry prefetch cache for the two professional-listing endpoints, added for the
 * profession-matching transition (`features/issues/ProfessionMatchPage`): that screen fires
 * the listing request while its animation plays, and the booking flow it hands off to then
 * asks for the same list a moment later. Without this the customer pays for the request
 * twice; with it, the second call adopts the in-flight promise.
 *
 * Deliberately minimal — this codebase has no query-cache library, and this is not the place
 * to introduce one:
 * - **Keyed on the exact request path**, so a different issue, address or sort never reads
 *   another request's result.
 * - **Single-use** — `takePrefetched` removes the entry, so changing the sort on the listing
 *   screen re-hits the network as it always did.
 * - **Short TTL** — an entry older than `PREFETCH_TTL_MS` is discarded rather than served, so
 *   a customer who lingers can never be shown a stale list.
 * - **Rejection-safe** — a failed prefetch is dropped from the cache, letting the real caller
 *   retry against the network and surface its own error state.
 */
const PREFETCH_TTL_MS = 30_000;

interface PrefetchEntry {
  path: string;
  storedAt: number;
  promise: Promise<ProfessionalListingResponse>;
}

let prefetchEntry: PrefetchEntry | null = null;

function takePrefetched(path: string): Promise<ProfessionalListingResponse> | null {
  const entry = prefetchEntry;
  if (!entry || entry.path !== path) {
    return null;
  }
  prefetchEntry = null;
  if (Date.now() - entry.storedAt > PREFETCH_TTL_MS) {
    return null;
  }
  return entry.promise;
}

/**
 * Starts a professional-listing request now so a screen mounting shortly afterwards can adopt
 * it instead of issuing its own. Returns the same promise, so the caller can also await it to
 * decide when it is safe to navigate. Safe to call repeatedly for the same params — a second
 * call for a path already prefetched returns the in-flight promise rather than a new request.
 */
export function prefetchProfessionalListing(
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
  const path = `/api/bookings/professionals?${params.toString()}`;

  if (prefetchEntry && prefetchEntry.path === path && Date.now() - prefetchEntry.storedAt <= PREFETCH_TTL_MS) {
    return prefetchEntry.promise;
  }

  const promise = httpClient.get<ProfessionalListingResponse>(path);
  prefetchEntry = { path, storedAt: Date.now(), promise };
  promise.catch(() => {
    if (prefetchEntry?.promise === promise) {
      prefetchEntry = null;
    }
  });
  return promise;
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
  const path = `/api/bookings/professionals?${params.toString()}`;
  return takePrefetched(path) ?? httpClient.get<ProfessionalListingResponse>(path);
}

/**
 * One entry in `GET /api/bookings/professionals/{professionalId}/available-windows
 * ?issueId=`'s `windows` array — a derived `AVAILABLE` window, already guaranteed
 * `>= defaultDurationMinutes` long (design §9.2.2). Verified directly against the real
 * backend record, `bookings.dto.AvailableWindow`.
 */
export interface AvailableWindow {
  startAt: string;
  endAt: string;
}

/**
 * Professional weekly availability calendar feature, M6 (design §9.2.2). Replaces the
 * retired `GET .../slots?issueId=`/`ProfessionalSlotsResponse`/`getProfessionalSlots` entirely
 * — not kept for backward compatibility, since the backend route itself no longer exists.
 * `defaultDurationMinutes`/`timezone` are echoed from the server rather than hardcoded
 * client-side (same single-source-of-truth reasoning `CalendarResponse.timezone` already
 * uses). Verified directly against the real backend record, `bookings.dto.AvailableWindowsResponse`.
 */
export interface AvailableWindowsResponse {
  professionalId: number;
  issueId: number;
  defaultDurationMinutes: number;
  timezone: string;
  windows: AvailableWindow[];
}

/**
 * `GET /api/bookings/professionals/{professionalId}/available-windows?issueId=` — replaces
 * the retired `GET .../slots?issueId=` (design §9.2.2). An empty `windows` array is a valid,
 * expected response (no derived availability fits a full job) — not an error, same UX as the
 * old empty-`slots` case.
 */
export function getAvailableWindows(professionalId: number, issueId: number): Promise<AvailableWindowsResponse> {
  return httpClient.get<AvailableWindowsResponse>(
    `/api/bookings/professionals/${professionalId}/available-windows?issueId=${issueId}`,
  );
}

/**
 * `serviceCity`/`serviceStreet`/`serviceHouseNumber` (+ optional `serviceApartment`) are a
 * real Milestone 8 addition to the request body (`orders.service_*` columns) — not present
 * in api-contract-bookings.md §2.4's original prose. `serviceFloor`/`serviceEntrance`/
 * `serviceAddressNotes` are a further optional addition (V22 — orders schema gap fix, see
 * `ms3-ms4-corrections-design.md` §2). **As of the professional weekly availability calendar
 * feature, M6 (design §9.2.2)**: `slotId` is dropped entirely (not kept, even as an
 * optional/ignored field) and replaced by `bookedStart` — a client-chosen ISO instant,
 * required, validated strictly-in-the-future server-side. `bookedEnd` is deliberately never a
 * field here — always computed server-side as `bookedStart + DEFAULT_JOB_DURATION_MINUTES`
 * (currently 60), never accepted from the client.
 */
export interface CreateOrderRequest {
  issueId: number;
  professionalId: number;
  bookedStart: string;
  serviceCity: string;
  serviceStreet: string;
  serviceHouseNumber: string;
  serviceApartment?: string;
  serviceFloor?: string;
  serviceEntrance?: string;
  serviceAddressNotes?: string;
}

export interface OrderResponse {
  id: number;
  issueId: number;
  customerId: number;
  professionalId: number;
  orderStatus: OrderStatus;
  bookedStart: string;
  bookedEnd: string | null;
  /**
   * Set exactly once, at the `ON_THE_WAY` transition (`BookingsService.onTheWay`), computed
   * from `DistanceEtaStrategy.calculate` — an immutable snapshot, `null` for every order that
   * never reached `ON_THE_WAY`. See `docs/architecture/active-booking-floating-indicator.md`.
   */
  expectedArrivalAt: string | null;
  finalPrice: number;
  basePriceSnapshot: number;
  sosSurcharge: number;
  serviceCity: string;
  serviceStreet: string;
  serviceHouseNumber: string;
  serviceApartment: string | null;
  serviceFloor: string | null;
  serviceEntrance: string | null;
  serviceAddressNotes: string | null;
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

/** `POST /api/bookings/orders/{orderId}/on-the-way` — PROFESSIONAL only. */
export function markOnTheWay(orderId: number): Promise<OrderResponse> {
  return httpClient.post<OrderResponse>(`/api/bookings/orders/${orderId}/on-the-way`);
}

/**
 * Body of `POST /api/bookings/orders/{orderId}/arrived` — the professional's device position at
 * the moment they claim to have arrived.
 *
 * The reading is sent rather than read from the professional's stored position because the
 * stored one is up to ten minutes old by design: fine for estimating a journey, nowhere near
 * good enough to be the sole evidence for "I am at this door right now".
 *
 * **The customer's coordinates never come back.** The comparison happens entirely on the server;
 * an endpoint that returned the destination for the client to check would both leak the address
 * and let any modified client claim to be anywhere.
 */
export interface ArrivalRequest {
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  capturedAt: string;
}

/**
 * `POST /api/bookings/orders/{orderId}/arrived` — PROFESSIONAL only, `ON_THE_WAY -> ARRIVED`.
 *
 * Rejections the caller must handle, all `ApiError`:
 * - `LOCATION_QUALITY_INSUFFICIENT` (422) — the fix is too old or too imprecise. Retryable.
 * - `ARRIVAL_OUT_OF_RANGE` (422) — the fix is fine and says they are not there. Not retryable
 *   from the same place.
 * - `ORDER_DESTINATION_UNKNOWN` (409) — the order's address never resolved to coordinates.
 * - `ORDER_NOT_ARRIVABLE` (409) — the order is not `ON_THE_WAY`.
 */
export function markArrived(orderId: number, fix: ArrivalRequest): Promise<OrderResponse> {
  return httpClient.post<OrderResponse>(`/api/bookings/orders/${orderId}/arrived`, fix);
}

/** `POST /api/bookings/orders/{orderId}/complete` — PROFESSIONAL only. */
export function completeOrder(orderId: number): Promise<OrderResponse> {
  return httpClient.post<OrderResponse>(`/api/bookings/orders/${orderId}/complete`);
}

/**
 * Same fields as `OrderResponse` plus display-friendly names. `customerPhone` — new,
 * professional weekly availability calendar design §9.1 — is populated by the same
 * party-to-order authorization check as everything else on this DTO (no extra client-side
 * gating needed): visible to the order's own customer (their own phone) and to the assigned
 * professional starting the moment the order is created (`PENDING` onward). Rendered only for
 * a `PROFESSIONAL` viewer (`OrderTrackingPage.tsx`) — no reciprocal "customer sees the
 * professional's phone" requirement exists in any source document.
 */
export interface OrderDetailResponse extends OrderResponse {
  customerName: string;
  customerPhone: string | null;
  professionalName: string;
}

/** `GET /api/bookings/orders/{orderId}` — either party (ownership checked server-side). */
export function getOrder(orderId: number): Promise<OrderDetailResponse> {
  return httpClient.get<OrderDetailResponse>(`/api/bookings/orders/${orderId}`);
}

export interface OrderSummary {
  id: number;
  issueId: number;
  /** Additive extension of api-contract-bookings.md §2.9's shape — see
   *  `bookings.dto.OrderSummaryResponse`'s Javadoc. Lets the orders list identify (and open) the
   *  professional without a per-row detail fetch. */
  professionalId: number;
  /** `null` only when the professional's user row can no longer be resolved server-side. */
  professionalName: string | null;
  orderStatus: OrderStatus;
  bookedStart: string;
  bookedEnd: string | null;
  /** Same as `OrderResponse.expectedArrivalAt` — `null` unless the order reached `ON_THE_WAY`. */
  expectedArrivalAt: string | null;
  finalPrice: number;
  createdAt: string;
  updatedAt: string;
}

export interface MyOrdersResponse {
  orders: OrderSummary[];
}

/** `GET /api/bookings/orders/me?status=` — either party, own orders only. */
export function getMyOrders(status?: OrderStatus): Promise<MyOrdersResponse> {
  const query = status ? `?status=${status}` : '';
  return httpClient.get<MyOrdersResponse>(`/api/bookings/orders/me${query}`);
}
