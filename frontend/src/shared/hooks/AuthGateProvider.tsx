import { useCallback, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { AuthGateContext } from './authGateContext';

/**
 * Holds the pending "do this once there is a session" action. Mounted above the router
 * (`App.tsx`), because the gate outlives no navigation but must be readable both by the screen
 * that opens it and by `features/auth`'s landing hook, which is rendered inside it.
 */
export function AuthGateProvider({ children }: { children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false);
  /**
   * The resume action, in a ref rather than in state: `completeInPlace` must be referentially
   * stable (it is a dependency of `useSessionLanding`'s callback, which every auth form holds),
   * and it must read the action registered *now*, not the one captured when it was created.
   */
  const pendingRef = useRef<(() => void) | null>(null);

  const open = useCallback((onAuthenticated: () => void) => {
    pendingRef.current = onAuthenticated;
    setIsOpen(true);
  }, []);

  const close = useCallback(() => {
    pendingRef.current = null;
    setIsOpen(false);
  }, []);

  const completeInPlace = useCallback(() => {
    const resume = pendingRef.current;
    if (!resume) {
      return false;
    }
    pendingRef.current = null;
    setIsOpen(false);
    resume();
    return true;
  }, []);

  const value = useMemo(() => ({ isOpen, open, close, completeInPlace }), [isOpen, open, close, completeInPlace]);
  return <AuthGateContext.Provider value={value}>{children}</AuthGateContext.Provider>;
}
