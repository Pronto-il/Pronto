import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, Card, Input } from '../../shared/components';
import { useAuth } from '../../shared/hooks';
import { ApiError } from '../../shared/api';
import { AccountLockoutBanner } from './AccountLockoutBanner';
import styles from './formStyles.module.css';

export interface LoginFormProps {
  initialEmail?: string;
}

/**
 * Email + password login. No "forgot password" link — out of scope for v1.0.
 *
 * **MS2 visual redesign (design doc §4.2)**: fields now wrap in a `<Card>` for real visual
 * hierarchy (today the form rendered bare against the page background); banners fade in via
 * the shared `motion-list-item` CSS utility on mount. **Zero logic changes** — every state
 * variable, validation rule, and `ApiError` code branch below is unchanged.
 */
export function LoginForm({ initialEmail = '' }: LoginFormProps) {
  const { login } = useAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState(initialEmail);
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [lockedRetryAfterSeconds, setLockedRetryAfterSeconds] = useState<number | null>(null);
  const [unverifiedEmail, setUnverifiedEmail] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setFieldErrors({});
    setBannerError(null);
    setLockedRetryAfterSeconds(null);
    setUnverifiedEmail(null);

    const trimmedEmail = email.trim();
    const nextFieldErrors: { email?: string; password?: string } = {};
    if (!trimmedEmail) {
      nextFieldErrors.email = 'יש להזין כתובת אימייל.';
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
      const user = await login(trimmedEmail, password);
      navigate(user.role === 'PROFESSIONAL' ? '/pro' : '/', { replace: true });
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === 'ACCOUNT_LOCKED') {
          const details = error.details as { retryAfterSeconds?: number } | null;
          setLockedRetryAfterSeconds(details?.retryAfterSeconds ?? 0);
        } else if (error.code === 'INVALID_CREDENTIALS') {
          setBannerError('אימייל או סיסמה שגויים.');
        } else if (error.code === 'EMAIL_NOT_VERIFIED') {
          setBannerError('יש לאמת את כתובת האימייל לפני ההתחברות.');
          setUnverifiedEmail(trimmedEmail);
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
        {bannerError && (
          <div className={`${styles.banner} motion-list-item`} role="alert">
            <p>{bannerError}</p>
            {unverifiedEmail && (
              <Link to={`/verify?email=${encodeURIComponent(unverifiedEmail)}`} className={styles.bannerLink}>
                לאימות כתובת האימייל
              </Link>
            )}
          </div>
        )}
        <Input
          label="אימייל"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          error={fieldErrors.email}
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
          התחברות
        </Button>
      </form>
    </Card>
  );
}
