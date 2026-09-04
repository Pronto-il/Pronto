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
/**
 * Identifies WHAT the customer needs a professional for.
 *
 * Deferred authentication: during matching there is usually no issue yet — it is created at the
 * booking commit, together with the order — so the listing is keyed on the category the review
 * step confirmed. `issueId` remains for the one case that still has one: a customer returning to
 * an issue they created on an earlier pass.
 *
 * Exactly one of the two is sent. The backend takes `issueId` in preference when both arrive and
 * authorizes it against the caller, so sending both would just make the ownership check decide
 * something the category already answered.
 */
export type ListingSubject = { issueId: number } | { categoryId: number };

/**
 * Thrown instead of issuing a listing request that the backend is certain to reject.
 *
 * Distinct from `ApiError` on purpose: nothing was sent, so there is no status, no code and no
 * server message to report. A caller that catches this is looking at its own bug — it asked for
 * professionals before it had somewhere to send them.
 */
export class IncompleteServiceLocationError extends Error {
  /** The required fields that were blank or malformed. */
  readonly missing: string[];

  constructor(missing: string[]) {
    super(`Refusing to request professionals without: ${missing.join(', ')}`);
    this.name = 'IncompleteServiceLocationError';
    this.missing = missing;
  }
}

/**
 * **The last line of defence against `?city=&street=&houseNumber=`.**
 *
 * `city`/`street`/`houseNumber` are required query params (`400 VALIDATION_ERROR`, one
 * `FieldError` each). The screens above this module gate on a complete address before they get
 * here — but this is where every listing request in the app is actually assembled, so it is the
 * one place that can guarantee an empty one never leaves the browser. The observed 400 came from
 * a screen whose guard was "is there an address object?" rather than "does it have an address
 * in it"; the object was `EMPTY_ADDRESS`, non-null and entirely blank, and this function did not
 * exist to notice.
 *
 * `houseNumber` is checked for shape, not just presence: the backend refuses a non-numeric one
 * too (see `BookingsController#parseServiceLocation`).
 */
function assertListable(location: ServiceLocation): void {
  const missing: string[] = [];
  if (!location.city?.trim()) missing.push('city');
  if (!location.street?.trim()) missing.push('street');
  if (!/^\d{1,20}$/.test(location.houseNumber?.trim() ?? '')) missing.push('houseNumber');
  if (missing.length > 0) {
    throw new IncompleteServiceLocationError(missing);
  }
}

function listingParams(subject: ListingSubject, location: ServiceLocation, sort?: ProfessionalSort) {
  assertListable(location);
  const params = new URLSearchParams();
  if ('issueId' in subject) {
    params.set('issueId', String(subject.issueId));
  } else {
    params.set('categoryId', String(subject.categoryId));
  }
  params.set('city', location.city);
  params.set('street', location.street);
  params.set('houseNumber', location.houseNumber);
  if (location.apartment) {
    params.set('apartment', location.apartment);
  }
  if (sort) {
    params.set('sort', sort);
  }
  return `/api/bookings/professionals?${params.toString()}`;
}

export function prefetchProfessionalListing(
  subject: ListingSubject,
  location: ServiceLocation,
  sort?: ProfessionalSort,
): Promise<ProfessionalListingResponse> {
  // A rejected promise rather than a synchronous throw: this is called from an effect that
  // stores the result and attaches its own `.catch`, and a synchronous throw there would take
  // the screen down instead of degrading into that handler.
  let path: string;
  try {
    path = listingParams(subject, location, sort);
  } catch (error) {
    return Promise.reject(error);
  }

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
 * api-contract-bookings.md §2.2's original prose implies. An incomplete `location` rejects with
 * {@link IncompleteServiceLocationError} **without issuing a request**.
 */
export function getProfessionalsForIssue(
  subject: ListingSubject,
  location: ServiceLocation,
  sort?: ProfessionalSort,
): Promise<ProfessionalListingResponse> {
  let path: string;
  try {
    path = listingParams(subject, location, sort);
  } catch (error) {
    return Promise.reject(error);
  }
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
  /**
   * The first instant a **standard** booking may start — `now + minLeadMinutes`, computed
   * server-side when this response was built.
   *
   * **`windows` is deliberately NOT filtered by it.** The professional's calendar really is open
   * before this time, and the screen says exactly that: those start times are shown, disabled, with
   * an explanation and an SOS prompt. Hiding them would make "we will not take this booking" look
   * identical to "they are busy", which is a different and untrue statement.
   *
   * Presentation only. The server re-derives this from its own clock at `POST /api/bookings/orders`
   * and answers `BOOKING_LEAD_TIME_NOT_MET` regardless of what the screen believed — so a customer
   * who leaves this page open for an hour is refused at the commit, not let through on a stale value.
   */
  earliestBookableAt: string;
  /** The rule itself, so copy can say "2.5 שעות" without the client owning the number. */
  minLeadMinutes: number;
  windows: AvailableWindow[];
}

/**
 * `GET /api/bookings/professionals/{professionalId}/available-windows?issueId=` — replaces
 * the retired `GET .../slots?issueId=` (design §9.2.2). An empty `windows` array is a valid,
 * expected response (no derived availability fits a full job) — not an error, same UX as the
 * old empty-`slots` case.
 */
/**
 * `issueId` is optional as of deferred authentication: during selection there is usually no
 * issue, and a professional's free windows are derived from their own published hours and
 * existing bookings rather than from the customer's request. When an issue IS supplied the
 * backend additionally checks it belongs to the caller and that the professional serves its
 * category — so passing it when you have it is strictly better, and omitting it is correct
 * rather than a workaround.
 */
export function getAvailableWindows(
  professionalId: number,
  issueId?: number,
): Promise<AvailableWindowsResponse> {
  const query = issueId === undefined ? '' : `?issueId=${issueId}`;
  return httpClient.get<AvailableWindowsResponse>(
    `/api/bookings/professionals/${professionalId}/available-windows${query}`,
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
  /** The place the customer selected for this booking's destination (`V55`). Required by the
   *  backend for any address other than the caller's own saved default, which stays
   *  grandfathered so an existing customer is never stopped mid-booking. */
  servicePlaceId?: string;
  serviceFormattedAddress?: string;
  serviceLatitude?: number;
  serviceLongitude?: number;
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
