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
import { BookingFlowPage, MyOrdersPage, OrderTrackingPage } from '../features/booking';
import { ProDashboardLayout, IncomingRequestsPage, MyJobsPage, AvailabilityPage } from '../features/dashboard';

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
 * once it leaves the pending feed.
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
        ],
      },
      {
        element: <RequireAuth role="CUSTOMER" />,
        children: [
          { path: 'issues/new', element: <NewIssuePage /> },
          { path: 'issues/:issueId/booking', element: <BookingFlowPage /> },
          { path: 'orders', element: <MyOrdersPage /> },
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
              { path: 'pro/availability', element: <AvailabilityPage /> },
            ],
          },
        ],
      },
    ],
  },
]);
