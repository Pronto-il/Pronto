import { useState } from 'react';
import { Button, Card, Select } from '../../shared/components';
import type { UploadedPhoto } from '../../shared/components';
import { createIssue, GENERIC_ERROR_MESSAGE, CATEGORIES, getCategoryNameHe } from '../../shared/api';
import type { ClassifyIssueResponse, IssueResponse, IssueUrgencyType } from '../../shared/api';
import styles from './ReviewStep.module.css';

export interface ReviewStepProps {
  classification: ClassifyIssueResponse;
  description: string;
  photos: UploadedPhoto[];
  urgencyType: IssueUrgencyType;
  onConfirmed: (issue: IssueResponse) => void;
}

const CATEGORY_OPTIONS = CATEGORIES.map((category) => ({ value: String(category.id), label: category.nameHe }));

/**
 * AI Review screen — a diagnosis-style category confirmation, not a technical prediction
 * (FRONTEND_AGENT.md §14 / DESIGN_SYSTEM.md §40). `classification`'s `explanation`/
 * `confidence` fields are the AI's internal reasoning (useful for debugging/logging, per
 * api-contract-issues.md §2.1) and must **never** be rendered to the customer — a deliberate,
 * backend-verified decision (design doc §5.3): in real (OpenAI) mode `explanation` is
 * English-only prose (contradicts Pronto's Hebrew-only v1.0 scope); in mock mode it literally
 * names the internal keyword-matching mechanism. Only `suggestedCategoryId`/
 * `suggestedCategoryCode` drive this screen. Confirming here is the call that actually
 * persists the issue (`POST /api/issues`, api-contract-issues.md §2.2).
 */
export function ReviewStep({ classification, description, photos, urgencyType, onConfirmed }: ReviewStepProps) {
  const [categoryId, setCategoryId] = useState(String(classification.suggestedCategoryId ?? ''));
  const [isChangingCategory, setIsChangingCategory] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [bannerError, setBannerError] = useState<string | null>(null);

  async function handleConfirm() {
    setBannerError(null);
    setIsSubmitting(true);
    try {
      const issue = await createIssue({
        categoryId: Number(categoryId),
        description,
        urgencyType,
        imageKeys: photos.map((photo) => photo.imageKey),
      });
      onConfirmed(issue);
    } catch {
      setBannerError(GENERIC_ERROR_MESSAGE);
    } finally {
      setIsSubmitting(false);
    }
  }

  const categoryName = getCategoryNameHe(Number(categoryId));

  return (
    <div className={styles.wrapper}>
      {bannerError && (
        <div className={styles.banner} role="alert">
          <p>{bannerError}</p>
        </div>
      )}
      <Card className={styles.card}>
        <p className={styles.eyebrow}>האבחון שלנו</p>
        {isChangingCategory ? (
          <Select
            label="תחום שירות"
            value={categoryId}
            onChange={(event) => setCategoryId(event.target.value)}
            options={CATEGORY_OPTIONS}
          />
        ) : (
          <>
            <div className={styles.headlineRow}>
              <h2 className={styles.headline}>נראה שמדובר בתקלה ב־{categoryName}</h2>
              {urgencyType === 'SOS' && <span className={styles.sosBadge}>דחוף — SOS</span>}
            </div>
            <p className={styles.reassurance}>כך נמצא לך את בעל המקצוע הכי מתאים.</p>
          </>
        )}
        {!isChangingCategory && (
          <button type="button" className={styles.changeLink} onClick={() => setIsChangingCategory(true)}>
            זה לא נכון? שינוי תחום
          </button>
        )}
      </Card>
      <Button onClick={handleConfirm} loading={isSubmitting} fullWidth>
        אישור והמשך
      </Button>
    </div>
  );
}
