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
  /**
   * The user this draft belongs to, or `null` while it is a GUEST draft.
   *
   * Deferred authentication made `null` a real, expected state: a visitor builds a complete
   * booking before they have an account, and the draft is what survives the trip through
   * login/registration and brings them back to the exact screen they left.
   *
   * The discard rule turns on this field and is deliberately asymmetric (see
   * `BookingDraftProvider`): a draft owned by a DIFFERENT user is discarded, because
   * localStorage is not user-scoped and one account must never see another's booking. A draft
   * owned by NOBODY is *adopted* by whoever signs in, because that guest was the person who
   * built it — discarding it there would throw away the entire journey at the exact moment the
   * customer did what we asked.
   */
  ownerId: number | null;

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

  /**
   * Set only by {@link sanitizeRestoredDraft} on a stale guest draft: the address below is kept
   * as prefill, but has not been confirmed *for this visit* and must be asked again.
   *
   * Absent on every live draft, so nothing about the normal flow changes. Cleared by whichever
   * address step the customer then confirms.
   *
   * A flag rather than clearing the address, because the two questions are different: "do we have
   * an address?" (we do, and re-typing it would be rude) and "has this customer confirmed it is
   * still where they want somebody sent?" — which after a night away is genuinely unknown, and is
   * the question `isAddressComplete` was standing in for.
   */
  addressUnconfirmed?: true;

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

/**
 * Resume route for a draft, per §4.4's routing table.
 *
 * <p>The booking routes carry no issue id any more. Deferred authentication moved issue creation
 * to the booking commit, so during matching and slot selection there IS no issue — for a guest
 * because they have no account yet, and for a signed-in customer because nothing has been
 * committed. The draft is the state, and the URL no longer pretends otherwise.
 */
export function resolveDraftRoute(draft: BookingDraft): string {
  switch (draft.stage) {
    case 'ISSUE_DESCRIBE':
    case 'ISSUE_CLARIFY':
    case 'ISSUE_REVIEW':
      return '/issues/new';
    default:
      return draft.urgencyType === 'SOS' ? '/sos-booking' : '/booking';
  }
}

/**
 * How long a **guest** draft keeps its navigation state before a return visit is treated as a new
 * one. Twelve hours: everything inside one visit — a refresh, a commute, a lunch break, a phone
 * that went to sleep — resumes exactly as it does today, while any overnight gap re-validates.
 *
 * <p>The upper bound it sits under is the backend's guest upload session
 * (`pronto.auth.guest-session-ttl-seconds`, 24h): past that a guest's photos are no longer
 * reachable with the token that uploaded them, so a draft older than a day has already lost part
 * of itself regardless of what this policy says.
 */
export const GUEST_DRAFT_STALE_AFTER_MS = 12 * 60 * 60 * 1000;

/** Stages that are still *describing the problem* — nothing here can skip a booking step. */
const ISSUE_STAGES: ReadonlySet<BookingDraftStage> = new Set<BookingDraftStage>([
  'ISSUE_DESCRIBE',
  'ISSUE_CLARIFY',
  'ISSUE_REVIEW',
]);

/**
 * The one freshness policy for restored drafts, applied at the single point a draft re-enters the
 * app ({@link BookingDraftProvider}'s load) rather than re-asked screen by screen.
 *
 * <h2>What went wrong without it</h2>
 *
 * A guest starts a booking, closes the app, and comes back the next day. Every screen decided
 * where to resume from data that had no age attached to it: `BookingFlowPage` skips its address
 * step whenever `stage` is past it and the address fields are non-empty, `ProfessionMatchPage`
 * opens straight into the matching wheel whenever `isAddressComplete(address)`, and
 * `ProntoSosEntryPage` goes further still — it *auto-activates a dispatch* against a usable
 * address without asking. So yesterday's address, professional and time were silently carried
 * into today's booking, and the customer never saw the screen that would have let them notice.
 *
 * <h2>What this keeps and what it drops</h2>
 *
 * Kept, because it is the customer's own account of their problem and does not go stale: the
 * description, the confirmed category, the uploaded photo keys, the clarification answers, the
 * urgency (so Regular stays Regular and SOS stays SOS), any issue already created, and the
 * address itself — as prefill, flagged {@link BookingDraft.addressUnconfirmed}.
 *
 * Dropped, because each is a *position in a flow* rather than a fact about the problem: the
 * stage (rewound to `ADDRESS_SELECTION`, the first step that asks anything), the chosen
 * professional, the chosen start time, the sort order and the address mode.
 *
 * <h2>Scope</h2>
 *
 * Guest drafts only (`ownerId === null`). A signed-in customer's draft is untouched: they have an
 * account to come back to, their address is on their profile, and nothing about their resume
 * behaviour was reported as wrong. Issue-stage drafts are also left alone — there is no booking
 * step for them to skip, and the describe/clarify/review screens re-derive everything anyway.
 */
export function sanitizeRestoredDraft(draft: BookingDraft, nowMs: number): BookingDraft {
  if (draft.ownerId !== null) {
    return draft;
  }
  const updatedAtMs = Date.parse(draft.updatedAt);
  // An unparseable timestamp is treated as stale: a draft that cannot say when it was written
  // cannot be trusted to say where the customer was.
  const isFresh = Number.isFinite(updatedAtMs) && nowMs - updatedAtMs < GUEST_DRAFT_STALE_AFTER_MS;
  if (isFresh || ISSUE_STAGES.has(draft.stage)) {
    return draft;
  }
  return {
    ...draft,
    stage: 'ADDRESS_SELECTION',
    professionalId: undefined,
    bookedStart: undefined,
    sort: undefined,
    addressMode: undefined,
    ...(draft.address ? { addressUnconfirmed: true as const } : {}),
  };
}
