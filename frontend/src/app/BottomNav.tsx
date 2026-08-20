import { NavLink } from 'react-router-dom';
import { Home, ClipboardList, Heart, User } from 'lucide-react';
import styles from './BottomNav.module.css';

const ITEMS = [
  { to: '/', label: 'בית', Icon: Home, end: true },
  { to: '/orders', label: 'הזמנות', Icon: ClipboardList, end: false },
  { to: '/favorites', label: 'מועדפים', Icon: Heart, end: false },
  { to: '/profile', label: 'פרופיל', Icon: User, end: false },
] as const;

/**
 * Mobile-only primary nav (design doc §3.2-3.3), `CUSTOMER`-only + authenticated-only —
 * `AppLayout` mounts this with the same gating condition it already uses for
 * `<ActiveOrderIndicator>`. The 4 items follow `DESIGN_SYSTEM.md` §50's explicit, binding
 * list (בית/הזמנות/מועדפים/פרופיל) — not the milestone dispatch's own "Notifications"
 * example, which the design doc's §3.3 resolves in favor of the design system: notifications
 * already has a fully working dedicated pattern (`NotificationBell`, kept in the mobile top
 * bar) that doesn't need a duplicate nav tab.
 *
 * Built on `NavLink` for automatic `aria-current="page"` — no manual active-state wiring.
 * Hidden entirely at `>=640px` via this file's own `.module.css` (mounted for every viewport
 * width during a `CUSTOMER` session, not just mobile, so the visibility switch has to live
 * here rather than in the mount condition).
 */
export function BottomNav() {
  return (
    <nav className={styles.bar} aria-label="ניווט ראשי">
      {ITEMS.map(({ to, label, Icon, end }) => (
        <NavLink key={to} to={to} end={end} className={({ isActive }) => `${styles.item} ${isActive ? styles.active : ''}`}>
          <Icon size={24} aria-hidden="true" />
          <span>{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
