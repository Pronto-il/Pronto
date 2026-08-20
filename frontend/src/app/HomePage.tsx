import { Link } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import type { TargetAndTransition } from 'framer-motion';
import { ShieldCheck, Tag, Activity } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { Mascot } from '../shared/components';
import { useAuth } from '../shared/hooks';
import { pageTransition, mascotSlideIn } from '../shared/motion/variants';
import styles from './HomePage.module.css';

interface TrustItem {
  Icon: LucideIcon;
  label: string;
}

/**
 * Local, single-consumer content (design doc §2.6, §1.3's reuse discipline — not a new
 * shared component). Wording deliberately avoids "מעקב בזמן אמת" (live tracking) —
 * GPS/live-location tracking is a hard v1.0 exclusion (`overview.md` §2); the product's real
 * real-time feature is polling-based order *status* updates, which "עדכוני סטטוס בזמן אמת"
 * accurately describes.
 */
const TRUST_ITEMS: TrustItem[] = [
  { Icon: ShieldCheck, label: 'בעלי מקצוע מאומתים' },
  { Icon: Tag, label: 'מחיר ברור מראש' },
  { Icon: Activity, label: 'עדכוני סטטוס בזמן אמת' },
];

/**
 * Home Page Hero (design doc §2) — greeting/headline/CTA-with-inline-mascot/trust-row.
 * Deliberately scoped to just the hero: DESIGN_SYSTEM.md §35's full Home hierarchy also
 * lists "Popular services"/"Active booking" below it — not built this milestone (§2.7):
 * active booking is already served by the product-wide `ActiveOrderIndicator`, and popular
 * services needs new product decisions (which categories, a `ServiceCategoryCard` with
 * per-category icons) this milestone wasn't asked to make.
 *
 * Reachable by any role/guest, unchanged — a `PROFESSIONAL` who navigates to `/` manually
 * still sees this customer-facing hero (`login()` already routes them to `/pro`; nothing
 * gates `/` itself by role). Pre-existing gap, not fixed here (design doc §7 item 4).
 */
export default function HomePage() {
  const { user } = useAuth();
  const firstName = user?.fullName?.trim().split(/\s+/)[0];
  // A plain `transition` prop override does NOT win against a variant's own
  // target-embedded `transition` (both `pageTransition.animate` and `mascotSlideIn.animate`
  // define one) — framer-motion gives the variant-level transition precedence. The
  // component-level `transition` prop only applies when the variant target itself has no
  // `transition` of its own, so the working fix (matching `Modal.tsx`/`ToastViewport.tsx`'s
  // actual behavior, confirmed live) is to override the `animate` target object itself when
  // reduced motion is on, rather than passing `transition` alongside `variants`.
  const shouldReduceMotion = useReducedMotion();
  const heroAnimate = shouldReduceMotion
    ? { ...(pageTransition.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';
  const mascotAnimate = shouldReduceMotion
    ? { ...(mascotSlideIn.animate as TargetAndTransition), transition: { duration: 0 } }
    : 'animate';

  return (
    <div className="page-container">
      <motion.section className={styles.hero} variants={pageTransition} initial="initial" animate={heroAnimate}>
        <div className={styles.heroContent}>
          {firstName && <p className={styles.greeting}>שלום, {firstName} 👋</p>}
          <h1 className={styles.title}>איך אפשר לעזור היום?</h1>
          <p className={styles.description}>
            ספר לנו מה קרה, ו-Pronto יעזור לך למצוא את בעל המקצוע המתאים.
          </p>

          <Link to="/issues/new" className={styles.cta}>
            <div className={styles.ctaText}>
              <span className={styles.ctaTitle}>יש לי תקלה</span>
              <span className={styles.ctaSubtitle}>בוא נמצא את האדם המתאים</span>
              <span className={styles.ctaArrow} aria-hidden="true">
                ←
              </span>
            </div>
            <motion.div variants={mascotSlideIn} initial="initial" animate={mascotAnimate} aria-hidden="true">
              <Mascot state="running" size="lg" loop className={styles.mascotArea} />
            </motion.div>
          </Link>

          <div className={styles.trustRow}>
            {TRUST_ITEMS.map(({ Icon, label }) => (
              <span className={styles.trustItem} key={label}>
                <Icon size={16} aria-hidden="true" />
                {label}
              </span>
            ))}
          </div>
        </div>
      </motion.section>
    </div>
  );
}
