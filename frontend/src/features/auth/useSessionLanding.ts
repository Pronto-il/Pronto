import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../shared/hooks';
import type { AuthSession } from '../../shared/api';

/**
 * Turns an issued session into a signed-in user on the right screen.
 *
 * <p>Extracted from `AuthChallengePage` rather than copied, because there are now two places a
 * session can arrive: after a redeemed one-time password, and — when the backend runs with
 * `AUTH_OTP_REQUIRED=false` — straight from `POST /api/auth/login`. Those two must persist the
 * session identically and land on the same screen, and the only way to guarantee that is for there
 * to be one implementation. Two copies is how "the bypass forgets to refresh the user, so the app
 * boots logged-in-but-roleless" gets introduced.
 *
 * <p>`replace: true` on the navigation is deliberate and inherited from the original: the login and
 * verification screens must not be reachable by pressing Back from an authenticated screen.
 */
export function useSessionLanding() {
  const navigate = useNavigate();
  const { establishSession } = useAuth();

  return useCallback(
    async (session: AuthSession) => {
      // establishSession stores the token and fetches /users/me — the role decides the landing
      // screen, so it must come from the server's answer rather than from anything the login
      // response happened to carry.
      const me = await establishSession(session);
      navigate(landingFor(me.role), { replace: true });
    },
    [establishSession, navigate],
  );
}

/** MS1: an ADMIN lands on the operator review queue — the only screen their role can reach. */
export function landingFor(role: string): string {
  if (role === 'PROFESSIONAL') return '/pro';
  if (role === 'ADMIN') return '/admin/professionals';
  return '/';
}
