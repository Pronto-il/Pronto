import { useContext } from 'react';
import { ActiveOrderContext, type ActiveOrderContextValue } from './activeOrderContext';

export function useActiveOrder(): ActiveOrderContextValue {
  const context = useContext(ActiveOrderContext);
  if (!context) {
    throw new Error('useActiveOrder must be used within an ActiveOrderProvider');
  }
  return context;
}
