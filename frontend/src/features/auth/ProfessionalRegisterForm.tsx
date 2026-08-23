import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import {
  Input,
  Select,
  Checkbox,
  ImageUploadField,
  DocumentUploadField,
  Skeleton,
  Badge,
  Button,
  WeeklyHoursFields,
  WEEKDAY_LABELS_HE,
  buildWeeklyHoursRows,
  validateWeeklyHoursRows,
  hasEnabledWeekday,
  toWeeklyHoursRequest,
} from '../../shared/components';
import type { SelectOption, WeeklyHoursRow } from '../../shared/components';
import {
  registerProfessional,
  ApiError,
  getFieldErrorMessages,
  GENERIC_ERROR_MESSAGE,
  getCategoriesWithSubServices,
} from '../../shared/api';
import type { CategoryWithSubServicesResponse } from '../../shared/api';
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
  subServiceIds?: string;
  basePrice?: string;
  verificationDocument?: string;
  workingHours?: string;
}

const TOTAL_STAGES = 6;

/** Per-row leaf field names `availability.service.WorkingHoursValidator` reports a bad week
 *  against. They carry no weekday, so the form can only attribute them to the stage as a
 *  whole — and they're shared with the availability-slot forms, hence not mapped globally in
 *  `errorMessages.ts`. */
const WORKING_HOURS_ROW_ERROR_FIELDS = ['weekday', 'startTime', 'endTime'];

