import { NavLink } from 'react-router-dom';
import { Home, ClipboardList, Heart, User } from 'lucide-react';
import styles from './BottomNav.module.css';

/**
 * Source order is the RTL *reading* order — בית first, פרופיל last. The bar lays out with
 * `direction: rtl` inherited from the document, so the first item renders at the inline-start
 * (right) edge, which is where "בית" belongs and how the brief's
 * `פרופיל | מועדפים | הזמנות | בית` sketch reads on screen.
 */
const ITEMS = [
  { to: '/', label: 'בית', Icon: Home, end: true },
  { to: '/orders', label: 'הזמנות', Icon: ClipboardList, end: false },
  { to: '/favorites', label: 'מועדפים', Icon: Heart, end: false },
  { to: '/profile', label: 'פרופיל', Icon: User, end: false },
] as const;

/**
 * Mobile-only primary nav, `CUSTOMER`-only + authenticated-only — `AppLayout` mounts this with
 * the same gating condition it uses for `<ActiveIssueToolbox>`.
 *
 * **Mobile shell redesign §9**: "פרופיל" is back, making this the four-item bar
 * `DESIGN_SYSTEM.md` §50 always specified. It had been moved out to a `.mobileProfileLink`
 * icon in the top bar; §1 empties that bar down to logo/notifications/logout, so the profile
 * destination returns here. `/profile` itself is untouched and there is still exactly one
 * profile entry point per viewport — this one on mobile, `AppLayout`'s desktop nav link above
 * 640px, where this bar is `display: none`.
 *
 * Notifications deliberately remain a top-bar bell rather than a fifth tab: §1 keeps them in
 * the header, and `NotificationBell` is a working popover pattern that a tab would duplicate.
 *
 * Built on `NavLink` for automatic `aria-current="page"` — no manual active-state wiring. `end`
 * is set only on `/` so that `/orders/123` still lights up "הזמנות".
 */
export function BottomNav() {
  return (
    <nav className={styles.bar} aria-label="ניווט ראשי">
      {ITEMS.map(({ to, label, Icon, end }) => (
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
