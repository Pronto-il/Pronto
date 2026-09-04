import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { PageHeader } from '../../shared/components';
import { AuthChallengeStep, CHALLENGE_HEADINGS } from './AuthChallengeStep';
import type { AuthChallengeState } from './AuthChallengeStep';
import styles from './formStyles.module.css';

export type { AuthChallengeState } from './AuthChallengeStep';

/**
 * The route around `AuthChallengeStep` — `/verify`.
 *
 * <p>The interaction itself (which endpoint redeems the code, what happens to each answer, where a
 * session lands) lives in `AuthChallengeStep`, because the deferred-authentication gate hosts the
 * very same step inside a modal. What is left here is what is genuinely about being a *page*: the
 * challenge arriving in router state, the heading, and the "this page was refreshed" recovery.
 *
 * <p><b>A refresh loses the challenge, on purpose.</b> Router state does not survive a reload, so a
 * reloaded page falls through to the "start again" notice below rather than persisting a live
 * authentication handle somewhere it could be read. Recovery costs the user one password entry.
 */
export default function AuthChallengePage() {
  const location = useLocation();
  const navigate = useNavigate();

  // A challenge-bearing step with no challenge is a shape the backend cannot produce — `register`,
  // `verify-email`, `login` and `login/otp` always populate `challenge` unless `nextStep` is
  // `LOGIN` or `AUTHENTICATED`, and the step below routes those two away before they reach state.
  // Treated as "no active flow" rather than trusted, because the alternative is a blank crash if
  // that ever stops being true.
  const raw = location.state as AuthChallengeState | null;
  const initial = raw?.challenge ? raw : null;
  /** Follows the conversation, so the heading tracks the step the customer is actually on. */
  const [nextStep, setNextStep] = useState(initial?.nextStep);

  if (!initial) {
    return (
      <div className="focused-page">
        <PageHeader title="התהליך פג" description="נדרשת התחלה מחדש" />
        <div className={styles.banner} role="alert">
          <p>
            לא נמצא תהליך אימות פעיל. ייתכן שהדף רוענן. אפשר להתחיל שוב מ
            <Link to="/login" className={styles.bannerLink}>מסך ההתחברות</Link>.
          </p>
        </div>
      </div>
    );
  }

  const copy = CHALLENGE_HEADINGS[nextStep ?? initial.nextStep];
  if (!copy) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="focused-page">
      <PageHeader title={copy.title} description={copy.description} />
      <AuthChallengeStep
        initial={initial}
        onStepChange={setNextStep}
        onExhausted={() => navigate('/login', { replace: true })}
      />
    </div>
  );
}
