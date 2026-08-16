import { useNavigate } from 'react-router-dom';
import { Button } from '../../shared/components';
import styles from './IssueSuccessStep.module.css';

/**
 * Calm confirmation state (DESIGN_SYSTEM.md §78) after `POST /api/issues` succeeds. Does
 * not route into a professional-matching screen — that's `features/booking`, still a stub
 * (Milestone 3+ frontend, out of this milestone's scope).
 */
export function IssueSuccessStep() {
  const navigate = useNavigate();
  return (
    <div className={styles.wrapper}>
      <span className={styles.check} aria-hidden="true">
        ✓
      </span>
      <h2 className={styles.title}>הבקשה נשלחה</h2>
      <p className={styles.text}>קיבלנו את הפרטים. בקרוב נציג לך בעלי מקצוע מתאימים.</p>
      <Button onClick={() => navigate('/')} fullWidth>
        חזרה לדף הבית
      </Button>
    </div>
  );
}
