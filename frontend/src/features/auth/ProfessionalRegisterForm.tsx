import { useState } from 'react';
import type { FormEvent } from 'react';
import {
  Button,
  Input,
  Select,
  ImageUploadField,
  DocumentUploadField,
} from '../../shared/components';
import type { SelectOption } from '../../shared/components';
import {
  registerProfessional,
  ApiError,
  getFieldErrorMessages,
  GENERIC_ERROR_MESSAGE,
  CATEGORIES,
} from '../../shared/api';
import styles from './formStyles.module.css';

export interface ProfessionalRegisterFormProps {
  onSuccess: (email: string) => void;
}

interface FormErrors {
  fullName?: string;
  email?: string;
  password?: string;
  confirmPassword?: string;
  categoryId?: string;
  serviceArea?: string;
  basePrice?: string;
  verificationDocument?: string;
}

const CATEGORY_OPTIONS: SelectOption[] = CATEGORIES.map((category) => ({
  value: String(category.id),
  label: category.nameHe,
}));

/**
 * Professional registration. Collects the profile photo and verification document in the
 * UI (selection, preview/filename, "required to proceed" validation on the verification
 * document) and uploads both as multipart parts on `POST /api/auth/register` alongside the
 * `data` part — see `registerProfessional` in `shared/api/auth.ts`.
 */
export function ProfessionalRegisterForm({ onSuccess }: ProfessionalRegisterFormProps) {
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [serviceArea, setServiceArea] = useState('');
  const [basePrice, setBasePrice] = useState('');
  const [photo, setPhoto] = useState<File | null>(null);
  const [verificationDocument, setVerificationDocument] = useState<File | null>(null);
  const [errors, setErrors] = useState<FormErrors>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  function validate(): FormErrors {
    const next: FormErrors = {};
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
    if (!categoryId) {
      next.categoryId = 'יש לבחור תחום שירות.';
    }
    if (!serviceArea.trim()) {
      next.serviceArea = 'יש להזין אזור שירות.';
    }
    const priceValue = Number(basePrice);
    if (!basePrice || Number.isNaN(priceValue) || priceValue <= 0) {
      next.basePrice = 'יש להזין מחיר תקין.';
    }
    if (!verificationDocument) {
      next.verificationDocument = 'יש לצרף מסמך לאימות (תעודה / רישיון / הסמכה).';
    }
    return next;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);

    const validationErrors = validate();
    setErrors(validationErrors);
    if (Object.keys(validationErrors).length > 0 || !verificationDocument) {
      return;
    }

    setIsSubmitting(true);
    try {
      await registerProfessional({
        fullName: fullName.trim(),
        email: email.trim(),
        password,
        categoryId: Number(categoryId),
        serviceArea: serviceArea.trim(),
        basePrice: Number(basePrice),
        verificationDocument,
        profilePhoto: photo,
      });
      onSuccess(email.trim());
    } catch (error) {
      if (error instanceof ApiError && error.code === 'DUPLICATE_EMAIL') {
        setErrors((prev) => ({ ...prev, email: 'כתובת האימייל הזו כבר רשומה במערכת.' }));
      } else {
        const fieldErrors = getFieldErrorMessages(error);
        if (fieldErrors) {
          setErrors((prev) => ({ ...prev, ...fieldErrors }));
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
      <Select
        label="תחום שירות"
        value={categoryId}
        onChange={(event) => setCategoryId(event.target.value)}
        options={CATEGORY_OPTIONS}
        placeholder="בחירת תחום"
        error={errors.categoryId}
        required
      />
      <Input
        label="אזור שירות"
        value={serviceArea}
        onChange={(event) => setServiceArea(event.target.value)}
        error={errors.serviceArea}
        hint="למשל: תל אביב והמרכז"
        required
      />
      <Input
        label="מחיר ביקור בסיסי (₪)"
        type="number"
        min="1"
        step="0.01"
        value={basePrice}
        onChange={(event) => setBasePrice(event.target.value)}
        error={errors.basePrice}
        required
      />
      <ImageUploadField label="תמונת פרופיל" value={photo} onChange={setPhoto} hint="לא חובה" />
      <DocumentUploadField
        label="מסמך לאימות (תעודה / רישיון)"
        value={verificationDocument}
        onChange={setVerificationDocument}
        error={errors.verificationDocument}
        required
      />
      <Button type="submit" loading={isSubmitting} fullWidth>
        הרשמה
      </Button>
    </form>
  );
}
