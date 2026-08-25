import type { ReactNode } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { Mascot } from '../../shared/components';
import type { MascotState } from '../../shared/components';
import { ProfessionalSummaryCard } from '../professionals';
import type { OrderStatus, ProfessionalProfileResponse } from '../../shared/api';
import { listStagger, pageTransition } from '../../shared/motion/variants';
import styles from './OrderStatusHero.module.css';

export interface OrderStatusHeroProps {
  status: OrderStatus;
  /** The professional's name — the customer's counterparty on this order. */
  professionalName: string;
  /** Live countdown from `useEtaCountdown`, already computed by the page. `null` unless the
   *  order is `ON_THE_WAY` with a persisted `expectedArrivalAt`. */
  remainingMinutes: number | null;
  /** `useEtaCountdown`'s "the window has effectively elapsed" flag. */
  isArriving: boolean;
  /** Booked date/time, pre-formatted by the caller (this component does no formatting). */
  bookedLabel: string;
  /**
   * The professional's real profile, when the page has already fetched it. While the order is
   * `PENDING` this is rendered as `ProfessionalSummaryCard` — who the customer is waiting for is
   * the substance of that screen, and a name in a headline is not it. `null`/absent (a slow or
   * failed profile fetch) simply falls back to the name-only headline; nothing is fabricated.
   */
  professional?: ProfessionalProfileResponse | null;
  /** Opens the professional's profile in place. Passed straight to `ProfessionalSummaryCard`,
   *  which is the identity element here; omitted ⇒ the card is static, exactly as before. */
  onOpenProfessional?: () => void;
  /** Rendered under the copy — the status-appropriate call to action, owned by the page. */
  action?: ReactNode;
}

interface HeroContent {
  headline: string;
  support?: string;
  mascot: MascotState;
  tone: 'neutral' | 'positive' | 'ended';
}

/**
 * DESIGN_SYSTEM.md §79's Active Job Screen, for the **customer** viewer only (design doc
 * §4 Q2 — the professional keeps MS6's own surfaces). §79's rule is that "the status should
 * become the main visual element"; before MS5 the status was a 28px badge in the corner of a
 * details card that rendered identically for all seven `OrderStatus` values, while the
 * loudest element on screen was the red cancel button.
 *
 * Everything here is derived from `orderStatus` plus values the page already has — no new
 * fetch, no new field, and no per-status timestamps (the `orders` table doesn't persist any,
 * so this deliberately says "בדרך אליך" and never "יצא לדרך ב-14:12").
 *
 * Copy avoids gendered verb forms for the professional (whose gender the product never
 * records) — "ההזמנה אושרה", not "אישר/ה".
 */
export function OrderStatusHero({
  status,
  professionalName,
  remainingMinutes,
  isArriving,
  bookedLabel,
  professional,
  onOpenProfessional,
  action,
}: OrderStatusHeroProps) {
  const shouldReduceMotion = useReducedMotion();
  // Same neutralization pattern `IssueSuccessStep`/`BookingSuccessStep` already use for this
  // variant pair: override the resolved `animate` target, since each variant embeds its own
  // spring transition that would otherwise win over a component-level `transition` prop.
  const containerAnimate = shouldReduceMotion
    ? { transition: { staggerChildren: 0, delayChildren: 0 } }
    : 'animate';
  const itemAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  // While waiting for a professional to answer, the card *is* the content: the headline drops the
  // name (it's on the card, in context, with their photo, profession and rating) and states the
  // situation instead.
  const showProfessionalCard = status === 'PENDING' && Boolean(professional);
  const content = describe(status, professionalName, bookedLabel, showProfessionalCard);
  const showEta = status === 'ON_THE_WAY' && remainingMinutes !== null;

  return (
    <div className={`${styles.hero} ${styles[content.tone]}`}>
      <Mascot state={content.mascot} size="lg" className={styles.mascot} />

      <motion.div className={styles.body} variants={listStagger} initial="initial" animate={containerAnimate}>
        <motion.h2 className={styles.headline} variants={pageTransition} animate={itemAnimate}>
          {content.headline}
        </motion.h2>

        {showProfessionalCard && professional && (
          <motion.div className={styles.professionalCard} variants={pageTransition} animate={itemAnimate}>
            <ProfessionalSummaryCard professional={professional} onOpen={onOpenProfessional} />
          </motion.div>
        )}

        {showEta ? (
          // §76/§79: while the professional is on the way this is the single most important
          // number on the screen, so it gets the §75 "important figure" weight.
          <motion.div className={styles.eta} variants={pageTransition} animate={itemAnimate}>
            <span className={styles.etaLabel}>הגעה משוערת</span>
            <span className={styles.etaValue} aria-live="polite">
              {isArriving ? 'מגיע/ה עכשיו' : `${remainingMinutes} דקות`}
            </span>
          </motion.div>
        ) : (
          content.support && (
            <motion.p className={styles.support} variants={pageTransition} animate={itemAnimate}>
              {content.support}
            </motion.p>
          )
        )}

        {action && (
          <motion.div className={styles.action} variants={pageTransition} animate={itemAnimate}>
            {action}
          </motion.div>
        )}
      </motion.div>
    </div>
  );
}

