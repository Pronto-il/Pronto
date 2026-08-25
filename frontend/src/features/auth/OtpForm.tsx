import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Button, Card, Input } from '../../shared/components';
import { ApiError, resendOtp, type OtpChallenge } from '../../shared/api';
import styles from './formStyles.module.css';

/** Matches `OtpService.RESEND_COOLDOWN_SECONDS`. */
const RESEND_COOLDOWN_SECONDS = 60;

export interface OtpFormProps {
  /** The challenge to redeem. Replaced in place when the user resends. */
  challenge: OtpChallenge;
  /** Called with the entered six digits. Rejects with `ApiError` on any redemption failure. */
  onSubmit: (code: string) => Promise<void>;
  /** Told about a resend so the parent can keep the authoritative challenge id. */
  onResent: (challenge: OtpChallenge) => void;
  submitLabel: string;
  /** Optional line above the code field, e.g. which step of registration this is. */
  intro?: string;
}

/** Distinct short Hebrew copy per error code — no raw code or backend message is ever shown. */
const OTP_ERROR_MESSAGES: Record<string, string> = {
  INVALID_CODE: 'הקוד שגוי. יש לבדוק ולנסות שוב.',
  CODE_EXPIRED: 'הקוד פג תוקף. יש לבקש קוד חדש.',
  CODE_ALREADY_CONSUMED: 'הקוד הזה כבר נוצל. יש לבקש קוד חדש.',
  OTP_ATTEMPTS_EXCEEDED: 'יותר מדי ניסיונות שגויים. יש לבקש קוד חדש.',
  EMAIL_ALREADY_VERIFIED: 'כתובת האימייל כבר אומתה.',
  PHONE_ALREADY_VERIFIED: 'מספר הטלפון כבר אומת.',
  EMAIL_NOT_VERIFIED: 'יש לאמת קודם את כתובת האימייל.',
  OTP_DELIVERY_FAILED: 'לא הצלחנו לשלוח את הקוד. אפשר לנסות לשלוח שוב.',
  RATE_LIMITED: 'בקשתם קודים רבים מדי. יש להמתין ולנסות שוב.',
};

/**
 * The one-time-password step, shared by every flow that has one: email verification, phone
 * verification, login's second factor and password reset.
 *
 * <p>One component rather than four, because the interaction is identical in all four — six digits,
 * a masked destination, a resend with a visible cooldown, and an expiry — and because four copies is
 * how "the login screen got the attempt-limit message and the registration screen didn't" happens.
 *
 * <p><b>What it deliberately does not do:</b> auto-submit on the sixth digit. A mistyped digit would
 * fire a request the user did not intend, and there are only five attempts before the challenge is
 * dead. The user presses the button.
 */
export function OtpForm({ challenge, onSubmit, onResent, submitLabel, intro }: OtpFormProps) {
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | undefined>();
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isResending, setIsResending] = useState(false);
  const [notice, setNotice] = useState<string | null>(
    // A challenge whose message never left the building leads with "resend", not with a code field.
    challenge.delivered ? null : 'לא הצלחנו לשלוח את הקוד. אפשר לנסות לשלוח שוב.',
  );

  const [cooldown, setCooldown] = useState(RESEND_COOLDOWN_SECONDS);
  const [secondsLeft, setSecondsLeft] = useState(challenge.expiresInSeconds);

  // Restart both clocks whenever the challenge is replaced, so a resend resets the cooldown and the
  // expiry rather than continuing to count down the previous code's life.
  const challengeId = challenge.challengeId;
  useEffect(() => {
    setCooldown(RESEND_COOLDOWN_SECONDS);
    setSecondsLeft(challenge.expiresInSeconds);
    setCode('');
    setCodeError(undefined);
  }, [challengeId, challenge.expiresInSeconds]);

  const tick = useRef<number | undefined>(undefined);
  useEffect(() => {
    tick.current = window.setInterval(() => {
      setCooldown((value) => (value > 0 ? value - 1 : 0));
      setSecondsLeft((value) => (value > 0 ? value - 1 : 0));
    }, 1000);
    return () => window.clearInterval(tick.current);
  }, []);

  const describeError = useCallback((error: unknown): string => {
    if (error instanceof ApiError && OTP_ERROR_MESSAGES[error.code]) {
      return OTP_ERROR_MESSAGES[error.code];
    }
    return 'משהו השתבש, נסו שוב.';
  }, []);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setCodeError(undefined);
    setBannerError(null);
    setNotice(null);

    if (!/^\d{6}$/.test(code)) {
      setCodeError('יש להזין קוד בן 6 ספרות.');
      return;
    }

    setIsSubmitting(true);
    try {
      await onSubmit(code);
    } catch (error) {
      setBannerError(describeError(error));
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleResend() {
    setBannerError(null);
    setNotice(null);
    setIsResending(true);
    try {
      const next = await resendOtp(challenge.challengeId);
      onResent(next);
      setNotice('שלחנו קוד חדש.');
    } catch (error) {
      setBannerError(describeError(error));
    } finally {
      setIsResending(false);
    }
  }

  const channelLabel = challenge.channel === 'SMS' ? 'הודעת SMS' : 'אימייל';
  const expired = secondsLeft <= 0;

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

        {intro && <p className={styles.stepHint}>{intro}</p>}
        <p className={styles.stepHint}>
          שלחנו קוד בן 6 ספרות ב{channelLabel} אל <strong dir="ltr">{challenge.destinationMasked}</strong>
        </p>

        <Input
          label="קוד אימות"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          value={code}
          onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
          error={codeError}
          hint={
            expired
              ? 'תוקף הקוד פג — יש לבקש קוד חדש.'
              : `הקוד תקף עוד ${Math.ceil(secondsLeft / 60)} דקות`
          }
          required
        />

        <Button type="submit" loading={isSubmitting} disabled={expired} fullWidth>
          {submitLabel}
        </Button>

        <Button
          type="button"
          variant="secondary"
          onClick={handleResend}
          loading={isResending}
          disabled={cooldown > 0}
          fullWidth
        >
          {cooldown > 0 ? `שליחת קוד חדש (${cooldown})` : 'שליחת קוד חדש'}
        </Button>
      </form>
    </Card>
  );
}
