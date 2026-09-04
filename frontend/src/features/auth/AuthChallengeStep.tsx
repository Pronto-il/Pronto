import { useCallback, useState } from 'react';
import {
  loginOtp,
  verifyEmail,
  verifyPhone,
  type AuthNextStep,
  type AuthStepResponse,
  type OtpChallenge,
} from '../../shared/api';
import { OtpForm } from './OtpForm';
import { useSessionLanding } from './useSessionLanding';

/**
 * What `navigate('/verify', { state })` carries. The challenge lives in router state, not in the
 * URL and not in `localStorage`: a challenge id is a short-lived handle to a live authentication
 * conversation, and neither a shareable link nor a value that outlives the tab is the right home
 * for one.
 */
export interface AuthChallengeState {
  nextStep: AuthNextStep;
  challenge: OtpChallenge;
}

export const CHALLENGE_HEADINGS: Record<string, { title: string; description: string; submit: string; intro?: string }> = {
  VERIFY_EMAIL: {
    title: 'אימות כתובת האימייל',
    description: 'שלב 1 מתוך 2 — אימות פרטי ההתקשרות',
    submit: 'אימות האימייל',
    intro: 'אחרי אישור האימייל נשלח קוד נוסף לטלפון שלכם.',
  },
  VERIFY_PHONE: {
    title: 'אימות מספר הטלפון',
    description: 'שלב 2 מתוך 2 — אימות פרטי ההתקשרות',
    submit: 'סיום ההרשמה',
  },
  LOGIN_OTP: {
    title: 'אימות ההתחברות',
    description: 'עוד שלב אחד ונכנסים',
    submit: 'התחברות',
  },
};

export interface AuthChallengeStepProps {
  initial: AuthChallengeState;
  /**
   * The conversation ended without a session — an account that has finished every step currently
   * asked of it, so there is no code on its way. The route version sends the user to `/login`; the
   * modal version puts its own login form back on screen. Neither is this component's decision.
   */
  onExhausted: () => void;
  /** Reported so a host can follow the heading (the page renders it above, the modal in its title). */
  onStepChange?: (nextStep: AuthNextStep) => void;
}

/**
 * Every one-time-password step of registration and login, as a component rather than a screen.
 *
 * <p>Extracted from `AuthChallengePage` when the deferred-authentication gate needed the same
 * interaction *inside a modal*: with `AUTH_OTP_REQUIRED=true` a password submit answers with a
 * challenge, and a guest confirming a booking must be able to answer it without the Booking
 * Summary they are confirming being navigated away from underneath them. Extracted rather than
 * copied for the reason this file's page already gives about `useSessionLanding`: two copies is
 * how one of them forgets a case.
 *
 * <p>The server decides what happens next — this component only reads `nextStep` off each response
 * and re-renders. That is why registration's two verification steps and login's second factor
 * share one implementation: to the client they are the same interaction, differing only in which
 * endpoint redeems the code and what the heading says.
 */
export function AuthChallengeStep({ initial, onExhausted, onStepChange }: AuthChallengeStepProps) {
  const land = useSessionLanding();
  const [state, setState] = useState<AuthChallengeState>(initial);

  const advance = useCallback(
    async (response: AuthStepResponse) => {
      if (response.nextStep === 'AUTHENTICATED' && response.session) {
        await land(response.session);
        return;
      }
      if (response.nextStep === 'LOGIN' || !response.challenge) {
        // Two ways to get here, handled identically and deliberately: a pre-MS1 row with no phone
        // on file, and a backend running with `pronto.verification.sms-required=false`, where a
        // verified email completes registration and no phone challenge is issued at all. The client
        // does not need to know which — in neither case is there a code on its way, and routing to
        // the phone screen would strand the user watching for an SMS nobody sent.
        onExhausted();
        return;
      }
      setState({ nextStep: response.nextStep, challenge: response.challenge });
      onStepChange?.(response.nextStep);
    },
    [land, onExhausted, onStepChange],
  );

  async function submit(code: string) {
    const submission = { challengeId: state.challenge.challengeId, code };
    const response =
      state.nextStep === 'VERIFY_EMAIL'
        ? await verifyEmail(submission)
        : state.nextStep === 'VERIFY_PHONE'
          ? await verifyPhone(submission)
          : await loginOtp(submission);
    await advance(response);
  }

  const copy = CHALLENGE_HEADINGS[state.nextStep];

  return (
    <OtpForm
      challenge={state.challenge}
      onSubmit={submit}
      onResent={(challenge) => setState({ nextStep: state.nextStep, challenge })}
      submitLabel={copy?.submit ?? 'אישור'}
      intro={copy?.intro}
    />
  );
}
