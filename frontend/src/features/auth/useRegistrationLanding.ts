import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import type { AuthStepResponse } from '../../shared/api';
import { useSessionLanding } from './useSessionLanding';

/**
 * Where a completed `POST /api/auth/register` sends the user — **decided by the server's
 * `nextStep`, not assumed**.
 *
 * ## The bug this fixes
 *
 * Both register pages used to navigate to `/verify` unconditionally, passing along
 * `{nextStep, challenge}`. That was correct while registration always answered `VERIFY_EMAIL` with
 * a challenge. It stopped being correct the moment verification could be switched off: with
 * `OTP_VERIFICATION_ENABLED=false` the backend creates the account and answers `AUTHENTICATED`
 * with a **session** and `challenge: null`, and `AuthChallengePage` — which treats a challengeless
 * state as "no active flow" — rendered "התהליך פג / נדרשת התחלה מחדש".
 *
 * So registration succeeded, a valid session was issued, and the frontend threw it away and showed
 * the user an error. The account existed; nothing said so.
 *
 * ## The three answers, handled the same way `AuthChallengePage` already handles them
 *
 * - **`AUTHENTICATED` + session** — verification is off (or was already satisfied). Adopt the
 *   session through `useSessionLanding`, the one implementation that persists a token and picks a
 *   landing screen, so registration and login cannot drift apart on either.
 * - **a challenge** — the ordinary verified flow. On to `/verify`, in router state rather than the
 *   URL: a challenge id is a live authentication handle, not something to put in a shareable link
 *   or a history entry.
 * - **neither** (`LOGIN`) — the account is complete but no session came back. Send them to sign in.
 *
 * Deliberately a shared hook rather than a copy in each page, for the reason `useSessionLanding`'s
 * own Javadoc gives: two copies is how one of them forgets a case.
 */
export function useRegistrationLanding() {
  const navigate = useNavigate();
  const land = useSessionLanding();

  return useCallback(
    async (response: AuthStepResponse) => {
      if (response.nextStep === 'AUTHENTICATED' && response.session) {
        await land(response.session);
        return;
      }
      if (response.challenge) {
        navigate('/verify', {
          state: { nextStep: response.nextStep, challenge: response.challenge },
        });
        return;
      }
      navigate('/login', { replace: true });
    },
    [land, navigate],
  );
}
