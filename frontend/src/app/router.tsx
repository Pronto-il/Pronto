import { createBrowserRouter } from 'react-router-dom';
import AppLayout from './AppLayout';
import HomePage from './HomePage';
import RequireAuth from './RequireAuth';
import ProfilePage from './ProfilePage';
import {
  RegisterChoicePage,
  CustomerRegisterPage,
  ProfessionalRegisterPage,
  VerifyPage,
  LoginPage,
} from '../features/auth';
import { NewIssuePage } from '../features/issues';
import {
  BookingFlowPage,
  SosBookingFlowPage,
  MyOrdersPage,
  OrderTrackingPage,
  CompletionReviewPage,
} from '../features/booking';
import {
  ProDashboardLayout,
  IncomingRequestsPage,
  MyJobsPage,
  WeeklyAvailabilityPage,
  ProfileEditorPage,
} from '../features/dashboard';
import { ProfessionalProfilePage } from '../features/professionals';
import { FavoritesPage } from '../features/favorites';

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
 * design's explicit "kept, not deleted yet" instruction (§7.1).
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'register', element: <RegisterChoicePage /> },
      { path: 'register/customer', element: <CustomerRegisterPage /> },
      { path: 'register/professional', element: <ProfessionalRegisterPage /> },
      { path: 'verify', element: <VerifyPage /> },
      { path: 'login', element: <LoginPage /> },
      {
        element: <RequireAuth />,
        children: [
          { path: 'profile', element: <ProfilePage /> },
          { path: 'orders/:orderId', element: <OrderTrackingPage /> },
          { path: 'professionals/:professionalId', element: <ProfessionalProfilePage /> },
        ],
      },
      {
        element: <RequireAuth role="CUSTOMER" />,
        children: [
          { path: 'issues/new', element: <NewIssuePage /> },
          { path: 'issues/:issueId/booking', element: <BookingFlowPage /> },
          { path: 'issues/:issueId/sos-booking', element: <SosBookingFlowPage /> },
          { path: 'orders', element: <MyOrdersPage /> },
          { path: 'orders/:orderId/review', element: <CompletionReviewPage /> },
          { path: 'favorites', element: <FavoritesPage /> },
        ],
      },
      {
        element: <RequireAuth role="PROFESSIONAL" />,
        children: [
          {
            element: <ProDashboardLayout />,
            children: [
              { path: 'pro', element: <IncomingRequestsPage /> },
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
