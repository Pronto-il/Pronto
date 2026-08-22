import type { BadgeTone } from '../../shared/components';
import type { SosOfferStatus, SosRequestStatus } from '../../shared/api';

/**
 * The professional's SOS vocabulary, in one file so the product's central distinction cannot drift
 * apart across screens.
 *
 * ## The rule
 *
 * `ACCEPTED` means **"אישרתי שאני זמין"** — I said I can come. It does **not** mean the job is
 * mine. The customer sees me as one candidate among up to three and may choose somebody else.
 * `SELECTED` is the award, and it is the only status whose copy says **"הלקוח בחר בך"**.
 * `NOT_SELECTED` is a customer's choice between good options — never a rejection of this
 * professional, never framed as failure or as anything Pronto did to them.
 *
 * Every string a professional reads about an offer comes from here.
 */

export interface SosOfferCopy {
  /** The state, stated plainly. */
  title: string;
  /** One supporting sentence. The professional is deciding or working — not reading. */
  description: string;
  badgeLabel: string;
  badgeTone: BadgeTone;
}

/**
 * Copy per offer status, exhaustive over `SosOfferStatus` so a status added backend-side becomes a
 * compile error rather than a blank card.
 *
 * Note what `ACCEPTED` deliberately does *not* say: no "אושר", no "העבודה שלך", no green
 * success tone. Its badge is `info`, because being available is a state, not an achievement.
 */
export const SOS_OFFER_COPY: Record<SosOfferStatus, SosOfferCopy> = {
  OFFERED: {
    title: 'קריאת SOS חדשה',
    description: 'לקוח מחפש בעל מקצוע שיכול להגיע עכשיו. אפשר לאשר זמינות או לדלג.',
    badgeLabel: 'חדש',
    badgeTone: 'error',
  },
  VIEWED: {
    title: 'קריאת SOS ממתינה לתשובה',
    description: 'לקוח מחפש בעל מקצוע שיכול להגיע עכשיו. אפשר לאשר זמינות או לדלג.',
    badgeLabel: 'ממתין לתשובתך',
    badgeTone: 'error',
  },
  ACCEPTED: {
    title: 'אישרת שאתה זמין',
    description: 'הלקוח רואה אותך כעת ויכול לבחור בך. נעדכן אותך ברגע שהוא יבחר.',
    badgeLabel: 'זמינות אושרה',
    badgeTone: 'info',
  },
  SELECTED: {
    title: 'הלקוח בחר בך',
    description: 'העבודה שלך. אשר את היציאה כדי להתחיל.',
    badgeLabel: 'נבחרת',
    badgeTone: 'success',
  },
  NOT_SELECTED: {
    title: 'הלקוח בחר בעל מקצוע אחר',
    description: 'הזמינות שלך נשלחה בזמן. הפעם הלקוח בחר מישהו אחר.',
    badgeLabel: 'לא נבחר',
    badgeTone: 'neutral',
  },
  REJECTED: {
    title: 'סימנת שאינך זמין',
    description: 'הקריאה הזו הועברה לבעלי מקצוע אחרים.',
    badgeLabel: 'דילגת',
    badgeTone: 'neutral',
  },
  EXPIRED: {
    title: 'הזמן להגיב לקריאה הסתיים',
    description: 'הקריאה לא ממתינה לך יותר. קריאות SOS נסגרות תוך דקות ספורות.',
    badgeLabel: 'פג תוקף',
    badgeTone: 'neutral',
  },
};

/**
 * The operational step a *selected* professional is on, and the single action that moves it.
 *
 * `cta === null` means there is nothing for them to press — either the job is over, or the next
 * move belongs to somebody else. An always-failing button is worse than no button.
 */
export interface SosJobStep {
  title: string;
  description: string;
  badgeLabel: string;
  badgeTone: BadgeTone;
  cta: string | null;
  /** Which endpoint the CTA calls. `null` when there is no CTA. */
  action: 'confirm' | 'on-the-way' | 'arrived' | 'complete' | null;
  /** Ask before firing. Reserved for the irreversible end of the job, per `FRONTEND_AGENT.md` §35. */
  confirmFirst?: boolean;
}

/**
 * Copy and CTA per request status, from the selected professional's point of view. Exhaustive over
 * `SosRequestStatus`: the pre-selection statuses are reachable here only in the moment between the
 * customer's choice and this client's next read, so they render as a calm waiting state rather
 * than as an error.
 *
 * `PROFESSIONAL_SELECTED` carries no countdown on purpose. The backend does enforce a confirmation
 * grace period (`pronto.sos.confirmation-grace-seconds`), but **that deadline is not on any DTO** —
 * only `selectedAt` is. Deriving one client-side would mean hardcoding a server config value, and a
 * countdown that disagrees with the server is worse than none. The urgency is carried by the copy
 * instead. See this milestone's report for the gap.
 */
