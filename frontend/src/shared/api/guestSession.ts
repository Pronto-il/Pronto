import { httpClient } from './httpClient';
import { readGuestSession, writeGuestSession } from './guestSessionStore';

interface GuestUploadSessionResponse {
  guestSessionToken: string;
  expiresInSeconds: number;
}

/**
 * `POST /api/storage/guest-sessions` — mints the upload namespace a visitor with no account owns
 * their issue photos under, or returns the one already stored.
 *
 * Called immediately before a guest's first upload and nowhere else, so a visitor who never
 * attaches a photo never causes a session to exist at all. `auth: false` because a session is
 * about a caller who by definition has no token; sending one would be harmless but meaningless.
 */
export async function ensureGuestSessionToken(): Promise<string> {
  const existing = readGuestSession();
  if (existing) {
    return existing.token;
  }
  const response = await httpClient.post<GuestUploadSessionResponse>(
    '/api/storage/guest-sessions',
    undefined,
    { auth: false },
  );
  const session = {
    token: response.guestSessionToken,
    expiresAt: Date.now() + response.expiresInSeconds * 1000,
  };
  writeGuestSession(session);
  return session.token;
}

export { clearGuestSession, getGuestSessionToken } from './guestSessionStore';
