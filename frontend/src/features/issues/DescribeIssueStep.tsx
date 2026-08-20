import { useState } from 'react';
import type { FormEvent } from 'react';
import { Clock, Zap } from 'lucide-react';
import { Textarea, PhotoUploader, Button, Card, Mascot } from '../../shared/components';
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
  /** Design doc §2.2 — fires `true` immediately before `classifyIssue` starts, `false` in the
   *  existing `finally`, so `NewIssuePage` can show `AiAnalyzingOverlay` over this step without
   *  unmounting it. */
  onAnalyzingChange: (isAnalyzing: boolean) => void;
}

const CLASSIFY_ERROR_MESSAGES: Record<string, string> = {
  IMAGE_KEY_INVALID: 'אחת התמונות לא נטענה כראוי. יש להסיר אותה ולנסות שוב.',
  AI_SERVICE_ERROR: 'לא הצלחנו לעבד את התיאור כרגע. אפשר לנסות שוב בעוד רגע.',
};

/** Local, single-consumer "helpful examples" row (design doc §3.2) — shown only while
 *  `description` is empty; clicking one prefills a fuller example sentence via the existing
 *  `onDescriptionChange` prop (no new prop, no API/behavior change). */
const EXAMPLE_PROMPTS: { label: string; example: string }[] = [
  { label: 'נזילת מים', example: 'יש לי נזילת מים מתחת לכיור במטבח' },
  { label: 'תקלה בחשמל', example: 'יש לי תקלה בחשמל, הנתיך קופץ כל הזמן' },
  { label: 'מזגן לא מקרר', example: 'המזגן בסלון לא מקרר כמו שצריך' },
  { label: 'דלת או מנעול תקוע', example: 'הדלת נתקעת ולא ניתן לנעול אותה' },
];

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
  onAnalyzingChange,
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
      setDescriptionError('יש לתאר את התקלה באורך של 10 עד 2000 תווים.');
      return;
    }
    setDescriptionError(undefined);

    setIsSubmitting(true);
    onAnalyzingChange(true);
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
      onAnalyzingChange(false);
    }
  }

  return (
    <div>
      <div className={styles.lead}>
        <Mascot state="idle" size="sm" />
        <h2 className={styles.heading}>ספר לי מה קרה</h2>
      </div>
      <Card className={styles.card}>
        <form className={styles.form} onSubmit={handleSubmit} noValidate>
          {bannerError && (
            <div className={styles.banner} role="alert">
              <p>{bannerError}</p>
            </div>
          )}
          <Textarea
            label="ספר לי מה קרה"
            placeholder="לדוגמה: יש נזילת מים מתחת לכיור במטבח"
            value={description}
            onChange={(event) => onDescriptionChange(event.target.value)}
            error={descriptionError}
            hint="כל פרט קטן עוזר לנו למצוא את בעל המקצוע המתאים"
            maxLength={2000}
            required
          />
          {description.length === 0 && (
            <div className={styles.examplesRow}>
              {EXAMPLE_PROMPTS.map((prompt) => (
                <button
                  key={prompt.label}
                  type="button"
                  className={styles.exampleChip}
                  onClick={() => onDescriptionChange(prompt.example)}
                >
                  {prompt.label}
                </button>
              ))}
            </div>
          )}
          <PhotoUploader
            label="אפשר להוסיף תמונה?"
            photos={photos}
            onChange={onPhotosChange}
            onUploadingChange={setPhotosUploading}
            hint="לא חובה, עד 6 תמונות"
          />
          <div className={styles.urgencySection}>
            <h3 className={styles.urgencyHeading}>באיזו דחיפות מדובר?</h3>
            <div className={styles.urgencyRow}>
              <button
                type="button"
                className={`${styles.urgencyCard} ${urgencyType === 'STANDARD' ? styles.urgencyCardActive : ''}`}
                onClick={() => onUrgencyChange('STANDARD')}
              >
                <Clock size={22} aria-hidden="true" className={styles.urgencyIcon} />
                <span className={styles.urgencyTitle}>רגיל</span>
                <span className={styles.urgencySubcopy}>מתאים לרוב התקלות, בוחרים זמן שנוח לכם.</span>
              </button>
              <button
                type="button"
                className={`${styles.urgencyCard} ${styles.sosCard} ${
                  urgencyType === 'SOS' ? styles.sosCardActive : ''
                }`}
                onClick={() => onUrgencyChange('SOS')}
              >
                <Zap size={22} aria-hidden="true" className={styles.urgencyIcon} />
                <span className={styles.urgencyTitle}>SOS — דחוף</span>
                <span className={styles.urgencySubcopy}>
                  נעדיף בעלי מקצוע שיכולים להגיע אליך במהירות. ייתכן חיוב נוסף.
                </span>
              </button>
            </div>
          </div>
          <Button type="submit" loading={isSubmitting} disabled={photosUploading} fullWidth>
            המשך
          </Button>
        </form>
      </Card>
    </div>
  );
}
