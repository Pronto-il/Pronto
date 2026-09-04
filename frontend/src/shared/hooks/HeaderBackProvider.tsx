import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { HeaderBackContext, type HeaderBackAction } from './headerBackContext';

/**
 * Holds the header's back slot. Mounted above the router (see `App.tsx`) so both `AppLayout`'s
 * header — the reader — and the routed screen below it — the writer — sit inside it.
 */
export function HeaderBackProvider({ children }: { children: ReactNode }) {
  const [action, setAction] = useState<HeaderBackAction | null>(null);
  // `setAction` is React's own state setter, so it is stable across renders and safe as an
  // effect dependency in `useHeaderBackAction`.
  const value = useMemo(() => ({ action, setAction }), [action]);
  return <HeaderBackContext.Provider value={value}>{children}</HeaderBackContext.Provider>;
}
