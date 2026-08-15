import { useRef } from 'react';
import type { ChangeEvent } from 'react';
import { FileText, Image as ImageIcon, Upload, X } from 'lucide-react';
import styles from './DocumentUploadField.module.css';

export interface DocumentUploadFieldProps {
  label: string;
  value: File | null;
  onChange: (file: File | null) => void;
  error?: string;
  hint?: string;
  required?: boolean;
}

/**
 * Generic file upload (PDF or image — e.g. a certificate/license/qualification). Not
 * image-preview-specific like `ImageUploadField` — a PDF can't be previewed as an image —
 * shows filename + file-type icon + remove action instead. Kept as a separate component
 * from `ImageUploadField` deliberately, since a later milestone may extend this one
 * differently.
 */
export function DocumentUploadField({
  label,
  value,
  onChange,
  error,
  hint,
  required,
}: DocumentUploadFieldProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  function handleChange(event: ChangeEvent<HTMLInputElement>) {
    onChange(event.target.files?.[0] ?? null);
  }

  function handleRemove() {
    onChange(null);
    if (inputRef.current) {
      inputRef.current.value = '';
    }
  }

  const isPdf = value?.type === 'application/pdf';

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
      {value ? (
        <div className={styles.filePill}>
          {isPdf ? <FileText size={20} aria-hidden="true" /> : <ImageIcon size={20} aria-hidden="true" />}
          <span className={styles.fileName}>{value.name}</span>
          <button
            type="button"
            className={styles.removeButton}
            onClick={handleRemove}
            aria-label="הסרת קובץ"
          >
            <X size={16} />
          </button>
        </div>
      ) : (
        <button type="button" className={styles.uploadButton} onClick={() => inputRef.current?.click()}>
          <Upload size={18} aria-hidden="true" />
          <span>העלאת קובץ</span>
        </button>
      )}
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf,image/*"
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
