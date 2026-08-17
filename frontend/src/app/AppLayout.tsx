import { Link, Outlet } from 'react-router-dom';
import { LogOut, User, ClipboardList, LayoutDashboard } from 'lucide-react';
import { useAuth } from '../shared/hooks';
import logoUrl from '../assets/pronto-logo.jpg';
import styles from './AppLayout.module.css';

/**
 * Application shell: a desktop top nav bar (DESIGN_SYSTEM.md §52) with just enough content
 * to reach the auth screens (login/register) or the authenticated profile. Frontend
 * Milestone 3 adds the first two real primary-nav destinations now that they exist as
 * real screens — a customer's "ההזמנות שלי" (`/orders`) and a professional's own dashboard
 * link (`/pro`) — per that same section, once a destination is real (not a placeholder)
 * it belongs in nav (FRONTEND_AGENT.md §29 only warns against nav links to pages that
 * don't exist yet). Favorites/full bottom nav (§50-51) still don't have a backing screen
 * and are not added.
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
                <button type="button" className={styles.logoutButton} onClick={logout}>
                  <LogOut size={18} aria-hidden="true" />
                  <span>יציאה</span>
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
    </div>
  );
}
