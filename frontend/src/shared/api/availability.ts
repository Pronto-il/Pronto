import { httpClient } from './httpClient';
import type { OrderStatus } from './bookings';

/**
 * `availability` domain — a professional's own Standard-booking calendar (`availability_slots`).
 * Class names below (`SlotResponse`/`SlotListItem`/`SlotListResponse`/`CreateSlotRequest`)
 * match the real backend DTO names directly, not the naming used in any architecture doc's
 * prose.
 */

export interface CreateSlotRequest {
  startTime: string;
  endTime: string;
}

export interface SlotResponse {
  id: number;
  professionalId: number;
  startTime: string;
  endTime: string;
  isAvailable: boolean;
  createdAt: string;
}

/** `POST /api/availability/slots` — PROFESSIONAL only. */
export function createAvailabilitySlot(payload: CreateSlotRequest): Promise<SlotResponse> {
  return httpClient.post<SlotResponse>('/api/availability/slots', payload);
}

/** `PUT /api/availability/slots/{slotId}` — PROFESSIONAL only, must be the slot's owner. */
export function updateAvailabilitySlot(slotId: number, payload: CreateSlotRequest): Promise<SlotResponse> {
  return httpClient.put<SlotResponse>(`/api/availability/slots/${slotId}`, payload);
}

/** `DELETE /api/availability/slots/{slotId}` — PROFESSIONAL only, must be the slot's owner. */
export function deleteAvailabilitySlot(slotId: number): Promise<void> {
  return httpClient.delete<void>(`/api/availability/slots/${slotId}`);
}

/** Note: no `professionalId` on list items (implicit — it's always the caller's own). */
export interface SlotListItem {
  id: number;
  startTime: string;
  endTime: string;
  isAvailable: boolean;
  createdAt: string;
}

export interface SlotListResponse {
  slots: SlotListItem[];
}

/** `GET /api/availability/slots/me` — PROFESSIONAL only, the caller's own slots. */
export function getMyAvailabilitySlots(): Promise<SlotListResponse> {
  return httpClient.get<SlotListResponse>('/api/availability/slots/me');
}

/**
 * `sos_availability` table — a professional's single on/off toggle for urgent (SOS) work,
 * distinct from the Standard-booking `availability_slots` calendar above but exposed via
 * the same `AvailabilityController`/URL base path.
 */
export interface SosAvailabilityResponse {
  professionalId: number;
  isAvailable: boolean;
  updatedAt: string;
}

/** `GET /api/availability/sos-availability` — PROFESSIONAL only, reads the caller's current toggle state. */
export function getSosAvailability(): Promise<SosAvailabilityResponse> {
  return httpClient.get<SosAvailabilityResponse>('/api/availability/sos-availability');
}

/** `PUT /api/availability/sos-availability` — PROFESSIONAL only. */
export function updateSosAvailability(isAvailable: boolean): Promise<SosAvailabilityResponse> {
  return httpClient.put<SosAvailabilityResponse>('/api/availability/sos-availability', { isAvailable });
}

/**
 * Professional weekly availability calendar (M1-M2 backend,
 * `docs/architecture/professional-weekly-calendar-design.md` §4.1/§4.2/§4.6). Shapes verified
 * directly against the real backend DTOs (`availability.dto.WorkingHoursItem`/
 * `WorkingHoursItemRequest`/`WorkingHoursListResponse`/`WorkingHoursUpdateRequest`/
 * `CalendarResponse`/`CalendarSegment`/`SegmentType`), same "read the real backend source"
 * convention this file's header comment already establishes for the slot/SOS types above.
 * `startTime`/`endTime` are `"HH:mm"` strings (`@JsonFormat(pattern = "HH:mm")` on the backend
 * record), `null` when `enabled = false`. `weekday` is `0` (Sunday) through `6` (Saturday).
 */
export interface WorkingHoursItem {
  weekday: number;
  enabled: boolean;
  startTime: string | null;
  endTime: string | null;
}

/** One entry of `PUT /api/availability/working-hours`'s request array — same field shape as
 *  `WorkingHoursItem`, kept as a separate type since the request/response are conceptually
 *  distinct even though structurally identical today (mirrors the backend's own
 *  `WorkingHoursItem`/`WorkingHoursItemRequest` split). */
export interface WorkingHoursItemRequest {
  weekday: number;
  enabled: boolean;
  startTime: string | null;
  endTime: string | null;
}

export interface WorkingHoursListResponse {
  workingHours: WorkingHoursItem[];
}

