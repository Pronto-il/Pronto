import { useEffect } from 'react';
import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import styles from './Modal.module.css';

export type ModalSize = 'small' | 'normal' | 'large';

export interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Rendered as the dialog's accessible name (`aria-labelledby`) and as a visible heading. */
  title?: string;
  children: ReactNode;
  /** Optional sticky footer (action buttons) rendered below `children`, separated by a divider. */
  footer?: ReactNode;
  /** Desktop centered-dialog width only, per DESIGN_SYSTEM.md §58 (`420px`/`560px`/`720px`).
   *  Ignored on mobile, where the panel is always full-width (bottom sheet). Default `normal`. */
  size?: ModalSize;
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
 *
 * Renders via `createPortal` into `document.body` so `position: fixed` positioning is never
 * broken by an ancestor with its own `transform`/`filter` (none exist today, but this is the
 * first modal in the app and a portal is the standard, low-risk way to guarantee it stays
 * correct as the component tree grows). Closes on overlay click and on `Escape`; locks body
 * scroll while open.
 */
export function Modal({ isOpen, onClose, title, children, footer, size = 'normal' }: ModalProps) {
  useEffect(() => {
    if (!isOpen) {
      return;
    }
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
      }
    }
    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', handleKeyDown);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  if (!isOpen) {
    return null;
  }

  return createPortal(
    <div className={styles.overlay} onClick={onClose}>
      <div
        className={`${styles.panel} ${styles[`size-${size}`]}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? 'modal-title' : undefined}
        onClick={(event) => event.stopPropagation()}
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
      </div>
    </div>,
    document.body,
  );
}
