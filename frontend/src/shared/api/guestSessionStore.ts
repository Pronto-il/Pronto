/**
 * Where the guest upload session token lives on the client. Deliberately dependency-free: it is
 * read by `httpClient` (to attach the header) and written by `guestSession.ts` (which mints via
 * `httpClient`), so putting the storage in its own module is what keeps that from being an import
 * cycle. Same one-way shape as `setAuthTokenGetter`, without needing a provider to inject it.
 *
 * The token is the backend-minted proof that a visitor owns the `guests/{id}/...` storage keys
 * their photos were uploaded under (`auth.security.GuestSessionTokenService`). It is persisted
 * rather than held in memory because the one thing it has to survive is a full page load — the
 * trip to the registration screen at the booking commit — exactly like the booking draft whose
 * photo keys it authorises.
 */

const GUEST_SESSION_STORAGE_KEY = 'pronto_guest_upload_session';

export interface StoredGuestSession {
  token: string;
  /** Epoch ms, so a session that expired while the tab was closed is replaced rather than sent. */
  expiresAt: number;
}

/**
 * The stored session if it is still comfortably valid, else `null`.
 *
 * A minute of headroom: a token that expires mid-request surfaces as a failed upload with a
 * confusing error, and minting a fresh one costs one cheap call.
 */
export function readGuestSession(): StoredGuestSession | null {
  try {
    const raw = localStorage.getItem(GUEST_SESSION_STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as StoredGuestSession;
    if (typeof parsed.token !== 'string' || typeof parsed.expiresAt !== 'number') {
      return null;
    }
    return parsed.expiresAt - 60_000 > Date.now() ? parsed : null;
  } catch {
    return null;
  }
}

/** The current token, or `null`. Never mints — see `guestSession.ts`'s `ensureGuestSessionToken`. */
export function getGuestSessionToken(): string | null {
  return readGuestSession()?.token ?? null;
}

export function writeGuestSession(session: StoredGuestSession): void {
  localStorage.setItem(GUEST_SESSION_STORAGE_KEY, JSON.stringify(session));
}

/**
 * Forgets the session.
 *
 * Called when the booking draft is cleared: at that point either the order was created — and the
 * backend has already promoted every photo onto the customer's own account, so the guest namespace
 * holds nothing anyone needs — or the customer discarded the draft outright. Deliberately NOT
 * called on login: a guest who registers mid-flow still needs this token, because their photos stay
 * in the guest namespace until the commit promotes them.
 */
export function clearGuestSession(): void {
  localStorage.removeItem(GUEST_SESSION_STORAGE_KEY);
}
