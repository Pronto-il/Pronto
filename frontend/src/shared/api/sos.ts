import { httpClient } from './httpClient';
import type { ArrivalRequest } from './bookings';

/**
 * Pronto SOS — the customer half of `/api/sos/**`.
 *
 * Every shape here was verified directly against the backend source (`com.pronto.sos.dto.*`,
 * `com.pronto.sos.entity.*` and `com.pronto.sos.realtime.*`), not against prose docs. Pronto SOS
 * is the only SOS flow: the legacy browse-and-pick endpoints (`GET /api/bookings/sos-professionals`,
 * `POST /api/bookings/sos-orders`) and their client functions are gone, and nothing here talks to
 * them.
 *
 * Covers both actors: the customer's activate/observe/choose/cancel, and the professional's
 * offer inbox plus the operational transitions they drive once selected. The two halves share
 * `SosRequestStatus`, `SosUrgency` and the realtime types rather than declaring them twice.
 */

/**
 * `sos_requests.status`. Mirrors `SosRequestStatus`, in the backend's own declaration order,
 * which is also the lifecycle order.
 */
export type SosRequestStatus =
  | 'CREATED'
  | 'MATCHING'
  | 'WAITING_FOR_PROFESSIONALS'
  | 'WAITING_FOR_CUSTOMER_SELECTION'
  | 'PROFESSIONAL_SELECTED'
  | 'CONFIRMED'
  | 'ON_THE_WAY'
  | 'ARRIVED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'FAILED';

export type SosUrgency = 'URGENT' | 'EMERGENCY';
export type SosActorType = 'CUSTOMER' | 'PROFESSIONAL' | 'SYSTEM';

/**
 * `sos_offers.status`. Mirrors `SosOfferStatus`.
 *
 * **The distinction this whole feature turns on:** `ACCEPTED` means *"this professional said they
 * are available and can come"*. It is **not** an award — nothing about who gets the job has been
 * decided. `SELECTED` is the award, and it is the customer's choice, made later.
 * `NOT_SELECTED` means they were available and the customer picked somebody else, which is a
 * choice and not a rejection of them.
 *
 * No professional-facing copy in this app may render `ACCEPTED` as "accepted the job" / "the job
 * is yours" — see `features/sos/sosProUiState.ts`, which owns the Hebrew for each of these.
 */
export type SosOfferStatus =
  /** Dispatched, not yet opened. */
  | 'OFFERED'
  /** Opened by the professional. A response-latency signal for the ranker, nothing more. */
  | 'VIEWED'
  /** **Available, not awarded.** Eligible to appear on the customer's candidate list. */
  | 'ACCEPTED'
  | 'REJECTED'
  /** This offer's own response window lapsed. Survivable — the request may still be alive. */
  | 'EXPIRED'
  /** **The award.** The customer chose this offer. At most one per request. */
  | 'SELECTED'
  /** Was available; the customer chose someone else. */
  | 'NOT_SELECTED';

/** True while the professional can still answer this offer (backend `SosOfferStatus.isOpen()`). */
export function isSosOfferOpen(status: SosOfferStatus): boolean {
  return status === 'OFFERED' || status === 'VIEWED';
}

/** True once the offer is over for this professional — nothing here is ever actionable again. */
export function isSosOfferResolved(status: SosOfferStatus): boolean {
  return status === 'REJECTED' || status === 'EXPIRED' || status === 'NOT_SELECTED';
}

/**
 * The ETA bounds `AcceptSosOfferRequest`/`UpdateEtaRequest` enforce (`@Min(0) @Max(480)`).
 * Mirrored here so the client can refuse an out-of-range value before spending a round trip on a
 * guaranteed `VALIDATION_ERROR`. 480 is a fat-finger guard (someone typing 900 for 90), not a
 * business rule.
 */
export const SOS_ETA_MIN_MINUTES = 0;
export const SOS_ETA_MAX_MINUTES = 480;

/** Mirrors `SosRequestStatus.isTerminal()` — no further transition is possible. */
export function isSosTerminalStatus(status: SosRequestStatus): boolean {
  return status === 'COMPLETED' || status === 'CANCELLED' || status === 'EXPIRED' || status === 'FAILED';
}

