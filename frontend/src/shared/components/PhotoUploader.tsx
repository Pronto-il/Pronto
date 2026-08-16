import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { ImagePlus, X } from 'lucide-react';
import { uploadImage } from '../api/storage';
import styles from './PhotoUploader.module.css';

export interface UploadedPhoto {
  imageKey: string;
  previewUrl: string;
}

export interface PhotoUploaderProps {
  label: string;
  photos: UploadedPhoto[];
  onChange: (photos: UploadedPhoto[]) => void;
  maxCount?: number;
  hint?: string;
  /** Reports whether any photo is still mid-upload, so a submit button can wait for it. */
  onUploadingChange?: (isUploading: boolean) => void;
}

interface PendingUpload {
  id: string;
  previewUrl: string;
  error?: string;
}

/**
 * Multi-photo picker for the New Issue screen (DESIGN_SYSTEM.md §39). Each selected file
 * starts uploading immediately via `POST /api/storage/images` — unlike `ImageUploadField`
 * (which just holds a `File` for a later multipart submit), issue photos must already exist
 * in storage before `classify`/`create` ever run (api-contract-issues.md §3.4).
 */
export function PhotoUploader({ label, photos, onChange, maxCount = 6, hint, onUploadingChange }: PhotoUploaderProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [pending, setPending] = useState<PendingUpload[]>([]);

  useEffect(() => {
    onUploadingChange?.(pending.some((item) => !item.error));
  }, [pending, onUploadingChange]);

  const remainingSlots = maxCount - photos.length - pending.length;
  const canAddMore = remainingSlots > 0;

  function handleSelect(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []).slice(0, Math.max(remainingSlots, 0));
    if (inputRef.current) {
      inputRef.current.value = '';
    }
    for (const file of files) {
      const id = `${Date.now()}-${Math.random()}`;
      const previewUrl = URL.createObjectURL(file);
      setPending((prev) => [...prev, { id, previewUrl }]);
      uploadImage(file)
        .then((result) => {
          setPending((prev) => prev.filter((item) => item.id !== id));
          onChange([...photos, { imageKey: result.imageKey, previewUrl }]);
        })
        .catch(() => {
          setPending((prev) =>
            prev.map((item) => (item.id === id ? { ...item, error: 'ההעלאה נכשלה' } : item)),
          );
        });
    }
  }

  function handleRemovePhoto(imageKey: string) {
    onChange(photos.filter((photo) => photo.imageKey !== imageKey));
  }

  function handleRemovePending(id: string) {
    setPending((prev) => prev.filter((item) => item.id !== id));
  }

  return (
    <div className={styles.field}>
      <span className={styles.label}>{label}</span>
      <div className={styles.grid}>
        {photos.map((photo) => (
          <div key={photo.imageKey} className={styles.thumbWrapper}>
            <img src={photo.previewUrl} alt="" className={styles.thumb} />
            <button
              type="button"
              className={styles.removeButton}
              onClick={() => handleRemovePhoto(photo.imageKey)}
              aria-label="הסרת תמונה"
            >
              <X size={14} />
            </button>
          </div>
        ))}
        {pending.map((item) => (
          <div key={item.id} className={styles.thumbWrapper}>
            <img src={item.previewUrl} alt="" className={styles.thumb} />
            {item.error ? (
              <button
                type="button"
                className={styles.removeButton}
                onClick={() => handleRemovePending(item.id)}
                aria-label="הסרת תמונה"
              >
                <X size={14} />
              </button>
            ) : (
              <div className={styles.uploadingOverlay}>
                <span className={styles.spinner} aria-hidden="true" />
              </div>
            )}
            {item.error && <span className={styles.itemError}>{item.error}</span>}
          </div>
        ))}
        {canAddMore && (
          <button type="button" className={styles.addButton} onClick={() => inputRef.current?.click()}>
            <ImagePlus size={22} aria-hidden="true" />
          </button>
        )}
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        multiple
        className={styles.hiddenInput}
        onChange={handleSelect}
        aria-label={label}
      />
      {hint && <p className={styles.hint}>{hint}</p>}
    </div>
  );
}
