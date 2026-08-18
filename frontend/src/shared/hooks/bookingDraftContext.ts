import { createContext } from 'react';
import type { AddressValue } from '../components/addressTypes';

/**
 * Booking-draft persistence (`ms3-ms4-corrections-design.md` §4). Mirrors the shape/location
 * of `authContext.ts` — global, cross-feature, localStorage-backed client state consumed both
 * by the app shell (`AppLayout`) and by `features/issues`/`features/booking`.
 */
export type BookingDraftStage =
  | 'ISSUE_DESCRIBE' // NewIssuePage 'describe' step
  | 'ISSUE_CLARIFY' // NewIssuePage 'clarify' step
  | 'ISSUE_REVIEW' // NewIssuePage 'review' step
  | 'ADDRESS_SELECTION' // BookingFlowPage/SosBookingFlowPage 'address' step
  | 'PROFESSIONAL_SELECTION' // 'professionals' step (both flows)
  | 'SLOT_SELECTION' // BookingFlowPage 'slot' step only (STANDARD has no SOS equivalent)
  | 'BOOKING_CONFIRM'; // 'confirm' step (both flows)

export interface BookingDraftPhoto {
  imageKey: string;
  /** Durable backend URL from the upload response (`UploadImageResponse.imageUrl`) — NOT the
   *  ephemeral `URL.createObjectURL(file)` blob preview, which does not survive a full page
   *  reload. See §4.7. */
  imageUrl: string;
}

export interface BookingDraft {
  /** Bumped on any schema-shape change; an unreadable/mismatched-version draft found in
   *  localStorage on load is discarded, not migrated. */
  version: 1;
  /** The user this draft belongs to — used to auto-discard on logout / different-account
   *  login, since localStorage is not otherwise user-scoped. See §4.6. */
  ownerId: number;

  stage: BookingDraftStage;
  urgencyType: 'STANDARD' | 'SOS';

  // -- issue-creation fields, present from ISSUE_DESCRIBE onward --
  description: string;
  photos: BookingDraftPhoto[];
  /** Only meaningful while stage === 'ISSUE_CLARIFY'; re-submitted to `classifyIssue` on resume. */
  clarificationAnswers?: { question: string; answer: string }[];
  /** Customer's confirmed/edited category once they reach ISSUE_REVIEW. */
  categoryId?: number;

  // -- present from ADDRESS_SELECTION onward (issue already persisted) --
  issueId?: number;

  // -- address selection --
  addressMode?: 'DEFAULT' | 'CUSTOM';
  address?: AddressValue; // full 7-field snapshot, whichever mode was chosen

  // -- professional/slot selection (present from PROFESSIONAL_SELECTION onward) --
  professionalId?: number;
  /** Both flows offer the identical 2-way RECOMMENDED|CHEAPEST toggle (§3 reconciliation) —
   *  FASTEST is never a value a customer's draft can hold. */
  sort?: 'RECOMMENDED' | 'CHEAPEST';
  /** STANDARD only. */
  slotId?: number;

  updatedAt: string; // ISO timestamp
}

/** Mirrors `AuthContextValue`'s minimal shape. */
export interface BookingDraftContextValue {
  draft: BookingDraft | null;
  /** Upsert: creates the draft (with sensible defaults) if none exists, else shallow-merges
   *  the patch. Always refreshes `updatedAt` and re-writes localStorage. Called on every step
   *  transition (forward AND backward) in NewIssuePage/BookingFlowPage/SosBookingFlowPage. */
  updateDraft: (patch: Partial<Omit<BookingDraft, 'version' | 'updatedAt' | 'ownerId'>>) => void;
  /** Clears context + localStorage. The ONLY two call sites: post-order-creation success
   *  (§4.5.1) and the indicator's explicit discard action. */
  clearDraft: () => void;
}

export const BookingDraftContext = createContext<BookingDraftContextValue | undefined>(undefined);

/** Resume route for a draft, per §4.4's routing table. */
export function resolveDraftRoute(draft: BookingDraft): string {
  switch (draft.stage) {
    case 'ISSUE_DESCRIBE':
    case 'ISSUE_CLARIFY':
    case 'ISSUE_REVIEW':
      return '/issues/new';
    default:
      return draft.urgencyType === 'SOS'
        ? `/issues/${draft.issueId}/sos-booking`
        : `/issues/${draft.issueId}/booking`;
  }
}
