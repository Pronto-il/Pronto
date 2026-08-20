import { useNavigate } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { Button, Mascot } from '../../shared/components';
import type { IssueUrgencyType } from '../../shared/api';
import { listStagger, pageTransition } from '../../shared/motion/variants';
import styles from './IssueSuccessStep.module.css';

export interface IssueSuccessStepProps {
  issueId: number;
  urgencyType: IssueUrgencyType;
}

/**
 * Calm confirmation state (DESIGN_SYSTEM.md §78) after `POST /api/issues` succeeds.
 * Frontend Milestone 3 hand-off: a `STANDARD` issue routes into `features/booking`'s
 * `/issues/:issueId/booking`. Frontend Milestone 4 adds the `SOS` hand-off: an `SOS` issue
 * routes into `features/booking`'s `/issues/:issueId/sos-booking` the same way — urgent
 * professional matching is now built.
 *
 * The CTA below stays a required, explicit user action — no auto-navigation (design doc
 * §6.2). `Mascot`'s `successPop` entrance fires automatically for `state="success"` (already
 * built into `Mascot.tsx`); the headline/body/actions settle in just after via `listStagger`,
 * a coordinated reveal rather than everything appearing at once.
 */
export function IssueSuccessStep({ issueId, urgencyType }: IssueSuccessStepProps) {
  const navigate = useNavigate();
  const isStandard = urgencyType === 'STANDARD';

  // Same pattern `RoleChooser.tsx` already uses for `listStagger`/`pageTransition`'s reuse:
  // the `animate` target itself must be overridden to neutralize both the stagger
  // orchestration and each item's own spring under reduced motion.
  const shouldReduceMotion = useReducedMotion();
  const containerAnimate = shouldReduceMotion
    ? { transition: { staggerChildren: 0, delayChildren: 0 } }
    : 'animate';
  const itemAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  return (
    <div className={styles.wrapper}>
      <Mascot state="success" size="xl" label="הבקשה נשלחה בהצלחה" className={styles.mascotSize} />
      <motion.div className={styles.content} variants={listStagger} initial="initial" animate={containerAnimate}>
        <motion.h2 className={styles.title} variants={pageTransition} animate={itemAnimate}>
          הבנתי. עכשיו נמצא לך מישהו.
        </motion.h2>
        <motion.p className={styles.text} variants={pageTransition} animate={itemAnimate}>
          {isStandard
            ? 'קיבלנו את הפרטים. עכשיו אפשר לבחור בעל מקצוע ותור שמתאים לכם.'
            : 'קיבלנו את הבקשה הדחופה שלכם. עכשיו אפשר לחפש בעל מקצוע זמין לעבודה דחופה.'}
        </motion.p>
        <motion.div className={styles.actions} variants={pageTransition} animate={itemAnimate}>
          <Button
            onClick={() => navigate(isStandard ? `/issues/${issueId}/booking` : `/issues/${issueId}/sos-booking`)}
            fullWidth
          >
            {isStandard ? 'בחירת בעל מקצוע' : 'חיפוש בעל מקצוע זמין'}
          </Button>
          <Button variant="secondary" onClick={() => navigate('/')} fullWidth>
            חזרה לדף הבית
          </Button>
        </motion.div>
      </motion.div>
    </div>
  );
}
