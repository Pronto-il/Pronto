import { useNavigate } from 'react-router-dom';
import { PageHeader, Card, Button } from '../shared/components';
import { useAuth } from '../shared/hooks';
import { getCategoryNameHe, type UserRole } from '../shared/api';
import styles from './ProfilePage.module.css';

const ROLE_LABELS: Record<UserRole, string> = {
  CUSTOMER: 'לקוח',
  PROFESSIONAL: 'בעל מקצוע',
};

/**
 * Read-only display of `GET /api/users/me` — auth-required (mounted under `RequireAuth`
 * in `router.tsx`, so `user` is always populated here). No address field is shown: the
 * real endpoint doesn't return one yet (same gap as registration, see
 * `shared/api/auth.ts`).
 */
export default function ProfilePage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  if (!user) {
    return null;
  }

  function handleLogout() {
    logout();
    navigate('/login', { replace: true });
  }

  return (
    <div className="focused-page">
      <PageHeader title="הפרופיל שלי" />
      <Card className={styles.card}>
        <dl className={styles.details}>
          <div className={styles.row}>
            <dt>שם מלא</dt>
            <dd>{user.fullName}</dd>
          </div>
          <div className={styles.row}>
            <dt>אימייל</dt>
            <dd>{user.email}</dd>
          </div>
          <div className={styles.row}>
            <dt>סוג משתמש</dt>
            <dd>{ROLE_LABELS[user.role]}</dd>
          </div>
          <div className={styles.row}>
            <dt>סטטוס אימות אימייל</dt>
            <dd>{user.emailVerified ? 'מאומת' : 'לא מאומת'}</dd>
          </div>
          {user.professional && (
            <>
              <div className={styles.row}>
                <dt>תחום שירות</dt>
                <dd>{getCategoryNameHe(user.professional.categoryId)}</dd>
              </div>
              <div className={styles.row}>
                <dt>אזור שירות</dt>
                <dd>{user.professional.serviceArea}</dd>
              </div>
              <div className={styles.row}>
                <dt>מחיר ביקור בסיסי</dt>
                <dd>₪{user.professional.basePrice}</dd>
              </div>
            </>
          )}
        </dl>
      </Card>
      <Button variant="secondary" onClick={handleLogout}>
        יציאה מהחשבון
      </Button>
    </div>
  );
}
