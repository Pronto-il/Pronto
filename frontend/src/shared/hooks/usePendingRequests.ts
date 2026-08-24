import { useContext, useEffect } from 'react';
import { PendingRequestsContext, type PendingRequestsContextValue } from './pendingRequestsContext';

export function usePendingRequests(): PendingRequestsContextValue {
  const context = useContext(PendingRequestsContext);
  if (!context) {
    throw new Error('usePendingRequests must be used within a PendingRequestsProvider');
  }
  return context;
}

/**
 * Same value as `usePendingRequests()`, plus a standing request for the live cadence while the
 * calling component is mounted — for a screen that *shows* the pending feed rather than a count
 * of it, where an order arriving or being taken by nobody is something the professional acts on
 * within seconds.
 *
 * The shared poll speeds up on mount and returns to the background cadence on unmount, so
 * leaving `/pro/requests` cannot leave a fast timer behind: there is only ever the one timer, and
 * it re-times itself from the remaining subscribers.
 */
export function useLivePendingRequests(): PendingRequestsContextValue {
  const context = usePendingRequests();
  const { setLiveCadence } = context;

  useEffect(() => {
    setLiveCadence(true);
    return () => setLiveCadence(false);
  }, [setLiveCadence]);

  return context;
}
