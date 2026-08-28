import { useNavigate } from 'react-router-dom';
import { SearchX } from 'lucide-react';
import { Button, EmptyState } from '../../shared/components';
import styles from './UnsupportedProfessionStep.module.css';

export interface UnsupportedProfessionStepProps {
  /** The trade Pronto identified, in Hebrew, from `ClassifyIssueResponse.detectedProfession`. */
  detectedProfession: string | null;
}

/**
 * Pronto understood the request and cannot serve it.
 *
 * ## Why this is its own screen and not an empty result list
 *
 * "לא נמצאו בעלי מקצוע פנויים" (`ProfessionalList`'s empty state) means *this trade exists on
 * Pronto and nobody is free right now* — try later, and the answer may change. This screen means
 * *Pronto does not do this at all* — trying later changes nothing. Showing the first message for
 * the second situation is a small lie that wastes the customer's time twice: once waiting through
 * a matching animation for a search that cannot succeed, and again when they come back tomorrow.
 *
 * ## Naming the profession is the whole point
 *
 * "We can't help with that" is unactionable. "We identified that you need a **gas technician**, and
 * we don't work with those yet" tells the customer what to search for elsewhere, and tells them
 * Pronto understood them — which is the difference between a dead end and a rejection.
 *
 * `detectedProfession` is nullable because the model may omit the label. The message degrades to a
 * generic-but-honest wording rather than rendering "בעל מקצוע מסוג null", and the customer is no
 * worse off than they would have been without the field.
 *
 * ## Deliberately terminal
 *
 * One action, back to home. There is no "continue anyway" button: that would route the customer
 * into the general-handyman search this entire change exists to stop, only with the customer
 * pressing the button instead of the classifier doing it silently. No issue row is created — the
 * flow ends here, before `POST /api/issues`.
 */
export function UnsupportedProfessionStep({ detectedProfession }: UnsupportedProfessionStepProps) {
  const navigate = useNavigate();

  const description = detectedProfession
    ? `זיהינו שהתקלה דורשת ${detectedProfession}, ואנחנו עדיין לא עובדים עם בעלי מקצוע בתחום הזה.`
    : 'זיהינו שהתקלה דורשת בעל מקצוע בתחום שאנחנו עדיין לא עובדים איתו.';

  return (
    <div className={styles.wrapper}>
      <EmptyState
        icon={<SearchX aria-hidden="true" />}
        title="מצטערים, אין לנו בעל מקצוע מתאים"
        description={description}
      />
      <p className={styles.note}>אנחנו מרחיבים כל הזמן את התחומים שלנו, אז שווה לבדוק שוב בהמשך.</p>
      <Button onClick={() => navigate('/', { replace: true })} fullWidth>
        חזרה לדף הבית
      </Button>
    </div>
  );
}
