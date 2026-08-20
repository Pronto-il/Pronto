import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition, Transition, Variants } from 'framer-motion';
import { X } from 'lucide-react';
import { modalTransition } from '../motion/variants';
import styles from './Modal.module.css';

export type ModalSize = 'small' | 'normal' | 'large';

/** Standard focusable-elements selector for the local focus trap — matches the elements a
 *  screen reader/keyboard user could tab to (mirrors the widely-used a11y pattern; no new
 *  dependency needed for a trap at this scale). */
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])';

/** Mobile breakpoint this component's own CSS already keys off of (`Modal.module.css`,
 *  matches `WeeklyCalendarGrid`'s existing `640px` convention). Read via `matchMedia` only to
 *  decide which `modalTransition` motion (scale+fade vs slide-up) applies — the actual layout
 *  switch stays CSS-only, unchanged from before. */
const MOBILE_MEDIA_QUERY = '(max-width: 640px)';

export interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Rendered as the dialog's accessible name (`aria-labelledby`) and as a visible heading. */
  title?: string;
  children: ReactNode;
  /** Optional sticky footer (action buttons) rendered below `children`, separated by a divider. */
  footer?: ReactNode;
  /** Desktop centered-dialog width only, per DESIGN_SYSTEM.md §58 (`420px`/`560px`/`720px`).
   *  Ignored on mobile when `mobilePresentation="sheet"` (default), where the panel is always
   *  full-width (bottom sheet). Default `normal`. */
  size?: ModalSize;
  /** How the modal presents on narrow (`max-width: 640px`) viewports. `'sheet'` (default)
   *  preserves today's behavior — a full-width bottom sheet with a drag handle. `'dialog'`
   *  opts out of the sheet treatment and keeps the centered-dialog presentation (respecting
   *  `size`) with subtle motion even on mobile widths, for consumers where a bottom sheet
   *  isn't the right UX. No current consumer needs `'dialog'` yet; it exists for future
   *  milestones per the MS1 plan's Binding Decision #6 ("Modal must... expose a way for
   *  future consumers to opt out and stay a centered dialog"). */
  mobilePresentation?: 'sheet' | 'dialog';
}

/**
 * Generic modal primitive — first component in this codebase needing this pattern (design
 * `docs/architecture/professional-weekly-calendar-design.md` §7.4/§10, M5). Implements
 * `DESIGN_SYSTEM.md` §13 ("Large" border radius, `20px`, used for "Modals, Mobile bottom
 * sheets, Major floating surfaces") and §57-59 (bottom sheet on mobile, centered dialog on
 * desktop) exactly.
 *
 * **Variant choice, documented per the design doc's own "your call" framing**: automatic
 * breakpoint-based switch (CSS `@media (max-width: 640px)`, this codebase's existing
 * breakpoint — see `WeeklyCalendarGrid.module.css`), **not** an explicit `variant` prop. A
 * single component renders both markups (desktop-dialog CSS classes + mobile-sheet CSS
 * classes on the same DOM node) and CSS decides which one is visually active, exactly the
 * same "one DOM tree, `display` toggled by breakpoint" pattern `WeeklyCalendarGrid` already
 * established for its desktop-grid/mobile-day-view split — chosen over a JS
 * `window.matchMedia` listener (fewer moving parts, no resize-listener cleanup, consistent
 * with existing precedent in this codebase) and over a caller-supplied `variant` prop
 * (callers shouldn't have to know/decide which visual treatment their own viewport wants).
 * (MS1 adds one narrow, opt-in exception to this: `mobilePresentation="dialog"` lets a
 * consumer explicitly ask to stay a centered dialog on mobile too — see that prop's doc.)
 *
 * Renders via `createPortal` into `document.body` so `position: fixed` positioning is never
 * broken by an ancestor with its own `transform`/`filter` (none exist today, but this is the
 * first modal in the app and a portal is the standard, low-risk way to guarantee it stays
 * correct as the component tree grows). Closes on overlay click and on `Escape`; locks body
 * scroll while open.
 *
 * **Motion (MS1)**: mount/exit animated via `framer-motion`'s `AnimatePresence` + the shared
 * `modalTransition` variant from `shared/motion/variants.ts` (scale+fade on desktop, slide-up
 * on mobile — selected via a `matchMedia` read, not re-derived here). Respects OS-level
 * reduced motion via framer-motion's own `useReducedMotion()`, which swaps the variant's
 * spring transitions for an instant (`duration: 0`) one rather than disabling the mount/exit
 * behavior itself (`AnimatePresence` still needs the exit to "complete" to unmount).
 *
 * **Focus trap + focus restore (MS1)**: a real, previously-missing a11y gap. On open, the
 * previously-focused element is captured and focus moves to the first focusable element in
 * the panel (in practice the close button, since it precedes the body content in DOM order);
 * `Tab`/`Shift+Tab` are trapped within the panel's focusable elements while open; on close,
 * focus is restored to the element that had it before the modal opened.
 */
