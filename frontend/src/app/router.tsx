import { createBrowserRouter } from 'react-router-dom';
import AppLayout from './AppLayout';
import HomePage from './HomePage';
import RequireAuth from './RequireAuth';
import ProfilePage from './ProfilePage';
import ProPlaceholderPage from './ProPlaceholderPage';
import {
  RegisterChoicePage,
  CustomerRegisterPage,
  ProfessionalRegisterPage,
  VerifyPage,
  LoginPage,
} from '../features/auth';
import { NewIssuePage } from '../features/issues';

/**
 * Root route configuration. Feature routes (auth, issues, booking, etc.) are added here
 * as each milestone lands — see docs/architecture/implementation-plan.md for the
 * milestone sequence. Milestone 1 adds the auth routes plus the authenticated
 * `/profile` and `/pro` placeholder routes (behind `RequireAuth`). Milestone 2 adds the
 * customer-only issue-report flow.
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
        children: [{ path: 'profile', element: <ProfilePage /> }],
      },
      {
        element: <RequireAuth role="CUSTOMER" />,
        children: [{ path: 'issues/new', element: <NewIssuePage /> }],
      },
      {
        element: <RequireAuth role="PROFESSIONAL" />,
        children: [{ path: 'pro', element: <ProPlaceholderPage /> }],
      },
    ],
  },
]);
