import { Link } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { PageHeader } from '../../shared/components';
import { pageTransition } from '../../shared/motion/variants';
import { RoleChooser } from './RoleChooser';
import styles from './formStyles.module.css';

export default function RegisterChoicePage() {
  // See `HomePage.tsx`'s comment: `pageTransition.animate` carries its own embedded
  // `transition`, which wins over a `transition` prop — the `animate` target itself must be
  // overridden to actually neutralize the spring under reduced motion.
  const shouldReduceMotion = useReducedMotion();
  const pageAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  return (
    <motion.div className="focused-page" variants={pageTransition} initial="initial" animate={pageAnimate}>
      <PageHeader title="הרשמה ל-Pronto" description="איך תרצו להצטרף?" />
      <RoleChooser />
      <p className={styles.footerLink}>
        כבר יש לך חשבון? <Link to="/login">התחברות</Link>
      </p>
    </motion.div>
  );
}
