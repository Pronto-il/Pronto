import { useState } from 'react';
import type { FormEvent } from 'react';
import { Input, AddressFormFields, EMPTY_ADDRESS, validateAddress } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { registerCustomer, ApiError, getFieldErrorMessages, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { AuthStepResponse } from '../../shared/api';
import { RegistrationWizardShell } from './RegistrationWizardShell';
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
  address?: Partial<Record<keyof AddressValue, string>>;
}

const ADDRESS_FIELD_KEYS: (keyof AddressValue)[] = [
  'city',
  'street',
  'houseNumber',
  'apartment',
  'floor',
  'entrance',
  'addressNotes',
];

const TOTAL_STAGES = 3;

/**
 * Customer registration — a 3-stage progressive wizard (design doc §6.2): (1) basic info
 * (name/email/password/confirm/phone), (2) address (`AddressFormFields`, unchanged), (3)
 * read-only confirmation + the real submit, which fires the same, unchanged
 * `registerCustomer()` call (still one `POST /api/auth/register`, all fields collected
 * across 3 UI stages then sent together).
 */
export function CustomerRegisterForm({ onSuccess, onExit }: CustomerRegisterFormProps) {
  const [currentStage, setCurrentStage] = useState(1);
  const [direction, setDirection] = useState(1);

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState<AddressValue>(EMPTY_ADDRESS);
  const [errors, setErrors] = useState<FormErrors>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  function validateStage1(): Pick<FormErrors, 'fullName' | 'email' | 'password' | 'confirmPassword' | 'phone'> {
    const next: Pick<FormErrors, 'fullName' | 'email' | 'password' | 'confirmPassword' | 'phone'> = {};
    if (fullName.trim().length < 2) {
      next.fullName = 'יש להזין שם מלא (לפחות 2 תווים).';
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      next.email = 'יש להזין כתובת אימייל תקינה.';
    }
    if (password.length < 8) {
      next.password = 'הסיסמה חייבת להכיל לפחות 8 תווים.';
    }
    if (confirmPassword !== password) {
      next.confirmPassword = 'אימות הסיסמה אינו תואם לסיסמה שהוזנה.';
    }
    if (!phone.trim()) {
      next.phone = 'יש להזין מספר טלפון.';
    }
    return next;
  }

  /** Delegates to the shared rule, which also enforces "an address must have been selected".
   *  Previously three inline `if`s here, duplicated on three other screens -- only one of which
   *  would have grown the selection requirement. */
  function validateStage2(): Partial<Record<keyof AddressValue, string>> {
    return validateAddress(address);
  }

  function goToStage(stage: number, dir: number) {
    setDirection(dir);
    setCurrentStage(stage);
  }

  /**
   * Routes a submit-time field error back to the stage that owns it (design doc §6.3) —
   * `fullName`/`email`/`password`/`phone` belong to stage 1, address fields to stage 2. A
   * generic/banner-level error (no field mapping) stays on stage 3, where the submit button
   * lives.
   */
  function routeFieldErrors(nextErrors: FormErrors) {
    const hasTopError = Boolean(nextErrors.fullName || nextErrors.email || nextErrors.password || nextErrors.phone);
    const hasAddressError = Boolean(nextErrors.address && Object.keys(nextErrors.address).length > 0);
    if (hasTopError) {
      goToStage(1, -1);
    } else if (hasAddressError) {
      goToStage(2, -1);
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();

    if (currentStage === 1) {
      const stage1Errors = validateStage1();
      setErrors((prev) => ({
        ...prev,
        fullName: stage1Errors.fullName,
        email: stage1Errors.email,
        password: stage1Errors.password,
        confirmPassword: stage1Errors.confirmPassword,
        phone: stage1Errors.phone,
      }));
      if (Object.keys(stage1Errors).length > 0) {
        return;
      }
      goToStage(2, 1);
      return;
    }

    if (currentStage === 2) {
      const addressErrors = validateStage2();
      setErrors((prev) => ({ ...prev, address: addressErrors }));
      if (Object.keys(addressErrors).length > 0) {
        return;
      }
      goToStage(3, 1);
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
        address: {
          city: address.city.trim(),
          street: address.street.trim(),
          houseNumber: address.houseNumber.trim(),
          placeId: address.placeId ?? undefined,
          formattedAddress: address.formattedAddress ?? undefined,
          latitude: address.latitude ?? undefined,
          longitude: address.longitude ?? undefined,
          apartment: address.apartment,
          floor: address.floor,
          entrance: address.entrance,
          addressNotes: address.addressNotes,
        },
      });
      onSuccess(response);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'DUPLICATE_EMAIL') {
        const nextErrors: FormErrors = { email: 'כתובת האימייל הזו כבר רשומה במערכת.' };
        setErrors((prev) => ({ ...prev, ...nextErrors }));
        routeFieldErrors(nextErrors);
      } else {
        const fieldErrors = getFieldErrorMessages(error);
        if (fieldErrors) {
          // The register endpoint's field-error paths are nested (e.g.
          // `customer.defaultAddress.city`); `getFieldErrorMessages` already reduces them
          // to leaf names, so split by whether the leaf belongs to the address group.
          const nextAddressErrors: Partial<Record<keyof AddressValue, string>> = {};
          const nextTopErrors: Pick<FormErrors, 'fullName' | 'email' | 'password' | 'phone'> = {};
          for (const [field, message] of Object.entries(fieldErrors)) {
            if ((ADDRESS_FIELD_KEYS as string[]).includes(field)) {
              nextAddressErrors[field as keyof AddressValue] = message;
            } else if (field === 'fullName' || field === 'email' || field === 'password' || field === 'phone') {
              nextTopErrors[field] = message;
            }
          }
          const nextErrors: FormErrors = {
            ...nextTopErrors,
            address: Object.keys(nextAddressErrors).length > 0 ? nextAddressErrors : undefined,
          };
          setErrors((prev) => ({
            ...prev,
            ...nextTopErrors,
            address: { ...prev.address, ...nextAddressErrors },
          }));
          routeFieldErrors(nextErrors);
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
        primaryLoading={isSubmitting}
      >
        {currentStage === 1 && (
          <>
            <Input
              label="שם מלא"
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
              error={errors.fullName}
              autoComplete="name"
              required
            />
            <Input
              label="אימייל"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              error={errors.email}
              autoComplete="email"
              required
            />
            <Input
              label="טלפון"
              type="tel"
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
              error={errors.phone}
              autoComplete="tel"
              required
            />
            <Input
              label="סיסמה"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
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
              error={errors.confirmPassword}
              autoComplete="new-password"
              required
            />
          </>
        )}

        {currentStage === 2 && (
          <>
            <div className={styles.sectionTitle}>כתובת</div>
            <AddressFormFields value={address} onChange={setAddress} errors={errors.address} />
          </>
        )}

        {currentStage === 3 && (
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
            <div className={styles.summaryRow}>
              <dt>כתובת</dt>
              <dd>
                {[address.street, address.houseNumber].filter(Boolean).join(' ')}
                {address.apartment ? `, דירה ${address.apartment}` : ''}
                {address.city ? `, ${address.city}` : ''}
              </dd>
            </div>
          </dl>
        )}
      </RegistrationWizardShell>
    </form>
  );
}
