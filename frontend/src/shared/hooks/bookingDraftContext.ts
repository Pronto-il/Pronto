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
  /** Raw storage key only — a resolved URL is never persisted here. Presigned URLs expire
   *  (backend MS9 design §6, 300s TTL) long before a paused draft is likely to be resumed, so
   *  a URL saved at upload time would already be dead by the time `NewIssuePage` rehydrates
   *  it. Instead, `NewIssuePage`'s resume flow re-resolves every photo's `imageKey` into a
   *  fresh presigned URL via a single batch call (`shared/api/storage.ts`'s
   *  `getPresignedImageUrls`) immediately on mount — see
   *  `docs/architecture/backend-ms9-presigned-image-urls-design.md` §12. This interface
   *  previously also carried an `imageUrl` field described as "durable" — that was true only
   *  while `POST /api/storage/images`'s response returned a non-expiring proxy URL; it
   *  stopped being true the moment upload responses became presigned (§7) and is corrected
   *  here, not left contradicting the new behavior. */
  imageKey: string;
}

export interface BookingDraft {
  /** Bumped on any schema-shape change; an unreadable/mismatched-version draft found in
   *  localStorage on load is discarded, not migrated. Bumped to `2` by the professional
   *  weekly availability calendar feature M6 (design §9.2.2/§9.2.3): `slotId` was replaced
   *  by `bookedStart` — an in-progress `version: 1` draft from before this change is
   *  discarded on load rather than misread (no `slotId`-to-`bookedStart` migration is
   *  possible, since a discarded/expired slot ID carries no timestamp to translate). */
  version: 2;
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
  /** STANDARD only. The chosen ISO start instant (design §9.2.2/§9.2.3) — replaces the
   *  retired `slotId` field as of `version: 2`. */
  bookedStart?: string;

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
