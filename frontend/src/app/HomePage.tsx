import { Link } from 'react-router-dom';
import styles from './HomePage.module.css';

/**
 * Customer home entry point. Milestone 2 adds the single primary CTA into the issue-report
 * flow (DESIGN_SYSTEM.md §35-36) — `/issues/new` is customer-gated by `RequireAuth`, so an
 * unauthenticated click redirects to login same as any other guarded route. The fuller home
 * screen (popular-service grid, active-booking card) is later milestones' scope — not built
 * ahead of the features that would back it.
 */
export default function HomePage() {
  return (
    <div className="page-container">
      <div className={styles.hero}>
        <h1 className={styles.title}>איך אפשר לעזור היום?</h1>
        <Link to="/issues/new" className={styles.cta}>
          <span className={styles.ctaTitle}>יש לי תקלה</span>
          <span className={styles.ctaSubtitle}>בוא נמצא את האדם המתאים</span>
        </Link>
      </div>
    </div>
  )
}
