import { useState } from 'react';
import type { ReactNode } from 'react';
import { ImageLightbox } from './ImageLightbox';
import styles from './ZoomableImage.module.css';

export interface ZoomableImageProps {
  /** The full-size image to open. A caller with nothing to enlarge must not render this at all. */
  imageUrl: string;
  /** Accessible name for the trigger, e.g. `הגדלת תמונת הפרופיל של דוד כהן`. */
  label: string;
  /** The thumbnail to make clickable — the caller's own `<img>`, with the caller's own styling. */
  children: ReactNode;
  /** Overrides the trigger's shape. Defaults to a circle, which is every consumer today. */
  className?: string;
}

/**
 * Makes an already-styled thumbnail open in {@link ImageLightbox} when it is clicked, and owns the
 * open/closed state so its consumers do not have to.
 *
 * <p>This exists so that "click a professional's photo to see it bigger" has exactly one
 * implementation. Before it, `ProfilePhoto` had that behavior inline and nothing else did, so every
 * customer-facing surface that grew the interaction would have grown its own copy of the state, the
 * button, the `aria-label` and the lightbox wiring — four chances to drift apart on four screens
 * showing the same photograph of the same person.
 *
 * <p><b>Only ever wraps a real image.</b> The no-photo fallback (initials in a coloured circle) is
 * not something a customer can usefully enlarge, so callers render it as plain, non-interactive
 * markup and never route it through here — an empty lightbox over a letter would be a dead end
 * dressed up as a feature.
 *
 * <p>The thumbnail is passed as `children` rather than as a `src`, because each surface sizes its
 * own avatar (40px on a candidate card, 96px on a profile) and this component has no business
 * knowing about any of them — the same division of labour `SosAvatar` already uses.
 */
export function ZoomableImage({ imageUrl, label, children, className }: ZoomableImageProps) {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <>
      <button
        type="button"
        className={`${styles.trigger} ${className ?? ''}`}
        onClick={() => setIsOpen(true)}
        aria-label={label}
      >
        {children}
      </button>
      <ImageLightbox
        isOpen={isOpen}
        onClose={() => setIsOpen(false)}
        imageUrl={imageUrl}
        alt={label}
      />
    </>
  );
}
