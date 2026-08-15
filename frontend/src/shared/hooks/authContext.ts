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
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);
