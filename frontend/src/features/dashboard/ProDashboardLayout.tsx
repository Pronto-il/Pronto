import { NavLink, Outlet } from 'react-router-dom';
import { Inbox, ClipboardList, CalendarDays, Siren, User } from 'lucide-react';
import { Badge } from '../../shared/components';
import { PendingRequestsProvider, ProSosProvider, useProSos, usePendingRequests } from '../../shared/hooks';
import { OnboardingStatusNotice } from './OnboardingStatusNotice';
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
 *
 * **Pronto SOS professional frontend, MS2 (2026-08-21)**: a fifth tab, `/pro/sos`, plus
 * `ProSosProvider` mounted for the same reason and at the same scope as `PendingRequestsProvider`
 * — the SOS count badge below and the `/pro/sos` screen share one poll of `GET /api/sos/offers`
 * and one `/user/queue/sos` subscription rather than each opening their own. Mounting it *here*
 * rather than on the SOS route is what makes discovery work: an SOS offer has a ~2-minute window,
 * so a professional sitting on the availability calendar has to learn about it without navigating
 * anywhere — the provider raises the toast and lights the badge from wherever they are inside
 * `/pro/*`. It is deliberately not in `App.tsx`: none of this concerns a customer session.
 */
export default function ProDashboardLayout() {
  return (
    <PendingRequestsProvider>
      <ProSosProvider>
        <div className="page-container">
          {/* No page title. "לוח בקרה לבעלי מקצוע" was a full-width `PageHeader` above the nav —
              an internal-dashboard label that named the shell rather than the screen, and said
              nothing the tab strip below it (and the "לוח בקרה" nav link that got you here)
              doesn't already say. The nav *is* the context; each screen keeps its own section
              headings. `.wrapper` now owns the block spacing the header's margin used to
              provide — `.page-container` has inline padding only. */}
          <div className={styles.wrapper}>
            <NavTabs />
            <div className={styles.content}>
              {/* MS1 (D5): rendered above every `/pro/*` screen, not just the profile/
                  availability pages — a professional who is no longer bookable is most likely
                  to notice it on the screen where the work isn't arriving. Renders nothing at
                  all once the account is eligible. */}
              <OnboardingStatusNotice />
              <Outlet />
            </div>
          </div>
        </div>
      </ProSosProvider>
    </PendingRequestsProvider>
  );
}

/** Extracted so it can call `usePendingRequests()`/`useProSos()` for the two count badges, nested
 *  inside both providers above. */
function NavTabs() {
  const { count } = usePendingRequests();
  const { attentionCount: sosCount } = useProSos();

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
      {/* `tone="error"` rather than `primary`: this is the one tab with a two-minute clock behind
          it, and it has to out-rank the pending-orders badge sitting directly above it. */}
      <NavLink to="/pro/sos" className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}>
        <Siren size={18} aria-hidden="true" className={styles.tabIcon} />
        {/* Two labels, one shown per breakpoint. A fifth tab pushed the mobile strip past a
            430px viewport, which left "פרופיל" clipped off the edge — the strip scrolls, so
            nothing was unreachable, but a half-visible tab is a discoverability regression for
            the tab this milestone didn't touch. "SOS" is unambiguous on its own; the descriptive
            label stays on the desktop sidebar, which has the room. */}
        <span className={styles.labelWide}>קריאות SOS</span>
        <span className={styles.labelNarrow}>SOS</span>
        {sosCount > 0 && (
          <Badge tone="error" size="sm" className={styles.tabBadge}>
            {sosCount}
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
