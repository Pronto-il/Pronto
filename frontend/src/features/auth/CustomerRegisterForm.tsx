import { useState } from 'react';
import type { FormEvent } from 'react';
import { Button, Input, AddressFormFields, EMPTY_ADDRESS } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { registerCustomer, ApiError, getFieldErrorMessages, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import styles from './formStyles.module.css';

export interface CustomerRegisterFormProps {
  onSuccess: (email: string) => void;
}

interface FormErrors {
  fullName?: string;
  email?: string;
  password?: string;
  confirmPassword?: string;
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

/**
 * Customer registration. Collects the full address (city/street/house number/apartment/
 * floor/entrance/notes) via `AddressFormFields` with real client-side validation and sends
 * it as `customer.defaultAddress` on `POST /api/auth/register` (multipart `data` part).
 */
export function CustomerRegisterForm({ onSuccess }: CustomerRegisterFormProps) {
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [address, setAddress] = useState<AddressValue>(EMPTY_ADDRESS);
  const [errors, setErrors] = useState<FormErrors>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  function validate(): FormErrors {
    const nextErrors: FormErrors = {};
    if (fullName.trim().length < 2) {
      nextErrors.fullName = 'יש להזין שם מלא (לפחות 2 תווים).';
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      nextErrors.email = 'יש להזין כתובת אימייל תקינה.';
    }
    if (password.length < 8) {
      nextErrors.password = 'הסיסמה חייבת להכיל לפחות 8 תווים.';
    }
    if (confirmPassword !== password) {
      nextErrors.confirmPassword = 'אימות הסיסמה אינו תואם לסיסמה שהוזנה.';
    }

    const addressErrors: Partial<Record<keyof AddressValue, string>> = {};
    if (!address.city.trim()) addressErrors.city = 'יש להזין עיר.';
    if (!address.street.trim()) addressErrors.street = 'יש להזין רחוב.';
    if (!address.houseNumber.trim()) addressErrors.houseNumber = 'יש להזין מספר בית.';
    if (Object.keys(addressErrors).length > 0) {
      nextErrors.address = addressErrors;
    }

    return nextErrors;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);

    const validationErrors = validate();
    setErrors(validationErrors);
    if (Object.keys(validationErrors).length > 0) {
      return;
    }

    setIsSubmitting(true);
    try {
      await registerCustomer({
        fullName: fullName.trim(),
        email: email.trim(),
        password,
        address: {
          city: address.city.trim(),
          street: address.street.trim(),
          houseNumber: address.houseNumber.trim(),
          apartment: address.apartment,
          floor: address.floor,
          entrance: address.entrance,
          addressNotes: address.addressNotes,
        },
      });
      onSuccess(email.trim());
    } catch (error) {
      if (error instanceof ApiError && error.code === 'DUPLICATE_EMAIL') {
        setErrors((prev) => ({ ...prev, email: 'כתובת האימייל הזו כבר רשומה במערכת.' }));
      } else {
        const fieldErrors = getFieldErrorMessages(error);
        if (fieldErrors) {
          // The register endpoint's field-error paths are nested (e.g.
          // `customer.defaultAddress.city`); `getFieldErrorMessages` already reduces them
          // to leaf names, so split by whether the leaf belongs to the address group.
          const nextAddressErrors: Partial<Record<keyof AddressValue, string>> = {};
          const nextTopErrors: Pick<FormErrors, 'fullName' | 'email' | 'password'> = {};
          for (const [field, message] of Object.entries(fieldErrors)) {
            if ((ADDRESS_FIELD_KEYS as string[]).includes(field)) {
              nextAddressErrors[field as keyof AddressValue] = message;
            } else if (field === 'fullName' || field === 'email' || field === 'password') {
              nextTopErrors[field] = message;
            }
          }
          setErrors((prev) => ({
            ...prev,
            ...nextTopErrors,
            address: { ...prev.address, ...nextAddressErrors },
          }));
        } else {
          setBannerError(GENERIC_ERROR_MESSAGE);
        }
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

      <div className={styles.sectionTitle}>כתובת</div>
      <AddressFormFields value={address} onChange={setAddress} errors={errors.address} />

      <Button type="submit" loading={isSubmitting} fullWidth>
        הרשמה
      </Button>
    </form>
  );
}