/** `GET /api/availability/working-hours` — PROFESSIONAL only. `workingHours` may have fewer
 *  than 7 entries before first-time setup completes (a brand-new professional) — not an
 *  error, the caller renders the first-time-setup flow in that case. */
export function getWorkingHours(): Promise<WorkingHoursListResponse> {
  return httpClient.get<WorkingHoursListResponse>('/api/availability/working-hours');
}

/** `PUT /api/availability/working-hours` — PROFESSIONAL only. Full-week replace: the caller
 *  must send exactly 7 entries, one per weekday `0`-`6`, no duplicates/gaps. */
export function updateWorkingHours(workingHours: WorkingHoursItemRequest[]): Promise<WorkingHoursListResponse> {
  return httpClient.put<WorkingHoursListResponse>('/api/availability/working-hours', { workingHours });
}

export type SegmentType = 'AVAILABLE' | 'BLOCKED' | 'BOOKED';

/** One entry in `GET /api/availability/calendar`'s `segments` array. `blockId`/`reason` are
 *  populated only when `type === 'BLOCKED'`; `orderId`/`orderStatus` only when
 *  `type === 'BOOKED'` — `null` otherwise. Exact, non-grid-rounded timestamps (design §5's
 *  "grid precision" note) — the 30-minute grid is a frontend rendering convention only. */
export interface CalendarSegment {
  type: SegmentType;
  startAt: string;
  endAt: string;
  blockId: number | null;
  reason: string | null;
  orderId: number | null;
  orderStatus: OrderStatus | null;
}

/** `GET /api/availability/calendar`'s response shape. `workingHours` is date-independent
 *  (returned once, not per-day). `timezone` is always the fixed business-timezone constant's
 *  zone id (`"Asia/Jerusalem"`), echoed explicitly so the frontend never hardcodes/guesses it. */
export interface CalendarResponse {
  professionalId: number;
  from: string;
  to: string;
  timezone: string;
  workingHours: WorkingHoursItem[];
  segments: CalendarSegment[];
}

/**
 * `GET /api/availability/calendar?from=&to=` — PROFESSIONAL only. `from`/`to` accept either a
 * full ISO-8601 date-time (with offset/`Z`) or a bare ISO date (`"2026-08-16"`, interpreted as
 * midnight in the business timezone server-side) — callers pass bare dates so the backend, not
 * the browser's own timezone, is the single source of truth for where a calendar day begins
 * (see `AvailabilityService#parseCalendarInstant`). Range is capped at 6 weeks server-side.
 */
export function getAvailabilityCalendar(from: string, to: string): Promise<CalendarResponse> {
  const params = new URLSearchParams({ from, to });
  return httpClient.get<CalendarResponse>(`/api/availability/calendar?${params.toString()}`);
}

/**
 * Manual availability blocks (M5, design §4.3-§4.5). Shapes verified directly against the
 * real backend records (`availability.dto.CreateBlockRequest`/`BlockResponse`). `reason` is
 * optional free text, `null`/omitted when not provided.
 */
export interface CreateBlockRequest {
  startAt: string;
  endAt: string;
  reason?: string | null;
}

export interface BlockResponse {
  id: number;
  professionalId: number;
  startAt: string;
  endAt: string;
  reason: string | null;
  createdAt: string;
  updatedAt: string;
}

/** `POST /api/availability/blocks` — PROFESSIONAL only. `409 BLOCK_OVERLAPS_EXISTING_BLOCK` /
 *  `409 BLOCK_OVERLAPS_BOOKING` on overlap (design §4.3). */
export function createAvailabilityBlock(payload: CreateBlockRequest): Promise<BlockResponse> {
  return httpClient.post<BlockResponse>('/api/availability/blocks', payload);
}

/** `PATCH /api/availability/blocks/{blockId}` — PROFESSIONAL only, must be the block's owner.
 *  Full replace of `startAt`/`endAt`/`reason`, not a partial patch, despite the HTTP verb
 *  (design §4.4). Same 409 overlap codes as create. */
export function updateAvailabilityBlock(blockId: number, payload: CreateBlockRequest): Promise<BlockResponse> {
  return httpClient.patch<BlockResponse>(`/api/availability/blocks/${blockId}`, payload);
}

/** `DELETE /api/availability/blocks/{blockId}` — PROFESSIONAL only, must be the block's owner. */
export function deleteAvailabilityBlock(blockId: number): Promise<void> {
  return httpClient.delete<void>(`/api/availability/blocks/${blockId}`);
}
