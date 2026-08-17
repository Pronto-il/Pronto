import { httpClient } from './httpClient';

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
