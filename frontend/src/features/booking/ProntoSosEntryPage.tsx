import { useNavigate, useParams } from 'react-router-dom';
import { PageHeader, Card, Button, Mascot } from '../../shared/components';
import styles from './ProntoSosEntryPage.module.css';

/**
 * `/issues/:issueId/sos-booking` — **a deliberate placeholder, and the single hand-off point
 * for the upcoming Pronto SOS frontend task.**
 *
 * ## Why this file exists
 *
 * The legacy browse-and-pick SOS flow (`SosBookingFlowPage` + `SosBookingSummary`, which called
 * `GET /api/bookings/sos-professionals` and `POST /api/bookings/sos-orders`) was removed with
 * those endpoints — Pronto SOS is now the product's only SOS behaviour. But the route those
 * pages occupied is still reachable: `ProfessionMatchPage` navigates here for any `SOS` issue,
 * and a persisted booking draft can resume here. Leaving the route pointing at deleted modules
 * would break the build; silently deleting the route would give a customer who taps "דחוף" a
 * blank screen and no explanation.
 *
 * So this screen holds the position and, importantly, **issues no requests at all** — there is
 * no API call here to a deleted endpoint, and none to `/api/sos/**` either.
 *
 * ## What replaces it
 *
 * The next task builds the real customer SOS experience against the backend that already
 * exists: `POST /api/sos/requests` → progress while offers fan out → the candidate list from
 * `GET /api/sos/requests/{id}/candidates` → `POST /api/sos/requests/{id}/select`, with live
 * updates over `/user/queue/sos`. That work replaces this component and this comment; the route
 * path itself can stay or move, since `ProfessionMatchPage` and `resolveDraftRoute` are the only
 * two places that name it.
 *
 * This file is intentionally NOT a partial implementation of that flow.
 */
export default function ProntoSosEntryPage() {
  const navigate = useNavigate();
  const { issueId } = useParams<{ issueId: string }>();

  return (
    <div className="focused-page">
      <PageHeader title="בקשה דחופה" onBack={() => navigate('/')} />

      <div className={styles.wrapper}>
        <Card className={styles.card}>
          <Mascot state="thinking" size="lg" />
          <h2 className={styles.title}>המצב החירום שלנו בשדרוג</h2>
          <p className={styles.body}>
            אנחנו מחליפים את הבחירה הידנית מתוך רשימה בחיפוש אוטומטי: פרונטו תפנה בעצמה לבעלי מקצוע
            פנויים באזור שלך, ותציג לך רק את מי שאישר שהוא יכול להגיע עכשיו.
          </p>
          <p className={styles.body}>
            התקלה שדיווחת עליה נשמרה במלואה — התיאור, התמונות והניתוח. לא תצטרך למלא אותה שוב.
          </p>

          <div className={styles.actions}>
            <Button onClick={() => navigate('/orders')} fullWidth>
              להזמנות שלי
            </Button>
            <Button variant="ghost" onClick={() => navigate('/')} fullWidth>
              חזרה לדף הבית
            </Button>
          </div>
        </Card>
      </div>

      {/* Rendered for the developer, not the customer: keeps the issue this route was reached
          for visible while the real flow is being built. */}
      {import.meta.env.DEV && issueId && (
        <p className={styles.body}>issue #{issueId} — Pronto SOS frontend pending</p>
      )}
    </div>
  );
}
