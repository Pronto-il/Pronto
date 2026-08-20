import { useNavigate } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { Button, Mascot } from '../../shared/components';
import { listStagger, pageTransition } from '../../shared/motion/variants';
import styles from './BookingSuccessStep.module.css';

export interface BookingSuccessStepProps {
  /** Caller-owned heading — Standard/SOS flows currently share the same copy, but this
   *  component stays copy-agnostic (design doc §3.A3/§4 Q2) rather than taking a
   *  `variant: 'standard' | 'sos'` prop. */
  title: string;
  /** Caller-owned body text — already includes whatever dynamic detail (professional name)
   *  the caller needs; this component renders it verbatim. */
  body: string;
  /** The just-created order's id — feeds the "view order" CTA's destination. */
  orderId: number;
}

/**
 * Booking-flow success screen (design doc §3.A3), extracted from the near-identical
 * hand-rolled `successWrapper`/`successCheck`/... blocks previously duplicated in
 * `BookingFlowPage.tsx` and `SosBookingFlowPage.tsx`. Mirrors
 * `features/issues/IssueSuccessStep.tsx`'s structure — `Mascot state="success"` entrance +
 * `listStagger`-staggered heading/text/actions — rather than inventing a new pattern.
 */
export function BookingSuccessStep({ title, body, orderId }: BookingSuccessStepProps) {
  const navigate = useNavigate();

  // Same neutralization pattern `IssueSuccessStep.tsx` already uses for `listStagger`/
  // `pageTransition`'s reuse: the `animate` target itself must be overridden to neutralize
  // both the stagger orchestration and each item's own spring under reduced motion.
  const shouldReduceMotion = useReducedMotion();
  const containerAnimate = shouldReduceMotion
    ? { transition: { staggerChildren: 0, delayChildren: 0 } }
    : 'animate';
  const itemAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  return (
    <div className={styles.wrapper}>
      <Mascot state="success" size="xl" label={title} className={styles.mascotSize} />
      <motion.div className={styles.content} variants={listStagger} initial="initial" animate={containerAnimate}>
        <motion.h2 className={styles.title} variants={pageTransition} animate={itemAnimate}>
          {title}
        </motion.h2>
        <motion.p className={styles.text} variants={pageTransition} animate={itemAnimate}>
          {body}
        </motion.p>
        <motion.div className={styles.actions} variants={pageTransition} animate={itemAnimate}>
          <Button onClick={() => navigate(`/orders/${orderId}`)} fullWidth>
            צפייה בהזמנה
          </Button>
          <Button variant="secondary" onClick={() => navigate('/')} fullWidth>
            חזרה לדף הבית
          </Button>
        </motion.div>
      </motion.div>
    </div>
  );
}
