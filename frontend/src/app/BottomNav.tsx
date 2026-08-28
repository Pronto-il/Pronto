import { Link, NavLink } from 'react-router-dom';
import { Home, ClipboardList, Heart, User, Wrench } from 'lucide-react';
import styles from './BottomNav.module.css';

/**
 * The four tab destinations, in RTL *reading* order — בית first, פרופיל last. The bar lays out
 * with `direction: rtl` inherited from the document, so the first item renders at the
 * inline-start (right) edge. The new-issue action is not in this list; it is the elevated centre
 * button rendered between the second and third tabs (see below).
 */
const LEADING_ITEMS = [
  { to: '/', label: 'בית', Icon: Home, end: true },
  { to: '/orders', label: 'הזמנות', Icon: ClipboardList, end: false },
] as const;

const TRAILING_ITEMS = [
  { to: '/favorites', label: 'מועדפים', Icon: Heart, end: false },
  { to: '/profile', label: 'פרופיל', Icon: User, end: false },
] as const;

/**
 * Mobile-only primary nav, `CUSTOMER`-only + authenticated-only — `AppLayout` mounts this with
 * the same gating condition it uses for `<ActiveIssueToolbox>`.
 *
 * **Redesign §4:** the "start a new issue" action moves here, as a prominent circular centre
 * button, giving the layout the familiar `בית | הזמנות | [ תקלה חדשה ] | מועדפים | פרופיל`
 * shape. This is the single persistent entry point for a new issue on mobile — it replaces the
 * oversized header banner that `AppLayout` used to render (§3), and it reuses the exact same
 * destination that banner used, `/issues/new` (§6), so there is one flow, not two.
 *
 * The centre action is a `Link`, not a `NavLink`: "start something new" is not a place you can
 * be *at*, so an `aria-current` active state would be meaningless. The four tabs stay `NavLink`s
 * for their automatic `aria-current="page"`. `end` is set only on `/` so `/orders/123` still
 * lights up "הזמנות".
 *
 * Notifications deliberately remain a top-bar bell rather than a tab: `NotificationBell` is a
 * working popover pattern a tab would duplicate.
 */
export function BottomNav() {
  return (
    <nav className={styles.bar} aria-label="ניווט ראשי">
      {LEADING_ITEMS.map(({ to, label, Icon, end }) => (
        <NavLink
          key={to}
          to={to}
          end={end}
          className={({ isActive }) => `${styles.item} ${isActive ? styles.active : ''}`}
        >
          <Icon size={22} aria-hidden="true" />
          <span className={styles.label}>{label}</span>
        </NavLink>
      ))}

      <Link to="/issues/new" className={styles.centerAction} aria-label="תקלה חדשה">
        <span className={styles.centerCircle}>
          <Wrench size={24} aria-hidden="true" />
        </span>
        <span className={styles.centerLabel}>תקלה חדשה</span>
      </Link>

      {TRAILING_ITEMS.map(({ to, label, Icon, end }) => (
        <NavLink
          key={to}
          to={to}
          end={end}
          className={({ isActive }) => `${styles.item} ${isActive ? styles.active : ''}`}
        >
          <Icon size={22} aria-hidden="true" />
          <span className={styles.label}>{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
