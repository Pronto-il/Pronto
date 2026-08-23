import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../shared/hooks';
import type { UserRole } from '../shared/api';

export interface RequireAuthProps {
  /** Optionally gate a route to one role (e.g. the professional dashboard, or MS1's
   *  `role="ADMIN"` operator verification screens). */
  role?: UserRole;
}

/**
 * Route guard: redirects to `/login` when not authenticated, renders `<Outlet />` otherwise.
 *
 * **This is UX, not security.** It decides which screen a browser shows; it does not protect any
 * data. Every gated API route is enforced backend-side (`common.security.RoleRequiredInterceptor`,
 * registered per package), which answers `403 FORBIDDEN` no matter how the request was made. A
 * role that reaches a URL it shouldn't sees a redirect here and an empty-handed `403` there.
 */
export default function RequireAuth({ role }: RequireAuthProps) {
  const { token, user, isLoading } = useAuth();
  const location = useLocation();

  if (isLoading) {
    return null;
  }

  if (!token || !user) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (role && user.role !== role) {
    return <Navigate to="/" replace />;
  }

  return <Outlet />;
}
