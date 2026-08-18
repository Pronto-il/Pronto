import { NavLink, Outlet } from 'react-router-dom';
import { PageHeader } from '../../shared/components';
import styles from './ProDashboardLayout.module.css';

/**
 * Shared shell for the professional dashboard's four routes (`/pro`, `/pro/jobs`,
 * `/pro/availability`, `/pro/profile`) — a simple tab layout per this milestone's brief,
 * deliberately not the full multi-item sidebar from DESIGN_SYSTEM.md §53 (ביקורות/הגדרות
 * etc. still have no screens — no dead nav links, FRONTEND_AGENT.md §29). "העבודות שלי"
 * (`/pro/jobs`) was added as a bug fix (QA-found gap: once an order leaves the pending feed
 * there was no in-app way to see it again) — read-only order list, not a new feature area.
 * "פרופיל" (`/pro/profile`, Frontend Milestone 8) extends this same established tab pattern
 * to the professional's own business-listing profile editor (`ProfileEditorPage`) —
 * `DESIGN_SYSTEM.md` §53's own mockup already lists `▢ פרופיל` alongside the other tabs.
 */
export default function ProDashboardLayout() {
  return (
    <div className="page-container">
      <PageHeader title="לוח בקרה לבעלי מקצוע" />
      <div className={styles.wrapper}>
        <nav className={styles.tabs}>
          <NavLink to="/pro" end className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}>
            בקשות חדשות
          </NavLink>
          <NavLink to="/pro/jobs" className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}>
            העבודות שלי
          </NavLink>
          <NavLink
            to="/pro/availability"
            className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}
          >
            יומן זמינות
          </NavLink>
          <NavLink to="/pro/profile" className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}>
            פרופיל
          </NavLink>
        </nav>
        <Outlet />
      </div>
    </div>
  );
}
