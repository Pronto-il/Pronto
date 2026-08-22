import { useContext } from 'react';
import { ProSosContext, type ProSosContextValue } from './proSosContext';

export function useProSos(): ProSosContextValue {
  const context = useContext(ProSosContext);
  if (!context) {
    throw new Error('useProSos must be used within a ProSosProvider');
  }
  return context;
}
