import { createBrowserRouter, Navigate } from 'react-router-dom';
import AppLayout from './AppLayout';
import HomePage from './HomePage';
import RequireAuth from './RequireAuth';
import ProfilePage from './ProfilePage';
import {
  RegisterChoicePage,
  CustomerRegisterPage,
  ProfessionalRegisterPage,
  AuthChallengePage,
  PasswordResetPage,
  PhoneCapturePage,
  LoginPage,
} from '../features/auth';
import { NewIssuePage, ProfessionMatchPage } from '../features/issues';
import {
  BookingFlowPage,
  MyOrdersPage,
  OrderTrackingPage,
  CompletionReviewPage,
} from '../features/booking';
import { ProntoSosEntryPage, ProSosPage } from '../features/sos';
import {
  ProDashboardLayout,
  IncomingRequestsPage,
  MyJobsPage,
  WeeklyAvailabilityPage,
  ProfileEditorPage,
} from '../features/dashboard';
import { ProfessionalProfilePage } from '../features/professionals';
import { FavoritesPage } from '../features/favorites';
import { ProfessionalReviewQueuePage, ProfessionalReviewPage } from '../features/admin';
import DesignSystemPage from './DesignSystemPage';
import type { RouteObject } from 'react-router-dom';

/**
 * Root route configuration. Feature routes (auth, issues, booking, etc.) are added here
 * as each milestone lands — see docs/architecture/implementation-plan.md for the
 * milestone sequence. Milestone 1 adds the auth routes plus the authenticated
 * `/profile` and `/pro` placeholder routes (behind `RequireAuth`). Milestone 2 adds the
 * customer-only issue-report flow. Frontend Milestone 3 adds the Standard booking flow
 * (`/issues/:issueId/booking`, `/orders`, `/orders/:orderId`) and replaces the `/pro`
 * placeholder with a real professional dashboard (`ProDashboardLayout`, nesting `/pro`,
 * `/pro/jobs` and `/pro/availability`). `/pro/jobs` was added post-QA as a bug fix (see
 * `features/dashboard/README.md`) to give a professional an in-app way to see a job again
 * once it leaves the pending feed. Frontend Milestone 4 adds the SOS booking flow
 * (`/issues/:issueId/sos-booking`, CUSTOMER-only, alongside the existing Standard
 * `/issues/:issueId/booking` route) — the professional's SOS-availability toggle has no
 * route of its own, it's rendered inline on the existing `/pro/availability` page. Frontend
 * Milestone 8 adds `/professionals/:professionalId` (bare `RequireAuth`, either role, matching
 * the backend's route-gate-free `GET /api/professionals/{id}`), `/favorites`
 * (CUSTOMER-only), and `/pro/profile` (a 4th `ProDashboardLayout` tab) — see
 * `docs/architecture/frontend-ms8-design.md` §3. **Professional weekly availability calendar,
 * M4**: `/pro/availability` now renders `WeeklyAvailabilityPage` instead of the old
 * `AvailabilityPage` (same route, unchanged path) — see
 * `docs/architecture/professional-weekly-calendar-design.md` §7.1/§10. `AvailabilityPage.tsx`/
 * `SlotForm.tsx`/`SlotList.tsx` are left in the repo, unreachable from any route, per that
 * design's explicit "kept, not deleted yet" instruction (§7.1). **MS9 dashboard/home
 * (2026-08-18)**: the professional dashboard's `ProDashboardLayout` children were
 * restructured per `docs/architecture/product-ms9-dashboard-home-design.md` §1.2 — `/pro`
 * is now a `<Navigate replace>` redirect to `/pro/availability` (the calendar is the
 * professional's home screen after login), and the former `/pro` content
 * (`IncomingRequestsPage`) moved to its own path, `/pro/requests`, matching its "בקשות
 * חדשות" nav label the same way `/pro/jobs`/`/pro/profile` already match theirs.
 * `/pro/jobs`, `/pro/availability`, and `/pro/profile` are otherwise unchanged.
 *
 * **MS1 dev-only showcase route (2026-08-20)**: `/__design` (`DesignSystemPage`) is a
 * top-level sibling of the `AppLayout` tree below (not nested under it, so it needs no app
 * chrome/auth) — see MS1 plan Architecture §9. Gated behind `import.meta.env.DEV` via the
 * conditional spread `designSystemRoutes` so it's absent from the route table (and, per Vite's
 * dead-code elimination on the statically-replaced `import.meta.env.DEV` literal, from the
 * production bundle) entirely in production builds.
 *
 * > **Superseded (2026-08-29) — read the route table below, not the history above.** Deferred
 * > authentication flattened the three creation routes to `/matching`, `/booking` and
 * > `/sos-booking`: issue creation moved to the booking commit, so during selection there is no
 * > issue and nothing to put in the URL. Everything above still describing
 * > `/issues/:issueId/(matching|booking|sos-booking)` as *the* path is history, not current state.
 * >
 * > That drift was not free. Two Production bugs came from code still reading the retired shape:
 * > `ProntoSosEntryPage` read `useParams().issueId`, got `NaN`, and posted `"issueId": null` on
 * > every authenticated SOS activation; and `toolboxState.isInBookingFlow` matched only the old
 * > paths, so the "ההזמנה שלי" widget reappeared across the whole booking flow. Both are fixed.
 * >
 * > `/issues/:issueId/booking` and `/issues/:issueId/sos-booking` now exist again **alongside**
 * > the flattened routes, for re-entry on an issue that already exists — see the comment on those
 * > two entries. They are not the creation path.
 *
 * **Pronto SOS customer flow, MS1 (2026-08-21)**: `/issues/:issueId/sos-booking` (see the note
 * above on how this path has since changed) keeps its CUSTOMER-only gate, but now renders
 * `features/sos`'s `ProntoSosEntryPage` — the real
 * flow against `/api/sos/**` — instead of `features/booking`'s no-API placeholder of the same
 * name, which is deleted. The path was unchanged at the time: `features/issues/ProfessionMatchPage`
 * and `shared/hooks/bookingDraftContext.resolveDraftRoute` are the two places that name it, and
 * neither needed to change. Pronto SOS is one continuous state-driven screen rather than a route
 * per step, so no new routes were added — a refresh re-attaches to the live request by looking it
 * up on `GET /api/sos/requests/me`.
 *
 * **Pronto SOS professional frontend, MS2 (2026-08-21)**: `/pro/sos` (`ProSosPage`) joins
 * `ProDashboardLayout`'s children as a fifth tab — the professional's whole SOS surface (offer
 * inbox, availability response, and the operational flow once selected). Its own route rather than
 * a section of `/pro/requests` because that page is an accept/reject feed of *scheduled orders*,
 * where "אישור" would mean something different from what it means on an SOS offer. Discovery does
 * not depend on being on this route: `ProSosProvider` is mounted on the layout, so the tab badge
 * and the new-offer toast reach a professional anywhere under `/pro/*`.
 *
 * **MS1 professional verification (production roadmap, 2026-08-22)**: `/admin/professionals` and
 * `/admin/professionals/:professionalId` join the tree behind a third `RequireAuth` group,
 * `role="ADMIN"` — the operator surface for the approval lifecycle MS1 introduces (design
 * `docs/architecture/ms1-professional-verification-design.md` D-F). Its own top-level path prefix
 * rather than a section of `/pro/*` or `/profile`, mirroring the backend's own split: the
 * `/api/admin/professionals/**` routes are the only `ADMIN`-gated ones in the app, and a distinct
 * prefix keeps "which routes have which audience" answerable by reading the path. **The guard here
 * is UX, not security** — `professionals.config.ProfessionalsWebConfig` answers `403` to a
 * non-`ADMIN` caller regardless of which screen asked, and a `CUSTOMER`/`PROFESSIONAL` who types
 * the URL is bounced to `/` by `RequireAuth` before any request is made. This is the minimal
 * operator capability, not MS7's admin console: no user management, no order management, no
 * analytics.
 */
