import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { resolveDraftRoute, useAuth, useAuthGate, useBookingDraft } from '../../shared/hooks';
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
  const { draft } = useBookingDraft();
  const { completeInPlace, close: closeAuthGate } = useAuthGate();

  return useCallback(
    async (session: AuthSession) => {
      // establishSession stores the token and fetches /users/me — the role decides the landing
      // screen, so it must come from the server's answer rather than from anything the login
      // response happened to carry.
      const me = await establishSession(session);

      // The deferred-authentication gate (`AuthGateProvider`) is open: the customer never left the
      // screen that needed the account — the login form is over it — so there is nothing to
      // navigate back to. Consuming the gate closes it and resumes the action they were refused,
      // with every piece of what they had entered still on screen behind it. Checked before the
      // draft route below, because that route would send them to a fresh copy of the screen they
      // are already looking at.
      // A non-CUSTOMER who signs in through the gate is a real (if odd) case — a professional
      // using a household member's phone — and resuming a customer booking for them would only
      // produce a 403 from the role-gated write. They get their own dashboard, and the gate is
      // closed on the way so no modal is left over a screen they are leaving.
      if (me.role === 'CUSTOMER' && completeInPlace()) {
        return;
      }
      closeAuthGate();

      // Deferred authentication: a customer who was sent here by the book button is mid-booking,
      // and landing them on Home would throw away the journey at the exact moment they did what
      // was asked of them. The draft is read rather than `location.state.from` because it is the
      // stronger record — it survives a closed tab, and it carries the professional and slot as
      // well as the route.
      //
      // Only a CUSTOMER resumes: a professional or admin signing in on a browser that happens to
      // hold a booking draft belongs on their own dashboard, and the draft is about to be
      // discarded by the provider's owner-mismatch rule anyway.
      if (me.role === 'CUSTOMER' && draft) {
        navigate(resolveDraftRoute(draft), { replace: true });
        return;
      }
      navigate(landingFor(me.role), { replace: true });
    },
    [establishSession, navigate, draft, completeInPlace, closeAuthGate],
  );
}

/** MS1: an ADMIN lands on the operator review queue — the only screen their role can reach. */
export function landingFor(role: string): string {
  if (role === 'PROFESSIONAL') return '/pro';
  if (role === 'ADMIN') return '/admin/professionals';
  return '/';
}
