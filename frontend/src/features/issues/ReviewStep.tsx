import { useState } from 'react';
import { Button, Card, Select } from '../../shared/components';
import type { UploadedPhoto } from '../../shared/components';
import {
  ApiError,
  createIssue,
  updateIssueCategory,
  GENERIC_ERROR_MESSAGE,
  CATEGORIES,
  getCategoryNameHe,
  getProfessionalNameHe,
} from '../../shared/api';
import type { ClarificationAnswer, ClassifyIssueResponse, IssueUrgencyType } from '../../shared/api';
import styles from './ReviewStep.module.css';

/** The subset of `IssueResponse` the flow actually carries forward once an issue exists — see
 *  `ReviewStepProps.existingIssue` for why confirming doesn't always produce a full one. */
export interface ConfirmedIssue {
  id: number;
  categoryId: number;
  urgencyType: IssueUrgencyType;
}

export interface ReviewStepProps {
  classification: ClassifyIssueResponse;
  description: string;
  photos: UploadedPhoto[];
  urgencyType: IssueUrgencyType;
  /** Sent with the issue so the conversation is persisted rather than discarded at this
   *  boundary — it is what Pronto's brief for the professional is built from. */
  clarificationAnswers: ClarificationAnswer[];
  /**
   * An issue this exact description/photo set already produced, when the customer came *back*
   * here from the address step to re-check the classification (`ProfessionMatchPage`'s back
   * button). When it is set, confirming never creates an issue: an unchanged category reuses it
   * as-is, and a changed one `PATCH`es that same issue's category. Cleared by `NewIssuePage` the
   * moment anything upstream is re-classified, so it can never point at an issue whose text no
   * longer matches.
   */
  existingIssue?: { id: number; categoryId: number };
  onConfirmed: (issue: ConfirmedIssue) => void;
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
  existingIssue,
  onConfirmed,
}: ReviewStepProps) {
  const [categoryId, setCategoryId] = useState(String(classification.suggestedCategoryId ?? ''));
  const [isChangingCategory, setIsChangingCategory] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [bannerError, setBannerError] = useState<string | null>(null);

  async function handleConfirm() {
    const chosenCategoryId = Number(categoryId);

    // Came back to look, changed nothing: the issue that already exists for this exact report is
    // still the right one, so nothing is written and the flow simply continues with it.
    if (existingIssue && existingIssue.categoryId === chosenCategoryId) {
      onConfirmed({ id: existingIssue.id, categoryId: existingIssue.categoryId, urgencyType });
      return;
    }

    setBannerError(null);
    setIsSubmitting(true);
    try {
      // Came back and corrected the category: the issue is *updated*, never re-created. Creating a
      // second one here is what used to strand the first as an `OPEN` orphan carrying the same
      // description, photos and answers — one reported fault stays one issue, and all of that
      // content stays attached to it (the endpoint takes a category and nothing else).
      const issue = existingIssue
        ? await updateIssueCategory(existingIssue.id, chosenCategoryId)
        : await createIssue({
            categoryId: chosenCategoryId,
            description,
            urgencyType,
            imageKeys: photos.map((photo) => photo.imageKey),
            clarificationAnswers,
          });
      onConfirmed(issue);
    } catch (error) {
      // The issue was booked from somewhere else while this screen was open, so the category can
      // no longer be corrected — said plainly, rather than as a generic failure the customer would
      // reasonably retry.
      setBannerError(
        error instanceof ApiError && error.code === 'ISSUE_NOT_EDITABLE'
          ? 'הבקשה הזו כבר בטיפול, ולכן לא ניתן לשנות את תחום השירות שלה.'
          : GENERIC_ERROR_MESSAGE,
      );
    } finally {
      setIsSubmitting(false);
    }
  }

  const categoryName = getCategoryNameHe(Number(categoryId));
  // Both lines follow the customer's current pick, not the AI's original suggestion — overriding
  // the category re-words the whole card.
  const professionalName = getProfessionalNameHe(Number(categoryId));

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
              <h2 className={styles.headline}>ה־AI שלנו סיווג את התקלה כ{categoryName}</h2>
              {urgencyType === 'SOS' && <span className={styles.sosBadge}>דחוף — SOS</span>}
            </div>
            <p className={styles.reassurance}>
              לפי המידע שסיפקת, נראה ש{professionalName} הוא בעל המקצוע המתאים.
            </p>
            {/* §44/FRONTEND_AGENT.md §14: the classification is an estimate the customer can
                overrule (the link below), and the screen has to say so rather than present it as
                a settled diagnosis. */}
            <p className={styles.disclaimer}>הסיווג הוא הערכה ראשונית ולא אבחון סופי.</p>
          </>
        )}
        {!isChangingCategory && (
          <button type="button" className={styles.changeLink} onClick={() => setIsChangingCategory(true)}>
            זה לא נראה נכון? שינוי תחום
          </button>
        )}
      </Card>
      <Button onClick={handleConfirm} loading={isSubmitting} fullWidth>
        אישור והמשך
      </Button>
    </div>
  );
}
