import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { Home, Wrench, ArrowLeft } from 'lucide-react';
import { Card, Badge } from '../../shared/components';
import { listStagger, pageTransition } from '../../shared/motion/variants';
import styles from './RoleChooser.module.css';

interface RoleOption {
  to: string;
  icon: ReactNode;
  title: string;
  subcopy: string;
  tags: [string, string];
  tint: 'primary' | 'neutral';
}

/**
 * Local, single-consumer config (design doc §5.6) — not a new shared component, per §1.3's
 * reuse discipline. Gives each option a genuinely distinct visual personality (icon, tint,
 * tags, subcopy) instead of two identical `Card`s differentiated only by text.
 */
const ROLE_OPTIONS: RoleOption[] = [
  {
    to: '/register/customer',
    icon: <Home size={32} aria-hidden="true" />,
    title: 'אני צריך עזרה בבית',
    subcopy: 'מחפש/ת בעל מקצוע אמין ומהיר לתיקון בבית',
    tags: ['בעלי מקצוע מאומתים', 'מחיר ברור'],
    tint: 'primary',
  },
  {
    to: '/register/professional',
    icon: <Wrench size={32} aria-hidden="true" />,
    title: 'אני בעל מקצוע',
    subcopy: 'רוצה לקבל פניות איכותיות מלקוחות באזור שלך',
    tags: ['פניות איכותיות', 'בלי דמי הרשמה'],
    tint: 'neutral',
  },
];

/**
 * "אני לקוח / אני בעל מקצוע" chooser — two distinct destinations
 * (`/register/customer`, `/register/professional`), not a toggle on a shared form.
 */
export function RoleChooser() {
  // See `HomePage.tsx`'s comment: a variant's own target-embedded `transition` wins over a
  // `transition` prop, so the `animate` target itself must be overridden to actually
  // neutralize motion under reduced motion — both the container's stagger orchestration and
  // each card's own fade-in.
  const shouldReduceMotion = useReducedMotion();
  const gridAnimate = shouldReduceMotion ? { transition: { staggerChildren: 0, delayChildren: 0 } } : 'animate';
  const cardAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  return (
    <motion.div className={styles.grid} variants={listStagger} initial="initial" animate={gridAnimate}>
      {ROLE_OPTIONS.map((option) => (
        <motion.div key={option.to} variants={pageTransition} animate={cardAnimate}>
          <Link to={option.to} className={styles.cardLink}>
            <Card interactive className={`${styles.card} ${styles[option.tint]}`}>
              <span className={styles.icon} aria-hidden="true">
                {option.icon}
              </span>
              <h2 className={styles.title}>{option.title}</h2>
              <p className={styles.subcopy}>{option.subcopy}</p>
              <div className={styles.tags}>
                {option.tags.map((tag) => (
                  <Badge key={tag} size="sm" tone={option.tint === 'primary' ? 'primary' : 'neutral'}>
                    {tag}
                  </Badge>
                ))}
              </div>
              <span className={styles.arrow}>
                בחירה
                <ArrowLeft size={16} aria-hidden="true" />
              </span>
            </Card>
          </Link>
        </motion.div>
      ))}
    </motion.div>
  );
}
