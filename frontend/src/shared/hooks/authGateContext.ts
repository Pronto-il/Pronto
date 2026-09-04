import { createContext } from 'react';

export interface AuthGateContextValue {
  /** Whether the gate is currently asking for a session. */
  isOpen: boolean;
  /**
   * Ask for a session without leaving the screen. `onAuthenticated` runs once a session exists —
   * it is the action the customer was refused, resumed.
   */
  open: (onAuthenticated: () => void) => void;
  /** The customer dismissed the gate. The pending action is dropped; nothing else changes. */
  close: () => void;
  /**
   * Called by `useSessionLanding` when a session lands. Returns `true` when the gate consumed it —
   * i.e. the caller must NOT navigate, because the whole point is that the screen underneath was
   * never left. Returns `false` when no gate is open, which is every ordinary login.
   */
  completeInPlace: () => boolean;
}

/**
 * The deferred-authentication gate: "you need an account to do *this*", asked over the screen the
 * customer is already on rather than by sending them to `/login`.
 *
 * <p>Defaulted rather than `undefined`-and-throw, like `headerBackContext`: a screen rendered
 * outside the provider (every component test) must still work, and without a gate the honest
 * fallback is the old behaviour — nothing opens, nothing is consumed.
 */
export const AuthGateContext = createContext<AuthGateContextValue>({
  isOpen: false,
  open: () => {},
  close: () => {},
  completeInPlace: () => false,
});
