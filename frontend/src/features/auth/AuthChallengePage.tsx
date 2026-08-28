import { useCallback, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { PageHeader } from '../../shared/components';
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
import styles from './formStyles.module.css';

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

const HEADINGS: Record<string, { title: string; description: string; submit: string; intro?: string }> = {
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

/**
 * Every one-time-password step of registration and login, in one screen.
 *
 * <p>The server decides what happens next — this component only reads `nextStep` off each response
 * and re-renders. That is why registration's two verification steps and login's second factor share
 * a route: to the client they are the same interaction, differing only in which endpoint redeems the
 * code and what the heading says.
 *
 * <p><b>A refresh loses the challenge, on purpose.</b> Router state does not survive a reload, so a
 * reloaded page falls through to the "start again" notice below rather than persisting a live
 * authentication handle somewhere it could be read. Recovery costs the user one password entry.
 */
export default function AuthChallengePage() {
  const location = useLocation();
  const navigate = useNavigate();
  const land = useSessionLanding();

  // A challenge-bearing step with no challenge is a shape the backend cannot produce — `register`,
  // `verify-email`, `login` and `login/otp` always populate `challenge` unless `nextStep` is
  // `LOGIN` or `AUTHENTICATED`, and `advance` below routes those two away before they reach state.
  // Treated as "no active flow" rather than trusted, because the alternative is a blank crash if
  // that ever stops being true.
  const raw = location.state as AuthChallengeState | null;
  const initial = raw?.challenge ? raw : null;
  const [state, setState] = useState<AuthChallengeState | null>(initial);

  const advance = useCallback(
    async (response: AuthStepResponse) => {
      if (response.nextStep === 'AUTHENTICATED' && response.session) {
        await land(response.session);
        return;
      }
      if (response.nextStep === 'LOGIN' || !response.challenge) {
        // An account that finished every step currently asked of it. Two ways to get here:
        //
        //   * a pre-MS1 row with no phone on file, which signs in normally and is asked for a
        //     phone by the marketplace gate;
        //   * the backend is running with `pronto.verification.sms-required=false` (Production MS5,
        //     while AWS production SMS access is unapproved), so a verified email completes
        //     registration and no phone challenge is issued at all.
        //
        // Both are handled identically and deliberately: the client does not need to know which,
        // because in neither case is there a code on its way. Routing to the phone screen would
        // strand the user watching for an SMS that is never sent.
        navigate('/login', { replace: true });
        return;
      }
      setState({ nextStep: response.nextStep, challenge: response.challenge });
    },
    [land, navigate],
  );

  if (!state) {
    return (
      <div className="focused-page">
        <PageHeader
          title="התהליך פג"
          description="נדרשת התחלה מחדש"
        />
        <div className={styles.banner} role="alert">
          <p>
            לא נמצא תהליך אימות פעיל. ייתכן שהדף רוענן. אפשר להתחיל שוב מ
            <Link to="/login" className={styles.bannerLink}>מסך ההתחברות</Link>.
          </p>
        </div>
      </div>
    );
  }

  const copy = HEADINGS[state.nextStep];
  if (!copy) {
    return <Navigate to="/login" replace />;
  }

  async function submit(code: string) {
    const { challengeId } = state!.challenge;
    const submission = { challengeId, code };
    const response =
      state!.nextStep === 'VERIFY_EMAIL'
        ? await verifyEmail(submission)
        : state!.nextStep === 'VERIFY_PHONE'
          ? await verifyPhone(submission)
          : await loginOtp(submission);
    await advance(response);
  }

  return (
    <div className="focused-page">
      <PageHeader title={copy.title} description={copy.description} />
      <OtpForm
        challenge={state.challenge}
        onSubmit={submit}
        onResent={(challenge) => setState({ nextStep: state.nextStep, challenge })}
        submitLabel={copy.submit}
        intro={copy.intro}
      />
    </div>
  );
}
