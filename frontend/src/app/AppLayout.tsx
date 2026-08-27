import { useEffect } from 'react';
import { Link, Outlet, useNavigate } from 'react-router-dom';
import { LogOut, User, ClipboardList, LayoutDashboard, ShieldCheck, Wrench } from 'lucide-react';
import { useAuth } from '../shared/hooks';
import { setPhoneVerificationRequiredHandler } from '../shared/api';
import { BookingDraftIndicator } from './BookingDraftIndicator';
import { ActiveIssueToolbox } from './ActiveIssueToolbox';
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
 *
 * **MS6 Professional Command Center (design doc §8)**: the brand logo `Link` is now
 * role-aware — a `PROFESSIONAL` clicking the logo goes to `/pro` (which redirects to
 * `/pro/availability`, their dashboard home), matching the existing "לוח בקרה" nav link right
 * next to it, which already targets `/pro` for exactly this reason. `CUSTOMER`/logged-out
 * stay `/`.
 *
 * **MS1 professional verification (2026-08-22)**: an `ADMIN` gets one nav destination,
 * "אימות בעלי מקצוע" (`/admin/professionals`), and the brand logo takes them there too — the same
 * role-aware treatment `PROFESSIONAL` already has. It renders for that role only, so no customer
 * or professional ever sees a link to an operator screen. That is discoverability, not access
 * control: `RequireAuth role="ADMIN"` bounces the other roles off the route and the backend
 * answers `403` regardless of what the UI shows. `ActiveIssueToolbox`/`BottomNav` stay
 * `CUSTOMER`-only and are therefore already correct for the new role.
 *
 * **Mobile shell redesign (2026-08-27).** The changes below are scoped to `<640px`; the desktop
 * bar renders exactly what it did before, per §14.
 *
 * - **§1 — the mobile top row is now only brand + `NotificationBell` + logout.** Three controls,
 *   down from six. `BookingDraftIndicator` moved inside `.desktopOnlyNav` (it keeps the draft's
 *   dismiss action, which exists nowhere else in the app, reachable on desktop); the mobile-only
 *   profile icon is deleted outright. Both jobs are covered on mobile by the two elements below.
 * - **§2 — the new-issue CTA is the one dominant action under the header.** Same `<Link to=
 *   "/issues/new">` element and the same navigation as before, restyled by the stylesheet into a
 *   ~96px full-width block on its own row. It is emphatically *not* the active-order control
 *   (§17): this link always starts a new issue.
 * - **§3-§8 — `<ActiveOrderIndicator>` is replaced by `<ActiveIssueToolbox>`**, a draggable
 *   toolbox rendered as a sibling of `<main>`. It keeps the review-prompt behaviour and the
 *   route resolution the old indicator had, and additionally covers the booking draft that
 *   `BookingDraftIndicator` used to surface on mobile. See that component for the visibility
 *   rule.
 * - **§9 — "פרופיל" returns to `<BottomNav>`**, making it the four-item bar `DESIGN_SYSTEM.md`
 *   §50 specifies. The desktop profile link is untouched, since `BottomNav` is hidden above
 *   640px and removing it there would strand `/profile`.
 */
export default function AppLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  // Production MS1. An account whose phone has never been verified authenticates normally but is
  // refused the marketplace mutations that end with a professional at somebody's front door
  // (users.service.ContactVerificationGuard). Registered here, inside the router, because that
  // refusal can arrive from any of several screens and a per-screen copy is one that gets
  // forgotten — the user lands on the one screen that can resolve it instead of on a dead end.
  useEffect(() => {
    setPhoneVerificationRequiredHandler(() => navigate('/verify-phone'));
  }, [navigate]);

  const brandTarget =
    user?.role === 'PROFESSIONAL' ? '/pro' : user?.role === 'ADMIN' ? '/admin/professionals' : '/';

  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <div className={styles.headerInner}>
          <Link to={brandTarget} className={styles.brand} aria-label="Pronto">
            <span className={styles.logo} style={{ backgroundImage: `url(${logoUrl})` }} />
          </Link>

          {/* The customer's primary action, in the chrome rather than on one page: reporting a
              fault is what this product is for, and it should be one tap away from wherever the
              customer already is. `CUSTOMER`-only — `/issues/new` is behind `RequireAuth
              role="CUSTOMER"`, so offering it to a guest, a professional or an operator would be
              a link to a bounce. One element at every width: on desktop it sits inline in the bar
              between the brand and the nav actions; under 640px `.headerInner` wraps and it
              becomes a full-width row directly under them (see the stylesheet), which is what
              keeps the full sentence readable on a phone instead of truncating it. */}
          {user?.role === 'CUSTOMER' && (
            <Link to="/issues/new" className={styles.issueCta}>
              <Wrench size={18} aria-hidden="true" />
              <span>יש לך תקלה? בוא נטפל בזה</span>
            </Link>
          )}

          <nav className={styles.nav}>
            {user ? (
              <>
                {/* Desktop only (§1 empties the *mobile* bar; §14 leaves the desktop shell
                    alone). `.desktopOnlyNav` is `display: contents` above 640px, so this stays
                    a direct flex child of `.nav` there and simply disappears below it — where
                    `<ActiveIssueToolbox>` takes over the same "resume my draft" job. Keeping it
                    on desktop also preserves the draft's dismiss (X) action, which has no other
                    entry point anywhere in the app. */}
                <div className={styles.desktopOnlyNav}>
                  <BookingDraftIndicator />
                </div>
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
                  {user.role === 'ADMIN' && (
                    <Link to="/admin/professionals" className={styles.navLink}>
                      <ShieldCheck size={18} aria-hidden="true" />
                      <span>אימות בעלי מקצוע</span>
                    </Link>
                  )}
                  <Link to="/profile" className={styles.navLink}>
                    <User size={18} aria-hidden="true" />
                    <span>הפרופיל שלי</span>
                  </Link>
                </div>
                {/* The mobile profile icon that used to sit here is gone (§1/§9) — "פרופיל" is
                    now BottomNav's fourth tab. The desktop profile link inside `.desktopOnlyNav`
                    above is untouched, since BottomNav is `display: none` at that width. */}
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
      {user?.role === 'CUSTOMER' && <ActiveIssueToolbox />}
      {user?.role === 'CUSTOMER' && <BottomNav />}
    </div>
  );
}