const designSystemRoutes: RouteObject[] = import.meta.env.DEV
  ? [{ path: '/__design', element: <DesignSystemPage /> }]
  : [];

export const router = createBrowserRouter([
  ...designSystemRoutes,
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'register', element: <RegisterChoicePage /> },
      { path: 'register/customer', element: <CustomerRegisterPage /> },
      { path: 'register/professional', element: <ProfessionalRegisterPage /> },
      { path: 'verify', element: <AuthChallengePage /> },
      { path: 'password-reset', element: <PasswordResetPage /> },
      { path: 'login', element: <LoginPage /> },
      // ---- the guest journey: deliberately OUTSIDE every RequireAuth group ----
      //
      // Deferred authentication. A visitor describes their problem, gets it classified, picks an
      // address, sees who could take the job and when they are free, and only meets a login form
      // when they press the final book/SOS button. Guarding these routes was the single biggest
      // source of "log in before you can find out whether we can even help you".
      //
      // This is NOT the security boundary and never was -- RequireAuth's own Javadoc says so. The
      // boundary is server-side: POST /api/issues, POST /api/bookings/orders and every /api/sos
      // write stay behind their RoleRequiredInterceptor, so an unauthenticated caller reaching
      // these screens can read, and cannot commit anything.
      //
      // The two booking routes carry no issue id: there is no issue until the booking is
      // committed. State lives in the booking draft.
      { path: 'issues/new', element: <NewIssuePage /> },
      { path: 'matching', element: <ProfessionMatchPage /> },
      { path: 'booking', element: <BookingFlowPage /> },
      { path: 'sos-booking', element: <ProntoSosEntryPage /> },

      // ---- re-entry on an issue that ALREADY exists ----
      //
      // The same two screens, reached by naming an issue instead of relying on the draft. These
      // are not creation: the customer is coming back to a problem that was described and
      // persisted some time ago, from a place that has no draft to hand --
      //
      //   OrderTrackingPage  "choose another professional", after an order was cancelled,
      //                      rejected or expired. Documented there as needing a plain URL that
      //                      survives a refresh, with no router state and no re-created issue.
      //   NotificationBell   a customer's SOS notification, keyed by issue rather than by attempt
      //                      because one problem accumulates many attempts.
      //
      // These paths were REMOVED when deferred authentication flattened the creation routes, and
      // removing them silently broke both links: there is no catch-all route, so each landed on a
      // blank screen -- the SOS one at the exact moment a search had just failed the customer.
      // Restored rather than reworked because the id genuinely belongs in the URL here, which is
      // the difference between this entry and creation. Both pages read the param and fall back to
      // the draft, so the flattened routes above are unaffected.
      { path: 'issues/:issueId/booking', element: <BookingFlowPage /> },
      { path: 'issues/:issueId/sos-booking', element: <ProntoSosEntryPage /> },
      { path: 'professionals/:professionalId', element: <ProfessionalProfilePage /> },
      {
        element: <RequireAuth />,
        children: [
          { path: 'profile', element: <ProfilePage /> },
          // Production MS1: authenticated, and reachable by anyone the PHONE_VERIFICATION_REQUIRED
          // gate turns away -- see features/auth/PhoneCapturePage.
          { path: 'verify-phone', element: <PhoneCapturePage /> },
          { path: 'orders/:orderId', element: <OrderTrackingPage /> },
        ],
      },
      {
        element: <RequireAuth role="CUSTOMER" />,
        children: [
          // `orders`/`favorites`/`review` stay gated: they are a customer's own private records,
          // not part of the pre-purchase journey.
          { path: 'orders', element: <MyOrdersPage /> },
          { path: 'orders/:orderId/review', element: <CompletionReviewPage /> },
          { path: 'favorites', element: <FavoritesPage /> },
        ],
      },
      {
        element: <RequireAuth role="ADMIN" />,
        children: [
          { path: 'admin/professionals', element: <ProfessionalReviewQueuePage /> },
          { path: 'admin/professionals/:professionalId', element: <ProfessionalReviewPage /> },
        ],
      },
      {
        element: <RequireAuth role="PROFESSIONAL" />,
        children: [
          {
            element: <ProDashboardLayout />,
            children: [
              { path: 'pro', element: <Navigate to="/pro/availability" replace /> },
              { path: 'pro/requests', element: <IncomingRequestsPage /> },
              { path: 'pro/sos', element: <ProSosPage /> },
              { path: 'pro/jobs', element: <MyJobsPage /> },
              { path: 'pro/availability', element: <WeeklyAvailabilityPage /> },
              { path: 'pro/profile', element: <ProfileEditorPage /> },
            ],
          },
        ],
      },
    ],
  },
]);
