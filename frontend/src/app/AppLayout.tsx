import { Link, Outlet } from 'react-router-dom';
import { LogOut, User, ClipboardList, LayoutDashboard } from 'lucide-react';
import { useAuth } from '../shared/hooks';
import { BookingDraftIndicator } from './BookingDraftIndicator';
import { ActiveOrderIndicator } from './ActiveOrderIndicator';
import { BottomNav } from './BottomNav';
import { NotificationBell } from '../features/notifications';
import logoUrl from '../assets/pronto-logo.jpg';
import styles from './AppLayout.module.css';

/**
 * Application shell: a desktop top nav bar (DESIGN_SYSTEM.md §52) with just enough content
 * to reach the auth screens (login/register) or the authenticated profile. Frontend
 * Milestone 3 adds the first two real primary-nav destinations now that they exist as
 * real screens — a customer's "ההזמנות שלי" (`/orders`) and a professional's own dashboard
 * link (`/pro`) — per that same section, once a destination is real (not a placeholder)
 * it belongs in nav (FRONTEND_AGENT.md §29 only warns against nav links to pages that
 * don't exist yet). Frontend Milestone 5 adds `NotificationBell` right after
 * `BookingDraftIndicator` and before the role-conditional link — rendered for *both* roles
 * (unlike `ActiveOrderIndicator` below, which is CUSTOMER-only), since `GET
 /api/notifications` is an either-role, self-scoped feed. Frontend Milestone 8 adds
 * `/favorites` and a professional-profile editor — `/favorites` is deliberately not a
 * primary-nav destination (approved UX decision, `frontend-ms8-design.md` §2.1 revision):
 * it lives under "הפרופיל שלי" -> "מועדפים" (`ProfilePage.tsx`) as a secondary customer
 * feature, not a top-nav link.
 *
 * **MS2 header/nav redesign (design doc §3)**: desktop gets a bigger brand + an icon-only,
 * demoted logout (`.logoutButton` — no visible "יציאה" label, `aria-label`/`title` carry the
 * meaning). `.desktopOnlyNav` wraps "ההזמנות שלי"/"לוח בקרה"/"הפרופיל שלי" —
 * `display: contents` on desktop (so it doesn't affect the flex layout) and `display: none`
 * under 640px, since those destinations move to the new `<BottomNav>` (`CUSTOMER`-only,
 * authenticated-only — same gating condition as `<ActiveOrderIndicator>` below) or stay
 * reachable via `/favorites`/`/profile` directly. `BookingDraftIndicator`/`NotificationBell`
 * stay visible at every width — they're already compact, icon-forward components.
 * `/favorites` is deliberately not a primary-nav destination on desktop either (see the
 * paragraph above) but *is* one of `BottomNav`'s 4 mobile items — DESIGN_SYSTEM.md §50's
 * explicit list, see `BottomNav.tsx`'s own doc comment.
 *
 * **Mobile-logout follow-up fix (post-MS2)**: `.logoutButton` deliberately renders as a
 * sibling *after* `.desktopOnlyNav` closes, not inside it, so it stays visible at every
 * viewport width for every authenticated user regardless of role. It was originally placed
 * inside `.desktopOnlyNav` alongside the profile link, which meant it vanished under 640px
 * — since `<BottomNav>` is `CUSTOMER`-only and `ProDashboardLayout`'s own mobile tab bar has
 * no logout entry, a `PROFESSIONAL` session on mobile had no nav-reachable way to log out at
 * all. Moving the button outside `.desktopOnlyNav` doesn't change desktop layout, since
 * `display: contents` never introduced a box for it to begin with — the button was always a
 * flex child of `.nav` directly, regardless of which element's JSX it's nested under.
 */
export default function AppLayout() {
  const { user, logout } = useAuth();

  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <div className={styles.headerInner}>
          <Link to="/" className={styles.brand} aria-label="Pronto">
            <span className={styles.logo} style={{ backgroundImage: `url(${logoUrl})` }} />
          </Link>
          <nav className={styles.nav}>
            {user ? (
              <>
                <BookingDraftIndicator />
                <NotificationBell />
                <div className={styles.desktopOnlyNav}>
                  {user.role === 'CUSTOMER' && (
                    <Link to="/orders" className={styles.navLink}>
                      <ClipboardList size={18} aria-hidden="true" />
                      <span>ההזמנות שלי</span>
                    </Link>
                  )}
                  {user.role === 'PROFESSIONAL' && (
                    <Link to="/pro" className={styles.navLink}>
                      <LayoutDashboard size={18} aria-hidden="true" />
                      <span>לוח בקרה</span>
                    </Link>
                  )}
                  <Link to="/profile" className={styles.navLink}>
                    <User size={18} aria-hidden="true" />
                    <span>הפרופיל שלי</span>
                  </Link>
                </div>
                <button
                  type="button"
                  className={styles.logoutButton}
                  onClick={logout}
                  aria-label="יציאה מהחשבון"
                  title="יציאה מהחשבון"
                >
                  <LogOut size={18} aria-hidden="true" />
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className={styles.navLink}>
                  התחברות
                </Link>
                <Link to="/register" className={styles.navLinkPrimary}>
                  הרשמה
                </Link>
              </>
            )}
          </nav>
        </div>
      </header>
      <main className={styles.main}>
        <Outlet />
      </main>
      {user?.role === 'CUSTOMER' && <ActiveOrderIndicator />}
      {user?.role === 'CUSTOMER' && <BottomNav />}
    </div>
  );
}
