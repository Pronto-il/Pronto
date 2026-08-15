import { useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Input } from '../../shared/components';
import { verifyEmail, ApiError } from '../../shared/api';
import styles from './formStyles.module.css';

export interface VerifyCodeFormProps {
  initialEmail?: string;
}

/** Distinct short Hebrew copy per verify error code — no raw code/message ever shown. */
const VERIFY_ERROR_MESSAGES: Record<string, string> = {
  INVALID_CODE: 'קוד האימות שגוי. יש לבדוק ולנסות שוב.',
  CODE_EXPIRED: 'קוד האימות פג תוקף. יש להירשם מחדש כדי לקבל קוד חדש.',
  EMAIL_ALREADY_VERIFIED: 'כתובת האימייל כבר אומתה. ניתן להתחבר.',
  CODE_ALREADY_CONSUMED: 'קוד האימות כבר נוצל.',
};

/**
 * 6-digit verification code entry. A single numeric input (`maxLength=6`) rather than
 * segmented OTP boxes — simpler, no meaningful UX loss. No resend-code link — that
 * endpoint doesn't exist.
 */
export function VerifyCodeForm({ initialEmail = '' }: VerifyCodeFormProps) {
  const navigate = useNavigate();

  const [email, setEmail] = useState(initialEmail);
  const [code, setCode] = useState('');
  const [emailError, setEmailError] = useState<string | undefined>();
  const [codeError, setCodeError] = useState<string | undefined>();
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setEmailError(undefined);
    setCodeError(undefined);
    setBannerError(null);

    const trimmedEmail = email.trim();
    let hasError = false;
    if (!trimmedEmail) {
      setEmailError('יש להזין כתובת אימייל.');
      hasError = true;
    }
    if (!/^\d{6}$/.test(code)) {
      setCodeError('יש להזין קוד בן 6 ספרות.');
      hasError = true;
    }
    if (hasError) {
      return;
    }

    setIsSubmitting(true);
    try {
      await verifyEmail({ email: trimmedEmail, code });
      navigate(`/login?email=${encodeURIComponent(trimmedEmail)}`, { replace: true });
    } catch (error) {
      if (error instanceof ApiError && VERIFY_ERROR_MESSAGES[error.code]) {
        setBannerError(VERIFY_ERROR_MESSAGES[error.code]);
      } else {
        setBannerError('משהו השתבש, נסו שוב.');
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit} noValidate>
      {bannerError && (
        <div className={styles.banner} role="alert">
          <p>{bannerError}</p>
        </div>
      )}
      <Input
        label="אימייל"
        type="email"
        autoComplete="email"
        value={email}
        onChange={(event) => setEmail(event.target.value)}
        error={emailError}
        required
      />
      <Input
        label="קוד אימות"
        inputMode="numeric"
        maxLength={6}
        value={code}
        onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
        error={codeError}
        hint="שלחנו קוד בן 6 ספרות לכתובת האימייל שלך"
        required
      />
      <Button type="submit" loading={isSubmitting} fullWidth>
        אימות
      </Button>
    </form>
  );
}
