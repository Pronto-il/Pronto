import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { ImagePlus, X } from 'lucide-react';
import styles from './ImageUploadField.module.css';

export interface ImageUploadFieldProps {
  label: string;
  value: File | null;
  onChange: (file: File | null) => void;
  error?: string;
  hint?: string;
  required?: boolean;
}

/**
 * Single-image upload with an object-URL preview + remove action, per
 * FRONTEND_AGENT.md §49 / DESIGN_SYSTEM.md §39. Accepts image mime types only. Kept
 * distinct from `DocumentUploadField` on purpose — a PDF can't be previewed as an image.
 */
export function ImageUploadField({ label, value, onChange, error, hint, required }: ImageUploadFieldProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!value) {
      setPreviewUrl(null);
      return;
    }
    const url = URL.createObjectURL(value);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [value]);

  function handleChange(event: ChangeEvent<HTMLInputElement>) {
    onChange(event.target.files?.[0] ?? null);
  }

  function handleRemove() {
    onChange(null);
    if (inputRef.current) {
      inputRef.current.value = '';
    }
  }

  return (
    <div className={styles.field}>
      <span className={styles.label}>
        {label}
        {required && (
          <span className={styles.required} aria-hidden="true">
            {' '}
            *
          </span>
        )}
      </span>
      {previewUrl ? (
        <div className={styles.previewWrapper}>
          <img src={previewUrl} alt={label} className={styles.preview} />
          <button
            type="button"
            className={styles.removeButton}
            onClick={handleRemove}
            aria-label="הסרת תמונה"
          >
            <X size={16} />
          </button>
        </div>
      ) : (
        <button type="button" className={styles.uploadButton} onClick={() => inputRef.current?.click()}>
          <ImagePlus size={24} aria-hidden="true" />
          <span>הוספת תמונה</span>
        </button>
      )}
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        className={styles.hiddenInput}
        onChange={handleChange}
        aria-label={label}
      />
      {error ? (
        <p className={styles.errorMessage} role="alert">
          {error}
        </p>
      ) : hint ? (
        <p className={styles.hint}>{hint}</p>
      ) : null}
    </div>
  );
}
