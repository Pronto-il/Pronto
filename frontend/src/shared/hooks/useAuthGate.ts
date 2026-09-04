import { useContext } from 'react';
import { AuthGateContext, type AuthGateContextValue } from './authGateContext';

/** Reader for the deferred-authentication gate — see `authGateContext.ts`. */
export function useAuthGate(): AuthGateContextValue {
  return useContext(AuthGateContext);
}
