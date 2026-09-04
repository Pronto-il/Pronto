import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import {
  Input,
  Select,
  MultiSelectField,
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
  SubServicePriceRow,
} from '../../shared/components';
import type { MultiSelectOption, SelectOption, WeeklyHoursRow } from '../../shared/components';
import {
  registerProfessional,
  ApiError,
  getFieldErrorMessages,
  GENERIC_ERROR_MESSAGE,
  getCategoriesWithSubServices,
  getServiceAreas,
  citiesForRegion,
  FULL_NAME_MAX_LENGTH,
  EMAIL_MAX_LENGTH,
  PHONE_INPUT_MAX_LENGTH,
} from '../../shared/api';
import type {
  AuthStepResponse,
  CategoryWithSubServicesResponse,
  ServiceRegionResponse,
} from '../../shared/api';
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
import {
  firstSubServicePriceError,
  toPriceSelections,
  validateSubServicePrice,
} from '../../shared/utils/subServicePrices';
import styles from './formStyles.module.css';

export interface ProfessionalRegisterFormProps {
  /** Production MS1: the registration response's OTP challenge — see CustomerRegisterForm. */
  onSuccess: (response: AuthStepResponse) => void;
  /** Stage 1's back button — exits the wizard entirely (design doc §6.1). */
  onExit: () => void;
}

interface FormErrors {
  fullName?: string;
  email?: string;
  phone?: string;
  password?: string;
  confirmPassword?: string;
  categoryIds?: string;
  serviceRegionId?: string;
  serviceCityIds?: string;
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
 * backend field for `PROFESSIONAL`; (2) service categories + service region + service cities;
 * (3) **sub-services**;
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
 * **MS4**: stage 2 collects a *set* of categories (a professional may be a plumber and a
 * handyman), and replaces the free-text service-area input with two controlled selectors — a
 * region `Select` and a searchable city `MultiSelectField`, both fed by `GET /api/service-areas`
 * through `shared/api/serviceAreas.ts`. There is no field left on this form that a registrant
 * can type a place name into: 'תל אביב', 'תל-אביב' and 'Tel Aviv' used to be three different
 * service areas. Changing the region re-scopes the city list via the shared `citiesForRegion()`
 * helper — this component holds no region→city map of its own. Stage 5 turns on
 * `WeeklyHoursFields`' "החל על הכל" (§11), which is why registrants no longer type the same
 * hours seven times.
 *
 * A successful submit yields `approval_status = PENDING`: an application awaiting review, not a
 * live marketplace listing. Stage 6's copy says exactly that.
 */
export function ProfessionalRegisterForm({ onSuccess, onExit }: ProfessionalRegisterFormProps) {
  const [currentStage, setCurrentStage] = useState(1);
  const [direction, setDirection] = useState(1);

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [selectedCategoryIds, setSelectedCategoryIds] = useState<number[]>([]);
  const [serviceRegionId, setServiceRegionId] = useState<number | null>(null);
  const [serviceCityIds, setServiceCityIds] = useState<number[]>([]);
  const [basePrice, setBasePrice] = useState('');
  const [photo, setPhoto] = useState<File | null>(null);
  const [verificationDocument, setVerificationDocument] = useState<File | null>(null);
  const [errors, setErrors] = useState<FormErrors>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isCheckingContacts, setIsCheckingContacts] = useState(false);

  // The same hook, the same endpoint and the same Hebrew copy the customer wizard uses.
  // Professional and customer registration hit one `POST /api/auth/register` with one
  // uniqueness rule, so a second availability mechanism could only ever disagree with this one.
  const emailAvailability = useContactAvailability('EMAIL', email);
  const phoneAvailability = useContactAvailability('PHONE', phone);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const [categories, setCategories] = useState<CategoryWithSubServicesResponse[] | null>(null);
  const [regions, setRegions] = useState<ServiceRegionResponse[] | null>(null);
  const [isLoadingCategories, setIsLoadingCategories] = useState(true);
  const [categoriesError, setCategoriesError] = useState<string | null>(null);
  const [catalogReloadKey, setCatalogReloadKey] = useState(0);
  const [selectedSubServiceIds, setSelectedSubServiceIds] = useState<number[]>([]);
  /* Prices as typed, keyed by sub-service id. Strings, not numbers: a half-typed "4" and an emptied
     field both have to survive a re-render, and a numeric state would turn the second into 0 --
     which is a real, different price. Entries are kept when a row is unticked, so a mis-tap does not
     silently discard a price the registrant already entered. */
  const [subServicePrices, setSubServicePrices] = useState<Record<number, string>>({});