/**
 * Mirrors `SosRequestStatus.hasSelection()` — a specific professional owns the job, so the
 * screen is tracking rather than searching.
 */
export function hasSosSelection(status: SosRequestStatus): boolean {
  return (
    status === 'PROFESSIONAL_SELECTED' ||
    status === 'CONFIRMED' ||
    status === 'ON_THE_WAY' ||
    status === 'ARRIVED' ||
    status === 'COMPLETED'
  );
}

/** True while the request is still looking for / collecting professionals. */
export function isSosSearching(status: SosRequestStatus): boolean {
  return status === 'CREATED' || status === 'MATCHING' || status === 'WAITING_FOR_PROFESSIONALS';
}

/**
 * `POST /api/sos/requests`.
 *
 * Deliberately carries **no** `professionalId` (the platform dispatches; the customer names
 * nobody) and **no** category/description (both come from the anchoring issue, which has already
 * been through AI classification). `urgency` defaults to `URGENT` server-side when omitted.
 */
export interface CreateSosRequestPayload {
  issueId: number;
  issueSummary?: string;
  urgency?: SosUrgency;
  serviceCity: string;
  serviceStreet: string;
  serviceHouseNumber: string;
  serviceApartment?: string;
  serviceFloor?: string;
  serviceEntrance?: string;
  serviceAddressNotes?: string;
  /** The DEVICE's own fix, when the handset had one — distinct from the selected place below. */
  latitude?: number;
  longitude?: number;
  /** The place the customer selected for the destination (`V55`). Same conditional requirement
   *  as booking: omittable for the caller's own saved default address, required for any other. */
  servicePlaceId?: string;
  serviceFormattedAddress?: string;
  serviceLatitude?: number;
  serviceLongitude?: number;
}

/**
 * The canonical request shape — returned by create, get, cancel and select.
 *
 * `matchingExpiresAt` is an absolute ISO instant, not a remaining-second
 * counts, and that is load-bearing: a countdown rendered from an absolute deadline stays correct
 * across a slow response, a remount or a backgrounded tab. **The backend enforces both deadlines
 * regardless of what this client displays** — every read path re-applies them server-side.
 */
export interface SosRequestResponse {
  id: number;
  issueId: number;
  customerId: number;
  categoryId: number;
  subServiceId: number | null;
  issueSummary: string | null;
  urgency: SosUrgency;
  status: SosRequestStatus;
  serviceCity: string;
  serviceStreet: string | null;
  serviceHouseNumber: string | null;
  serviceApartment: string | null;
  serviceFloor: string | null;
  serviceEntrance: string | null;
  serviceAddressNotes: string | null;
  latitude: number | null;
  longitude: number | null;
  selectedProfessionalId: number | null;
  selectedProfessionalName: string | null;
  selectedOfferId: number | null;
  /**
   * The selected professional's own committed ETA, kept current as they revise it. `null` until
   * somebody is selected.
   *
   * **Read this, not the retained candidate row, once a selection exists.** `/candidates` returns
   * only `ACCEPTED` offers and so goes empty at selection, which is why the tracking panel used to
   * render from a pre-selection snapshot — and why a post-selection ETA revision could never reach
   * it however promptly the client refetched.
   */
  selectedEstimatedArrivalMinutes: number | null;
  orderId: number | null;
  cancelledBy: SosActorType | null;
  /** How many professionals were contacted. Never who they are. */
  offerCount: number;
  /** How many have positively responded "I am available" so far. **Not** how many got the job. */
  acceptedCandidateCount: number;
  /** How many times the customer has widened this search with "סרוק שוב". `0` at the initial scope. */
  searchExpansions: number;
  /**
   * The platform's configured ceiling. Sent so the screen can say "this is as wide as it gets"
   * without hardcoding a bound that is a backend configuration value.
   */
  maxSearchExpansions: number;
  /**
   * **Whether `POST .../scan-again` would be accepted right now** — the backend's own answer, not
   * a client-side guess at the rules. Drives the control's enabled state, and goes `false` the
   * moment a professional is selected, which is what removes the button as part of "selection
   * stops the search".
   */
  canExpandSearch: boolean;
  /**
   * When active scanning stops. **The only deadline on this shape.** There is deliberately no
   * `selectionExpiresAt`: the customer's decision window was removed in the MS3 follow-up, so a
   * professional who accepted stays selectable until the customer chooses, cancels, or every
   * offer has lapsed with nothing accepted. "Can I choose right now" is `status ===
   * 'WAITING_FOR_CUSTOMER_SELECTION'`, and the candidates endpoint says so directly.
   */
  matchingExpiresAt: string | null;
  createdAt: string;
  updatedAt: string | null;
  matchedAt: string | null;
  candidatesReadyAt: string | null;
  selectedAt: string | null;
  confirmedAt: string | null;
  cancelledAt: string | null;
  completedAt: string | null;
}

