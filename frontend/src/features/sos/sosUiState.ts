import type { BadgeTone } from '../../shared/components';
import type { SosRequestStatus } from '../../shared/api';

/**
 * The customer's view of the SOS lifecycle: `SosRequestStatus` (12 backend states) collapsed to
 * the four things the screen actually has to *look* like, plus the Hebrew copy for each state.
 *
 * The phases are a presentation grouping, never a second state model — nothing here is stored,
 * and every value is derived from the `status` the backend just returned. `ProntoSosScreen`
 * switches its layout on the phase; the header reads its words from `SOS_STATUS_COPY`.
 */
export type SosUiPhase =
  /** `CREATED` / `MATCHING` / `WAITING_FOR_PROFESSIONALS` — the scan is running. */
  | 'SCANNING'
  /** `WAITING_FOR_CUSTOMER_SELECTION` — the choosing window is open and counting down. */
  | 'CHOOSING'
  /** `PROFESSIONAL_SELECTED` … `ARRIVED` — one professional owns the job; the screen tracks them. */
  | 'TRACKING'
  /** `COMPLETED` — the visit is done. */
  | 'DONE'
  /** `CANCELLED` / `EXPIRED` / `FAILED` — over without a completed visit; offer a retry. */
  | 'ENDED';

export function toSosUiPhase(status: SosRequestStatus): SosUiPhase {
  switch (status) {
    case 'CREATED':
    case 'MATCHING':
    case 'WAITING_FOR_PROFESSIONALS':
      return 'SCANNING';
    case 'WAITING_FOR_CUSTOMER_SELECTION':
      return 'CHOOSING';
    case 'PROFESSIONAL_SELECTED':
    case 'CONFIRMED':
    case 'ON_THE_WAY':
    case 'ARRIVED':
      return 'TRACKING';
    case 'COMPLETED':
      return 'DONE';
    case 'CANCELLED':
    case 'EXPIRED':
    case 'FAILED':
      return 'ENDED';
  }
}

export interface SosStatusCopy {
  /** The one-line answer to "what is happening right now". */
  title: string;
  /** One supporting sentence. Never more — the customer is in a hurry. */
  description: string;
  /** Short label for the status pill in the header. */
  badgeLabel: string;
  badgeTone: BadgeTone;
}

/**
 * Hebrew copy per backend status. Deliberately exhaustive over `SosRequestStatus`, so a status
 * added backend-side becomes a TypeScript error here rather than a blank screen.
 *
 * Tone follows FRONTEND_AGENT.md §13: urgency is visible, but the screen is not a panic
 * interface — SOS red is used for the brand mark and the terminal states, not as a wash over
 * everything. Nothing here says "אישר"/"accepted" for a professional who merely reported
 * availability; that word is reserved for the professional confirming a job they were given.
 */
export const SOS_STATUS_COPY: Record<SosRequestStatus, SosStatusCopy> = {
  CREATED: {
    title: 'מפעילים את פרונטו SOS',
    description: 'רק רגע, מתחילים לסרוק את האזור שלך.',
    badgeLabel: 'מתחילים',
    badgeTone: 'info',
  },
  MATCHING: {
    title: 'מחפשים עבורך בעל מקצוע זמין עכשיו',
    description: 'סורקים בעלי מקצוע פנויים שיכולים להגיע אליך מיד.',
    badgeLabel: 'סורקים',
    badgeTone: 'info',
  },
  WAITING_FOR_PROFESSIONALS: {
    title: 'שלחנו קריאה לבעלי מקצוע באזור',
    description: 'כל מי שיאשר שהוא פנוי להגיע יופיע כאן מיד — בלי להמתין לכולם.',
    badgeLabel: 'ממתינים לתשובות',
    badgeTone: 'info',
  },
  WAITING_FOR_CUSTOMER_SELECTION: {
    title: 'אפשר לבחור כבר עכשיו',
    description: 'בחרו את בעל המקצוע שיצא אליכם. הבחירה פתוחה לזמן מוגבל.',
    badgeLabel: 'הבחירה פתוחה',
    badgeTone: 'primary',
  },
  PROFESSIONAL_SELECTED: {
    title: 'מחכים לאישור היציאה',
    description: 'העברנו את הקריאה לבעל המקצוע שבחרתם, והוא מאשר את היציאה אליכם.',
    badgeLabel: 'ממתין לאישור',
    badgeTone: 'info',
  },
  CONFIRMED: {
    title: 'הקריאה אושרה',
    description: 'בעל המקצוע אישר את הקריאה ויוצא אליכם בקרוב.',
    badgeLabel: 'אושר',
    badgeTone: 'primary',
  },
  ON_THE_WAY: {
    title: 'בדרך אליך',
    description: 'בעל המקצוע יצא לדרך ומגיע לכתובת שמסרתם.',
    badgeLabel: 'בדרך',
    badgeTone: 'info',
  },
  ARRIVED: {
    title: 'בעל המקצוע הגיע',
    description: 'הוא כבר בכתובת שמסרתם ומתחיל לטפל בתקלה.',
    badgeLabel: 'הגיע',
    badgeTone: 'primary',
  },
  COMPLETED: {
    title: 'הטיפול הושלם',
    description: 'הקריאה נסגרה. תודה שהשתמשתם בפרונטו SOS.',
    badgeLabel: 'הושלם',
    badgeTone: 'success',
  },
  CANCELLED: {
    title: 'הקריאה בוטלה',
    description: 'הקריאה הדחופה בוטלה. התקלה שלכם נשמרה ואפשר לנסות שוב.',
    badgeLabel: 'בוטל',
    badgeTone: 'neutral',
  },
  EXPIRED: {
    title: 'הזמן לקריאה הזו נגמר',
    description: 'לא הספקנו לסגור בעל מקצוע בזמן. אפשר לנסות שוב על אותה תקלה.',
    badgeLabel: 'פג תוקף',
    badgeTone: 'neutral',
  },
  FAILED: {
    title: 'לא נמצא בעל מקצוע פנוי כרגע',
    description: 'אף בעל מקצוע מתאים לא זמין באזור שלכם ברגע זה. אפשר לנסות שוב.',
    badgeLabel: 'לא נמצאו',
    badgeTone: 'warning',
  },
};

