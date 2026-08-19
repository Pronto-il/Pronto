import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import styles from './ImageLightbox.module.css';

export interface ImageLightboxProps {
  isOpen: boolean;
  onClose: () => void;
  imageUrl: string;
  alt?: string;
}

/**
 * Full-viewport, dark-overlay image viewer — the "Facebook-style" click-to-enlarge
 * interaction named by `docs/architecture/product-ms10-profile-redesign-design.md` §0/§2.2.
 * Deliberately **not** a `Modal` variant: `Modal`'s API (`title`/`footer`/fixed `size`
 * tokens) is shaped around form dialogs, while this wants a near-fullscreen, chrome-free
 * image with no title/footer — forcing `Modal` to also serve this shape would mean adding
 * props whose only caller would be this one screen (§1.7 of the design doc). Reimplements
 * the same three behaviors `Modal` already has (portal render, `Escape`-to-close, body
 * scroll lock) in a small, purpose-built component instead.
 *
 * First (and, as of MS10, only) consumer: `ProfilePhoto`, opened when its photo is clicked.
 */
export function ImageLightbox({ isOpen, onClose, imageUrl, alt = '' }: ImageLightboxProps) {
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
      <button type="button" className={styles.closeButton} onClick={onClose} aria-label="סגירה">
        <X size={20} aria-hidden="true" />
      </button>
      <img
        src={imageUrl}
        alt={alt}
        className={styles.image}
        onClick={(event) => event.stopPropagation()}
      />
    </div>,
    document.body,
  );
}
