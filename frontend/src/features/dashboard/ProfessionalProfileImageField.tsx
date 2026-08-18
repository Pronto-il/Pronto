import { useState } from 'react';
import { ImageUploadField } from '../../shared/components';
import { uploadProfessionalProfileImage } from '../../shared/api';
import styles from './ProfessionalProfileImageField.module.css';

export interface ProfessionalProfileImageFieldProps {
  /** The professional's currently-saved photo (`professional.profileImageUrl`), shown until
   *  a new file is picked. */
  currentImageUrl: string | null;
  /** Called with the new `imageUrl` immediately after a successful upload, so the parent page
   *  can update its displayed photo without a full profile refetch. */
  onUploaded: (imageUrl: string) => void;
}

/**
 * Thin wrapper around `shared/components/ImageUploadField.tsx` for the professional-profile
 * photo — but, mirroring `PhotoUploader.tsx`'s existing "upload immediately on selection"
 * pattern rather than `ImageUploadField`'s own "hold a `File` for a later multipart submit"
 * default, calls `uploadProfessionalProfileImage(file)` as soon as a file is picked
 * (`frontend-ms8-design.md` §4.3) — the backend models the image as its own endpoint,
 * independent of `PUT /me`'s field save, so there is no "submit together" moment to wait for.
 *
 * `ImageUploadField` only knows how to preview a `File`, not an already-saved URL, so the
 * currently-saved photo is rendered separately (this component's own small `<img>`, §30's
 * 88-104px profile-page sizing) until a new file is picked; `ImageUploadField` itself only
 * ever holds the transient "picking a replacement" state, reset back to empty once the
 * upload succeeds (the parent's updated `currentImageUrl` prop then takes over).
 */
export function ProfessionalProfileImageField({ currentImageUrl, onUploaded }: ProfessionalProfileImageFieldProps) {
  const [pendingFile, setPendingFile] = useState<File | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [error, setError] = useState<string | undefined>();

  function handleChange(file: File | null) {
    if (!file) {
      setPendingFile(null);
      setError(undefined);
      return;
    }
    setPendingFile(file);
    setError(undefined);
    setIsUploading(true);
    uploadProfessionalProfileImage(file)
      .then((result) => {
        onUploaded(result.imageUrl);
        setPendingFile(null);
      })
      .catch(() => {
        setError('ההעלאה נכשלה, אפשר לנסות שוב.');
      })
      .finally(() => {
        setIsUploading(false);
      });
  }

  return (
    <div className={styles.field}>
      <span className={styles.label}>תמונת פרופיל</span>
      <div className={styles.row}>
        {!pendingFile && (
          <div className={styles.current}>
            {currentImageUrl ? (
              <img src={currentImageUrl} alt="" className={styles.currentPhoto} />
            ) : (
              <span className={styles.currentPhotoFallback} aria-hidden="true" />
            )}
          </div>
        )}
        <ImageUploadField
          label="החלפת תמונה"
          value={pendingFile}
          onChange={handleChange}
          error={error}
          hint={isUploading ? 'מעלה תמונה…' : undefined}
        />
      </div>
    </div>
  );
}
