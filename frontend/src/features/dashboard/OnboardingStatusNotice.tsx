import { Link } from 'react-router-dom';
import { Card } from '../../shared/components';
import { usePolling } from '../../shared/hooks';
import { getMyProfessionalProfile, getMySubServices, getWorkingHours } from '../../shared/api';
import styles from './OnboardingStatusNotice.module.css';

/** Slow on purpose: this is a "something is still missing" reminder, not live data. It does
 *  keep polling after the account becomes bookable, so clearing your own sub-services later in
 *  the profile editor brings the notice back without a page reload. */
const STATUS_POLL_INTERVAL_MS = 60000;

interface OnboardingStatus {
  bookable: boolean;
  /** Self-view only, so always present here — `PENDING` / `APPROVED` / `REJECTED`. */
  approvalStatus: string | null;
  missingSubServices: boolean;
  missingWorkingHours: boolean;
}

/**
 * Reads the caller's own onboarding state, and only looks further when something is actually
 * wrong: `GET /api/professionals/me` alone answers "are you bookable", and the two follow-up
 * reads exist purely to say *which* piece is missing.
 */
async function fetchOnboardingStatus(): Promise<OnboardingStatus> {
  const profile = await getMyProfessionalProfile();
  if (profile.bookable) {
    return {
      bookable: true,
      approvalStatus: profile.approvalStatus,
      missingSubServices: false,
      missingWorkingHours: false,
    };
  }
  const [subServices, workingHours] = await Promise.all([getMySubServices(), getWorkingHours()]);
  return {
    bookable: false,
    approvalStatus: profile.approvalStatus,
    missingSubServices: subServices.subServiceIds.length === 0,
    missingWorkingHours: !workingHours.workingHours.some((item) => item.enabled),
  };
}

/**
 * "Your account isn't visible to customers yet, and here is what's left" — MS1 (D5), rendered
 * by `ProDashboardLayout` above every `/pro/*` screen.
 *
 * MS1 makes marketplace eligibility `approval_status = APPROVED` **and** completed onboarding,
 * evaluated per query on the backend. Professionals who registered before MS1 have neither
 * sub-services nor working hours (the registration flow never collected them), so they stop
 * being listed with nothing on screen explaining why. This notice closes that gap without a
 * parallel onboarding flow: it names what's missing and links to the two existing surfaces that
 * already own it — `/pro/profile`'s sub-services checklist and `/pro/availability`'s working
 * hours. It never fabricates anything on the professional's behalf, and it never claims to know
 * a reason the backend didn't give it.
 *
 * Everything it says comes from backend truth: `bookable` and `approvalStatus` from
 * `GET /api/professionals/me` (`approvalStatus` is self-view-only per D-G, which is exactly the
 * caller here), the two gaps from `GET /api/professionals/me/sub-services` and
 * `GET /api/availability/working-hours`. When the account is eligible it renders nothing.
 */
export function OnboardingStatusNotice() {
  const { data } = usePolling<OnboardingStatus>(fetchOnboardingStatus, {
    intervalMs: STATUS_POLL_INTERVAL_MS,
  });

  // Nothing to say while loading, on a failed read, or once the account is eligible — a
  // half-known state must not be turned into an alarming message.
  if (!data || data.bookable) {
    return null;
  }

  const isRejected = data.approvalStatus === 'REJECTED';
  const hasGaps = data.missingSubServices || data.missingWorkingHours;

  // Exactly what the three backend reads support, and nothing beyond it: an approved account
  // with no detectable gap is still not bookable for a reason this screen isn't told (a missing
  // verification document is not exposed to the professional's own profile response), so it
  // states the fact and invents no cause.
  const body = isRejected
    ? 'הבקשה שלך נבדקה ולא אושרה בשלב זה.'
    : hasGaps
      ? 'כדי להתחיל לקבל פניות צריך להשלים את הפרטים הבאים:'
      : data.approvalStatus === 'PENDING'
        ? 'הפרטים שלך נמצאים בבדיקה של צוות פרונטו. אין צורך בפעולה נוספת מצדך.'
        : 'החשבון אינו זמין להזמנות כרגע.';

  return (
    <Card className={styles.notice}>
      <p className={styles.title}>
        {isRejected ? 'החשבון שלך אינו מוצג ללקוחות' : 'החשבון שלך עדיין לא מוצג ללקוחות'}
      </p>

      <p className={styles.body}>{body}</p>

      {!isRejected && hasGaps && (
        <ul className={styles.list}>
          {data.missingSubServices && (
            <li>
              <Link to="/pro/profile" className={styles.link}>
                בחירת התחומים שבהם אתה נותן שירות
              </Link>
            </li>
          )}
          {data.missingWorkingHours && (
            <li>
              <Link to="/pro/availability" className={styles.link}>
                הגדרת שעות עבודה שבועיות
              </Link>
            </li>
          )}
        </ul>
      )}

      {!isRejected && data.approvalStatus === 'PENDING' && hasGaps && (
        <p className={styles.footnote}>לאחר ההשלמה החשבון ימתין לאישור צוות פרונטו.</p>
      )}
    </Card>
  );
}
