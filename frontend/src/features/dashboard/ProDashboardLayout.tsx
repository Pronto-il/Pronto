import { NavLink, Outlet } from 'react-router-dom';
import { Inbox, ClipboardList, CalendarDays, User } from 'lucide-react';
import { PageHeader, Badge } from '../../shared/components';
import { PendingRequestsProvider, usePendingRequests } from '../../shared/hooks';
import styles from './ProDashboardLayout.module.css';

/**
 * Shared shell for the professional dashboard's four routes (`/pro/requests`, `/pro/jobs`,
 * `/pro/availability`, `/pro/profile`). `/pro` itself is a redirect to `/pro/availability`
 * (`app/router.tsx`) — the availability calendar is the professional's home screen after
 * login, per `docs/architecture/product-ms9-dashboard-home-design.md` §1. At >=640px this
 * renders as a sidebar on the RTL inline-start edge (the physical right — see that design
 * doc's §2 for why); at <640px it stays the original horizontal tab bar
 * (`DESIGN_SYSTEM.md` §54's mobile pattern), deliberately not the full multi-item sidebar
 * from `DESIGN_SYSTEM.md` §53 (ביקורות/הגדרות etc. still have no screens — no dead nav
 * links, `FRONTEND_AGENT.md` §29). "העבודות שלי" (`/pro/jobs`) was added as a bug fix
 * (QA-found gap: once an order leaves the pending feed there was no in-app way to see it
 * again) — read-only order list, not a new feature area. "פרופיל" (`/pro/profile`, Frontend
 * Milestone 8) extends this same established nav pattern to the professional's own
 * business-listing profile editor (`ProfileEditorPage`) — `DESIGN_SYSTEM.md` §53's own
 * mockup already lists `▢ פרופיל` alongside the other tabs.
 *
 * **MS6 Professional Command Center (design doc §1.3/§3.3)**: mounts `PendingRequestsProvider`
 * around both the nav and `<Outlet />` so the sidebar's pending-count `Badge` (`NavTabs` below)
 * and `WeeklyAvailabilityPage`'s `CommandCenterBanner` share one poll of
 * `GET /api/bookings/orders/me?status=PENDING`, scoped to this subtree only (not app-wide).
 */
export default function ProDashboardLayout() {
  return (
    <PendingRequestsProvider>
      <div className="page-container">
        <PageHeader title="לוח בקרה לבעלי מקצוע" />
        <div className={styles.wrapper}>
          <NavTabs />
          <div className={styles.content}>
            <Outlet />
          </div>
        </div>
      </div>
    </PendingRequestsProvider>
  );
}

/** Extracted so it can call `usePendingRequests()` for the "בקשות חדשות" count badge, nested
 *  inside `PendingRequestsProvider` above. */
function NavTabs() {
  const { count } = usePendingRequests();

  return (
    <nav className={styles.tabs}>
      <NavLink to="/pro/requests" className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}>
        <Inbox size={18} aria-hidden="true" className={styles.tabIcon} />
        <span>בקשות חדשות</span>
        {count > 0 && (
          <Badge tone="primary" size="sm" className={styles.tabBadge}>
            {count}
          </Badge>
        )}
      </NavLink>
      <NavLink to="/pro/jobs" className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}>
        <ClipboardList size={18} aria-hidden="true" className={styles.tabIcon} />
        <span>העבודות שלי</span>
      </NavLink>
      <NavLink to="/pro/availability" className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}>
        <CalendarDays size={18} aria-hidden="true" className={styles.tabIcon} />
        <span>יומן זמינות</span>
      </NavLink>
      <NavLink to="/pro/profile" className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}>
        <User size={18} aria-hidden="true" className={styles.tabIcon} />
        <span>פרופיל</span>
      </NavLink>
    </nav>
  );
}