/**
 * One professional the customer may choose between.
 *
 * A candidate is a professional who answered **"I am available and can come"** — it is not an
 * assignment, and nothing about the request's ownership has changed. The job is awarded only when
 * the customer posts `offerId` back to `/select`.
 *
 * The price breakdown is itemized rather than collapsed to one number on purpose: `totalVisitCost`
 * is what the customer pays *for the visit* (`visitFee + sosFee`), never the price of the repair
 * itself, which is agreed on site.
 */
/**
 * How far one professional has got on this request, in the only two states a customer is shown.
 *
 * A projection of the backend's seven `SosOfferStatus` values, not a second state model: `OFFERED`
 * and `VIEWED` both mean `REQUESTED` here, because "they opened your request" is not progress and
 * must never be rendered as any. Rejected and expired professionals are absent from the list
 * entirely rather than carrying a state of their own.
 */
export type SosCandidateState =
  /** Contacted, has not answered. No ETA, not selectable, rendered muted. */
  | 'REQUESTED'
  /** Answered and committed to an arrival time. Selectable, rendered at full contrast. */
  | 'ACCEPTED';

export interface SosCandidate {
  /** What `/select` takes — the offer, not the professional: it carries the agreed price and ETA. */
  offerId: number;
  professionalId: number;
  /** See {@link SosCandidateState}. Only `ACCEPTED` may be passed to `/select`. */
  state: SosCandidateState;
  fullName: string;
  profileImageUrl: string | null;
  city: string | null;
  /** MS4: canonical service-region label, replacing the old free-text service area. */
  serviceRegion: string | null;
  /** Null when the professional has no reviews yet — render honestly, never a fabricated 0.0. */
  averageRating: number | null;
  reviewCount: number;
  /**
   * The professional's own committed ETA, given when they said they were available.
   *
   * **Always `null` while `state` is `REQUESTED`** — and that is a backend guarantee, not a
   * convention this client maintains: the offer row does carry a platform-computed estimate from
   * the moment it is dispatched, and the server deliberately does not send it, because a customer
   * cannot tell a computed guess from a promise. Never substitute one.
   */
  estimatedArrivalMinutes: number | null;
  distanceKm: number | null;
  visitFee: number | null;
  sosFee: number;
  totalVisitCost: number;
  platformCommission: number | null;
  /** When they answered, or `null` for a `REQUESTED` candidate — who by definition has not. */
  respondedAt: string | null;
}

/**
 * `GET /api/sos/requests/{id}/candidates`.
 *
 * `candidates` is capped at `target-candidate-count` (3) plus one per "סרוק שוב" the customer has
 * used, so at most 5 at the default ceiling. It may legitimately be shorter or empty — "up to",
 * never "exactly".
 *
 * The list is filled in **arrival order** server-side and only then sorted by ETA for display,
 * which guarantees the property this screen depends on: a candidate already on screen is never
 * pushed off by a faster professional arriving later.
 *
 * `selectionOpen` is the only authority on whether `/select` will be accepted right now. It flips
 * true on the first acceptance — no waiting for a second or a third — and, since the MS3
 * follow-up, it does not flip back on a timer: there is no decision deadline, so it stays true
 * until the customer chooses or cancels.
 */
