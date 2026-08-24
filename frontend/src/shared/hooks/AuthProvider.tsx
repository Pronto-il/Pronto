import { useEffect, useRef, useState, type ReactNode } from 'react';
import { login as loginRequest } from '../api/auth';
import { getMe, type UserMeResponse } from '../api/users';
import { ApiError, setAuthTokenGetter, setUnauthorizedHandler } from '../api/httpClient';
import { AuthContext } from './authContext';
import { clearPollingStore } from './pollingStore';

const TOKEN_STORAGE_KEY = 'pronto_auth_token';

/**
 * Holds the current auth token/user in React context, persists the token to
 * `localStorage`, and rehydrates on app load by calling `GET /api/users/me`. A 401 during
 * rehydration just ends up logged-out (no forced redirect from here — `RequireAuth`
 * handles redirecting *already-authenticated* screens).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_STORAGE_KEY));
  const [user, setUser] = useState<UserMeResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  // Mirrors `token` synchronously so the injected token-getter (below) never reads a
  // stale closure from before a state update has flushed.
  const tokenRef = useRef(token);

  useEffect(() => {
    tokenRef.current = token;
  }, [token]);

  useEffect(() => {
    // Registered once so httpClient can attach the Authorization header without
    // importing React or reading localStorage directly — keeps the api layer
    // framework-agnostic and the token's source of truth in this provider only.
    setAuthTokenGetter(() => tokenRef.current);

    // Any request we sent *with* a token that came back 401 means that token is dead
    // (expired, or its user row is gone). Ending the session here is the same logout path
    // `logout()` takes, so `RequireAuth` sends the user to `/login` on the next render
    // instead of leaving them on a screen whose every write fails with UNAUTHORIZED. This is
    // the app's intended behavior for an expired token — there is no refresh-token flow in
    // this system (`docs/architecture/api-contract.md` §3.1: a single 24h access token).
    setUnauthorizedHandler(() => {
      if (!tokenRef.current) {
        return;
      }
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      tokenRef.current = null;
      setToken(null);
      setUser(null);
      clearPollingStore();
    });
  }, []);

  /**
   * Guards the one-and-only bootstrap read. `StrictMode` runs mount effects twice in
   * development, which made every page load issue `GET /api/users/me` twice — the duplicate
   * `/me` most visible in DevTools. A ref (not a cleanup flag) is what fixes it: refs survive
   * StrictMode's simulated remount, so the second pass sees the guard and does nothing, whereas
   * a `cancelled` flag would have cancelled the *first* pass and left the second one suppressed
   * by the guard, i.e. no user at all.
   *
   * There is deliberately no cancellation here any more: `AuthProvider` wraps the whole app and
   * is never unmounted while the tab is open, so there is no unmount for a late response to race.
   */
  const hasBootstrappedRef = useRef(false);

  useEffect(() => {
    if (hasBootstrappedRef.current) {
      return;
    }
    hasBootstrappedRef.current = true;

    async function rehydrate() {
      if (!tokenRef.current) {
        setIsLoading(false);
        return;
      }
      try {
        const me = await getMe();
        setUser(me);
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          tokenRef.current = null;
          setToken(null);
          localStorage.removeItem(TOKEN_STORAGE_KEY);
        }
      } finally {
        setIsLoading(false);
      }
    }

    void rehydrate();
  }, []);

  async function login(email: string, password: string): Promise<UserMeResponse> {
    const response = await loginRequest({ email, password });
    localStorage.setItem(TOKEN_STORAGE_KEY, response.token);
    tokenRef.current = response.token;
    setToken(response.token);
    const me = await getMe();
    setUser(me);
    return me;
  }

  function logout() {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    tokenRef.current = null;
    setToken(null);
    setUser(null);
    // Shared polling entries are keyed by request, not by caller, so `bookings:orders:me` would
    // otherwise still be holding the outgoing session's orders when the next account signs in.
    clearPollingStore();
  }

  async function refreshUser(): Promise<void> {
    if (!tokenRef.current) {
      return;
    }
    try {
      const me = await getMe();
      setUser(me);
    } catch {
      // Best-effort refresh only — a transient failure here leaves the previous (stale but
      // valid) `user` in place rather than surfacing an error to an unrelated caller.
    }
  }

  return (
    <AuthContext.Provider value={{ token, user, isLoading, login, logout, refreshUser }}>
      {children}
    </AuthContext.Provider>
  );
}
