import { useState } from 'react';
import { Modal } from '../../shared/components';
import { useAuthGate } from '../../shared/hooks';
import type { AuthStepResponse } from '../../shared/api';
import { LoginForm } from './LoginForm';
import { CustomerRegisterForm } from './CustomerRegisterForm';
import { AuthChallengeStep, CHALLENGE_HEADINGS } from './AuthChallengeStep';
import type { AuthChallengeState } from './AuthChallengeStep';
import { useSessionLanding } from './useSessionLanding';
import styles from './AuthGateModal.module.css';

type Mode =
  | { name: 'login' }
  | { name: 'register' }
  | { name: 'challenge'; state: AuthChallengeState };

/**
 * The deferred-authentication gate's UI: the account question asked **over** the screen that needs
 * it, instead of by navigating to `/login`.
 *
 * <h2>Why a modal and not a route</h2>
 *
 * The screen underneath is the answer to "what am I signing in for": a Booking Summary with a
 * chosen professional, a date, a time and a price on it. Sending the customer to `/login` replaced
 * that answer with a form, and getting back relied on the draft being re-read and every screen
 * being re-derived from it — which worked, but asked the customer to trust that it would. Here
 * they can see it did not go anywhere.
 *
 * <h2>Nothing here is new auth UI</h2>
 *
 * `LoginForm`, `CustomerRegisterForm` and `AuthChallengeStep` are the same components the
 * `/login`, `/register/customer` and `/verify` routes render, with the same endpoints, the same
 * validation and the same error treatments. This file is a host: it chooses which of the three is
 * on screen and closes when a session lands. The landing itself stays in `useSessionLanding`,
 * which consumes the gate (`completeInPlace`) rather than navigating — see that hook.
 *
 * <p>Registration is the **customer** form only. This gate exists on the customer booking journey,
 * and a professional signing up mid-booking is not a thing that happens; `/register` remains the
 * way to choose a role.
 */
export function AuthGateModal() {
  const { isOpen, close } = useAuthGate();
  const land = useSessionLanding();
  const [mode, setMode] = useState<Mode>({ name: 'login' });

  function handleClose() {
    // Dismissing means "not now", not "start again": the pending action is dropped by the gate and
    // the screen underneath is untouched, with everything the customer entered still on it.
    setMode({ name: 'login' });
    close();
  }

  /** Registration's own answer, handled exactly as `useRegistrationLanding` handles it — minus the
   *  navigation, which is the one thing this host does differently. */
  async function handleRegistered(response: AuthStepResponse) {
    if (response.nextStep === 'AUTHENTICATED' && response.session) {
      await land(response.session);
      return;
    }
    if (response.challenge) {
      setMode({ name: 'challenge', state: { nextStep: response.nextStep, challenge: response.challenge } });
      return;
    }
    setMode({ name: 'login' });
  }

  const title =
    mode.name === 'challenge'
      ? (CHALLENGE_HEADINGS[mode.state.nextStep]?.title ?? 'אימות')
      : mode.name === 'register'
        ? 'הרשמה מהירה'
        : 'התחברות להשלמת ההזמנה';

  return (
    <Modal isOpen={isOpen} onClose={handleClose} title={title} size="small">
      <div className={styles.body}>
        {mode.name === 'login' && (
          <>
            <p className={styles.intro}>
              ההזמנה שלך שמורה — נשאר רק להתחבר כדי לאשר אותה.
            </p>
            <LoginForm onChallenge={(state) => setMode({ name: 'challenge', state })} />
            <p className={styles.switch}>
              אין לכם חשבון?{' '}
              <button type="button" className={styles.switchButton} onClick={() => setMode({ name: 'register' })}>
                הרשמה
              </button>
            </p>
          </>
        )}

        {mode.name === 'register' && (
          <>
            <CustomerRegisterForm onSuccess={handleRegistered} onExit={() => setMode({ name: 'login' })} />
            <p className={styles.switch}>
              כבר יש לכם חשבון?{' '}
              <button type="button" className={styles.switchButton} onClick={() => setMode({ name: 'login' })}>
                התחברות
              </button>
            </p>
          </>
        )}

        {mode.name === 'challenge' && (
          <AuthChallengeStep
            initial={mode.state}
            onStepChange={(nextStep) =>
              setMode((current) =>
                current.name === 'challenge'
                  ? { name: 'challenge', state: { ...current.state, nextStep } }
                  : current,
              )
            }
            // No code is coming. Back to the form rather than to `/login`, which would leave the
            // screen this gate exists to keep on display.
            onExhausted={() => setMode({ name: 'login' })}
          />
        )}
      </div>
    </Modal>
  );
}