export interface SosCandidatesResponse {
  sosRequestId: number;
  status: SosRequestStatus;
  selectionOpen: boolean;
  candidates: SosCandidate[];
}

/**
 * The professional's view of one SOS opportunity — `GET /api/sos/offers`,
 * `GET /api/sos/offers/{id}`, and the response body of accept / reject / ETA update.
 *
 * Carries the request's context inline (category, summary, city, urgency, `requestStatus`) so the
 * decision screen renders from one call — the professional is deciding in seconds. `requestStatus`
 * is what drives the operational flow after selection, so the whole professional lifecycle can be
 * read off this one shape.
 *
 * **Address disclosure**: `serviceCity` and nothing more. Street, house number, apartment, floor,
 * entrance, notes and coordinates are withheld until this professional is actually selected, and
 * then served through `GET /api/sos/requests/{id}`. Offers go to up to 15 people; an
 * available-but-not-selected professional has no business holding a stranger's home address. **Do
 * not try to source those fields from anywhere else before selection** — the backend redacts them
 * on every path, and a client that worked around it would be the bug.
 *
 * `orderId` is non-null only when `status === 'SELECTED'`.
 * `professionalNet` is what the professional keeps after Pronto's commission — computed
 * server-side so they never have to do the arithmetic to know what answering is worth.
 */
export interface SosOfferResponse {
  id: number;
  sosRequestId: number;
  professionalId: number;
  status: SosOfferStatus;
  /** The parent request's status — the source of truth for the post-selection operational flow. */
  requestStatus: SosRequestStatus;
  categoryId: number;
  issueSummary: string | null;
  urgency: SosUrgency;
  /**
   * The two location fields exposed before selection, by design — enough to estimate a realistic
   * arrival time, which is the whole point of being asked for one. The house number, apartment,
   * floor, entrance, notes and coordinates arrive only once this professional is actually
   * selected, via the order. Enforced server-side (`sos.service.SosAddressAccess`), not by this
   * type choosing not to read them.
   */
  serviceCity: string;
  serviceStreet: string | null;
  matchRank: number | null;
  distanceKm: number | null;
  /** The platform's estimate at dispatch, replaced by the professional's own figure on accept. */
  estimatedArrivalMinutes: number | null;
  visitFee: number | null;
  sosFee: number;
  platformCommission: number | null;
  professionalNet: number | null;
  /** Only meaningful — and only non-null — once this offer is the `SELECTED` one. */
  orderId: number | null;
  offeredAt: string;
  viewedAt: string | null;
  respondedAt: string | null;
  /** Absolute deadline for answering **this offer**. Drives the countdown; the server enforces it. */
  expiresAt: string;
}

/** `GET /api/sos/offers` — the professional's SOS inbox, newest first. */
export interface SosOffersListResponse {
  offers: SosOfferResponse[];
}

export type SosEventType =
  | 'SOS_CREATED'
  | 'MATCHING_STARTED'
  | 'OFFERS_SENT'
  | 'OFFER_VIEWED'
  | 'OFFER_EXPIRED'
  | 'PROFESSIONAL_RESPONDED'
  | 'ETA_UPDATED'
  | 'SEARCH_EXPANDED'
  | 'CANDIDATES_READY'
  | 'CUSTOMER_SELECTION_STARTED'
  | 'PROFESSIONAL_SELECTED'
  | 'PROFESSIONAL_CONFIRMED'
  | 'ON_THE_WAY'
  | 'ARRIVED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'FAILED';

export interface SosEventResponse {
  id: number;
  eventType: SosEventType;
  actorType: SosActorType;
  professionalId: number | null;
  sosOfferId: number | null;
  fromStatus: SosRequestStatus | null;
  toStatus: SosRequestStatus | null;
  detail: string | null;
  createdAt: string;
}

/** `GET /api/sos/requests/{id}/events` — full chronological history, oldest first. */
export interface SosTimelineResponse {
  sosRequestId: number;
  status: SosRequestStatus;
  events: SosEventResponse[];
}

