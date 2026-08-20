import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import {
  Input,
  Select,
  ImageUploadField,
  DocumentUploadField,
  Skeleton,
  Badge,
} from '../../shared/components';
import type { SelectOption } from '../../shared/components';
import {
  registerProfessional,
  ApiError,
  getFieldErrorMessages,
  GENERIC_ERROR_MESSAGE,
  CATEGORIES,
  getCategoryNameHe,
  getCategoriesWithSubServices,
} from '../../shared/api';
import { RegistrationWizardShell } from './RegistrationWizardShell';
import styles from './formStyles.module.css';

export interface ProfessionalRegisterFormProps {
  onSuccess: (email: string) => void;
  /** Stage 1's back button — exits the wizard entirely (design doc §6.1). */
  onExit: () => void;
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

const TOTAL_STAGES = 4;

/**
 * Professional registration — a **4-stage** wizard (design doc §6.5-6.6, a deliberate
 * deviation from the milestone dispatch's original 6-stage ask, approved by planning): (1)
 * personal details — no `phone`, not a backend field for `PROFESSIONAL`; (2) profession +
 * service area, with an honest, non-interactive sub-service preview (real data from
 * `getCategoriesWithSubServices()`, never implies a selection is being saved); (3) pricing +
 * documents; (4) read-only summary + the real submit (`registerProfessional()`, unchanged)
 * + an honest "what's next" block. There is no authenticated session during/after
 * registration (verified in `AuthService.java`) and `ProfessionalRegistrationData` has no
 * sub-service/availability fields, so those two topics are covered as informational content
 * rather than fake data-collection steps — see the design doc for the full rationale.
 */
export function ProfessionalRegisterForm({ onSuccess, onExit }: ProfessionalRegisterFormProps) {
  const [currentStage, setCurrentStage] = useState(1);
  const [direction, setDirection] = useState(1);

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

  const [subServicePreview, setSubServicePreview] = useState<string[] | null>(null);
  const [subServiceLoading, setSubServiceLoading] = useState(false);

  // Honest sub-service preview (design doc §6.6) — fetched from the already-built, public,
  // no-auth `GET /api/categories` once a category is chosen. Never blocks registration: a
  // fetch failure just silently omits the preview.
  useEffect(() => {
    if (!categoryId) {
      setSubServicePreview(null);
      return;
    }
    let cancelled = false;
    setSubServiceLoading(true);
    setSubServicePreview(null);
    getCategoriesWithSubServices()
      .then((categories) => {
        if (cancelled) return;
        const match = categories.find((category) => category.id === Number(categoryId));
        setSubServicePreview(match ? match.subServices.map((subService) => subService.nameHe) : null);
      })
      .catch(() => {
        if (!cancelled) {
          setSubServicePreview(null);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setSubServiceLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [categoryId]);

  function validateStage1(): Pick<FormErrors, 'fullName' | 'email' | 'password' | 'confirmPassword'> {
    const next: Pick<FormErrors, 'fullName' | 'email' | 'password' | 'confirmPassword'> = {};
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
    return next;
  }

  function validateStage2(): Pick<FormErrors, 'categoryId' | 'serviceArea'> {
    const next: Pick<FormErrors, 'categoryId' | 'serviceArea'> = {};
    if (!categoryId) {
      next.categoryId = 'יש לבחור תחום שירות.';
    }
    if (!serviceArea.trim()) {
      next.serviceArea = 'יש להזין אזור שירות.';
    }
    return next;
  }

  function validateStage3(): Pick<FormErrors, 'basePrice' | 'verificationDocument'> {
    const next: Pick<FormErrors, 'basePrice' | 'verificationDocument'> = {};
    const priceValue = Number(basePrice);
    if (!basePrice || Number.isNaN(priceValue) || priceValue <= 0) {
      next.basePrice = 'יש להזין מחיר תקין.';
    }
    if (!verificationDocument) {
      next.verificationDocument = 'יש לצרף מסמך לאימות (תעודה / רישיון / הסמכה).';
    }
    return next;
  }

  function goToStage(stage: number, dir: number) {
    setDirection(dir);
    setCurrentStage(stage);
  }

  /** Same routing principle as the customer wizard (design doc §6.3) — jump back to whichever stage owns the offending field. */
  function routeFieldErrors(nextErrors: FormErrors) {
    const hasStage1Error = Boolean(nextErrors.fullName || nextErrors.email || nextErrors.password);
    const hasStage2Error = Boolean(nextErrors.categoryId || nextErrors.serviceArea);
    const hasStage3Error = Boolean(nextErrors.basePrice || nextErrors.verificationDocument);
    if (hasStage1Error) {
      goToStage(1, -1);
    } else if (hasStage2Error) {
      goToStage(2, -1);
    } else if (hasStage3Error) {
      goToStage(3, -1);
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
      }));
      if (Object.keys(stage1Errors).length > 0) {
        return;
      }
      goToStage(2, 1);
      return;
    }

    if (currentStage === 2) {
      const stage2Errors = validateStage2();
      setErrors((prev) => ({ ...prev, categoryId: stage2Errors.categoryId, serviceArea: stage2Errors.serviceArea }));
      if (Object.keys(stage2Errors).length > 0) {
        return;
      }
      goToStage(3, 1);
      return;
    }

    if (currentStage === 3) {
      const stage3Errors = validateStage3();
      setErrors((prev) => ({
        ...prev,
        basePrice: stage3Errors.basePrice,
        verificationDocument: stage3Errors.verificationDocument,
      }));
      if (Object.keys(stage3Errors).length > 0) {
        return;
      }
      goToStage(4, 1);
      return;
    }

    if (!verificationDocument) {
      // Defensive only — stage 3 already required this before advancing here.
      goToStage(3, -1);
      return;
    }

    setBannerError(null);
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
        const nextErrors: FormErrors = { email: 'כתובת האימייל הזו כבר רשומה במערכת.' };
        setErrors((prev) => ({ ...prev, ...nextErrors }));
        routeFieldErrors(nextErrors);
      } else {
        const fieldErrors = getFieldErrorMessages(error);
        if (fieldErrors) {
          setErrors((prev) => ({ ...prev, ...fieldErrors }));
          routeFieldErrors(fieldErrors as FormErrors);
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
        title="הרשמה כבעל מקצוע"
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

            {categoryId && (
              <div className={styles.subServicePreview}>
                {subServiceLoading ? (
                  <div className={styles.subServiceChips}>
                    <Skeleton radius="999px" style={{ width: 84, height: 28 }} />
                    <Skeleton radius="999px" style={{ width: 108, height: 28 }} />
                    <Skeleton radius="999px" style={{ width: 64, height: 28 }} />
                  </div>
                ) : subServicePreview && subServicePreview.length > 0 ? (
                  <>
                    <p className={styles.subServiceNote}>
                      לאחר אישור החשבון תוכל לבחור מתוכם בעמוד הפרופיל שלך
                    </p>
                    <div className={styles.subServiceChips}>
                      {subServicePreview.map((name) => (
                        <Badge key={name} size="sm">
                          {name}
                        </Badge>
                      ))}
                    </div>
                  </>
                ) : null}
              </div>
            )}
          </>
        )}

        {currentStage === 3 && (
          <>
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
          </>
        )}

        {currentStage === 4 && (
          <>
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
                <dt>תחום שירות</dt>
                <dd>{categoryId ? getCategoryNameHe(Number(categoryId)) : ''}</dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>אזור שירות</dt>
                <dd>{serviceArea}</dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>מחיר ביקור בסיסי</dt>
                <dd>{basePrice ? `₪${basePrice}` : ''}</dd>
              </div>
            </dl>

            <div className={styles.nextSteps}>
              <p className={styles.nextStepsTitle}>מה קורה אחרי ההרשמה?</p>
              <ol className={styles.nextStepsList}>
                <li>אימות האימייל שלך</li>
                <li>התחברות לחשבון</li>
                <li>השלמת הפרופיל: תת-התמחויות ושעות זמינות, בעמוד הפרופיל שלך</li>
              </ol>
            </div>
          </>
        )}
      </RegistrationWizardShell>
    </form>
  );
}
