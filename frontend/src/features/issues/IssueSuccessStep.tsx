import { useNavigate } from 'react-router-dom';
import { Button } from '../../shared/components';
import type { IssueUrgencyType } from '../../shared/api';
import styles from './IssueSuccessStep.module.css';

export interface IssueSuccessStepProps {
  issueId: number;
  urgencyType: IssueUrgencyType;
}

/**
 * Calm confirmation state (DESIGN_SYSTEM.md §78) after `POST /api/issues` succeeds.
 * Frontend Milestone 3 hand-off: a `STANDARD` issue routes into `features/booking`'s
 * `/issues/:issueId/booking`. `SOS` isn't built yet (still `features/booking`'s SOS-flow
 * scope, Milestone 4 frontend) — rather than link into a flow that doesn't exist or
 * silently do nothing, this shows the same calm success state with honest copy
 * acknowledging the urgent request was recorded and that urgent-professional matching
 * isn't available yet.
 */
export function IssueSuccessStep({ issueId, urgencyType }: IssueSuccessStepProps) {
  const navigate = useNavigate();
  const isStandard = urgencyType === 'STANDARD';

  return (
    <div className={styles.wrapper}>
      <span className={styles.check} aria-hidden="true">
        ✓
      </span>
      <h2 className={styles.title}>הבקשה נשלחה</h2>
      <p className={styles.text}>
        {isStandard
          ? 'קיבלנו את הפרטים. עכשיו אפשר לבחור בעל מקצוע ותור שמתאים לכם.'
          : 'קיבלנו את הבקשה הדחופה שלכם. בקרוב נוסיף חיפוש בעלי מקצוע זמינים לתקלות דחופות.'}
      </p>
      {isStandard ? (
        <div className={styles.actions}>
          <Button onClick={() => navigate(`/issues/${issueId}/booking`)} fullWidth>
            בחירת בעל מקצוע
          </Button>
          <Button variant="secondary" onClick={() => navigate('/')} fullWidth>
            חזרה לדף הבית
          </Button>
        </div>
      ) : (
        <Button onClick={() => navigate('/')} fullWidth>
          חזרה לדף הבית
        </Button>
      )}
    </div>
  );
}
