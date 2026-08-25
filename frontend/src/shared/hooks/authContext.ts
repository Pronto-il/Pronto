import { createContext } from 'react';
import type { AuthSession } from '../api/auth';
import type { UserMeResponse } from '../api/users';

export interface AuthContextValue {
  token: string | null;
  user: UserMeResponse | null;
  isLoading: boolean;
  /**
   * Adopts a session the auth flow has already earned, and resolves with the freshly-fetched
   * `GET /api/users/me` user.
   *
   * **Production MS1 replaced `login(email, password)` with this.** A password no longer produces a
   * session — only redeeming a one-time password does — so the provider cannot own "log in" as a
   * single call any more. It owns what it always really owned: holding a token once one exists. The
   * two endpoints that mint one (`POST /api/auth/login/otp` and `POST /api/auth/verify-phone`) both
   * hand their `AuthSession` here.
   */
  establishSession: (session: AuthSession) => Promise<UserMeResponse>;
  /** Client-side discard only — there is no server-side logout endpoint in v1.0. */
  logout: () => void;
  /**
   * Re-runs `GET /api/users/me` and updates `user` in place — for screens that write to a
   * field `users/me` itself reflects (e.g. `PUT /api/professionals/me`'s `fullName`, which
   * updates the underlying `users` row) but that isn't `user`'s own fetch call. No-ops if
   * not currently authenticated. Resolves once the refetch settles; swallows errors (a
   * failed background refresh shouldn't surface as an unhandled rejection to the caller
   * that merely wanted to keep `user` fresh after its own already-successful save).
   */
  refreshUser: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);
