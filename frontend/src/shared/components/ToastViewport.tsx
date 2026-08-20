import { createPortal } from 'react-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition, Transition } from 'framer-motion';
import { AlertTriangle, CheckCircle2, Info, X } from 'lucide-react';
import { useToast } from '../hooks/useToast';
import type { ToastItem, ToastTone } from '../hooks/toastContext';
import { toastTransition } from '../motion/variants';
import styles from './ToastViewport.module.css';

const TONE_ICON: Partial<Record<ToastTone, typeof CheckCircle2>> = {
  success: CheckCircle2,
  error: AlertTriangle,
  info: Info,
};

/**
 * Portaled toast stack (`createPortal` into `document.body`, same pattern `Modal.tsx` already
 * uses). Reads the live stack from `ToastProvider` via `useToast()` — there is no
 * viewport-specific context, the same `ToastContext` backs both the imperative `showToast` API
 * and this render. Mount this once, near the root (`App.tsx`) — it renders nothing (`null`)
 * whenever the stack is empty, so mounting it "inert" (before any `showToast` caller exists)
 * has zero visible effect.
 *
 * **Positioning, relative to `ActiveOrderIndicator`**: that FAB is `position: fixed` at the
 * bottom-end corner (`bottom`/`inset-inline-end: var(--space-6)`, `--z-floating`,
 * `ActiveOrderIndicator.module.css`) and only renders for authenticated customers. Rather than
 * inset the toast stack around that one corner (fragile if the FAB's own position ever moves,
 * and toasts should be visible regardless of role/screen), this viewport docks to the
 * **top-center** of the viewport instead — a standard toast position that structurally cannot
 * overlap a bottom-corner FAB on any screen, at `z-index: var(--z-toast)` (1100, above
 * `--z-overlay`'s 1000, so a toast triggered by a modal action stays visible above it).
 *
 * `role="status"` + `aria-live="polite"` on the stack container (not `assertive` — toasts are
 * informational, not urgent interrupts, even for `error` tone; a genuinely blocking error
 * belongs in a `Modal`/inline validation, not a toast). Enter/exit via `AnimatePresence` +
 * the shared `toastTransition` variant; respects OS-level reduced motion via `framer-motion`'s
 * own `useReducedMotion()` (swapped to an instant transition, mirroring `Modal.tsx`'s approach).
 */
export function ToastViewport() {
  const { toasts, dismissToast } = useToast();
  const shouldReduceMotion = useReducedMotion();

  if (typeof document === 'undefined') {
    return null;
  }

  const instantTransition: Transition = { duration: 0 };

  return createPortal(
    <div className={styles.viewport} role="status" aria-live="polite">
      <AnimatePresence>
        {toasts.map((toast) => (
          <ToastCard
            key={toast.id}
            toast={toast}
            onDismiss={() => dismissToast(toast.id)}
            transitionOverride={shouldReduceMotion ? instantTransition : undefined}
          />
        ))}
      </AnimatePresence>
    </div>,
    document.body,
  );
}

interface ToastCardProps {
  toast: ToastItem;
  onDismiss: () => void;
  /** Set to an instant (`duration: 0`) transition when `useReducedMotion()` is on. */
  transitionOverride?: Transition;
}

function ToastCard({ toast, onDismiss, transitionOverride }: ToastCardProps) {
  const Icon = TONE_ICON[toast.tone];

  return (
    <motion.div
      layout
      className={[styles.toast, styles[toast.tone]].join(' ')}
      variants={toastTransition}
      initial="initial"
      animate={
        transitionOverride
          ? { ...(toastTransition.animate as TargetAndTransition), transition: transitionOverride }
          : 'animate'
      }
      exit={
        transitionOverride
          ? { ...(toastTransition.exit as TargetAndTransition), transition: transitionOverride }
          : 'exit'
      }
    >
      {Icon && (
        <span className={styles.icon} aria-hidden="true">
          <Icon size={18} />
        </span>
      )}
      <p className={styles.message}>{toast.message}</p>
      <button type="button" className={styles.closeButton} onClick={onDismiss} aria-label="סגירה">
        <X size={16} aria-hidden="true" />
      </button>
    </motion.div>
  );
}
