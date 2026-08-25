import { MapPinOff } from 'lucide-react';
import { Button } from '../../shared/components';
import { useProfessionalLocationSync } from './useProfessionalLocationSync';
import styles from './LocationStatusNotice.module.css';

/**
 * Tells a professional when Pronto cannot see where they are, and what that costs them.
 *
 * ## Renders nothing at all when the location is usable
 *
 * Which is the common case, and the reason this is a notice rather than a status widget: a
 * permanent "location: OK" chip is noise on every screen for a state nobody needs to act on.
 * The component only appears when something is actually degraded.
 *
 * ## The message says the consequence, not the mechanism
 *
 * "Location services are disabled" is a true sentence that tells a professional nothing about
 * why they should care. What they need to know is that customers will not see an arrival time
 * for them and that urgent jobs will not reach them — that is the fact that makes granting
 * permission worth doing, and it is the fact this component leads with.
 *
 * ## And it never blocks anything
 *
 * A professional who refuses location can still browse, still see their calendar, still accept
 * and complete scheduled work. The two things they lose are ETA display and SOS matching, both
 * of which genuinely depend on knowing where they are. Nothing here is a modal, nothing traps
 * focus, and nothing is un-dismissable by navigating away.
 */
export function LocationStatusNotice() {
  const { status, failureReason, failureMessage, isRefreshing, refresh } =
    useProfessionalLocationSync(true);

  // Usable position and no device-side problem: say nothing.
  if (status?.usable && failureReason === null) {
    return null;
  }

  // Before the first answer there is nothing honest to say yet -- and a notice that flashes on
  // every dashboard load while a fix is acquired would train people to ignore it.
  if (status === null && failureReason === null) {
    return null;
  }

  const { title, body, showRetry } = describe(failureReason, status?.reason ?? null, failureMessage);

  return (
    <div className={styles.notice} role="status">
      <MapPinOff size={18} className={styles.icon} aria-hidden="true" />
      <div className={styles.text}>
        <p className={styles.title}>{title}</p>
        <p className={styles.body}>{body}</p>
      </div>
      {showRetry && (
        <Button variant="secondary" onClick={() => void refresh()} loading={isRefreshing}>
          נסה שוב
        </Button>
      )}
    </div>
  );
}

/**
 * One message per situation, because the required action differs in each.
 *
 * @param deviceFailure why the browser could not give us a position, if that is what happened
 * @param serverReason  the backend's own verdict on the stored position
 *                      (`maps.RouteUnavailableReason`), when the device is fine but the stored
 *                      reading is not good enough
 */
function describe(
  deviceFailure: string | null,
  serverReason: string | null,
  failureMessage: string | null,
): { title: string; body: string; showRetry: boolean } {
  switch (deviceFailure) {
    case 'PERMISSION_DENIED':
      return {
        title: 'שירותי המיקום חסומים',
        // Named consequences, not a generic "some features may not work".
        body: 'בלי מיקום, לקוחות לא יראו זמן הגעה משוער עבורך, ולא תוכל לקבל קריאות SOS דחופות. יש לאפשר גישה למיקום בהגדרות הדפדפן.',
        // No retry button: pressing it cannot help while the browser is remembering a refusal,
        // and a button that reliably does nothing is worse than no button.
        showRetry: false,
      };
    case 'UNSUPPORTED':
      return {
        title: 'הדפדפן לא תומך במיקום',
        body: 'קריאות SOS ואימות הגעה דורשים שירותי מיקום. מומלץ לעבוד מדפדפן נייד עדכני.',
        showRetry: false,
      };
    case 'INACCURATE':
      return {
        title: 'המיקום שהתקבל אינו מדויק מספיק',
        body: 'זה קורה בדרך כלל בתוך מבנים. מומלץ להפעיל מיקום מדויק, ולנסות שוב ליד חלון או בחוץ.',
        showRetry: true,
      };
    case 'TIMEOUT':
    case 'UNAVAILABLE':
      return {
        title: 'לא הצלחנו לאתר את המיקום שלך',
        body: failureMessage ?? 'יש לוודא ששירותי המיקום פעילים ולנסות שוב.',
        showRetry: true,
      };
    default:
      break;
  }

  // The device is fine; the backend does not consider the stored reading usable.
  if (serverReason === 'PROFESSIONAL_LOCATION_STALE') {
    return {
      title: 'המיקום שלך אינו עדכני',
      body: 'קריאות SOS נשלחות רק לבעלי מקצוע עם מיקום עדכני. אפשר לרענן עכשיו.',
      showRetry: true,
    };
  }
  if (serverReason === 'PROFESSIONAL_LOCATION_INACCURATE') {
    return {
      title: 'המיקום שלך אינו מדויק מספיק',
      body: 'מומלץ להפעיל מיקום מדויק בהגדרות המכשיר ולרענן.',
      showRetry: true,
    };
  }
  return {
    title: 'המיקום שלך עדיין לא נשלח',
    body: 'בלי מיקום עדכני לא תופיע בחיפושים דחופים ולא יוצג זמן הגעה משוער. אפשר לרענן עכשיו.',
    showRetry: true,
  };
}
