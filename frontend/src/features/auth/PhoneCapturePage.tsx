import { useState } from 'react';
import type { FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { Button, Card, Input, PageHeader } from '../../shared/components';
import { useAuth } from '../../shared/hooks';
import { ApiError, capturePhone, verifyPhone, PHONE_INPUT_MAX_LENGTH, type OtpChallenge } from '../../shared/api';
import { OtpForm } from './OtpForm';
import styles from './formStyles.module.css';

const CAPTURE_ERROR_MESSAGES: Record<string, string> = {
  DUPLICATE_PHONE: 'מספר הטלפון הזה כבר רשום בחשבון אחר.',
  VALIDATION_ERROR: 'מספר הטלפון אינו תקין. יש להזין מספר נייד ישראלי.',
  PHONE_ALREADY_VERIFIED: 'מספר הטלפון כבר אומת.',
  RATE_LIMITED: 'נשלחו יותר מדי בקשות. יש להמתין ולנסות שוב.',
  OTP_DELIVERY_FAILED: 'לא הצלחנו לשלוח את הקוד. נסו שוב בעוד רגע.',
};

/**
 * Phone capture for an account that does not have a verified number — the way out of the legacy
 * cohort.
 *
 * <p>Every account created before Production MS1 is here: professionals and operators never had a
 * phone number at all, and a customer's was never confirmed. Those accounts sign in normally, and
 * the backend refuses their bookings, SOS activations and marketplace listing with
 * `PHONE_VERIFICATION_REQUIRED` until they finish this screen. The refusal is the rule; this screen
 * is how a user satisfies it instead of hitting a dead end.
 */
export default function PhoneCapturePage() {
  const navigate = useNavigate();
  const { user, isLoading, establishSession } = useAuth();
  const [challenge, setChallenge] = useState<OtpChallenge | null>(null);

  if (isLoading) {
    return null;
  }
  // Two ways this screen has nothing to do, and they are different states that must not be
  // conflated. The number is already proved — or nobody is being asked to prove one, because the
  // deployment is running with phone verification (or all of OTP) switched off. In the second case
  // `phoneVerified` is still `false` and correctly so: the number genuinely was not proved. What
  // changed is that no one is asking. Offering the capture form here would send a code through a
  // channel the backend has switched off — `AuthService#capturePhone` refuses it under this policy
  // — and leave the user watching for an SMS that is never coming.
  if (user?.phoneVerified || user?.phoneVerificationRequired === false) {
    return <Navigate to="/" replace />;
  }

  return (
    <div className="focused-page">
      <PageHeader
        title="אימות מספר טלפון"
        description="כדי להזמין בעל מקצוע צריך מספר טלפון מאומת"
      />
      {challenge === null ? (
        <CaptureStage onIssued={setChallenge} />
      ) : (
        <OtpForm
          challenge={challenge}
          submitLabel="אימות המספר"
          onResent={setChallenge}
          onSubmit={async (code) => {
            const response = await verifyPhone({ challengeId: challenge.challengeId, code });
            // Adopt the session this returns rather than discarding it. `verify-phone` mints a fresh
            // token for anyone who proves both channels, so ignoring it left a valid, unused JWT in
            // circulation while the client kept the older one. Adopting it also refreshes `user`,
            // which is what clears the phone-capture prompt.
            if (response.session) {
              await establishSession(response.session);
            }
            navigate('/', { replace: true });
          }}
        />
      )}
    </div>
  );
}

function CaptureStage({ onIssued }: { onIssued: (challenge: OtpChallenge) => void }) {
  const [phone, setPhone] = useState('');
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setFieldError(undefined);
    setBannerError(null);

    if (!phone.trim()) {
      setFieldError('יש להזין מספר טלפון.');
      return;
    }

    setIsSubmitting(true);
    try {
      onIssued(await capturePhone(phone.trim()));
    } catch (error) {
      if (error instanceof ApiError && error.code === 'VALIDATION_ERROR') {
        setFieldError(CAPTURE_ERROR_MESSAGES.VALIDATION_ERROR);
      } else if (error instanceof ApiError && CAPTURE_ERROR_MESSAGES[error.code]) {
        setBannerError(CAPTURE_ERROR_MESSAGES[error.code]);
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
        {bannerError && (
          <div className={`${styles.banner} motion-list-item`} role="alert">
            <p>{bannerError}</p>
          </div>
        )}
        <p className={styles.stepHint}>
          נשלח קוד בן 6 ספרות ב-SMS. המספר משמש גם ליצירת קשר בזמן הזמנה וגם להתחברות לחשבון.
        </p>
        <Input
          label="טלפון נייד"
          type="tel"
          inputMode="tel"
          autoComplete="tel"
          dir="ltr"
          value={phone}
          onChange={(event) => setPhone(event.target.value)}
          error={fieldError}
          maxLength={PHONE_INPUT_MAX_LENGTH}
          hint="למשל 050-1234567"
          required
        />
        <Button type="submit" loading={isSubmitting} fullWidth>
          שליחת קוד
        </Button>
      </form>
    </Card>
  );
}
