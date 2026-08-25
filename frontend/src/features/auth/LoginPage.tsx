import { Link, useSearchParams } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { Mascot, PageHeader } from '../../shared/components';
import { pageTransition } from '../../shared/motion/variants';
import { LoginForm } from './LoginForm';
import styles from './formStyles.module.css';

export default function LoginPage() {
  const [searchParams] = useSearchParams();
  // Production MS1: the login field takes an email address OR a phone number, so the
  // prefill parameter is named for what it now is. `?email=` is still honoured, because links
  // built before this milestone are still in people's inboxes.
  const identifier = searchParams.get('identifier') ?? searchParams.get('email') ?? '';
  // See `HomePage.tsx`'s comment: `pageTransition.animate` carries its own embedded
  // `transition`, which wins over a `transition` prop — the `animate` target itself must be
  // overridden to actually neutralize the spring under reduced motion.
  const shouldReduceMotion = useReducedMotion();
  const pageAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  return (
    <motion.div className="focused-page" variants={pageTransition} initial="initial" animate={pageAnimate}>
      <div className={styles.loginMascot}>
        <Mascot state="idle" size="sm" />
      </div>
      <PageHeader title="התחברות ל-Pronto" description="שמחים לראות אתכם שוב" />
      <LoginForm initialIdentifier={identifier} />
      <p className={styles.footerLink}>
        אין לכם חשבון? <Link to="/register">הרשמה</Link>
      </p>
    </motion.div>
  );
}
