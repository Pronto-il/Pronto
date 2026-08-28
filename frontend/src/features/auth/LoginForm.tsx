import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, Card, Input } from '../../shared/components';
import { ApiError, login as loginRequest } from '../../shared/api';
import { AccountLockoutBanner } from './AccountLockoutBanner';
import { LoginRateLimitBanner } from './LoginRateLimitBanner';
import type { AuthChallengeState } from './AuthChallengePage';
import { useSessionLanding } from './useSessionLanding';
import styles from './formStyles.module.css';

export interface LoginFormProps {
  initialIdentifier?: string;
}

/**
 * Step one of login: identifier + password.
 *
 * <p><b>Production MS1 changed what this screen does.</b> It used to sign the user in. It now
 * checks the password and, normally, nothing else — the session is issued one step later, at
 * `/verify`, once the one-time password is redeemed. That is the milestone's central rule made
 * visible in the UI.
 *
 * <p><b>Which server decides, not this component.</b> A backend running with
 * `AUTH_OTP_REQUIRED=false` answers `login` with `AUTHENTICATED` and a session instead of a
 * challenge, and this form lands it directly. There is deliberately no client-side flag mirroring
 * the server's: the form branches on the `nextStep` it is given, so the two can never disagree
 * about whether a second factor is coming, and no build of the frontend is specific to either mode.
 *
 * <p><b>One identifier field, not a toggle.</b> The field accepts an email address or a phone
 * number and the server works out which; asking the user to classify their own identifier first is
 * a question with no wrong answer worth collecting, and both resolve to the same account anyway.
 */
export function LoginForm({ initialIdentifier = '' }: LoginFormProps) {
  const navigate = useNavigate();
  const land = useSessionLanding();

  const [identifier, setIdentifier] = useState(initialIdentifier);
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<{ identifier?: string; password?: string }>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [lockedRetryAfterSeconds, setLockedRetryAfterSeconds] = useState<number | null>(null);
  const [rateLimitedRetryAfterSeconds, setRateLimitedRetryAfterSeconds] = useState<number | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setFieldErrors({});
    setBannerError(null);
    setLockedRetryAfterSeconds(null);
    setRateLimitedRetryAfterSeconds(null);

    const trimmed = identifier.trim();
    const nextFieldErrors: { identifier?: string; password?: string } = {};
    if (!trimmed) {
      nextFieldErrors.identifier = 'יש להזין אימייל או מספר טלפון.';
    }
    if (!password) {
      nextFieldErrors.password = 'יש להזין סיסמה.';
    }
    if (Object.keys(nextFieldErrors).length > 0) {
      setFieldErrors(nextFieldErrors);
      return;
    }

    setIsSubmitting(true);
    try {
      const response = await loginRequest({ identifier: trimmed, password });
      // The backend runs with `AUTH_OTP_REQUIRED=false` (see auth.config.AuthOtpPolicy): a correct
      // password completes the login and the session arrives here rather than one step later. Landed
      // through the same hook the OTP screen uses, so the token is persisted and the destination
      // chosen identically in both modes — there is no second copy of that logic to drift.
      if (response.nextStep === 'AUTHENTICATED' && response.session) {
        await land(response.session);
        return;
      }
      if (!response.challenge) {
        setBannerError('משהו השתבש, נסו שוב.');
        return;
      }
      // The server decides whether this is an ordinary second factor or a resumed registration
      // (a correct password on an account that never verified its email).
      const state: AuthChallengeState = {
        nextStep: response.nextStep,
        challenge: response.challenge,
      };
      navigate('/verify', { state });
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === 'ACCOUNT_LOCKED') {
          const details = error.details as { retryAfterSeconds?: number } | null;
          setLockedRetryAfterSeconds(details?.retryAfterSeconds ?? 0);
        } else if (error.code === 'RATE_LIMITED') {
          const details = error.details as { retryAfterSeconds?: number } | null;
          setRateLimitedRetryAfterSeconds(details?.retryAfterSeconds ?? 0);
        } else if (error.code === 'INVALID_CREDENTIALS') {
          // Deliberately one message for "no such account" and "wrong password" — the backend does
          // not distinguish them either, and a client that invented a distinction would undo that.
          setBannerError('הפרטים שהוזנו אינם נכונים.');
        } else if (error.code === 'OTP_DELIVERY_FAILED') {
          setBannerError('לא הצלחנו לשלוח את קוד האימות. נסו שוב בעוד רגע.');
        } else {
          setBannerError('משהו השתבש, נסו שוב.');
        }
      } else {
        setBannerError('משהו השתבש, נסו שוב.');
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <Card>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        {lockedRetryAfterSeconds !== null && (
          <div className="motion-list-item">
            <AccountLockoutBanner retryAfterSeconds={lockedRetryAfterSeconds} />
          </div>
        )}
        {rateLimitedRetryAfterSeconds !== null && (
          <div className="motion-list-item">
            <LoginRateLimitBanner retryAfterSeconds={rateLimitedRetryAfterSeconds} />
          </div>
        )}
        {bannerError && (
          <div className={`${styles.banner} motion-list-item`} role="alert">
            <p>{bannerError}</p>
          </div>
        )}
        <Input
          label="אימייל או טלפון"
          type="text"
          autoComplete="username"
          value={identifier}
          onChange={(event) => setIdentifier(event.target.value)}
          error={fieldErrors.identifier}
          hint="אפשר להתחבר עם כתובת האימייל או עם מספר הטלפון"
          required
        />
        <Input
          label="סיסמה"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          error={fieldErrors.password}
          required
        />
        <Button type="submit" loading={isSubmitting} fullWidth>
          המשך
        </Button>
        <Link to="/password-reset" className={styles.footerLink}>
          שכחתם סיסמה?
        </Link>
      </form>
    </Card>
  );
}
