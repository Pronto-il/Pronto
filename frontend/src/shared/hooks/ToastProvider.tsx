import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { ToastContext, type ToastItem, type ToastOptions } from './toastContext';

const DEFAULT_TOAST_DURATION_MS = 4000;
/** Max stacked toasts (MS1 plan, Architecture §5). When a 4th arrives, the oldest is
 *  dismissed first to make room, rather than dropping the newest. */
const MAX_TOASTS = 3;

/**
 * Holds the current toast stack in React context, mirroring `AuthProvider`'s structural
 * conventions (state + imperative actions exposed via context, mounted once near the root of
 * `App.tsx`). Each toast auto-dismisses after its own `duration` (default `4000`ms,
 * overridable per `showToast` call); at most `MAX_TOASTS` are shown at once — enqueuing a 4th
 * dismisses the oldest immediately so the stack never exceeds 3.
 *
 * Purely additive/inert on its own: nothing calls `showToast` yet in MS1 (see `App.tsx`) — this
 * is plumbing for MS2+ consumers.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const nextIdRef = useRef(0);
  const timersRef = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  const clearTimer = useCallback((id: string) => {
    const timer = timersRef.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timersRef.current.delete(id);
    }
  }, []);

  const dismissToast = useCallback(
    (id: string) => {
      clearTimer(id);
      setToasts((prev) => prev.filter((toast) => toast.id !== id));
    },
    [clearTimer],
  );

  const showToast = useCallback(
    (message: string, options?: ToastOptions): string => {
      nextIdRef.current += 1;
      const id = `toast-${nextIdRef.current}`;
      const duration = options?.duration ?? DEFAULT_TOAST_DURATION_MS;
      const toast: ToastItem = { id, message, tone: options?.tone ?? 'neutral', duration };

      setToasts((prev) => {
        const next = [...prev, toast];
        if (next.length <= MAX_TOASTS) {
          return next;
        }
        // Oldest dismissed first when a 4th arrives.
        const [oldest, ...rest] = next;
        clearTimer(oldest.id);
        return rest;
      });

      timersRef.current.set(
        id,
        setTimeout(() => dismissToast(id), duration),
      );

      return id;
    },
    [clearTimer, dismissToast],
  );

  useEffect(() => {
    // Snapshot the ref's Map instance at mount — it never changes identity, but this avoids
    // referencing `.current` directly inside the cleanup closure.
    const timers = timersRef.current;
    return () => {
      timers.forEach((timer) => clearTimeout(timer));
      timers.clear();
    };
  }, []);

  return <ToastContext.Provider value={{ toasts, showToast, dismissToast }}>{children}</ToastContext.Provider>;
}
