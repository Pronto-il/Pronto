import { useState } from 'react';
import type { FormEvent } from 'react';
import { Textarea, PhotoUploader, Button } from '../../shared/components';
import type { UploadedPhoto } from '../../shared/components';
import { classifyIssue, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { ClassifyIssueResponse, IssueUrgencyType } from '../../shared/api';
import styles from './DescribeIssueStep.module.css';

export interface DescribeIssueStepProps {
  description: string;
  onDescriptionChange: (value: string) => void;
  photos: UploadedPhoto[];
  onPhotosChange: (photos: UploadedPhoto[]) => void;
  urgencyType: IssueUrgencyType;
  onUrgencyChange: (value: IssueUrgencyType) => void;
  onClassified: (result: ClassifyIssueResponse) => void;
}

const CLASSIFY_ERROR_MESSAGES: Record<string, string> = {
  IMAGE_KEY_INVALID: 'אחת התמונות לא נטענה כראוי. יש להסיר אותה ולנסות שוב.',
  AI_SERVICE_ERROR: 'לא הצלחנו לעבד את התיאור כרגע. אפשר לנסות שוב בעוד רגע.',
};

/**
 * Step 1 of the New Issue flow — description + optional photos + urgency. Owns the
 * `POST /api/issues/classify` call (api-contract-issues.md §2.1); nothing is persisted here
 * (§3.4 — the first DB write only happens on final confirm in `ReviewStep`).
 */
export function DescribeIssueStep({
  description,
  onDescriptionChange,
  photos,
  onPhotosChange,
  urgencyType,
  onUrgencyChange,
  onClassified,
}: DescribeIssueStepProps) {
  const [descriptionError, setDescriptionError] = useState<string | undefined>();
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [photosUploading, setPhotosUploading] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);

    const trimmed = description.trim();
    if (trimmed.length < 10 || trimmed.length > 2000) {
      setDescriptionError('יש לתאר את התקלה בלפחות 10 תווים.');
      return;
    }
    setDescriptionError(undefined);

    setIsSubmitting(true);
    try {
      const result = await classifyIssue({
        description: trimmed,
        imageKeys: photos.map((photo) => photo.imageKey),
      });
      onClassified(result);
    } catch (error) {
      if (error instanceof ApiError && CLASSIFY_ERROR_MESSAGES[error.code]) {
        setBannerError(CLASSIFY_ERROR_MESSAGES[error.code]);
      } else {
        setBannerError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit} noValidate>
      {bannerError && (
        <div className={styles.banner} role="alert">
          <p>{bannerError}</p>
        </div>
      )}
      <Textarea
        label="מה קרה?"
        placeholder="לדוגמה: יש נזילת מים מתחת לכיור במטבח"
        value={description}
        onChange={(event) => onDescriptionChange(event.target.value)}
        error={descriptionError}
        hint="ספרו לנו בקצרה מה קרה"
        maxLength={2000}
        required
      />
      <PhotoUploader
        label="אפשר להוסיף תמונה?"
        photos={photos}
        onChange={onPhotosChange}
        onUploadingChange={setPhotosUploading}
        hint="לא חובה, עד 6 תמונות"
      />
      <div className={styles.urgencyRow}>
        <button
          type="button"
          className={`${styles.urgencyChip} ${urgencyType === 'STANDARD' ? styles.urgencyChipActive : ''}`}
          onClick={() => onUrgencyChange('STANDARD')}
        >
          רגיל
        </button>
        <button
          type="button"
          className={`${styles.sosChip} ${urgencyType === 'SOS' ? styles.sosChipActive : ''}`}
          onClick={() => onUrgencyChange('SOS')}
        >
          SOS — דחוף
        </button>
      </div>
      {urgencyType === 'SOS' && (
        <p className={styles.sosNote}>נעדיף בעלי מקצוע שיכולים להגיע אליך במהירות. ייתכן חיוב נוסף.</p>
      )}
      <Button type="submit" loading={isSubmitting} disabled={photosUploading} fullWidth>
        המשך
      </Button>
    </form>
  );
}
