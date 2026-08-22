import { createContext } from 'react';
import type { SosOfferResponse, SosRequestResponse } from '../api/sos';
import type { StompConnectionStatus } from '../realtime';

/**
 * Professional-side Pronto SOS state. Mirrors `pendingRequestsContext.ts`'s shape and scoping
 * decision — context + non-component values in their own file (the Fast-Refresh lint reason
 * `authContext.ts` established), provider mounted inside `ProDashboardLayout` rather than
 * `App.tsx`, because none of this has any reason to poll outside the `/pro/*` subtree.
 *
 * Unlike that context this one carries the derived buckets rather than a raw list, because the
 * bucketing *is* the product semantics: an offer awaiting an answer, an offer where the
 * professional has reported availability and is waiting on the customer, and the one job they were
 * actually chosen for are three different situations with three different affordances, and
 * collapsing them into "offers" is exactly the mistake that produces "you got the job!" copy for a
 * professional who has merely said they are free.
 */

/** The job this professional was chosen for, plus the canonical request state behind it. */
export interface ProSosJob {
  /** The winning offer — `status === 'SELECTED'`. */
  offer: SosOfferResponse;
  /**
   * `GET /api/sos/requests/{id}` for that offer. **This is where the exact service address comes
   * from**, and only because selection is what grants it (`SosAddressAccess.FULL`). Null only if
   * that fetch failed; the panel degrades to the offer's city rather than blocking the job.
   */
  request: SosRequestResponse | null;
}

export interface ProSosContextValue {
  /** Awaiting an answer — `OFFERED`/`VIEWED`. The only bucket with accept/decline actions. */
  incomingOffers: SosOfferResponse[];
  /**
   * `ACCEPTED` — **"I am available", not "I got the job"**. The customer can now see them as a
   * candidate and may still choose somebody else.
   */
  availableOffers: SosOfferResponse[];
  /** The one live job. Null when this professional hasn't been selected for anything in flight. */
  activeJob: ProSosJob | null;
  /**
   * Recently-closed outcomes still worth showing: not selected, expired, declined, and finished/
   * cancelled jobs. Kept briefly so an outcome is never silently swallowed, then dropped.
   */
  resolvedOffers: SosOfferResponse[];
  /** Dismisses one resolved card early. Local only — nothing to persist, nothing to tell the server. */
  dismissResolved: (offerId: number) => void;
  /** Nav-badge count: things that want the professional's attention right now. */
  attentionCount: number;
  isLoading: boolean;
  error: Error | null;
  /** Forces an immediate re-read of canonical state (offers, and the active job's request). */
  refetch: () => void;
  realtimeStatus: StompConnectionStatus;
}

export const ProSosContext = createContext<ProSosContextValue | undefined>(undefined);
