import { useNavigate } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { pageTransition } from '../../shared/motion/variants';
import { ProfessionalRegisterForm } from './ProfessionalRegisterForm';
import { useRegistrationLanding } from './useRegistrationLanding';

/**
 * Thin wrapper (design doc §6.1) — `RegistrationWizardShell` (rendered inside
 * `ProfessionalRegisterForm`) now owns the page header, so this page no longer renders its
 * own standalone `PageHeader`. `onExit` is used only by stage 1's back button; stage 2+'s
 * back button moves to the previous stage internally, not out of the flow.
 */
export default function ProfessionalRegisterPage() {
  const navigate = useNavigate();
  const landAfterRegistration = useRegistrationLanding();
  // See `HomePage.tsx`'s comment: `pageTransition.animate` carries its own embedded
  // `transition`, which wins over a `transition` prop — the `animate` target itself must be
  // overridden to actually neutralize the spring under reduced motion.
  const shouldReduceMotion = useReducedMotion();
  const pageAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  return (
    <motion.div className="focused-page" variants={pageTransition} initial="initial" animate={pageAnimate}>
      <ProfessionalRegisterForm
        onExit={() => navigate('/register')}
        // Where registration ends is the SERVER's answer, not this page's assumption: a
        // challenge goes to /verify, and an AUTHENTICATED response (verification switched off)
        // carries a real session that must be adopted rather than discarded. See
        // useRegistrationLanding for the failure that came from assuming.
        onSuccess={landAfterRegistration}
      />
    </motion.div>
  );
}
