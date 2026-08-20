import { useContext } from 'react';
import { PendingRequestsContext, type PendingRequestsContextValue } from './pendingRequestsContext';

export function usePendingRequests(): PendingRequestsContextValue {
  const context = useContext(PendingRequestsContext);
  if (!context) {
    throw new Error('usePendingRequests must be used within a PendingRequestsProvider');
  }
  return context;
}
