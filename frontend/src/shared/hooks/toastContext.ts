import { createContext } from 'react';

/**
 * Toast tone vocabulary — a sensible subset of `Badge`'s `BadgeTone`
 * (`shared/components/Badge.tsx`), not a separately-invented vocabulary. `'primary'` and
 * `'warning'` are dropped: no named MS1+ toast consumer needs a "recommended" or "warning"
 * toast yet, and `Badge`'s full 6-tone set is deliberately not reused wholesale here per the
 * MS1 plan's scope-discipline decision (#5) — every tone must have a concrete purpose. Add
 * back from `BadgeTone` if/when a consumer needs it, not speculatively.
 */
export type ToastTone = 'neutral' | 'success' | 'error' | 'info';

export interface ToastOptions {
  /** Default `'neutral'`. */
  tone?: ToastTone;
  /** Auto-dismiss delay in ms. Default `4000` (see `ToastProvider`'s `DEFAULT_TOAST_DURATION_MS`). */
  duration?: number;
}

/** A single toast currently in the stack, as rendered by `ToastViewport`. */
export interface ToastItem {
  id: string;
  message: string;
  tone: ToastTone;
  duration: number;
}

export interface ToastContextValue {
  /** Current stack, oldest first. Capped at 3 — see `ToastProvider`. */
  toasts: ToastItem[];
  /** Enqueues a toast and returns its id (e.g. for an early manual `dismissToast` call). */
  showToast: (message: string, options?: ToastOptions) => string;
  /** Dismisses a toast before its auto-dismiss timer fires (e.g. a manual close button). */
  dismissToast: (id: string) => void;
}

export const ToastContext = createContext<ToastContextValue | undefined>(undefined);