/**
 * Professional registration — a **6-stage** wizard: (1) personal details — no `phone`, not a
 * backend field for `PROFESSIONAL`; (2) profession + service area; (3) **sub-services**;
 * (4) pricing + documents; (5) **weekly working hours**; (6) read-only summary + the real
 * submit (`registerProfessional()`) + an honest "what's next" block.
 *
 * **MS1 (D4/D7)**: stages 3 and 5 are new and both required. `POST /api/auth/register` now
 * refuses a professional without at least one category-valid sub-service and without a full
 * 7-day week carrying at least one enabled day — before MS1 registration created zero
 * `professional_sub_services` and zero `professional_working_hours` rows, so a professional was
 * listed to customers while deriving an empty calendar. Nothing here is defaulted on the
 * registrant's behalf: no sub-service is pre-checked and no working week is pre-filled
 * (Playbook MS1: "do not invent default working hours"). Both stages reuse the surfaces that
 * already own these concerns — the sub-service list is the same `GET /api/categories` data the
 * `/pro/profile` editor uses, and stage 5 renders the shared `WeeklyHoursFields` that
 * `/pro/availability`'s `WorkingHoursForm` renders — so registration and later editing cannot
 * drift apart.
 *
 * The whole category/sub-service catalog comes from one `getCategoriesWithSubServices()` fetch:
 * the main-category `Select` is built from it too, rather than from `shared/api/categories.ts`'s
 * static mirror, so a category and its sub-services can never disagree. The fetch failing is
 * therefore a real blocking error with a retry — sub-services are required, so there is no
 * honest way to complete registration without the catalog.
 *
 * A successful submit yields `approval_status = PENDING`: an application awaiting review, not a
 * live marketplace listing. Stage 6's copy says exactly that.
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

  const [categories, setCategories] = useState<CategoryWithSubServicesResponse[] | null>(null);
  const [isLoadingCategories, setIsLoadingCategories] = useState(true);
  const [categoriesError, setCategoriesError] = useState<string | null>(null);
  const [catalogReloadKey, setCatalogReloadKey] = useState(0);
  const [selectedSubServiceIds, setSelectedSubServiceIds] = useState<number[]>([]);

  // Blank week — 7 rows, every day off, no times. The registrant chooses.
  const [workingHoursRows, setWorkingHoursRows] = useState<WeeklyHoursRow[]>(() => buildWeeklyHoursRows([]));
  const [workingHoursRowErrors, setWorkingHoursRowErrors] = useState<Record<number, string>>({});

  // The one catalog fetch (public, no auth) behind both the category select and the
  // sub-service checklist.
  useEffect(() => {
    let cancelled = false;
    setIsLoadingCategories(true);
    setCategoriesError(null);
    getCategoriesWithSubServices()
      .then((result) => {
        if (!cancelled) setCategories(result);
      })
      .catch(() => {
        if (!cancelled) setCategoriesError('לא הצלחנו לטעון את רשימת התחומים.');
      })
      .finally(() => {
        if (!cancelled) setIsLoadingCategories(false);
      });
    return () => {
      cancelled = true;
    };
  }, [catalogReloadKey]);

  const selectedCategory = categories?.find((category) => String(category.id) === categoryId) ?? null;
  const categoryOptions: SelectOption[] = (categories ?? []).map((category) => ({
    value: String(category.id),
    label: category.nameHe,
  }));
  const availableSubServices = selectedCategory?.subServices ?? [];
  const selectedSubServices = availableSubServices.filter((subService) =>
    selectedSubServiceIds.includes(subService.id),
  );
  const enabledWorkingHoursRows = workingHoursRows.filter((row) => row.enabled);

  /** Changing the main category invalidates every sub-service already chosen — the backend
   *  refuses a cross-category id with `400 CATEGORY_MISMATCH`, so they are dropped here rather
   *  than carried into a submit that cannot succeed. */
  function handleCategoryChange(nextCategoryId: string) {
    if (nextCategoryId === categoryId) {
      return;
    }
    setCategoryId(nextCategoryId);
    setSelectedSubServiceIds([]);
    setErrors((prev) => ({ ...prev, categoryId: undefined, subServiceIds: undefined }));
  }

  function toggleSubService(subServiceId: number) {
    setSelectedSubServiceIds((prev) =>
      prev.includes(subServiceId) ? prev.filter((id) => id !== subServiceId) : [...prev, subServiceId],
    );
  }

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
      // Without the catalog there is no category list to choose from — say that, rather than
      // asking for a choice the screen can't offer.
      next.categoryId = categories
        ? 'יש לבחור תחום שירות.'
        : 'לא ניתן להמשיך ללא רשימת התחומים. יש לנסות לטעון אותה שוב.';
    }
    if (!serviceArea.trim()) {
      next.serviceArea = 'יש להזין אזור שירות.';
    }
    return next;
  }

  function validateStage3(): Pick<FormErrors, 'subServiceIds'> {
    if (selectedSubServiceIds.length === 0) {
      return { subServiceIds: 'יש לבחור לפחות תחום אחד שבו אתה נותן שירות.' };
    }
    return {};
  }

  function validateStage4(): Pick<FormErrors, 'basePrice' | 'verificationDocument'> {
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

  /** Client-side only, for fast feedback — `WorkingHoursValidator` re-runs all of it server-side. */
  function validateStage5(): Pick<FormErrors, 'workingHours'> {
    const rowErrors = validateWeeklyHoursRows(workingHoursRows);
    setWorkingHoursRowErrors(rowErrors);
    if (!hasEnabledWeekday(workingHoursRows)) {
      return { workingHours: 'יש להפעיל לפחות יום עבודה אחד בשבוע.' };
    }
    if (Object.keys(rowErrors).length > 0) {
      return { workingHours: 'יש לבדוק את שעות העבודה שהוזנו.' };
    }
    return {};
  }

  function goToStage(stage: number, dir: number) {
    setDirection(dir);
    setCurrentStage(stage);
  }

  /** Same routing principle as the customer wizard (design doc §6.3) — jump back to whichever stage owns the offending field. */
  function routeFieldErrors(nextErrors: FormErrors) {
    if (nextErrors.fullName || nextErrors.email || nextErrors.password) {
      goToStage(1, -1);
    } else if (nextErrors.categoryId || nextErrors.serviceArea) {
      goToStage(2, -1);
    } else if (nextErrors.subServiceIds) {
      goToStage(3, -1);
    } else if (nextErrors.basePrice || nextErrors.verificationDocument) {
      goToStage(4, -1);
    } else if (nextErrors.workingHours) {
      goToStage(5, -1);
    }
  }

  /** Folds the backend's per-row week errors into stage 5's single error slot. */
  function toFormErrors(fieldErrors: Record<string, string>): FormErrors {
    const next: FormErrors = { ...(fieldErrors as FormErrors) };
    if (WORKING_HOURS_ROW_ERROR_FIELDS.some((field) => fieldErrors[field])) {
      for (const field of WORKING_HOURS_ROW_ERROR_FIELDS) {
        delete (next as Record<string, string | undefined>)[field];
      }
      next.workingHours = next.workingHours ?? 'יש לבדוק את שעות העבודה שהוזנו.';
    }
    return next;
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
      setErrors((prev) => ({ ...prev, subServiceIds: stage3Errors.subServiceIds }));
      if (Object.keys(stage3Errors).length > 0) {
        return;
      }
      goToStage(4, 1);
      return;
    }

    if (currentStage === 4) {
      const stage4Errors = validateStage4();
      setErrors((prev) => ({
        ...prev,
        basePrice: stage4Errors.basePrice,
        verificationDocument: stage4Errors.verificationDocument,
      }));
      if (Object.keys(stage4Errors).length > 0) {
        return;
      }
      goToStage(5, 1);
      return;
    }

    if (currentStage === 5) {
      const stage5Errors = validateStage5();
      setErrors((prev) => ({ ...prev, workingHours: stage5Errors.workingHours }));
      if (Object.keys(stage5Errors).length > 0) {
        return;
      }
      goToStage(6, 1);
      return;
    }

    if (!verificationDocument) {
      // Defensive only — stage 4 already required this before advancing here.
      goToStage(4, -1);
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
        subServiceIds: selectedSubServiceIds,
        workingHours: toWeeklyHoursRequest(workingHoursRows),
        verificationDocument,
        profilePhoto: photo,
      });
      onSuccess(email.trim());
    } catch (error) {
      if (error instanceof ApiError && error.code === 'DUPLICATE_EMAIL') {
        const nextErrors: FormErrors = { email: 'כתובת האימייל הזו כבר רשומה במערכת.' };
        setErrors((prev) => ({ ...prev, ...nextErrors }));
        routeFieldErrors(nextErrors);
      } else if (error instanceof ApiError && error.code === 'CATEGORY_MISMATCH') {
        // A chosen sub-service doesn't belong to the chosen category — only reachable if the
        // catalog changed under the wizard mid-session.
        const nextErrors: FormErrors = {
          subServiceIds: 'חלק מהתחומים שנבחרו אינם שייכים לתחום השירות שנבחר. יש לבחור אותם מחדש.',
        };
        setSelectedSubServiceIds([]);
        setErrors((prev) => ({ ...prev, ...nextErrors }));
        routeFieldErrors(nextErrors);
      } else {
        const fieldErrors = getFieldErrorMessages(error);
        if (fieldErrors) {
          const nextErrors = toFormErrors(fieldErrors);
          setErrors((prev) => ({ ...prev, ...nextErrors }));
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

  const primaryLabel = currentStage === TOTAL_STAGES ? 'שליחת הבקשה' : 'המשך';

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
            {isLoadingCategories && <Skeleton radius="var(--radius-md)" style={{ height: 64 }} />}

            {!isLoadingCategories && categoriesError && (
              <div className={styles.banner} role="alert">
                <p>{categoriesError}</p>
                <Button type="button" variant="secondary" onClick={() => setCatalogReloadKey((key) => key + 1)}>
                  נסה שוב
                </Button>
              </div>
            )}

            {!isLoadingCategories && !categoriesError && (
              <Select
                label="תחום שירות"
                value={categoryId}
                onChange={(event) => handleCategoryChange(event.target.value)}
                options={categoryOptions}
                placeholder="בחירת תחום"
                error={errors.categoryId}
                required
              />
            )}

            {/* The `Select` above owns the message when it's on screen; while the catalog is
                loading or failed it isn't, so the error would otherwise be invisible. */}
            {(isLoadingCategories || categoriesError) && errors.categoryId && (
              <p className={styles.stepError} role="alert">
                {errors.categoryId}
              </p>
            )}

            <Input
              label="אזור שירות"
              value={serviceArea}
              onChange={(event) => setServiceArea(event.target.value)}
              error={errors.serviceArea}
              hint="למשל: תל אביב והמרכז"
              required
            />
          </>
        )}

        {currentStage === 3 && (
          <>
            <p className={styles.sectionTitle}>באילו תחומים אתה נותן שירות?</p>
            <p className={styles.stepHint}>אפשר לבחור כמה שרוצים, לפחות אחד. תמיד אפשר לעדכן בהמשך מעמוד הפרופיל.</p>

            {isLoadingCategories && (
              <div className={styles.subServicesList}>
                <Skeleton radius="var(--radius-md)" style={{ height: 24 }} />
                <Skeleton radius="var(--radius-md)" style={{ height: 24 }} />
                <Skeleton radius="var(--radius-md)" style={{ height: 24 }} />
              </div>
            )}

            {!isLoadingCategories && categoriesError && (
              <div className={styles.banner} role="alert">
                <p>{categoriesError}</p>
                <Button type="button" variant="secondary" onClick={() => setCatalogReloadKey((key) => key + 1)}>
                  נסה שוב
                </Button>
              </div>
            )}

            {!isLoadingCategories && !categoriesError && availableSubServices.length === 0 && (
              <p className={styles.stepHint}>אין עדיין תת-שירותים מוגדרים עבור התחום שנבחר.</p>
            )}

            {!isLoadingCategories && !categoriesError && availableSubServices.length > 0 && (
              <div className={styles.subServicesList}>
                {availableSubServices.map((subService) => (
                  <Checkbox
                    key={subService.id}
                    label={subService.nameHe}
                    checked={selectedSubServiceIds.includes(subService.id)}
                    onChange={() => toggleSubService(subService.id)}
                  />
                ))}
              </div>
            )}

            {errors.subServiceIds && (
              <p className={styles.stepError} role="alert">
                {errors.subServiceIds}
              </p>
            )}
          </>
        )}

        {currentStage === 4 && (
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

        {currentStage === 5 && (
          <>
            <p className={styles.sectionTitle}>באילו ימים ושעות תרצה לקבל הזמנות?</p>
            <p className={styles.stepHint}>
              יש להפעיל לפחות יום אחד. שעת הסיום חייבת להיות אחרי שעת ההתחלה, ומשמרת לילה שחוצה חצות אינה נתמכת.
            </p>

            <WeeklyHoursFields
              rows={workingHoursRows}
              onChange={setWorkingHoursRows}
              errors={workingHoursRowErrors}
            />

            {errors.workingHours && (
              <p className={styles.stepError} role="alert">
                {errors.workingHours}
              </p>
            )}
          </>
        )}

        {currentStage === 6 && (
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
                <dd>{selectedCategory?.nameHe ?? ''}</dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>אזור שירות</dt>
                <dd>{serviceArea}</dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>מחיר ביקור בסיסי</dt>
                <dd>{basePrice ? `₪${basePrice}` : ''}</dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>תחומי שירות</dt>
                <dd>
                  <span className={styles.subServiceChips}>
                    {selectedSubServices.map((subService) => (
                      <Badge key={subService.id} size="sm">
                        {subService.nameHe}
                      </Badge>
                    ))}
                  </span>
                </dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>שעות עבודה</dt>
                <dd>
                  {enabledWorkingHoursRows.map((row) => (
                    <span key={row.weekday} className={styles.summaryLine}>
                      {WEEKDAY_LABELS_HE[row.weekday]}{' '}
                      <bdi>
                        {row.startTime}–{row.endTime}
                      </bdi>
                    </span>
                  ))}
                </dd>
              </div>
            </dl>

            <div className={styles.nextSteps}>
              <p className={styles.nextStepsTitle}>מה קורה אחרי שליחת הבקשה?</p>
              <ol className={styles.nextStepsList}>
                <li>אימות כתובת האימייל שלך</li>
                <li>הבקשה עוברת לבדיקה של צוות פרונטו</li>
                <li>עד לאישור הבקשה החשבון שלך עדיין לא מוצג ללקוחות</li>
                <li>בינתיים אפשר להתחבר ולעדכן את הפרופיל, תחומי השירות ושעות העבודה</li>
              </ol>
            </div>
          </>
        )}
      </RegistrationWizardShell>
    </form>
  );
}