/** `GET /api/sos/requests/me` — the caller's own requests, newest first. */
export interface SosRequestsListResponse {
  requests: SosRequestResponse[];
}

// ---------------------------------------------------------------------------
// Realtime wire contract (`/user/queue/sos`)
// ---------------------------------------------------------------------------

/**
 * Mirrors `com.pronto.sos.realtime.SosRealtimeEventType`. Deliberately a different vocabulary
 * from `SosEventType` above: that one is "what happened to this request", this one is "what is
 * *this recipient* being told".
 *
 * Note the naming — nothing a client sees says "accepted", because that word is ambiguous
 * between "I am available" and "I got the job". `PROFESSIONAL_AVAILABLE` is the former;
 * `SOS_SELECTED` (professional-facing) is the latter.
 *
 * The professional-facing members are listed for completeness of the union; a customer session
 * never receives them, since delivery is scoped per user from committed state.
 */
export type SosRealtimeEventType =
  // customer-facing
  | 'SOS_CREATED'
  | 'MATCHING_STARTED'
  | 'OFFERS_SENT'
  | 'PROFESSIONAL_AVAILABLE'
  | 'SEARCH_EXPANDED'
  | 'CANDIDATES_UPDATED'
  | 'CUSTOMER_SELECTION_STARTED'
  | 'PROFESSIONAL_SELECTED'
  | 'ETA_UPDATED'
  // professional-facing
  | 'SOS_OFFER_RECEIVED'
  | 'OFFER_RESPONSE_RECORDED'
  | 'SOS_OFFER_EXPIRED'
  | 'SOS_SELECTED'
  | 'SOS_NOT_SELECTED'
  // shared operational lifecycle
  | 'PROFESSIONAL_CONFIRMED'
  | 'ON_THE_WAY'
  | 'ARRIVED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'SOS_FAILED';

/**
 * The one shape delivered over `/user/queue/sos`.
 *
 * **`data` is minimal by design and is never the source of truth.** It carries ids, counts and
 * deadlines only — never a candidate list, never an address. A message means *something changed,
 * go read the canonical state*; `useSosRequest` reacts by refetching REST. Typed as
 * `Record<string, unknown>` rather than a per-event payload union precisely so no screen is
 * tempted to render from it.
 */
