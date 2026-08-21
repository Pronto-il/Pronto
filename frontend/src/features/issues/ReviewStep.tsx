import { useState } from 'react';
import { Button, Card, Select } from '../../shared/components';
import type { UploadedPhoto } from '../../shared/components';
import { createIssue, GENERIC_ERROR_MESSAGE, CATEGORIES, getCategoryNameHe } from '../../shared/api';
import type {
  ClarificationAnswer,
  ClassifyIssueResponse,
  IssueResponse,
  IssueUrgencyType,
} from '../../shared/api';
import styles from './ReviewStep.module.css';

export interface ReviewStepProps {
  classification: ClassifyIssueResponse;
  description: string;
  photos: UploadedPhoto[];
  urgencyType: IssueUrgencyType;
  /** Sent with the issue so the conversation is persisted rather than discarded at this
   *  boundary — it is what Pronto's brief for the professional is built from. */
  clarificationAnswers: ClarificationAnswer[];
  onConfirmed: (issue: IssueResponse) => void;
}

const CATEGORY_OPTIONS = CATEGORIES.map((category) => ({ value: String(category.id), label: category.nameHe }));

/**
 * AI Review screen — a diagnosis-style category confirmation, not a technical prediction
 * (FRONTEND_AGENT.md §14 / DESIGN_SYSTEM.md §40). Only `suggestedCategoryId`/
 * `suggestedCategoryCode` drive this screen, and they are all the response now carries:
 * confidence, candidates and the ambiguity reason are backend-only diagnostics and no longer
 * cross the wire at all (they used to, and were documented as never allowed to be rendered —
 * the field removal replaces that convention with a structural guarantee).
 *
 * The customer keeps the final word here: the category can still be overridden, and whatever
 * they confirm is what `POST /api/issues` persists. Confirming is the call that actually
 * creates the issue (api-contract-issues.md §2.2), now carrying the clarification answers with
 * it so the professional's brief can be built from them.
 */
export function ReviewStep({
  classification,
  description,
  photos,
  urgencyType,
  clarificationAnswers,
  onConfirmed,
}: ReviewStepProps) {
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
        clarificationAnswers,
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
