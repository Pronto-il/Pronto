import type { NotificationMessageType } from '../../shared/api';

/**
 * Hebrew label for each `messageType`, exhaustive over the backend enum
 * (`notifications.entity.NotificationMessageType`).
 *
 * **Exhaustive is the whole point.** This is a `Record` keyed on the full union precisely so a
 * backend value with no copy here is a compile error rather than a row that quietly renders
 * `עדכון חדש`. That is exactly what went wrong with Pronto SOS: the 12 `SOS_*` values existed on
 * the backend but not in the frontend union, so the `Record` was "exhaustive" over a union that
 * was missing them, and every SOS notification fell through to the fallback. A customer watching
 * their urgent call would see three consecutive rows reading "עדכון חדש" — not duplicates, three
 * genuinely different events all rendered as nothing.
 *
 * ## Copy rules
 *
 * Each label says what happened, from the recipient's point of view, in the product's own voice.
 * Two of those are load-bearing:
 *
 * 1. **Availability is not selection** (`sos/README.md`). `SOS_PROFESSIONAL_SELECTED` goes to the
 *    professional who *won* — "הלקוח בחר בך". Nothing here may describe merely being available in
 *    words that could be read as being awarded the job.
 * 2. **Recipient, not observer.** `SOS_ON_THE_WAY` is written to the customer, so it reads
 *    "בעל המקצוע יצא לדרך", not "יצאת לדרך".
 */
const MESSAGE_TYPE_LABELS: Record<NotificationMessageType, string> = {
  ORDER_CREATED: 'בקשה חדשה התקבלה',
  ORDER_CONFIRMED: 'ההזמנה שלך אושרה',
  ORDER_ON_THE_WAY: 'בעל המקצוע בדרך אליך',
  // Production MS2 -- the verified-arrival notification. Reads as a statement because the
  // backend checked it, unlike every other status message here, which reports what somebody
  // said they were doing.
  ORDER_ARRIVED: 'בעל המקצוע הגיע לכתובת שלך',
  ORDER_COMPLETED: 'העבודה הושלמה',
  ORDER_CANCELLED: 'ההזמנה בוטלה',
  ORDER_REJECTED: 'הבקשה שלך נדחתה',
  ORDER_EXPIRED: 'הבקשה פגה תוקף',
  EMAIL_VERIFICATION: 'קוד אימות נשלח לאימייל',

  // ---- Pronto SOS, professional-facing ----
  SOS_OFFER_RECEIVED: 'קריאת SOS חדשה התקבלה',
  SOS_OFFER_EXPIRED: 'הזמן להגיב לקריאת ה-SOS הסתיים',
  // The award. Deliberately unambiguous about who chose whom.
  SOS_PROFESSIONAL_SELECTED: 'הלקוח בחר בך',
  // Sent only to professionals who said they were available and were passed over — never to
  // those who simply never answered. The copy assumes the recipient was in the running.
  SOS_NOT_SELECTED: 'הלקוח בחר בעל מקצוע אחר',

  // ---- Pronto SOS, customer-facing ----
  // Written when the shortlist settles, not once per acceptance: "אפשר לבחור" is the actionable
  // part, and it is the honest description of what this row means.
  SOS_CANDIDATES_READY: 'יש בעלי מקצוע זמינים — אפשר לבחור',
  SOS_PROFESSIONAL_CONFIRMED: 'בעל המקצוע אישר את ההזמנה',
  SOS_ON_THE_WAY: 'בעל המקצוע יצא לדרך',
  SOS_ARRIVED: 'בעל המקצוע הגיע',
  SOS_COMPLETED: 'הטיפול הושלם',
  SOS_CANCELLED: 'קריאת ה-SOS בוטלה',
  SOS_EXPIRED: 'קריאת ה-SOS פגה',
  // Production MS2. Deliberately NOT worded as "no professionals available": this fires when
  // the routing provider could not be reached, so nobody's distance could be measured. Telling
  // a customer with a burst pipe that nobody is available, when the truth is that Pronto could
  // not do the arithmetic, would be both false and actively harmful.
  SOS_TEMPORARILY_UNAVAILABLE: 'לא הצלחנו לחפש בעלי מקצוע כרגע — אפשר לנסות שוב',
  SOS_NO_PROFESSIONALS: 'לא נמצא בעל מקצוע בזמן',
};

const FALLBACK_LABEL = 'עדכון חדש';

/**
 * Looks up the Hebrew label for a notification's `messageType`.
 *
 * The `??` fallback stays, but its job is now genuinely narrow: a backend deployed ahead of this
 * client, emitting a type this build has never heard of. It is a crash guard, not a coverage gap
 * — every value the backend can persist today has real copy above. If this fallback starts
 * appearing in the notification centre, a new backend enum value has shipped without being
 * mirrored in `shared/api/notifications.ts`.
 */
export function getMessageTypeLabel(messageType: NotificationMessageType): string {
  return MESSAGE_TYPE_LABELS[messageType] ?? FALLBACK_LABEL;
}