  // Blank week — 7 rows, every day off, no times. The registrant chooses.
  const [workingHoursRows, setWorkingHoursRows] = useState<WeeklyHoursRow[]>(() => buildWeeklyHoursRows([]));
  const [workingHoursRowErrors, setWorkingHoursRowErrors] = useState<Record<number, string>>({});

  // Both catalogs (public, no auth) in one pass: the category/sub-service tree behind stages 2
  // and 3, and the region/city catalogue behind stage 2's coverage selectors. They share one
  // loading/error/retry state because neither stage can be completed without both, so splitting
  // them would only mean two spinners and two ways to be half-broken.
  useEffect(() => {
    let cancelled = false;
    setIsLoadingCategories(true);
    setCategoriesError(null);
    Promise.all([getCategoriesWithSubServices(), getServiceAreas()])
      .then(([categoriesResult, regionsResult]) => {
        if (cancelled) return;
        setCategories(categoriesResult);
        setRegions(regionsResult);
      })
      .catch(() => {
        if (!cancelled) setCategoriesError('לא הצלחנו לטעון את רשימת התחומים ואזורי השירות.');
      })
      .finally(() => {
        if (!cancelled) setIsLoadingCategories(false);
      });
    return () => {
      cancelled = true;
    };
  }, [catalogReloadKey]);

  const selectedCategories = (categories ?? []).filter((category) =>
    selectedCategoryIds.includes(category.id),
  );
  const categoryOptions: MultiSelectOption[] = (categories ?? []).map((category) => ({
    value: category.id,
    label: category.nameHe,
  }));
  const regionOptions: SelectOption[] = (regions ?? []).map((region) => ({
    value: String(region.id),
    label: region.nameHe,
  }));
  /** MS4 §3: the city options are the chosen region's own cities, from the shared data layer. */
  const cityOptions: MultiSelectOption[] = citiesForRegion(regions, serviceRegionId).map((city) => ({
    value: city.id,
    label: city.nameHe,
  }));
  const selectedCityNames = cityOptions
    .filter((option) => serviceCityIds.includes(option.value))
    .map((option) => option.label);

  /** Sub-services across every selected category — MS4 lets a professional hold several trades,
   *  and each of them brings its own sub-service list. */
  const availableSubServices = selectedCategories.flatMap((category) => category.subServices);
  const selectedSubServices = availableSubServices.filter((subService) =>
    selectedSubServiceIds.includes(subService.id),
  );
  const enabledWorkingHoursRows = workingHoursRows.filter((row) => row.enabled);

  /** Dropping a category invalidates any sub-service that belonged only to it — the backend
   *  refuses an id outside the professional's own categories with `400 CATEGORY_MISMATCH`, so
   *  they are dropped here rather than carried into a submit that cannot succeed. Sub-services
   *  under the categories that remain are kept: re-picking them would be busywork. */
  function handleCategoriesChange(nextCategoryIds: number[]) {
    setSelectedCategoryIds(nextCategoryIds);
    const stillValid = new Set(
      (categories ?? [])
        .filter((category) => nextCategoryIds.includes(category.id))
        .flatMap((category) => category.subServices.map((subService) => subService.id)),
    );
    setSelectedSubServiceIds((prev) => prev.filter((id) => stillValid.has(id)));
    setErrors((prev) => ({ ...prev, categoryIds: undefined, subServiceIds: undefined }));
  }

  /** MS4 §3: changing region re-scopes the city options, so any city already chosen that isn't
   *  in the new region is dropped — it could not be submitted anyway (the backend refuses a
   *  cross-region city). This is registration, where nothing is persisted yet and there is
   *  nothing to warn about; the profile editor, which *is* editing saved data, says so out loud
   *  instead. */
  function handleRegionChange(nextRegionId: number | null) {
    if (nextRegionId === serviceRegionId) {
      return;
    }
    setServiceRegionId(nextRegionId);
    const allowed = new Set(citiesForRegion(regions, nextRegionId).map((city) => city.id));
    setServiceCityIds((prev) => prev.filter((id) => allowed.has(id)));
    setErrors((prev) => ({ ...prev, serviceRegionId: undefined, serviceCityIds: undefined }));
  }