export function Modal({
  isOpen,
  onClose,
  title,
  children,
  footer,
  size = 'normal',
  mobilePresentation = 'sheet',
}: ModalProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const previouslyFocusedElementRef = useRef<HTMLElement | null>(null);
  const shouldReduceMotion = useReducedMotion();

  const [isNarrowViewport, setIsNarrowViewport] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(MOBILE_MEDIA_QUERY).matches,
  );

  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }
    const mediaQuery = window.matchMedia(MOBILE_MEDIA_QUERY);
    const handleChange = () => setIsNarrowViewport(mediaQuery.matches);
    mediaQuery.addEventListener('change', handleChange);
    return () => mediaQuery.removeEventListener('change', handleChange);
  }, []);

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    previouslyFocusedElementRef.current = document.activeElement as HTMLElement | null;

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    const initialFocusable = panelRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)[0];
    (initialFocusable ?? panelRef.current)?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
        return;
      }
      if (event.key !== 'Tab' || !panelRef.current) {
        return;
      }
      const focusable = Array.from(panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
      if (focusable.length === 0) {
        event.preventDefault();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (event.shiftKey) {
        if (active === first || !panelRef.current.contains(active)) {
          event.preventDefault();
          last.focus();
        }
      } else if (active === last || !panelRef.current.contains(active)) {
        event.preventDefault();
        first.focus();
      }
    }
    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', handleKeyDown);
      previouslyFocusedElementRef.current?.focus();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  // Only the `sheet` presentation (default) switches its *motion* to the mobile slide-up;
  // `dialog` keeps the desktop scale+fade motion even on narrow viewports, matching its CSS.
  const isMobileSheet = mobilePresentation === 'sheet' && isNarrowViewport;

  const panelVariants: Variants = useMemo(() => {
    if (!shouldReduceMotion) {
      return modalTransition;
    }
    const instant: Transition = { duration: 0 };
    const animate = modalTransition.animate as TargetAndTransition;
    const exit = modalTransition.exit as (custom: boolean) => TargetAndTransition;
    return {
      initial: modalTransition.initial,
      animate: { ...animate, transition: instant },
      exit: (custom: boolean) => ({ ...exit(custom), transition: instant }),
    };
  }, [shouldReduceMotion]);

  const overlayTransition: Transition = shouldReduceMotion
    ? { duration: 0 }
    : { duration: 0.18, ease: 'easeOut' };

  return createPortal(
    <AnimatePresence>
      {isOpen && (
        <motion.div
          className={[styles.overlay, mobilePresentation === 'dialog' ? styles.overlayDialog : '']
            .filter(Boolean)
            .join(' ')}
          onClick={onClose}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={overlayTransition}
        >
          <motion.div
            ref={panelRef}
            className={[
              styles.panel,
              styles[`size-${size}`],
              mobilePresentation === 'dialog' ? styles.dialogPanel : '',
            ]
              .filter(Boolean)
              .join(' ')}
            role="dialog"
            aria-modal="true"
            aria-labelledby={title ? 'modal-title' : undefined}
            tabIndex={-1}
            onClick={(event) => event.stopPropagation()}
            custom={isMobileSheet}
            variants={panelVariants}
            initial="initial"
            animate="animate"
            exit="exit"
          >
            <span className={styles.dragHandle} aria-hidden="true" />
            <div className={styles.header}>
              {title && (
                <h2 id="modal-title" className={styles.title}>
                  {title}
                </h2>
              )}
              <button type="button" className={styles.closeButton} onClick={onClose} aria-label="סגירה">
                <X size={18} aria-hidden="true" />
              </button>
            </div>
            <div className={styles.body}>{children}</div>
            {footer && <div className={styles.footer}>{footer}</div>}
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
  );
}
