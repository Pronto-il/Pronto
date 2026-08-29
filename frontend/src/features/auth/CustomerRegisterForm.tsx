import { useState } from 'react';
import type { FormEvent } from 'react';
import { Input } from '../../shared/components';
import { registerCustomer, getFieldErrorMessages, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { AuthStepResponse } from '../../shared/api';
import { RegistrationWizardShell } from './RegistrationWizardShell';
import { isBlocking, useContactAvailability } from './useContactAvailability';
import {
  mapDuplicateContactError,
  validateConfirmPassword,
  validateEmail,
  validateFullName,
  validatePassword,
  validatePhone,
} from './registrationValidation';
import styles from './formStyles.module.css';

export interface CustomerRegisterFormProps {
  /** Production MS1: the registration response's OTP challenge, not the email address — the
   *  next screen redeems a challenge, and the email is no longer part of that conversation. */
  onSuccess: (response: AuthStepResponse) => void;
  /** Stage 1's back button — exits the wizard entirely (design doc §6.1). */
  onExit: () => void;
}

interface FormErrors {
  fullName?: string;
  email?: string;
  password?: string;
  confirmPassword?: string;
  phone?: string;
}

const TOTAL_STAGES = 2;

/**
 * Customer registration — a 2-stage progressive wizard (design doc §6.2, with the address stage
 * removed): (1) account details (name/email/password/confirm/phone), (2) read-only confirmation
 * plus the real submit, which fires one `POST /api/auth/register`.
 *
 * ## Every field is settled before stage 2 is reachable
 *
 * The confirmation screen used to be where a customer discovered that their phone number was
 * malformed, or that the address they had just chosen a password for was already registered — the
 * whole form filled in, reviewed, submitted, and only then bounced back to stage 1. Nothing is
 * deferred to submit any more:
 *
 * * **Locally answerable rules** — name length, email shape, phone shape, password length,
 *   confirmation match — live in `registrationValidation.ts` and run on blur *and* again on
 *   Continue, so a field the customer never focused still cannot slip through.
 * * **Uniqueness**, which no browser can know, is asked of `POST /api/auth/availability` when the
 *   email and phone fields lose focus (`useContactAvailability`). The answer lands under the field.
 * * **Continue awaits both checks**, so pressing it before a blur-triggered request has come back
 *   waits for the verdict rather than racing past it.
 *
 * ## What is deliberately still checked at submit
 *
 * `DUPLICATE_EMAIL`/`DUPLICATE_PHONE` handling below is unchanged and is not redundant. The
 * availability answer is advisory — true when given, and possibly false a minute later while the
 * customer picks a password — and the backend's own duplicate checks plus `ux_users_email` /
 * `ux_users_phone` are what actually decide. A form that treated the early answer as permission
 * would simply be a form with an unhandled 409 in it.
 *
 * ## Why there is no address stage any more
 *
 * A home address was required to open an account, and an address is not an account detail — it is
 * a detail of a *job*. The job's address is collected in the booking flow regardless, right after
 * the AI has classified the issue and immediately before anything needs it, so asking at
 * registration bought nothing but a mandatory extra screen — and, for somebody booking on behalf
 * of a parent, a saved default that was wrong from the first day.
 *
 * A customer who wants a saved home address still gets one: the booking flow's
 * "הפוך את זה לכתובת הבית" option and the profile screen both write the same validated,
 * place-id-carrying address this stage used to.
 *
 * The backend is what makes this safe rather than merely convenient: `customer.defaultAddress` is
 * optional on `POST /api/auth/register`, and `users.default_*` has always been nullable — an
 * account with no address is a state the data model already supported, and `GET /api/users/me`
 * has always answered `defaultAddress: null` for it.
 */
export function CustomerRegisterForm({ onSuccess, onExit }: CustomerRegisterFormProps) {
  const [currentStage, setCurrentStage] = useState(1);
  const [direction, setDirection] = useState(1);

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [phone, setPhone] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isAdvancing, setIsAdvancing] = useState(false);

  const emailAvailability = useContactAvailability('EMAIL', email);
  const phoneAvailability = useContactAvailability('PHONE', phone);

  function validateStage1(): FormErrors {
    const next: FormErrors = {};
    const rules: Array<[keyof FormErrors, string | undefined]> = [
      ['fullName', validateFullName(fullName)],
      ['email', validateEmail(email)],
      ['phone', validatePhone(phone)],
      ['password', validatePassword(password)],
      ['confirmPassword', validateConfirmPassword(password, confirmPassword)],
    ];
    for (const [field, message] of rules) {
      if (message) {
        next[field] = message;
      }
    }
    return next;
  }

  function goToStage(stage: number, dir: number) {
    setDirection(dir);
    setCurrentStage(stage);
  }

  /**
   * Validates one field the moment the customer leaves it, and — for the two fields whose rule
   * lives on the server — asks the backend as well. Nothing here can be triggered by typing: see
   * `useContactAvailability` for why the availability endpoint is blur-driven rather than debounced.
   */
  function handleBlur(field: keyof FormErrors, message: string | undefined) {
    setErrors((prev) => ({ ...prev, [field]: message }));
    if (message) {
      return;
    }
    if (field === 'email') {
      void emailAvailability.check(email);
    } else if (field === 'phone') {
      void phoneAvailability.check(phone);
    }
  }

  /**
   * Routes a submit-time field error back to the stage that owns it (design doc §6.3). Every
   * field this form still has belongs to stage 1; a generic/banner-level error (no field mapping)
   * stays on the confirmation stage, where the submit button lives.
   */
  function routeFieldErrors(nextErrors: FormErrors) {
    if (nextErrors.fullName || nextErrors.email || nextErrors.password || nextErrors.phone) {
      goToStage(1, -1);
    }
  }

  /**
   * Stage 1's Continue. Refuses to advance while anything is known to be wrong, and **waits** for
   * an availability answer rather than assuming one: a customer who fills the last field and hits
   * Continue immediately would otherwise sail past a check that was still in flight, which is the
   * exact "found out on the summary screen" failure this is meant to end. Both checks run
   * concurrently, and a value already answered for resolves from cache without a request.
   */
  async function advanceFromStage1(): Promise<void> {
    const stage1Errors = validateStage1();
    setErrors({
      fullName: stage1Errors.fullName,
      email: stage1Errors.email,
      password: stage1Errors.password,
      confirmPassword: stage1Errors.confirmPassword,
      phone: stage1Errors.phone,
    });
    if (Object.keys(stage1Errors).length > 0) {
      return;
    }

    setIsAdvancing(true);
    try {
      const [emailStatus, phoneStatus] = await Promise.all([
        emailAvailability.check(email),
        phoneAvailability.check(phone),
      ]);
      if (isBlocking(emailStatus) || isBlocking(phoneStatus)) {
        return;
      }
    } finally {
      setIsAdvancing(false);
    }

    goToStage(2, 1);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();

    if (currentStage === 1) {
      await advanceFromStage1();
      return;
    }

    setBannerError(null);
    setIsSubmitting(true);
    try {
      const response = await registerCustomer({
        fullName: fullName.trim(),
        email: email.trim(),
        password,
        phone: phone.trim(),
      });
      onSuccess(response);
    } catch (error) {
      // Still handled, and still necessary: the availability check on stage 1 is advisory, and
      // the account may have been registered by somebody else in the meantime.
      const duplicate = mapDuplicateContactError(error);
      if (duplicate) {
        const nextErrors: FormErrors = { [duplicate.field]: duplicate.message };
        setErrors((prev) => ({ ...prev, ...nextErrors }));
        routeFieldErrors(nextErrors);
      } else {
        const fieldErrors = getFieldErrorMessages(error);
        if (fieldErrors) {
          const nextTopErrors: Pick<FormErrors, 'fullName' | 'email' | 'password' | 'phone'> = {};
          for (const [field, message] of Object.entries(fieldErrors)) {
            if (field === 'fullName' || field === 'email' || field === 'password' || field === 'phone') {
              nextTopErrors[field] = message;
            }
          }
          setErrors((prev) => ({ ...prev, ...nextTopErrors }));
          routeFieldErrors(nextTopErrors);
        } else {
          setBannerError(GENERIC_ERROR_MESSAGE);
        }
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  function handleBack() {
    if (currentStage === 1) {
      onExit();
      return;
    }
    goToStage(currentStage - 1, -1);
  }

  const primaryLabel = currentStage === TOTAL_STAGES ? 'יצירת חשבון' : 'המשך';
  // A local rule always wins over a server answer: a malformed address has no availability, and
  // showing "this email is taken" under text that is not an email address would be nonsense.
  const emailError = errors.email ?? emailAvailability.error;
  const phoneError = errors.phone ?? phoneAvailability.error;

  return (
    <form onSubmit={handleSubmit} noValidate>
      {currentStage === TOTAL_STAGES && bannerError && (
        <div className={`${styles.banner} motion-list-item`} role="alert">
          <p>{bannerError}</p>
        </div>
      )}

      <RegistrationWizardShell
        title="הרשמה כלקוח"
        currentStage={currentStage}
        totalStages={TOTAL_STAGES}
        direction={direction}
        onBack={handleBack}
        primaryLabel={primaryLabel}
        primaryLoading={isSubmitting || isAdvancing}
      >
        {currentStage === 1 && (
          <>
            <Input
              label="שם מלא"
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
              onBlur={() => handleBlur('fullName', validateFullName(fullName))}
              error={errors.fullName}
              autoComplete="name"
              required
            />
            <Input
              label="אימייל"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              onBlur={() => handleBlur('email', validateEmail(email))}
              error={emailError}
              hint={emailAvailability.status === 'checking' ? 'בודקים את כתובת האימייל…' : undefined}
              autoComplete="email"
              required
            />
            <Input
              label="טלפון"
              type="tel"
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
              onBlur={() => handleBlur('phone', validatePhone(phone))}
              error={phoneError}
              hint={
                phoneAvailability.status === 'checking'
                  ? 'בודקים את מספר הטלפון…'
                  : 'מספר נייד, למשל 050-1234567'
              }
              autoComplete="tel"
              required
            />
            <Input
              label="סיסמה"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              onBlur={() => handleBlur('password', validatePassword(password))}
              error={errors.password}
              autoComplete="new-password"
              hint="לפחות 8 תווים"
              required
            />
            <Input
              label="אימות סיסמה"
              type="password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
              onBlur={() =>
                handleBlur('confirmPassword', validateConfirmPassword(password, confirmPassword))
              }
              error={errors.confirmPassword}
              autoComplete="new-password"
              required
            />
          </>
        )}

        {currentStage === 2 && (
          <dl className={styles.summaryList}>
            <div className={styles.summaryRow}>
              <dt>שם מלא</dt>
              <dd>{fullName}</dd>
            </div>
            <div className={styles.summaryRow}>
              <dt>אימייל</dt>
              <dd>{email}</dd>
            </div>
            <div className={styles.summaryRow}>
              <dt>טלפון</dt>
              <dd>{phone}</dd>
            </div>
          </dl>
        )}
      </RegistrationWizardShell>
    </form>
  );
}
