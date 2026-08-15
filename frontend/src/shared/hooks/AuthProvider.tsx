import { useEffect, useRef, useState, type ReactNode } from 'react';
import { login as loginRequest } from '../api/auth';
import { getMe, type UserMeResponse } from '../api/users';
import { ApiError, setAuthTokenGetter } from '../api/httpClient';
import { AuthContext } from './authContext';

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
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function rehydrate() {
      if (!tokenRef.current) {
        setIsLoading(false);
        return;
      }
      try {
        const me = await getMe();
        if (!cancelled) {
          setUser(me);
        }
      } catch (error) {
        if (!cancelled && error instanceof ApiError && error.status === 401) {
          tokenRef.current = null;
          setToken(null);
          localStorage.removeItem(TOKEN_STORAGE_KEY);
        }
      } finally {
        if (!cancelled) {
          setIsLoading(false);
        }
      }
    }

    void rehydrate();
    return () => {
      cancelled = true;
    };
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
  }

  return (
    <AuthContext.Provider value={{ token, user, isLoading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
