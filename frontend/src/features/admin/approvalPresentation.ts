import type { BadgeTone } from '../../shared/components';

/**
 * The one place where the backend's approval/eligibility data becomes Hebrew an operator can
 * read. **No screen in this package renders a raw backend value** — not `PENDING`, not
 * `approvalStatus`, not `onboardingComplete`, not `bookable`. They render the output of these
 * functions.
 *
 * Two separate concepts, deliberately kept apart because MS1 exists to stop them being confused
 * (D4):
 *
 * 1. **The decision** — what the operator team decided about this application. `describeDecision`.
 * 2. **Marketplace visibility** — whether customers can actually reach this professional right
 *    now. `describeVisibility`. Approval alone never implies it.
 *
 * Everything here is a pure mapping of values the backend already computed. It does **not**
 * re-derive eligibility or completeness — that rule lives in
 * `professionals.ProfessionalEligibility` and reaches us as `bookable`/`onboardingComplete`.
 */

export interface StatePresentation {
  label: string;
  tone: BadgeTone;
}

export interface VisibilityPresentation extends StatePresentation {
  /** One sentence explaining the label. Always present — the label alone is what leaves an
   *  operator guessing why an approved professional still isn't reaching customers. */
  explanation: string;
}

const DECISION_PRESENTATION: Record<string, StatePresentation> = {
  PENDING: { label: 'ממתין לבדיקה', tone: 'info' },
  APPROVED: { label: 'אושר', tone: 'success' },
  REJECTED: { label: 'נדחה', tone: 'error' },
  // Reserved by MS1's migration and unreachable today (nothing can write it). Mapped anyway so
  // that if a later milestone starts producing it, this screen shows Hebrew rather than a code.
  DISABLED: { label: 'הושעה', tone: 'neutral' },
};

const UNKNOWN_DECISION: StatePresentation = { label: 'מצב לא ידוע', tone: 'neutral' };

/** The operator team's decision on this application. */
export function describeDecision(approvalStatus: string): StatePresentation {
  return DECISION_PRESENTATION[approvalStatus] ?? UNKNOWN_DECISION;
}

export interface VisibilityInput {
  approvalStatus: string;
  bookable: boolean;
  onboardingComplete: boolean;
}

/**
 * Whether customers can find and book this professional right now, and why.
 *
 * The case this exists for is the third branch: **approved, and still invisible**, because the
 * professional has not finished their own registration. An operator who cannot see that reads a
 * green "approved" badge and concludes the job is done, when in fact nothing changed for the
 * customer. Both inputs come from the backend (`bookable`, `onboardingComplete`); this function
 * chooses words for them and infers nothing.
 */
export function describeVisibility({
  approvalStatus,
  bookable,
  onboardingComplete,
}: VisibilityInput): VisibilityPresentation {
  if (bookable) {
    return {
      label: 'מוצג ללקוחות',
      tone: 'success',
      explanation: 'בעל המקצוע מופיע בחיפוש של לקוחות ויכול לקבל פניות ועבודות.',
    };
  }

  if (approvalStatus === 'APPROVED') {
    return onboardingComplete
      ? {
          label: 'אינו מוצג ללקוחות',
          tone: 'warning',
          explanation:
            'הבקשה אושרה והפרטים שנדרשים להרשמה קיימים, ובכל זאת בעל המקצוע אינו מוצג ללקוחות כרגע.',
        }
      : {
          label: 'אושר, אך אינו מוצג ללקוחות',
          tone: 'warning',
          explanation:
            'הבקשה אושרה, אבל בעל המקצוע עדיין לא השלים את פרטי ההרשמה שלו — ולכן הוא אינו מופיע ' +
            'בחיפוש של לקוחות ואינו יכול לקבל עבודות. ההשלמה נעשית על ידו, מתוך החשבון שלו.',
        };
  }

  if (approvalStatus === 'PENDING') {
    return {
      label: 'אינו מוצג ללקוחות',
      tone: 'neutral',
      explanation: 'הבקשה ממתינה לבדיקה, ולכן בעל המקצוע אינו מופיע בחיפוש של לקוחות.',
    };
  }

  if (approvalStatus === 'REJECTED') {
    return {
      label: 'אינו מוצג ללקוחות',
      tone: 'neutral',
      explanation: 'הבקשה נדחתה, ולכן בעל המקצוע אינו מופיע בחיפוש של לקוחות.',
    };
  }

  return {
    label: 'אינו מוצג ללקוחות',
    tone: 'neutral',
    explanation: 'בעל המקצוע אינו מופיע בחיפוש של לקוחות כרגע.',
  };
}

/** The registration material the professional supplies. Short form, for the queue rows. */
export function describeOnboarding(onboardingComplete: boolean): StatePresentation {
  return onboardingComplete
    ? { label: 'פרטי הרשמה מלאים', tone: 'neutral' }
    : { label: 'פרטי הרשמה חסרים', tone: 'warning' };
}

/**
 * Which decisions the backend will accept from the current state, mirroring
 * `Professional#canApprove`/`#canReject` so the UI offers only actions that can succeed. It
 * mirrors, it does not replace: the backend still answers
 * `409 PROFESSIONAL_APPROVAL_INVALID_TRANSITION` if the state moved under us, and the screens
 * handle that.
 */
export function canApprove(approvalStatus: string): boolean {
  return approvalStatus === 'PENDING' || approvalStatus === 'REJECTED';
}

export function canReject(approvalStatus: string): boolean {
  return approvalStatus === 'PENDING';
}

/**
 * Hebrew for a `409 PROFESSIONAL_APPROVAL_INVALID_TRANSITION`. Realistically this means someone
 * else decided the same application first, or a stale tab was left open — the message says so
 * instead of "something went wrong", because the operator's next move (reload and look again) is
 * completely different from a retry.
 *
 * The approve→reject case is called out by name: it is refused **by design**, not by accident.
 * Withdrawing an approval is a suspension, which MS1 does not build.
 */
export function describeDecisionConflict(action: 'approve' | 'reject', approvalStatus: string): string {
  if (action === 'reject' && approvalStatus === 'APPROVED') {
    return 'לא ניתן לדחות בקשה שכבר אושרה. השהיית חשבון מאושר אינה אפשרית בשלב הזה.';
  }
  if (action === 'approve' && approvalStatus === 'APPROVED') {
    return 'הבקשה כבר אושרה. ייתכן שמפעיל אחר אישר אותה זה עתה.';
  }
  if (action === 'reject' && approvalStatus === 'REJECTED') {
    return 'הבקשה כבר נדחתה. ייתכן שמפעיל אחר דחה אותה זה עתה.';
  }
  return 'מצב הבקשה השתנה מאז שהמסך נטען, ולכן לא ניתן לבצע את הפעולה. יש לרענן את הבקשה ולבדוק שוב.';
}
