import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, Card, Input } from '../../shared/components';
import { ApiError, login as loginRequest } from '../../shared/api';
import { AccountLockoutBanner } from './AccountLockoutBanner';
import { LoginRateLimitBanner } from './LoginRateLimitBanner';
import type { AuthChallengeState } from './AuthChallengePage';
import styles from './formStyles.module.css';

export interface LoginFormProps {
  initialIdentifier?: string;
}

/**
 * Step one of login: identifier + password.
 *
 * <p><b>Production MS1 changed what this screen does.</b> It used to sign the user in. It now
 * checks the password and nothing else — the session is issued one step later, at
 * `/verify`, once the one-time password is redeemed. That is the milestone's central rule made
 * visible in the UI: there is no path from this form directly to an authenticated screen.
 *
 * <p><b>One identifier field, not a toggle.</b> The field accepts an email address or a phone
 * number and the server works out which; asking the user to classify their own identifier first is
 * a question with no wrong answer worth collecting, and both resolve to the same account anyway.
 */
export function LoginForm({ initialIdentifier = '' }: LoginFormProps) {
  const navigate = useNavigate();

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