/**
 * The post-selection tracking steps, in order. `PROFESSIONAL_SELECTED` onward only — the search
 * itself is not a step here, it is the phase this stepper replaces.
 */
export const SOS_TRACKING_STEPS: { status: SosRequestStatus; label: string }[] = [
  { status: 'PROFESSIONAL_SELECTED', label: 'נבחר' },
  { status: 'CONFIRMED', label: 'אישר' },
  { status: 'ON_THE_WAY', label: 'בדרך' },
  { status: 'ARRIVED', label: 'הגיע' },
  { status: 'COMPLETED', label: 'הושלם' },
];

/**
 * Hebrew, user-facing copy for the SOS-specific `ErrorCode`s a customer action can produce.
 * Anything not listed falls back to `GENERIC_ERROR_MESSAGE` — the backend's English message is
 * never shown (FRONTEND_AGENT.md §26).
 */
export const SOS_ERROR_MESSAGES: Record<string, string> = {
  // -- activation --
  SOS_REQUEST_ALREADY_EXISTS: 'כבר קיימת קריאת SOS פעילה לתקלה הזו.',
  ISSUE_URGENCY_MISMATCH: 'התקלה הזו לא מסומנת כדחופה, ולכן אי אפשר להפעיל עליה SOS.',
  ISSUE_NOT_BOOKABLE: 'התקלה הזו כבר לא פתוחה להזמנה חדשה.',
  SOS_NO_PROFESSIONALS_AVAILABLE: 'לא נמצא בעל מקצוע פנוי באזור שלכם כרגע.',
  // -- selection --
  SOS_WINDOW_EXPIRED: 'הזמן לבחירה נגמר. אפשר להתחיל קריאה חדשה על אותה תקלה.',
  SOS_ALREADY_SELECTED: 'כבר נבחר בעל מקצוע לקריאה הזו.',
  SOS_CANDIDATE_NOT_AVAILABLE: 'בעל המקצוע הזה כבר לא זמין. אפשר לבחור מישהו אחר מהרשימה.',
  SOS_INVALID_STATE: 'הבקשה השתנתה בינתיים. רגע, מרעננים את המצב.',
  // -- search expansion ("סרוק שוב") --
  SOS_EXPANSION_LIMIT_REACHED: 'הרחבנו את החיפוש עד הסוף. אפשר לבחור מבין מי שכבר אישר שהוא זמין.',
};

/**
 * Why the select CTA is disabled, in one sentence — shared by the tray's cards and the details
 * sheet so the two cannot explain the same rule differently.
 *
 * **This is now a rare state, and that is the point.** Selection opens on the *first* acceptance:
 * the moment one professional says they can come, `selectionOpen` is true and the CTA is live. So
 * a visible candidate with a disabled CTA no longer means "wait for two more" — it means the
 * backend has moved on in a way this render has not caught up with yet (the window just lapsed,
 * the request was just cancelled), and the next poll or push will resolve it. The copy says that
 * rather than promising an opening that may never come.
 *
 * An enabled button that produced `SOS_INVALID_STATE` would be a worse lie than a disabled one
 * that explains itself, which is why the CTA still follows `selectionOpen` rather than the mere
 * presence of a candidate.
 */
export const SOS_SELECTION_PENDING_HINT = 'רגע, מעדכנים את מצב הקריאה.';
