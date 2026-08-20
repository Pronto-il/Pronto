import { useMemo } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition, Transition, Variants } from 'framer-motion';
import { Mascot } from '../../shared/components';
import { modalTransition } from '../../shared/motion/variants';
import styles from './AiAnalyzingOverlay.module.css';

export interface AiAnalyzingOverlayProps {
  show: boolean;
  /** 'overlay' = absolutely positioned over a still-mounted step (in-session analyzing,
   *  `DescribeIssueStep`/`ClarifyQuestionsStep`'s `classifyIssue` calls).
   *  'inline' = the only content on screen (resume-time re-derivation of the AI result while
   *  nothing is mounted underneath yet — `NewIssuePage`'s `isResuming` gate). */
  variant: 'overlay' | 'inline';
}

/**
 * Branded loading state for the two real `classifyIssue` call sites (design doc §2) — Pronto
 * "thinking" rather than a bare button spinner. Deliberately not a `Step`-union member; see
 * `NewIssuePage.tsx`'s own doc comment and the design doc §2.2 for why this renders as a
 * sibling over the still-mounted step instead of unmounting it.
 *
 * Own internal `AnimatePresence`, independent of `NewIssuePage`'s step-to-step
 * `AnimatePresence` (§2.5) — this component's visibility is driven purely by `show`
 * (`isAnalyzing`/`isResuming`), not by which step is current.
 */
export function AiAnalyzingOverlay({ show, variant }: AiAnalyzingOverlayProps) {
  const shouldReduceMotion = useReducedMotion();

  // Same neutralization pattern `RegistrationWizardShell.tsx` already applies to
  // `stepTransition` — `modalTransition`'s `animate`/`exit` targets each carry their own
  // embedded spring `transition`, which wins over a component-level `transition` prop, so the
  // reduced-motion variant set has to override it per-target rather than at the `motion.div`.
  const overlayVariants: Variants = useMemo(() => {
    if (!shouldReduceMotion) {
      return modalTransition;
    }
    const instant: Transition = { duration: 0 };
    const animate = modalTransition.animate as TargetAndTransition;
    const initial = modalTransition.initial as (custom: boolean) => TargetAndTransition;
    const exit = modalTransition.exit as (custom: boolean) => TargetAndTransition;
    return {
      initial: (custom: boolean) => ({ ...initial(custom), transition: instant }),
      animate: { ...animate, transition: instant },
      exit: (custom: boolean) => ({ ...exit(custom), transition: instant }),
    };
  }, [shouldReduceMotion]);

  return (
    <AnimatePresence>
      {show && (
        <motion.div
          className={variant === 'overlay' ? styles.overlay : styles.inline}
          // `false` = modalTransition's non-mobile (scale+fade) branch, reused directly per
          // design doc §2.3 — mobile sizing here is handled independently, via the mascot's
          // own CSS custom-property override below, not via modalTransition's isMobile shape.
          custom={false}
          variants={overlayVariants}
          initial="initial"
          animate="animate"
          exit="exit"
        >
          <Mascot state="thinking" size="lg" loop className={styles.mascotSize} />
          <div className={styles.status} role="status" aria-live="polite">
            <span>Pronto בודק את התקלה...</span>
            <span className={styles.dots} aria-hidden="true">
              <span />
              <span />
              <span />
            </span>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