export const SOS_JOB_STEPS: Record<SosRequestStatus, SosJobStep> = {
  CREATED: {
    title: 'הקריאה נפתחת',
    description: 'רגע, טוענים את פרטי הקריאה.',
    badgeLabel: 'טוען',
    badgeTone: 'neutral',
    cta: null,
    action: null,
  },
  MATCHING: {
    title: 'הקריאה בתהליך שיוך',
    description: 'רגע, טוענים את פרטי הקריאה.',
    badgeLabel: 'טוען',
    badgeTone: 'neutral',
    cta: null,
    action: null,
  },
  WAITING_FOR_PROFESSIONALS: {
    title: 'הקריאה עדיין פתוחה',
    description: 'רגע, טוענים את פרטי הקריאה.',
    badgeLabel: 'טוען',
    badgeTone: 'neutral',
    cta: null,
    action: null,
  },
  WAITING_FOR_CUSTOMER_SELECTION: {
    title: 'הלקוח בוחר',
    description: 'רגע, טוענים את פרטי הקריאה.',
    badgeLabel: 'טוען',
    badgeTone: 'neutral',
    cta: null,
    action: null,
  },
  PROFESSIONAL_SELECTED: {
    title: 'הלקוח בחר בך',
    description: 'צריך לאשר את היציאה עכשיו. אם לא תאשר בזמן הקרוב, הקריאה תיסגר והלקוח יחפש מחדש.',
    badgeLabel: 'ממתין לאישורך',
    badgeTone: 'error',
    cta: 'אישור יציאה',
    action: 'confirm',
  },
  CONFIRMED: {
    title: 'אישרת את היציאה',
    description: 'הלקוח מעודכן ומחכה לך. סמן יציאה כשאתה יוצא לדרך.',
    badgeLabel: 'אושר',
    badgeTone: 'primary',
    cta: 'יצאתי לדרך',
    action: 'on-the-way',
  },
  ON_THE_WAY: {
    title: 'בדרך ללקוח',
    description: 'הלקוח רואה שאתה בדרך. סמן הגעה כשאתה במקום.',
    badgeLabel: 'בדרך',
    badgeTone: 'info',
    cta: 'הגעתי',
    action: 'arrived',
  },
  ARRIVED: {
    title: 'הגעת ללקוח',
    description: 'בסיום הטיפול סמן שהעבודה הושלמה. הלקוח יוכל להשאיר ביקורת.',
    badgeLabel: 'הגעת',
    badgeTone: 'primary',
    cta: 'סיום העבודה',
    action: 'complete',
    confirmFirst: true,
  },
  COMPLETED: {
    title: 'העבודה הושלמה',
    description: 'הקריאה נסגרה והלקוח יכול להשאיר ביקורת.',
    badgeLabel: 'הושלם',
    badgeTone: 'success',
    cta: null,
    action: null,
  },
  CANCELLED: {
    title: 'הקריאה בוטלה',
    description: 'הקריאה בוטלה ואין צורך להגיע.',
    badgeLabel: 'בוטל',
    badgeTone: 'neutral',
    cta: null,
    action: null,
  },
  EXPIRED: {
    title: 'הקריאה נסגרה',
    description: 'הזמן לאישור היציאה חלף והקריאה נסגרה. הלקוח יכול לפתוח קריאה חדשה.',
    badgeLabel: 'פג תוקף',
    badgeTone: 'neutral',
    cta: null,
    action: null,
  },
  FAILED: {
    title: 'הקריאה נסגרה',
    description: 'הקריאה נסגרה ואין צורך להגיע.',
    badgeLabel: 'נסגר',
    badgeTone: 'neutral',
    cta: null,
    action: null,
  },
};

/**
 * Hebrew for the SOS error codes a *professional* action can produce. Merged over the customer-side
 * map at the call site, so shared codes keep one wording and the professional-specific readings win
 * where the same code means something different from this side.
 *
 * `SOS_INVALID_STATE` is the interesting one: for a customer it means "your selection window
 * moved", for a professional it usually means somebody else was already chosen. Both are handled by
 * refetching, but only one of them is honest to say out loud.
 */
export const SOS_PRO_ERROR_MESSAGES: Record<string, string> = {
  SOS_WINDOW_EXPIRED: 'הזמן להגיב לקריאה הזו הסתיים.',
  SOS_OFFER_NOT_OPEN: 'כבר הגבת לקריאה הזו.',
  SOS_INVALID_STATE: 'הקריאה כבר לא ממתינה לתשובה — ייתכן שנבחר בעל מקצוע אחר.',
  SOS_ALREADY_SELECTED: 'כבר נבחר בעל מקצוע לקריאה הזו.',
  FORBIDDEN: 'הפעולה הזו כבר לא זמינה עבורך.',
  VALIDATION_ERROR: 'יש לבדוק את זמן ההגעה שהוזן.',
};

/**
 * ETA chips offered on the accept sheet. Covers the realistic range for an urgent call-out without
 * making the professional type; anything outside it goes through the free-text field, which is
 * bounded by the backend's own `@Min(0) @Max(480)`.
 */
export const SOS_ETA_PRESET_MINUTES = [15, 20, 30, 45, 60, 90];
