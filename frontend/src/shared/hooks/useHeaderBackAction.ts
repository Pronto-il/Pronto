import { useContext, useEffect, useRef } from 'react';
import { HeaderBackContext } from './headerBackContext';

/**
 * Renders this screen's back control inside the app header (right of the brand under
 * `dir="rtl"`), for as long as the screen is mounted, and clears it on unmount.
 *
 * `onBack` is held in a ref and invoked through a stable wrapper, so a handler that closes over
 * live state — `NewIssuePage`'s, which reads the current step — stays current without the
 * registration effect re-running on every keystroke.
 *
 * `null` means "this screen has no back action right now" — for a screen where back is
 * conditional (`BookingFlowPage`'s success step, `ProfessionMatchPage`'s matching phase) rather
 * than absent. It empties the slot instead of registering a dead button, so callers never need a
 * conditional hook call.
 */
export function useHeaderBackAction(onBack: (() => void) | null, label = 'חזרה'): void {
  const { setAction } = useContext(HeaderBackContext);
  const handlerRef = useRef(onBack);
  const hasAction = onBack !== null;

  useEffect(() => {
    handlerRef.current = onBack;
  });

  useEffect(() => {
    if (!hasAction) {
      setAction(null);
      return;
    }
    setAction({ label, onBack: () => handlerRef.current?.() });
    return () => setAction(null);
  }, [setAction, label, hasAction]);
}
