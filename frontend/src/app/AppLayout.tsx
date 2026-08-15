import { Link, Outlet } from 'react-router-dom';
import { LogOut, User } from 'lucide-react';
import { useAuth } from '../shared/hooks';
import styles from './AppLayout.module.css';

/**
 * Minimal application shell for Milestone 1: a desktop top nav bar (DESIGN_SYSTEM.md
 * §52) with just enough content to reach the auth screens (login/register) or the
 * authenticated profile. A real primary nav (home/bookings/favorites/profile) and mobile
 * bottom nav (§50-51) land once those destinations actually exist — building them now
 * would just be dead links.
 */
export default function AppLayout() {
  const { user, logout } = useAuth();

  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <div className={styles.headerInner}>
          <Link to="/" className={styles.brand}>
            Pronto
          </Link>
          <nav className={styles.nav}>
            {user ? (
              <>
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
