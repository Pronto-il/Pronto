import { Link, useNavigate } from 'react-router-dom';
import { Heart } from 'lucide-react';
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
 * in `router.tsx`, so `user` is always populated here). Shows the customer's saved default
 * address (`user.defaultAddress`, `ms3-ms4-corrections-design.md` §1) when present — `null`
 * for a `PROFESSIONAL` caller or a pre-V20 `CUSTOMER` with no recorded default, per that
 * field's own "absent means no such object" convention.
 *
 * Also the entry point to `/favorites` for a `CUSTOMER` — approved UX decision (Frontend
 * Milestone 8 correction): favorites is a secondary feature reached via "הפרופיל שלי" →
 * "מועדפים", not a primary top-nav destination.
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
          {user.defaultAddress && (
            <>
              <div className={styles.row}>
                <dt>עיר</dt>
                <dd>{user.defaultAddress.city}</dd>
              </div>
              <div className={styles.row}>
                <dt>רחוב</dt>
                <dd>{user.defaultAddress.street}</dd>
              </div>
              <div className={styles.row}>
                <dt>מספר בית</dt>
                <dd>{user.defaultAddress.houseNumber}</dd>
              </div>
              {user.defaultAddress.apartment && (
                <div className={styles.row}>
                  <dt>דירה</dt>
                  <dd>{user.defaultAddress.apartment}</dd>
                </div>
              )}
              {user.defaultAddress.floor && (
                <div className={styles.row}>
                  <dt>קומה</dt>
                  <dd>{user.defaultAddress.floor}</dd>
                </div>
              )}
              {user.defaultAddress.entrance && (
                <div className={styles.row}>
                  <dt>כניסה</dt>
                  <dd>{user.defaultAddress.entrance}</dd>
                </div>
              )}
              {user.defaultAddress.addressNotes && (
                <div className={styles.row}>
                  <dt>הערות לגישה לבית</dt>
                  <dd>{user.defaultAddress.addressNotes}</dd>
                </div>
              )}
            </>
          )}
        </dl>
      </Card>
      {user.role === 'CUSTOMER' && (
        <Link to="/favorites" className={styles.favoritesLink}>
          <Heart size={18} aria-hidden="true" />
          <span>מועדפים</span>
        </Link>
      )}
      <Button variant="secondary" onClick={handleLogout}>
        יציאה מהחשבון
      </Button>
    </div>
  );
}
