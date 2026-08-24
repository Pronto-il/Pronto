import { useRef } from 'react';
import type { ChangeEvent } from 'react';
import { Pencil } from 'lucide-react';
import { ZoomableImage } from './ZoomableImage';
import styles from './ProfilePhoto.module.css';

export interface ProfilePhotoProps {
  imageUrl: string | null;
  /** Fallback initial(s) shown in the circle when `imageUrl` is `null`. */
  fallbackInitial: string;
  /** `DESIGN_SYSTEM.md`'s documented "provider profile page: 88-104px" sizing range. Default 104. */
  size?: 96 | 104;
  /** Omit entirely to render a non-interactive, non-editable avatar (read-only caller). */
  onUpload?: (file: File) => void;
  isUploading?: boolean;
  uploadError?: string;
}

/**
 * Circular, centered profile photo — replaces `dashboard/ProfessionalProfileImageField.tsx`'s
 * job (`docs/architecture/product-ms10-profile-redesign-design.md` §2.1) and is reused,
 * without `onUpload`, as a read-only avatar for both roles on the shared `/profile` page
 * (§2.4, §3.1 "Reading A").
 *
 * Exactly **one** edit affordance when `onUpload` is supplied: a small round icon button
 * overlapping the photo's bottom-inline-end edge, wired directly to a hidden
 * `<input type="file">` — no separate "Add photo" control, resolving the mismatched
 * dual-affordance finding in the design doc's §1.2. Clicking the photo itself (when a real
 * `imageUrl` exists) opens `ImageLightbox` for a larger view; an empty avatar has nothing to
 * enlarge, so the photo itself renders as a plain non-interactive `<div>` in that case.
 */
export function ProfilePhoto({
  imageUrl,
  fallbackInitial,
  size = 104,
  onUpload,
  isUploading = false,
  uploadError,
}: ProfilePhotoProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const dimension = { width: size, height: size };

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (file && onUpload) {
      onUpload(file);
    }
    event.target.value = '';
  }

  const photoContent = imageUrl ? (
    <img src={imageUrl} alt="" className={styles.image} />
  ) : (
    <span className={styles.fallback} style={{ fontSize: size * 0.4 }} aria-hidden="true">
      {fallbackInitial}
    </span>
  );

  return (
    <div className={styles.wrapper}>
      <div className={styles.photoArea}>
        {imageUrl ? (
          <ZoomableImage
            imageUrl={imageUrl}
            label="הגדלת תמונת פרופיל"
            className={styles.photoButton}
          >
            <span className={styles.photoInner} style={dimension}>
              {photoContent}
            </span>
          </ZoomableImage>
        ) : (
          <div className={styles.photoStatic} style={dimension}>
            {photoContent}
          </div>
        )}

        {onUpload && (
          <>
            <button
              type="button"
              className={styles.editButton}
              onClick={() => inputRef.current?.click()}
              disabled={isUploading}
              aria-label="עריכת תמונת פרופיל"
            >
              <Pencil size={14} aria-hidden="true" />
            </button>
            <input
              ref={inputRef}
              type="file"
              accept="image/*"
              className={styles.hiddenInput}
              onChange={handleFileChange}
              aria-label="עריכת תמונה"
            />
          </>
        )}
      </div>

      {(isUploading || uploadError) && (
        <p className={`${styles.status} ${uploadError ? styles.error : styles.hint}`} role={uploadError ? 'alert' : undefined}>
          {uploadError ?? 'מעלה תמונה…'}
        </p>
      )}

    </div>
  );
}
