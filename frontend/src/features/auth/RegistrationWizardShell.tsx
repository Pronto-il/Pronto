import type { ReactNode } from 'react';
import { useMemo } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition, Transition, Variants } from 'framer-motion';
import { PageHeader, Button } from '../../shared/components';
import { stepTransition } from '../../shared/motion/variants';
import styles from './RegistrationWizardShell.module.css';

export interface RegistrationWizardShellProps {
  title: string;
  currentStage: number;
  totalStages: number;
  /** `1` when advancing forward, `-1` when going back — drives `stepTransition`'s slide direction. */
  direction: number;
  backLabel?: string;
  onBack: () => void;
  primaryLabel: string;
  primaryLoading?: boolean;
  /** Current stage's field group. Swapped via `AnimatePresence` + `stepTransition` on `currentStage` change. */
  children: ReactNode;
}

/**
 * Shared shell for the customer/professional registration wizards (design doc §6.1) — the
 * one new shared component this milestone introduces, justified by having two real,
 * simultaneous consumers (`CustomerRegisterForm`, `ProfessionalRegisterForm`).
 *
 * Owns: the `PageHeader` (title + `steps` progress track), the animated per-stage viewport,
 * and the back/primary footer button row. Does **not** own field state, per-field
 * validation, or the API call — the caller wraps this in its own `<form onSubmit=...>` (so
 * `Enter` naturally triggers whatever the current stage's primary action is, since the
 * primary button below is always `type="submit"`) and owns the actual stage content passed
 * as `children`. The caller decides what "primary" means per stage (validate-and-advance on
 * stage 1..N-1, real submit on the last stage) — this component just renders the button and
 * forwards its `loading` state.
 */
export function RegistrationWizardShell({
  title,
  currentStage,
  totalStages,
  direction,
  backLabel = 'חזרה',
  onBack,
  primaryLabel,
  primaryLoading = false,
  children,
}: RegistrationWizardShellProps) {
  const shouldReduceMotion = useReducedMotion();

  // `stepTransition`'s `animate`/`exit` targets each carry their own embedded `transition`
  // (a spring), which wins over a component-level `transition` prop — same pitfall as
  // `pageTransition`/`modalTransition` (see `HomePage.tsx`/`Modal.tsx`). Because `initial`
  // and `exit` here are functions of `direction` (not static objects), the neutralized
  // variant set is built the same way `Modal.tsx` builds its `panelVariants`: wrap each
  // function/object so the resolved target's `transition` is forced to `{ duration: 0 }`.
  const stageVariants: Variants = useMemo(() => {
    if (!shouldReduceMotion) {
      return stepTransition;
    }
    const instant: Transition = { duration: 0 };
    const animate = stepTransition.animate as TargetAndTransition;
    const initial = stepTransition.initial as (custom: number) => TargetAndTransition;
    const exit = stepTransition.exit as (custom: number) => TargetAndTransition;
    return {
      initial: (custom: number) => ({ ...initial(custom), transition: instant }),
      animate: { ...animate, transition: instant },
      exit: (custom: number) => ({ ...exit(custom), transition: instant }),
    };
  }, [shouldReduceMotion]);

  return (
    <div>
      <PageHeader title={title} steps={{ current: currentStage, total: totalStages }} />

      <div className={styles.stageViewport}>
        <AnimatePresence mode="wait" custom={direction}>
          <motion.div
            key={currentStage}
            custom={direction}
            variants={stageVariants}
            initial="initial"
            animate="animate"
            exit="exit"
            className={styles.stage}
          >
            {children}
          </motion.div>
        </AnimatePresence>
      </div>

      <div className={styles.footer}>
        <Button type="button" variant="secondary" onClick={onBack}>
          {backLabel}
        </Button>
        <Button type="submit" variant="primary" loading={primaryLoading}>
          {primaryLabel}
        </Button>
      </div>
    </div>
  );
}
