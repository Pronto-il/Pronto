import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { ImagePlus, X } from 'lucide-react';
import { uploadImage } from '../api/storage';
import { prepareImageForUpload } from '../lib/imageCompression';
import styles from './PhotoUploader.module.css';

export interface UploadedPhoto {
  imageKey: string;
  /** `null` is a deliberate, distinct sentinel for "not yet (re-)resolved to a displayable
   *  URL" — used only during `NewIssuePage`'s draft-resume flow, while a batch presigned-URL
   *  lookup is in flight (backend MS9 design §12.4). Every other producer of `UploadedPhoto`
   *  (a live upload via this component) always sets a real string immediately. */
  previewUrl: string | null;
  /** Same-page-load display URL from the upload response — an ephemeral presigned URL (backend
   *  MS9, 300s TTL), NOT a durable/persistable one. Unlike `previewUrl` (a `URL.
   *  createObjectURL(file)` blob), this is a real backend URL, but it must never be persisted
   *  across a page reload (e.g. into `BookingDraftPhoto`, see `shared/hooks/
   *  bookingDraftContext.ts`) — only the bare `imageKey` survives a reload; `imageUrl` is
   *  re-resolved fresh on resume via `shared/api/storage.ts`'s `getPresignedImageUrls`. */
  imageUrl: string;
  /** Set only when a resume-time re-resolution of this photo's `imageKey` failed outright
   *  (backend MS9 design §12.4's sub-case (a) — a full batch-request failure, not a
   *  per-key-missing partial response, which instead drops the photo from state entirely).
   *  Never set for a live upload failure — that's `PendingUpload.error`, a separate,
   *  differently-triggered case. */
  error?: string;
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
  /**
   * `compressing` is genuinely indeterminate — a canvas decode/encode reports nothing until it
   * finishes — so the two phases render differently rather than pretending compression is
   * "0% uploaded".
   */
  phase: 'compressing' | 'uploading';
  /** 0-1, meaningful only while `phase === 'uploading'`. */
  progress: number;
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

  /**
   * The authoritative `photos` list for code running inside an upload's `await`.
   *
   * `onChange` takes a value rather than an updater, so an async completion has to build the
   * next array from somewhere. Reading the `photos` *prop* is wrong: every upload started by
   * one `handleSelect` call closes over the same render's array, so selecting three photos at
   * once had each completion compute `[...thatOneOldArray, itsOwnPhoto]` and the last writer
   * won — two of the three photos silently vanished. Faster uploads (which is the point of
   * this change) make that race fire more often, not less.
   */
  const photosRef = useRef(photos);
  useEffect(() => {
    photosRef.current = photos;
  }, [photos]);

  useEffect(() => {
    onUploadingChange?.(pending.some((item) => !item.error));
  }, [pending, onUploadingChange]);

  const remainingSlots = maxCount - photos.length - pending.length;
  const canAddMore = remainingSlots > 0;

  /** Single writer for `onChange`, keeping {@link photosRef} in step ahead of the re-render. */
  function commitPhotos(next: UploadedPhoto[]) {
    photosRef.current = next;
    onChange(next);
  }

  function updatePending(id: string, patch: Partial<PendingUpload>) {
    setPending((prev) => prev.map((item) => (item.id === id ? { ...item, ...patch } : item)));
  }

  /**
   * Compression is awaited one file at a time, on purpose. Uploads are not.
   *
   * Decoding a 22 MP photo materialises its full uncompressed frame — tens of megabytes of
   * bitmap — and six of those in flight together is an out-of-memory tab reload on a
   * mid-range handset rather than a slow upload. Serialising the CPU/memory-bound half costs
   * nothing perceptible (each encode is well under a second) while the network-bound half
   * still overlaps: each upload is started and left to run as soon as its own file is ready.
   */
  async function processFiles(files: File[]) {
    for (const file of files) {
      const id = `${Date.now()}-${Math.random()}`;
      const previewUrl = URL.createObjectURL(file);
      setPending((prev) => [...prev, { id, previewUrl, phase: 'compressing', progress: 0 }]);

      const prepared = await prepareImageForUpload(file);
      updatePending(id, { phase: 'uploading', progress: 0 });

      void uploadImage(prepared.file, {
        onProgress: (fraction) => updatePending(id, { progress: fraction }),
      })
        .then((result) => {
          setPending((prev) => prev.filter((item) => item.id !== id));
          commitPhotos([
            ...photosRef.current,
            { imageKey: result.imageKey, previewUrl, imageUrl: result.imageUrl },
          ]);
        })
        .catch(() => {
          updatePending(id, { error: 'ההעלאה נכשלה' });
        });
    }
  }

  function handleSelect(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []).slice(0, Math.max(remainingSlots, 0));
    if (inputRef.current) {
      inputRef.current.value = '';
    }
    void processFiles(files);
  }

  function handleRemovePhoto(imageKey: string) {
    commitPhotos(photosRef.current.filter((photo) => photo.imageKey !== imageKey));
  }

  function handleRemovePending(id: string) {
    setPending((prev) => prev.filter((item) => item.id !== id));
  }

  return (
    <div className={styles.field}>
      {/* Label and hint share one line: this section is optional, and on a phone it should cost
          the description above it as little vertical room as possible. */}
      <div className={styles.labelRow}>
        <span className={styles.label}>{label}</span>
        {hint && <span className={styles.hint}>{hint}</span>}
      </div>
      <div className={styles.grid}>
        {photos.map((photo) => (
          <div key={photo.imageKey} className={styles.thumbWrapper}>
            {photo.previewUrl === null ? (
              <div className={styles.uploadingOverlay}>
                <span className={styles.spinner} aria-hidden="true" />
              </div>
            ) : (
              <img src={photo.previewUrl} alt="" className={styles.thumb} />
            )}
            <button
              type="button"
              className={styles.removeButton}
              onClick={() => handleRemovePhoto(photo.imageKey)}
              aria-label="הסרת תמונה"
            >
              <X size={14} />
            </button>
            {photo.error && <span className={styles.itemError}>{photo.error}</span>}
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
                {item.phase === 'compressing' ? (
                  <span className={styles.spinner} aria-hidden="true" />
                ) : (
                  <div
                    className={styles.progress}
                    role="progressbar"
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={Math.round(item.progress * 100)}
                    aria-label="התקדמות ההעלאה"
                  >
                    <span className={styles.progressValue}>{Math.round(item.progress * 100)}%</span>
                    <span className={styles.progressTrack}>
                      {/* Width is the only inline style here because it is the one value that
                          changes many times a second; a CSS custom property would re-trigger
                          the same style recalculation for no readability gain. */}
                      <span
                        className={styles.progressBar}
                        style={{ width: `${Math.round(item.progress * 100)}%` }}
                      />
                    </span>
                  </div>
                )}
              </div>
            )}
            {item.error && <span className={styles.itemError}>{item.error}</span>}
          </div>
        ))}
        {canAddMore && (
          <button type="button" className={styles.addButton} onClick={() => inputRef.current?.click()}>
            <ImagePlus size={18} aria-hidden="true" />
            <span>הוספת תמונה</span>
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
    </div>
  );
}
