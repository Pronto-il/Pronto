import { createContext } from 'react';
import type { UserMeResponse } from '../api/users';

export interface AuthContextValue {
  token: string | null;
  user: UserMeResponse | null;
  isLoading: boolean;
  /** Resolves with the freshly-fetched `GET /api/users/me` user, or throws `ApiError`. */
  login: (email: string, password: string) => Promise<UserMeResponse>;
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
