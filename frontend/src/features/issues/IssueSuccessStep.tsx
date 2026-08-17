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
 * `/issues/:issueId/booking`. Frontend Milestone 4 adds the `SOS` hand-off: an `SOS` issue
 * routes into `features/booking`'s `/issues/:issueId/sos-booking` the same way — urgent
 * professional matching is now built.
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
          : 'קיבלנו את הבקשה הדחופה שלכם. עכשיו אפשר לחפש בעל מקצוע זמין לעבודה דחופה.'}
      </p>
      <div className={styles.actions}>
        <Button
          onClick={() => navigate(isStandard ? `/issues/${issueId}/booking` : `/issues/${issueId}/sos-booking`)}
          fullWidth
        >
          {isStandard ? 'בחירת בעל מקצוע' : 'חיפוש בעל מקצוע זמין'}
        </Button>
        <Button variant="secondary" onClick={() => navigate('/')} fullWidth>
          חזרה לדף הבית
        </Button>
      </div>
    </div>
  );
}
