import { useState } from 'react';
import { Button, Card, Select } from '../../shared/components';
import {
  ApiError,
  updateIssueCategory,
  GENERIC_ERROR_MESSAGE,
  CATEGORIES,
  getCategoryNameHe,
  getProfessionalNameHe,
} from '../../shared/api';
import type { ClassifyIssueResponse, IssueUrgencyType } from '../../shared/api';
import styles from './ReviewStep.module.css';

/** The subset of `IssueResponse` the flow actually carries forward once an issue exists — see
 *  `ReviewStepProps.existingIssue` for why confirming doesn't always produce a full one. */
export interface ConfirmedIssue {
  /** `null` when the review was confirmed without persisting an issue — the guest case, where
   *  `POST /api/issues` is deferred to the booking commit. The category is known either way,
   *  which is all the rest of the flow actually reads. */
  id: number | null;
  categoryId: number;
  urgencyType: IssueUrgencyType;
}

/**
 * `description`, `photos` and `clarificationAnswers` used to be props here, passed in purely so
 * this screen could hand them to `createIssue`. With creation moved to the commit they are no
 * longer this component's business: they live in the booking draft from the moment they are
 * entered, and `BookingSummary`/`ProntoSosEntryPage` read them from there. Removed rather than
 * left unused, so nothing suggests this screen still persists a report.
 */
export interface ReviewStepProps {
  classification: ClassifyIssueResponse;
  urgencyType: IssueUrgencyType;
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
 * The customer keeps the final word here: the category can still be overridden, and whatever they
 * confirm is what the eventual `POST /api/issues` is called with.
 *
 * **Confirming does not create the issue, for anyone.** It used to, for signed-in customers — see
 * `handleConfirm`, which now defers for guests and customers alike so there is one lifecycle
 * instead of two. The only write this screen can still make is a category correction on an issue
 * that genuinely already exists (`existingIssue`).
 */
export function ReviewStep({ classification, urgencyType, existingIssue, onConfirmed }: ReviewStepProps) {
  const [categoryId, setCategoryId] = useState(String(classification.suggestedCategoryId ?? ''));
  const [isChangingCategory, setIsChangingCategory] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [bannerError, setBannerError] = useState<string | null>(null);

  async function handleConfirm() {
    const chosenCategoryId = Number(categoryId);

    // ---- ONE lifecycle, for guests and signed-in customers alike ----
    //
    // **Confirming a classification is not a commitment, so it writes nothing.** There is no issue
    // yet and this screen does not create one, whoever is looking at it. The booking draft carries
    // the category, description, photos and clarification answers forward, and the FIRST database
    // row is written at the commit — `features/booking/BookingSummary` for Standard,
    // `features/sos/ProntoSosEntryPage` for SOS — where an account exists and a professional is
    // actually about to be engaged.
    //
    // This branch used to read `!isAuthenticated && !existingIssue`, so only guests deferred. A
    // signed-in customer fell through to `createIssue` below and got a row here, at the *review*
    // step, which is the earliest point in the journey at which anything could be persisted. That
    // split was the defect:
    //
    //   - Abandoning after the classification -- closing the tab, wandering off at the address or
    //     matching screen -- left an `OPEN` issue nobody ever booked, indistinguishable from a
    //     genuine unbooked request.
    //   - Starting a fresh report abandoned the first one for good: `NewIssuePage.handleClassified`
    //     clears `reusableIssue` on any re-classification, so the next confirm created a SECOND
    //     issue and stranded the first with the same description, the same photos and the same
    //     answers.
    //   - Guests and customers behaved differently through the same screens, so every downstream
    //     "is there an issue yet?" question had two answers depending on who was asking.
    //
    // The comments elsewhere that describe issue creation as happening at the booking commit were
    // written for this design; they are now true for everyone rather than for half the users.
    if (!existingIssue) {
      onConfirmed({ id: null, categoryId: chosenCategoryId, urgencyType });
      return;
    }

    // Came back to look, changed nothing: the issue that already exists for this exact report is
    // still the right one, so nothing is written and the flow simply continues with it.
    if (existingIssue.categoryId === chosenCategoryId) {
      onConfirmed({ id: existingIssue.id, categoryId: existingIssue.categoryId, urgencyType });
      return;
    }

    setBannerError(null);
    setIsSubmitting(true);
    try {
      // ---- the ONLY write this screen can still make, and only against a REAL issue ----
      //
      // `existingIssue` is not a leftover of the old eager-creation design and is deliberately
      // kept: it is set only when the draft carries an `issueId` that a commit already persisted
      // (`NewIssuePage`'s `reusableIssue`), which happens when the customer walks BACK into the
      // classification from the address/matching screens after an issue exists -- most importantly
      // after a `createOrder` failure, where `BookingSummary` has written the id to the draft and
      // the retry must reuse it.
      //
      // Correcting the category there edits that issue; it never creates a second one. Creating a
      // duplicate is precisely what used to strand the first as an `OPEN` orphan carrying the same
      // content -- one reported fault stays one issue (the endpoint takes a category and nothing
      // else, so everything already attached to it is preserved).
      //
      // In the new deferred flow there is no issue to PATCH, and none is invented in order to have
      // something to PATCH: the corrected category goes to the draft via `onConfirmed` above and is
      // what `createIssue` is eventually called with at the commit.
      const issue = await updateIssueCategory(existingIssue.id, chosenCategoryId);
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
