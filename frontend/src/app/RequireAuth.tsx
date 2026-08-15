import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../shared/hooks';
import type { UserRole } from '../shared/api';

export interface RequireAuthProps {
  /** Optionally gate a route to one role (e.g. the professional placeholder). */
  role?: UserRole;
}

/** Route guard: redirects to `/login` when not authenticated, renders `<Outlet />` otherwise. */
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