function describe(
  status: OrderStatus,
  professionalName: string,
  bookedLabel: string,
  hasProfessionalCard: boolean,
): HeroContent {
  switch (status) {
    case 'PENDING':
      return {
        // With the card on screen the name would be printed twice, once as a headline about a
        // person the customer can't see and once on the card that actually shows them.
        headline: hasProfessionalCard ? 'ממתינים לאישור בעל המקצוע' : `ממתינים לאישור של ${professionalName}`,
        support: `שמרנו לך את המועד ${bookedLabel}. נעדכן אותך ברגע שתתקבל תשובה.`,
        mascot: 'thinking',
        tone: 'neutral',
      };
    case 'CONFIRMED':
      return {
        headline: 'ההזמנה אושרה',
        support: `ההגעה מתוכננת ל${bookedLabel}.`,
        mascot: 'found',
        tone: 'positive',
      };
    case 'ON_THE_WAY':
      return {
        headline: `${professionalName} בדרך אליך`,
        mascot: 'running',
        tone: 'positive',
      };
    case 'ARRIVED':
      return {
        // Production MS2. Said as a fact rather than an estimate, because for the first time it
        // is one: the backend measured this person's position against the order's address before
        // writing the status. No countdown is shown -- there is nothing left to count.
        headline: `${professionalName} הגיע/ה`,
        support: 'בעל המקצוע נמצא בכתובת שלך.',
        mascot: 'found',
        tone: 'positive',
      };
    case 'COMPLETED':
      return {
        headline: 'העבודה הושלמה',
        support: `תודה שהזמנת דרך פרונטו. איך היה השירות של ${professionalName}?`,
        mascot: 'success',
        tone: 'positive',
      };
    case 'CANCELLED':
      return {
        headline: 'ההזמנה בוטלה',
        support: 'התקלה שלך עדיין פתוחה — אפשר לבחור בעל מקצוע אחר מתי שנוח.',
        mascot: 'idle',
        tone: 'ended',
      };
    case 'REJECTED':
      return {
        headline: 'הבקשה לא אושרה',
        support: `${professionalName} לא זמינים לעבודה הזו. התקלה שלך עדיין פתוחה, ויש עוד בעלי מקצוע זמינים באזור.`,
        mascot: 'idle',
        tone: 'ended',
      };
    case 'EXPIRED':
    default:
      // Reworded alongside the backend change that reopens the issue on expiry instead of
      // expiring it: "אפשר לפתוח בקשה חדשה" described a dead end that no longer exists, and
      // would now actively mislead — the customer's issue is intact and immediately re-bookable.
      return {
        headline: 'הבקשה פגה',
        support: `${professionalName} לא הגיבו בזמן, אז סגרנו את הבקשה. התקלה שלך עדיין פתוחה — אפשר לבחור בעל מקצוע אחר.`,
        mascot: 'idle',
        tone: 'ended',
      };
  }
}