  function toggleSubService(subServiceId: number) {
    setSelectedSubServiceIds((prev) =>
      prev.includes(subServiceId) ? prev.filter((id) => id !== subServiceId) : [...prev, subServiceId],
    );
  }

  /**
   * Stage 1's locally answerable rules — the SAME functions the customer wizard uses
   * (`registrationValidation.ts`), not a second copy.
   *
   * <p>They were a second copy, and the copies had already drifted: this form checked only that
   * the phone field was non-empty, so `1` was accepted here and rejected in the customer flow.
   * The registration rules are identical for both roles — same `RegisterRequest`, same
   * `@Email`, same `PhoneNumberNormalizer`, same 8-character password — so there is nothing for a
   * separate implementation to express.
   */
  function validateStage1(): Pick<FormErrors, 'fullName' | 'email' | 'phone' | 'password' | 'confirmPassword'> {
    const next: Pick<FormErrors, 'fullName' | 'email' | 'phone' | 'password' | 'confirmPassword'> = {};
    type Stage1Field = 'fullName' | 'email' | 'phone' | 'password' | 'confirmPassword';
    const rules: Array<[Stage1Field, string | undefined]> = [
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

  /**
   * Validates one field on blur, and — for the two whose rule only the server holds — asks
   * `POST /api/auth/availability`. Blur-driven rather than debounced-per-keystroke for the reason
   * `useContactAvailability` documents: that endpoint is deliberately rate limited tightly.
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
   * Stage 1's Continue. Blocks on a local error, then **waits** for the availability verdict
   * rather than assuming one — a registrant who fills the last field and presses Continue
   * immediately would otherwise sail past a check still in flight, which is the "found out five
   * screens later" failure this exists to end.
   */
  async function advanceFromStage1(): Promise<boolean> {
    const stage1Errors = validateStage1();
    setErrors((prev) => ({
      ...prev,
      fullName: stage1Errors.fullName,
      email: stage1Errors.email,
      phone: stage1Errors.phone,
      password: stage1Errors.password,
      confirmPassword: stage1Errors.confirmPassword,
    }));
    if (Object.keys(stage1Errors).length > 0) {
      return false;
    }

    setIsCheckingContacts(true);
    try {
      const [emailStatus, phoneStatus] = await Promise.all([
        emailAvailability.check(email),
        phoneAvailability.check(phone),
      ]);
      return !isBlocking(emailStatus) && !isBlocking(phoneStatus);
    } finally {
      setIsCheckingContacts(false);
    }
  }

  function validateStage2(): Pick<FormErrors, 'categoryIds' | 'serviceRegionId' | 'serviceCityIds'> {
    const next: Pick<FormErrors, 'categoryIds' | 'serviceRegionId' | 'serviceCityIds'> = {};
    if (selectedCategoryIds.length === 0) {
      // Without the catalog there is no category list to choose from — say that, rather than
      // asking for a choice the screen can't offer.
      next.categoryIds = categories
        ? 'יש לבחור לפחות תחום שירות אחד.'
        : 'לא ניתן להמשיך ללא רשימת התחומים. יש לנסות לטעון אותה שוב.';
    }
    if (serviceRegionId === null) {
      next.serviceRegionId = regions
        ? 'יש לבחור אזור שירות.'
        : 'לא ניתן להמשיך ללא רשימת אזורי השירות. יש לנסות לטעון אותה שוב.';
    } else if (serviceCityIds.length === 0) {
      next.serviceCityIds = 'יש לבחור לפחות עיר אחת שבה אתה נותן שירות.';
    }
    return next;
  }

  function validateStage3(): Pick<FormErrors, 'subServiceIds'> {
    if (selectedSubServiceIds.length === 0) {
      return { subServiceIds: 'יש לבחור לפחות תחום אחד שבו אתה נותן שירות.' };
    }
    // A malformed price blocks the step; a MISSING one does not. Pricing is optional by design --
    // a registrant may finish signing up and price their services from their profile later -- and
    // the backend enforces exactly the same distinction.
    const priceError = firstSubServicePriceError(selectedSubServiceIds, subServicePrices);
    if (priceError) {
      return { subServiceIds: priceError };
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
    if (nextErrors.fullName || nextErrors.email || nextErrors.phone || nextErrors.password) {
      goToStage(1, -1);
    } else if (nextErrors.categoryIds || nextErrors.serviceRegionId || nextErrors.serviceCityIds) {
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
      if (await advanceFromStage1()) {
        goToStage(2, 1);
      }
      return;
    }

    if (currentStage === 2) {
      const stage2Errors = validateStage2();
      setErrors((prev) => ({
        ...prev,
        categoryIds: stage2Errors.categoryIds,
        serviceRegionId: stage2Errors.serviceRegionId,
        serviceCityIds: stage2Errors.serviceCityIds,
      }));
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
      const response = await registerProfessional({
        fullName: fullName.trim(),
        email: email.trim(),
        phone: phone.trim(),
        password,
        categoryIds: selectedCategoryIds,
        serviceRegionId: serviceRegionId as number,
        serviceCityIds,
        // The base city is the first city they picked, in the catalogue's own order — MS4
        // requires it to be one of the service cities, and asking a registrant to nominate a
        // "main" city on top of choosing them would be a question with no obvious right answer.
        baseCityId: serviceCityIds[0],
        basePrice: Number(basePrice),
        // The priced form. The ids-only field is deliberately not sent alongside it: the server
        // treats `subServices` as authoritative when both are present, so sending both would only
        // create a second source of truth for the same list.
        subServices: toPriceSelections(selectedSubServiceIds, subServicePrices),
        workingHours: toWeeklyHoursRequest(workingHoursRows),
        verificationDocument,
        profilePhoto: photo,
      });
      onSuccess(response);
    } catch (error) {
      // DUPLICATE_EMAIL / DUPLICATE_PHONE are expected validation outcomes, not surprises, and
      // both must land on their field. DUPLICATE_PHONE previously had no branch at all: it fell
      // through to `getFieldErrorMessages`, which returns null for anything that is not
      // VALIDATION_ERROR, and ended up as the generic "משהו השתבש, נסו שוב" banner on the summary
      // screen — a registrant who had just filled in six stages was told nothing about what was
      // wrong. `routeFieldErrors` sends them back to stage 1 with every other stage's answers
      // still in state, so only the offending field needs correcting.
      const duplicate = mapDuplicateContactError(error);
      if (duplicate) {
        const nextErrors: FormErrors = { [duplicate.field]: duplicate.message };
        setErrors((prev) => ({ ...prev, ...nextErrors }));
        routeFieldErrors(nextErrors);
      } else if (error instanceof ApiError && error.code === 'CATEGORY_MISMATCH') {
        // A chosen sub-service doesn't belong to any of the chosen categories — only reachable
        // if the catalog changed under the wizard mid-session.
        const nextErrors: FormErrors = {
          subServiceIds: 'חלק מהתחומים שנבחרו אינם שייכים לתחומי השירות שנבחרו. יש לבחור אותם מחדש.',
        };
        setSelectedSubServiceIds([]);
        setSubServicePrices({});
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
  // A local rule always wins over a server answer: showing "this email is taken" under text that
  // is not an email address would be nonsense.
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
        title="הרשמה כבעל מקצוע"
        currentStage={currentStage}
        totalStages={TOTAL_STAGES}
        direction={direction}
        onBack={handleBack}
        primaryLabel={primaryLabel}
        primaryLoading={isSubmitting || isCheckingContacts}
      >
        {currentStage === 1 && (
          <>
            <Input
              label="שם מלא"
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
              onBlur={() => handleBlur('fullName', validateFullName(fullName))}
              error={errors.fullName}
              maxLength={FULL_NAME_MAX_LENGTH}
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
              maxLength={EMAIL_MAX_LENGTH}
              hint={emailAvailability.status === 'checking' ? 'בודקים את כתובת האימייל…' : undefined}
              autoComplete="email"
              required
            />
            <Input
              label="טלפון נייד"
              type="tel"
              inputMode="tel"
              dir="ltr"
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
              onBlur={() => handleBlur('phone', validatePhone(phone))}
              error={phoneError}
              maxLength={PHONE_INPUT_MAX_LENGTH}
              autoComplete="tel"
              hint={
                phoneAvailability.status === 'checking'
                  ? 'בודקים את מספר הטלפון…'
                  : 'למשל 050-1234567 — נאמת אותו בהמשך ההרשמה'
              }
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
              <>
                <MultiSelectField
                  label="תחומי שירות"
                  options={categoryOptions}
                  selected={selectedCategoryIds}
                  onChange={handleCategoriesChange}
                  placeholder="בחירת תחומים"
                  error={errors.categoryIds}
                  hint="אפשר לבחור יותר מתחום אחד — למשל אינסטלציה והנדימן."
                  required
                />

                <Select
                  label="אזור שירות"
                  value={serviceRegionId === null ? '' : String(serviceRegionId)}
                  onChange={(event) =>
                    handleRegionChange(event.target.value ? Number(event.target.value) : null)
                  }
                  options={regionOptions}
                  placeholder="בחירת אזור"
                  error={errors.serviceRegionId}
                  required
                />

                <MultiSelectField
                  label="ערים שבהן אתה נותן שירות"
                  options={cityOptions}
                  selected={serviceCityIds}
                  onChange={(next) => {
                    setServiceCityIds(next);
                    setErrors((prev) => ({ ...prev, serviceCityIds: undefined }));
                  }}
                  placeholder="בחירת ערים"
                  emptyMessage="יש לבחור אזור שירות תחילה."
                  searchable
                  searchPlaceholder="חיפוש עיר…"
                  error={errors.serviceCityIds}
                  hint="הערים מוצגות לפי האזור שנבחר. העיר הראשונה ברשימה היא עיר הבסיס שממנה מחושב זמן ההגעה."
                  required
                />
              </>
            )}

            {/* The fields above own their messages when they're on screen; while the catalog is
                loading or failed they aren't, so the errors would otherwise be invisible. */}
            {(isLoadingCategories || categoriesError) && (errors.categoryIds || errors.serviceRegionId) && (
              <p className={styles.stepError} role="alert">
                {errors.categoryIds ?? errors.serviceRegionId}
              </p>
            )}
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
              <p className={styles.stepHint}>אין עדיין תת-שירותים מוגדרים עבור התחומים שנבחרו.</p>
            )}

            {/* MS4: grouped by category, because a professional who chose two trades needs to
                see which trade each sub-service belongs to — an undivided list of, say,
                plumbing and handyman items reads as one long jumble. A single-category
                registrant sees one heading, which costs nothing. */}
            {!isLoadingCategories &&
              !categoriesError &&
              selectedCategories.map((category) =>
                category.subServices.length === 0 ? null : (
                  <div key={category.id} className={styles.subServiceGroup}>
                    <p className={styles.subServiceGroupTitle}>{category.nameHe}</p>
                    <div className={styles.subServicesList}>
                      {category.subServices.map((subService) => (
                        <SubServicePriceRow
                          key={subService.id}
                          label={subService.nameHe}
                          checked={selectedSubServiceIds.includes(subService.id)}
                          onToggle={() => toggleSubService(subService.id)}
                          price={subServicePrices[subService.id] ?? ''}
                          onPriceChange={(value) =>
                            setSubServicePrices((prev) => ({ ...prev, [subService.id]: value }))
                          }
                          error={
                            selectedSubServiceIds.includes(subService.id)
                              ? validateSubServicePrice(subServicePrices[subService.id] ?? '')
                              : null
                          }
                        />
                      ))}
                    </div>
                  </div>
                ),
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

            {/* MS4 §11: "החל על הכל" — set common hours once instead of typing the same pair
                seven times. The week itself stays blank until the registrant acts (nothing is
                pre-filled on their behalf), and every day remains independently editable
                afterwards (§12). Same shared component, same 24-hour `TimeField`, as
                /pro/availability — §13's "registration and dashboard must agree" is satisfied by
                them being literally the same code. */}
            <WeeklyHoursFields
              rows={workingHoursRows}
              onChange={setWorkingHoursRows}
              errors={workingHoursRowErrors}
              showApplyToAll
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
                <dt>טלפון</dt>
                <dd dir="ltr">{phone}</dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>תחומי שירות</dt>
                <dd>
                  <span className={styles.subServiceChips}>
                    {selectedCategories.map((category) => (
                      <Badge key={category.id} size="sm">
                        {category.nameHe}
                      </Badge>
                    ))}
                  </span>
                </dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>אזור שירות</dt>
                <dd>{regions?.find((region) => region.id === serviceRegionId)?.nameHe ?? ''}</dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>ערי שירות</dt>
                <dd>
                  <span className={styles.subServiceChips}>
                    {selectedCityNames.map((name) => (
                      <Badge key={name} size="sm">
                        {name}
                      </Badge>
                    ))}
                  </span>
                </dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>מחיר ביקור בסיסי</dt>
                <dd>{basePrice ? `₪${basePrice}` : ''}</dd>
              </div>
              <div className={styles.summaryRow}>
                <dt>תת-שירותים</dt>
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
