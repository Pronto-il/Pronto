import { useNavigate } from 'react-router-dom';
import { SearchX } from 'lucide-react';
import { Button, EmptyState } from '../../shared/components';
import { SupportedCategoriesList } from './SupportedCategoriesList';
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
 * ## Showing what we *do* cover
 *
 * "We don't do that" is honest but leaves the customer guessing whether Pronto is worth returning
 * to at all. `SupportedCategoriesList` answers that in the same breath, from `GET /api/categories`
 * — the same table the classifier resolves against, so this screen cannot advertise a trade the
 * AI would not route to, or omit one it would.
 *
 * ## Deliberately terminal, and the list is deliberately not clickable
 *
 * One action, back to home. There is no "continue anyway" button: that would route the customer
 * into the general-handyman search this entire change exists to stop, only with the customer
 * pressing the button instead of the classifier doing it silently. No issue row is created — the
 * flow ends here, before `POST /api/issues`.
 *
 * The supported-category tiles are informational for the same reason. Tapping one would have to
 * mean "book a plumber", and this customer does not need a plumber — they told us what they need
 * and we said no. Re-entering the flow under a category the customer picked to get *past* a
 * rejection is how a locksmith gets dispatched to a gas leak. The architecture would technically
 * allow it (`/matching` already accepts a `categoryId` for the guest journey), which is exactly
 * why the restraint is worth stating rather than leaving as an oversight.
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
      <SupportedCategoriesList />
      <p className={styles.note}>אנחנו מרחיבים כל הזמן את התחומים שלנו, אז שווה לבדוק שוב בהמשך.</p>
      <Button onClick={() => navigate('/', { replace: true })} fullWidth>
        חזרה לדף הבית
      </Button>
    </div>
  );
}