export interface SosRealtimeMessage {
  /** The `sos_events` row id this message mirrors — correlates a push with the persisted timeline. */
  eventId: number;
  eventType: SosRealtimeEventType;
  sosRequestId: number;
  /** When the underlying event was recorded, not when it was pushed. */
  timestamp: string;
  data: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Endpoints
// ---------------------------------------------------------------------------

/**
 * `POST /api/sos/requests` — activate SOS on an existing issue. CUSTOMER-only.
 *
 * Matching and the first dispatch wave run synchronously inside this call, so the response
 * already reflects `WAITING_FOR_PROFESSIONALS` (offers went out) or `FAILED` (nobody eligible).
 *
 * Notable failures: `SOS_REQUEST_ALREADY_EXISTS` (409 — an attempt is *already in progress* for
 * this issue; a previous attempt that expired/failed/was cancelled never blocks a retry),
 * `ISSUE_URGENCY_MISMATCH` (409 — not an SOS issue), `ISSUE_NOT_BOOKABLE` (409 — the issue is not
 * `OPEN`).
 */
export function createSosRequest(payload: CreateSosRequestPayload): Promise<SosRequestResponse> {
  return httpClient.post<SosRequestResponse>('/api/sos/requests', payload);
}

/** `GET /api/sos/requests/me` — newest first. Used to re-attach to an in-flight request after a refresh. */
export function getMySosRequests(): Promise<SosRequestsListResponse> {
  return httpClient.get<SosRequestsListResponse>('/api/sos/requests/me');
}

/** `GET /api/sos/requests/{id}` — current canonical state. Re-applies elapsed deadlines server-side. */
export function getSosRequest(sosRequestId: number): Promise<SosRequestResponse> {
  return httpClient.get<SosRequestResponse>(`/api/sos/requests/${sosRequestId}`);
}

/**
 * `GET /api/sos/requests/{id}/candidates` — CUSTOMER-only.
 *
 * Never an error to call early or late: while still gathering it returns an empty list with
 * `selectionOpen: false`, and after expiry an empty list with a terminal `status`.
 */
export function getSosCandidates(sosRequestId: number): Promise<SosCandidatesResponse> {
  return httpClient.get<SosCandidatesResponse>(`/api/sos/requests/${sosRequestId}/candidates`);
}

/**
 * `POST /api/sos/requests/{id}/scan-again` — **widen the search on this same request.**
 *
 * A real domain operation, not a refetch: it dispatches offers to professionals who were not
 * contacted before, extends the deadline the search runs against, and writes a history row.
 * Nothing is reset — same request, same issue, same candidates. Everyone who has already said
 * they are available stays visible and stays selectable throughout.
 *
 * Bounded by the platform (`maxSearchExpansions`), and idempotent under a double-tap: the
 * expansion counter is advanced by a compare-and-set server-side, so the second of two racing
 * calls changes nothing and returns the state the first produced.
 *
 * Notable failures: `SOS_EXPANSION_LIMIT_REACHED` (409 — already as wide as it gets),
 * `SOS_ALREADY_SELECTED` (409 — selection always wins over an in-flight expansion),
 * `SOS_WINDOW_EXPIRED` (410), `SOS_INVALID_STATE` (409 — no longer searching).
 */
export function expandSosSearch(sosRequestId: number): Promise<SosRequestResponse> {
  return httpClient.post<SosRequestResponse>(`/api/sos/requests/${sosRequestId}/scan-again`);
}

/** `GET /api/sos/requests/{id}/events` — the chronological history. Not rendered this milestone. */
export function getSosTimeline(sosRequestId: number): Promise<SosTimelineResponse> {
  return httpClient.get<SosTimelineResponse>(`/api/sos/requests/${sosRequestId}/events`);
}

/**
 * `POST /api/sos/requests/{id}/select` — one-shot and deadline-enforced, by a single atomic
 * guarded update in the database. Two concurrent taps cannot both win.
 *
 * Notable failures: `SOS_WINDOW_EXPIRED` (410 — the two-minute window closed),
 * `SOS_ALREADY_SELECTED` (409), `SOS_CANDIDATE_NOT_AVAILABLE` (409 — that offer is no longer an
 * available candidate), `SOS_INVALID_STATE` (409 — the request is not awaiting selection).
 */
export function selectSosProfessional(sosRequestId: number, offerId: number): Promise<SosRequestResponse> {
  return httpClient.post<SosRequestResponse>(`/api/sos/requests/${sosRequestId}/select`, { offerId });
}

/**
 * `POST /api/sos/requests/{id}/cancel`. Either role, but a professional qualifies **only** once
 * they are the selected one — holding an unselected offer confers no right to cancel a customer's
 * request. For a customer, allowed until the job is under way.
 */
export function cancelSosRequest(sosRequestId: number): Promise<SosRequestResponse> {
  return httpClient.post<SosRequestResponse>(`/api/sos/requests/${sosRequestId}/cancel`);
}

// ---------------------------------------------------------------------------
// Professional: the offer inbox
// ---------------------------------------------------------------------------

/**
 * `GET /api/sos/offers` — PROFESSIONAL-only.
 *
 * Defaults to **live offers only** (`OFFERED`/`VIEWED`/`ACCEPTED`/`SELECTED`): an SOS inbox is a
 * work queue, not a history, and burying two live offers under fifty expired ones is how urgent
 * calls get missed. Pass `includeClosed` to also get `REJECTED`/`EXPIRED`/`NOT_SELECTED` — which
 * is what lets a professional see *"the customer chose someone else"* at all, since that outcome
 * moves their offer straight out of the live set.
 */
export function getMySosOffers(includeClosed = false): Promise<SosOffersListResponse> {
  const query = includeClosed ? '?includeClosed=true' : '';
  return httpClient.get<SosOffersListResponse>(`/api/sos/offers${query}`);
}

/**
 * `GET /api/sos/offers/{id}`.
 *
 * **This is a mutation dressed as a read**: opening an offer marks it `VIEWED` server-side. That
 * is deliberate (it is idempotent, it is the only honest moment to record that the professional
 * saw the opportunity, and response latency is a ranking signal) — but it means this must be
 * called when the professional actually looks at an offer, never speculatively or in a loop.
 */
export function getSosOffer(offerId: number): Promise<SosOfferResponse> {
  return httpClient.get<SosOfferResponse>(`/api/sos/offers/${offerId}`);
}

/**
 * `POST /api/sos/offers/{id}/accept` — **"I am available and can come."** Not an acceptance of
 * the job: the customer has not chosen yet, and may choose someone else.
 *
 * `estimatedArrivalMinutes` is optional; omitted, the platform's own dispatch-time estimate
 * stands. When supplied it replaces that estimate, and the customer is choosing partly on this
 * number. Expiry is enforced by the database inside the same guarded update, so an offer that
 * lapses between render and tap fails here rather than slipping through.
 *
 * Notable failures: `SOS_WINDOW_EXPIRED` (410 — this offer's window closed),
 * `SOS_OFFER_NOT_OPEN` (409 — already answered), `SOS_INVALID_STATE` (409 — the request stopped
 * accepting responses, e.g. somebody was already selected).
 */
export function acceptSosOffer(offerId: number, estimatedArrivalMinutes: number): Promise<SosOfferResponse> {
  return httpClient.post<SosOfferResponse>(`/api/sos/offers/${offerId}/accept`, { estimatedArrivalMinutes });
}

/**
 * `POST /api/sos/offers/{id}/reject` — "not available". No reason field exists, and none is
 * invented here. Deliberately has no expiry guard server-side: declining late is harmless.
 */
export function rejectSosOffer(offerId: number): Promise<SosOfferResponse> {
  return httpClient.post<SosOfferResponse>(`/api/sos/offers/${offerId}/reject`);
}

/*
 * `POST /api/sos/offers/{id}/eta` has no client function any more (MS3). The endpoint still
 * exists and answers `409 SOS_ETA_LOCKED` to anything that calls it — an ETA committed at
 * acceptance is what the customer chooses on, so it is final. Keeping a wrapper for a call whose
 * only possible outcome is a refusal would be a client for an operation this product does not
 * have.
 */

// ---------------------------------------------------------------------------
// Professional: operational transitions (the selected professional only)
// ---------------------------------------------------------------------------

/*
 * All four are gated server-side on `sos_requests.selected_professional_id`, re-checked inside
 * each guarded update — a losing candidate cannot drive the job of the one who won. They all
 * return the full `SosRequestResponse` with FULL address access, since by definition the caller
 * is the selected professional.
 */

/** `POST /api/sos/requests/{id}/confirm` — the selected professional takes the job. */
export function confirmSosRequest(sosRequestId: number): Promise<SosRequestResponse> {
  return httpClient.post<SosRequestResponse>(`/api/sos/requests/${sosRequestId}/confirm`);
}

/** `POST /api/sos/requests/{id}/on-the-way`. Mirrors the transition onto the linked order. */
export function markSosOnTheWay(sosRequestId: number): Promise<SosRequestResponse> {
  return httpClient.post<SosRequestResponse>(`/api/sos/requests/${sosRequestId}/on-the-way`);
}

/** `POST /api/sos/requests/{id}/arrived` — SOS-only; `orders` has no equivalent status. */
export function markSosArrived(
  sosRequestId: number,
  fix: ArrivalRequest,
): Promise<SosRequestResponse> {
  return httpClient.post<SosRequestResponse>(`/api/sos/requests/${sosRequestId}/arrived`, fix);
}

/** `POST /api/sos/requests/{id}/complete` — completes the request, its order and its issue. */
export function completeSosRequest(sosRequestId: number): Promise<SosRequestResponse> {
  return httpClient.post<SosRequestResponse>(`/api/sos/requests/${sosRequestId}/complete`);
}
