import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, Card, Input, PageHeader } from '../../shared/components';
import {
  ApiError,
  confirmPasswordReset,
  requestPasswordReset,
  resendOtp,
  type OtpChallenge,
} from '../../shared/api';
import styles from './formStyles.module.css';

/**
 * Password recovery, both halves.
 *
 * <p><b>The screen never says whether the account exists</b>, because the API never does. A request
 * for an unregistered address returns a perfectly ordinary challenge that simply refers to nothing,
 * so this component can render the same "we sent you a code" step in both cases without any special
 * handling — which is exactly the point. There is no branch here to get wrong.
 *
 * <p>The consequence, accepted deliberately: somebody who mistypes their address is told a code was
 * sent and never receives one. Making that honest would require telling an anonymous caller whether
 * an address is registered.
 */
export default function PasswordResetPage() {
  const navigate = useNavigate();
  const [challenge, setChallenge] = useState<OtpChallenge | null>(null);

  return (
    <div className="focused-page">
      {challenge === null ? (
        <>
          <PageHeader
            title="איפוס סיסמה"
            description="נשלח קוד לכתובת האימייל המאומתת של החשבון"
          />
          <RequestStage onIssued={setChallenge} />
        </>
      ) : (
        <>
          <PageHeader title="בחירת סיסמה חדשה" description="הזינו את הקוד ששלחנו ואת הסיסמה החדשה" />
          <ConfirmStage
            challenge={challenge}
            onChallengeReplaced={setChallenge}
            onDone={() => navigate('/login', { replace: true })}
          />
        </>
      )}
    </div>
  );
}

function RequestStage({ onIssued }: { onIssued: (challenge: OtpChallenge) => void }) {
  const [identifier, setIdentifier] = useState('');
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setFieldError(undefined);
    setBannerError(null);

    const trimmed = identifier.trim();
    if (!trimmed) {
      setFieldError('יש להזין אימייל או מספר טלפון.');
      return;
    }

    setIsSubmitting(true);
    try {
      onIssued(await requestPasswordReset(trimmed));
    } catch (error) {
      // Only transport-level or rate-limit failures can land here — the endpoint itself answers
      // identically for accounts that exist and accounts that do not.
      setBannerError(
        error instanceof ApiError && error.code === 'RATE_LIMITED'
          ? 'נשלחו יותר מדי בקשות. יש להמתין ולנסות שוב.'
          : 'משהו השתבש, נסו שוב.',
      );
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <Card>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
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
          error={fieldError}
          hint="הקוד נשלח תמיד לכתובת האימייל המאומתת של החשבון"
          required
        />
        <Button type="submit" loading={isSubmitting} fullWidth>
          שליחת קוד
        </Button>
        <Link to="/login" className={styles.footerLink}>
          חזרה להתחברות
        </Link>
      </form>
    </Card>
  );
}

const CONFIRM_ERROR_MESSAGES: Record<string, string> = {
  INVALID_CODE: 'הקוד שגוי. יש לבדוק ולנסות שוב.',
  CODE_EXPIRED: 'הקוד פג תוקף. יש לבקש קוד חדש.',
  CODE_ALREADY_CONSUMED: 'הקוד הזה כבר נוצל. יש לבקש קוד חדש.',
  OTP_ATTEMPTS_EXCEEDED: 'יותר מדי ניסיונות שגויים. יש לבקש קוד חדש.',
  VALIDATION_ERROR: 'הסיסמה חייבת להכיל לפחות 8 תווים.',
  RATE_LIMITED: 'נשלחו יותר מדי בקשות. יש להמתין ולנסות שוב.',
};

function ConfirmStage({
  challenge,
  onChallengeReplaced,
  onDone,
}: {
  challenge: OtpChallenge;
  onChallengeReplaced: (challenge: OtpChallenge) => void;
  onDone: () => void;
}) {
  const [code, setCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [errors, setErrors] = useState<{ code?: string; newPassword?: string; confirmPassword?: string }>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isResending, setIsResending] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);
    setNotice(null);

    const next: typeof errors = {};
    if (!/^\d{6}$/.test(code)) {
      next.code = 'יש להזין קוד בן 6 ספרות.';
    }
    if (newPassword.length < 8) {
      next.newPassword = 'הסיסמה חייבת להכיל לפחות 8 תווים.';
    }
    // Confirmation is frontend-only, exactly as it is at registration — it is never sent.
    if (newPassword !== confirmPassword) {
      next.confirmPassword = 'הסיסמאות אינן תואמות.';
    }
    setErrors(next);
    if (Object.keys(next).length > 0) {
      return;
    }

    setIsSubmitting(true);
    try {
      await confirmPasswordReset({ challengeId: challenge.challengeId, code, newPassword });
      onDone();
    } catch (error) {
      setBannerError(
        error instanceof ApiError && CONFIRM_ERROR_MESSAGES[error.code]
          ? CONFIRM_ERROR_MESSAGES[error.code]
          : 'משהו השתבש, נסו שוב.',
      );
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleResend() {
    setBannerError(null);
    setIsResending(true);
    try {
      onChallengeReplaced(await resendOtp(challenge.challengeId));
      setNotice('שלחנו קוד חדש.');
      setCode('');
    } catch (error) {
      setBannerError(
        error instanceof ApiError && CONFIRM_ERROR_MESSAGES[error.code]
          ? CONFIRM_ERROR_MESSAGES[error.code]
          : 'משהו השתבש, נסו שוב.',
      );
    } finally {
      setIsResending(false);
    }
  }

  return (
    <Card>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        {bannerError && (
          <div className={`${styles.banner} motion-list-item`} role="alert">
            <p>{bannerError}</p>
          </div>
        )}
        {notice && (
          <div className={`${styles.banner} motion-list-item`} role="status">
            <p>{notice}</p>
          </div>
        )}
        <p className={styles.stepHint}>
          אם קיים חשבון מתאים, שלחנו אליו קוד בן 6 ספרות אל{' '}
          <strong dir="ltr">{challenge.destinationMasked}</strong>
        </p>
        <Input
          label="קוד אימות"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          value={code}
          onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
          error={errors.code}
          required
        />
        <Input
          label="סיסמה חדשה"
          type="password"
          autoComplete="new-password"
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
          error={errors.newPassword}
          hint="לפחות 8 תווים"
          required
        />
        <Input
          label="אימות סיסמה חדשה"
          type="password"
          autoComplete="new-password"
          value={confirmPassword}
          onChange={(event) => setConfirmPassword(event.target.value)}
          error={errors.confirmPassword}
          required
        />
        <Button type="submit" loading={isSubmitting} fullWidth>
          עדכון הסיסמה
        </Button>
        <Button type="button" variant="secondary" onClick={handleResend} loading={isResending} fullWidth>
          שליחת קוד חדש
        </Button>
      </form>
    </Card>
  );
}
