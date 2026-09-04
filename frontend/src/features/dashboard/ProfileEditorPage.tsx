import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import {
  PageHeader,
  Card,
  Input,
  Select,
  Textarea,
  Button,
  ProfilePhoto,
  MultiSelectField,
  SubServicePriceRow,
} from '../../shared/components';
import type { MultiSelectOption, SelectOption } from '../../shared/components';
import { useAuth } from '../../shared/hooks';
import {
  getMyProfessionalProfile,
  updateMyProfessionalProfile,
  uploadProfessionalProfileImage,
  getCategoriesWithSubServices,
  getMySubServices,
  updateMySubServices,
  GENERIC_ERROR_MESSAGE,
  getFieldErrorMessages,
  getServiceAreas,
  citiesForRegion,
  BIO_MAX_LENGTH,
  FULL_NAME_MAX_LENGTH,
} from '../../shared/api';
import type {
  ProfessionalProfileResponse,
  CategoryWithSubServicesResponse,
  MySubServicesResponse,
  ServiceRegionResponse,
} from '../../shared/api';
import { ProfessionalProfileDisplay, ProfessionalReviewsModal } from '../professionals';
import type { ProfessionalProfileDisplayProps } from '../professionals';
import {
  firstSubServicePriceError,
  toPriceSelections,
  validateSubServicePrice,
} from '../../shared/utils/subServicePrices';
import styles from './ProfileEditorPage.module.css';

/**
 * `/pro/profile` — PROFESSIONAL-only, the 4th `ProDashboardLayout` tab
 * (`frontend-ms8-design.md` §2.2/§4.3). Reads/writes `professionals/me` (bio/city/price/
 * photo) — a different DTO/concern than the shared, read-only `app/ProfilePage.tsx`
 * (`GET /api/users/me`), which is left completely untouched.
 *
 * `categoryId`/`approvalStatus` are shown read-only or not at all: the `Update...Request`
 * DTO has no field to change either. **MS1 (2026-08-22)**: `approvalStatus` is now a real
 * decision (`PENDING`/`APPROVED`/`REJECTED`) and is returned here because this is the
 * professional's own self-view — it is still not rendered as a field on this form; whether the
 * account is visible to customers is communicated by `OnboardingStatusNotice`, mounted for
 * every `/pro/*` screen by `ProDashboardLayout`.
 *
 * `fullName` writes to the underlying `users` row (§0 of the design doc), so a successful
 * save also calls `useAuth().refreshUser()` — otherwise the top-nav/`ProfilePage`'s cached
 * `user.fullName` would go stale until the next full page load or re-login (§6 Risk 1).
 *
 * **MS10 profile redesign (2026-08-19)**: the photo widget is now `shared/components`'
 * `ProfilePhoto` (replacing the retired `ProfessionalProfileImageField.tsx` — see
 * `docs/architecture/product-ms10-profile-redesign-design.md` §2.1/§2.3) — circular,
 * centered, exactly one edit-in-place affordance, click-to-enlarge via `ImageLightbox`.
 * Upload orchestration (calling `uploadProfessionalProfileImage`, tracking
 * `isUploading`/`uploadError`) now lives directly in this page rather than in a dedicated
 * wrapper component, since `ProfilePhoto` itself is upload-mechanism-agnostic (`onUpload`
 * is just a callback). Layout became a responsive two-region grid (`<900px` single column,
 * `>=900px` a `240px 1fr` grid) to fix the "large empty area on the left" root cause (§1.3)
 * — the previous `.card { max-width: 480px }` capped width with no auto margins.
 *
 * **MS11 — Services & Sub-services (2026-08-19)**: gained a sub-services checklist section,
 * below `basePrice`, above the main save button (`docs/architecture/product-ms11-sub-
 * services-design.md` §5.1). One unified always-editable checklist (no separate onboarding
 * step, design §6 item 3) scoped to the professional's own `categoryId` (never shows another
 * category's sub-services). Its own independent "שמירת תחומי עיסוק" save button, calling
 * `updateMySubServices` — a fully separate API call/loading/error/success state from the
 * main form's `handleSubmit` (design §6 item 4, lead-approved: two backend endpoints, two
 * independent saves, not one atomic action). The checklist's checkbox items use the new
 * `shared/components/Checkbox` primitive (this feature's first consumer).
 *
 * **MS6 Professional Command Center — live preview (design doc §7.1/§7.2)**: a third column
 * renders `ProfessionalProfileDisplay` (`features/professionals`, shared with the real public
 * profile page) fed a same-shaped object assembled **per render, from local form state**
 * (`fullName`/`serviceArea`/`city`/`bio`, `basePrice` parsed from its text input) plus the
 * already-loaded, non-editable `profile.categoryId`/`profileImageUrl`/`averageRating`/
 * `reviewCount` — no new API call, this is what makes the preview update live as the
 * professional types. Layout extends MS10's `240px 1fr` two-column grid to
 * `240px 1fr minmax(280px, 340px)` (photo | form | preview) at `>=900px`, preview column
 * `position: sticky`; below `900px` the preview stacks below the form (no sticky) — the
 * lead-approved recommended option (design doc §7.2).
 */
/**
 * The server's stored prices as editable strings, keyed by id. An unpriced service maps to `''`
 * (an empty input) rather than `'0'` — the professional has not named a price, and pre-filling a
 * zero would put a number in their mouth that they would then have to notice and delete.
 */
function pricesFromResponse(response: MySubServicesResponse): Record<number, string> {
  const prices: Record<number, string> = {};
  for (const item of response.subServices ?? []) {
    prices[item.subServiceId] = item.price === null ? '' : String(item.price);
  }
  return prices;
}

export default function ProfileEditorPage() {
  const { refreshUser } = useAuth();

  const [profile, setProfile] = useState<ProfessionalProfileResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [fullName, setFullName] = useState('');
  const [serviceRegionId, setServiceRegionId] = useState<number | null>(null);
  const [serviceCityIds, setServiceCityIds] = useState<number[]>([]);
  const [baseCityId, setBaseCityId] = useState<number | null>(null);
  const [selectedCategoryIds, setSelectedCategoryIds] = useState<number[]>([]);
  const [bio, setBio] = useState('');
  const [basePrice, setBasePrice] = useState('');

  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  const [isUploadingPhoto, setIsUploadingPhoto] = useState(false);
  const [photoUploadError, setPhotoUploadError] = useState<string | undefined>();

  /** The professional's own reviews, opened from the review count in the preview column —
   *  inline in a modal, never a navigation away from an editor holding unsaved changes. */
  const [isReviewsModalOpen, setIsReviewsModalOpen] = useState(false);

  // Sub-services checklist (MS11) — fully independent state from the main profile form above.
  const [categories, setCategories] = useState<CategoryWithSubServicesResponse[] | null>(null);
  const [regions, setRegions] = useState<ServiceRegionResponse[] | null>(null);
  /**
   * MS4 §3: cities the professional had saved that no longer belong to the region now selected.
   * They are dropped from the selection (a cross-region city is refused by the backend), but
   * never silently — this is what the warning below names, so a professional who changed region
   * by accident can see exactly what it cost before they save.
   */
  const [droppedCityNames, setDroppedCityNames] = useState<string[]>([]);
  const [selectedSubServiceIds, setSelectedSubServiceIds] = useState<Set<number>>(new Set());
  /* Prices as typed, keyed by sub-service id. Strings for the same reason registration keeps them
     as strings: an emptied field means "no price", and a numeric state would turn that into 0. */
  const [subServicePrices, setSubServicePrices] = useState<Record<number, string>>({});
  const [isLoadingSubServices, setIsLoadingSubServices] = useState(true);
  const [subServicesLoadError, setSubServicesLoadError] = useState<string | null>(null);
  const [isSavingSubServices, setIsSavingSubServices] = useState(false);
  const [subServicesSaveError, setSubServicesSaveError] = useState<string | null>(null);
  const [subServicesSavedAt, setSubServicesSavedAt] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    getMyProfessionalProfile()
      .then((result) => {
        if (cancelled) return;
        setProfile(result);
        setFullName(result.fullName);
        setServiceRegionId(result.serviceRegionId);
        setServiceCityIds(result.serviceCityIds);
        setBaseCityId(result.baseCityId);
        setSelectedCategoryIds(result.categoryIds);
        setBio(result.bio ?? '');
        setBasePrice(String(result.basePrice));
      })
      .catch(() => {
        if (!cancelled) setLoadError(GENERIC_ERROR_MESSAGE);
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // MS11 §5.1: fetched alongside the profile above but as a fully independent request/state
  // pair — the catalog (GET /api/categories) and the current selection (GET
  // /api/professionals/me/sub-services) are loaded together here since both are needed before
  // the checklist can render its pre-checked boxes.
  useEffect(() => {
    let cancelled = false;
    Promise.all([getCategoriesWithSubServices(), getMySubServices(), getServiceAreas()])
      .then(([categoriesResult, mineResult, regionsResult]) => {
        if (cancelled) return;
        setCategories(categoriesResult);
        setSelectedSubServiceIds(new Set(mineResult.subServiceIds));
        setSubServicePrices(pricesFromResponse(mineResult));
        setRegions(regionsResult);
      })
      .catch(() => {
        if (!cancelled) setSubServicesLoadError(GENERIC_ERROR_MESSAGE);
      })
      .finally(() => {
        if (!cancelled) setIsLoadingSubServices(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const categoryOptions: MultiSelectOption[] = (categories ?? []).map((category) => ({
    value: category.id,
    label: category.nameHe,
  }));
  const regionOptions: SelectOption[] = (regions ?? []).map((region) => ({
    value: String(region.id),
    label: region.nameHe,
  }));
  /** MS4 §3: the city options are the selected region's own cities, from the shared data layer. */
  const cityOptions: MultiSelectOption[] = citiesForRegion(regions, serviceRegionId).map((city) => ({
    value: city.id,
    label: city.nameHe,
  }));
  /** The base city is chosen from the cities they actually serve — the backend requires it. */
  const baseCityOptions: SelectOption[] = cityOptions
    .filter((option) => serviceCityIds.includes(option.value))
    .map((option) => ({ value: String(option.value), label: option.label }));

  /**
   * MS4 §3, on an *existing* professional: changing region cannot keep cities that belong to
   * the old one (the backend refuses a cross-region city), so they are dropped — and named, in
   * a warning, rather than vanishing. Nothing is written until the professional saves, so this
   * is still fully reversible by switching the region back.
   */
  function handleRegionChange(nextRegionId: number | null) {
    if (nextRegionId === serviceRegionId) {
      return;
    }
    const allowed = new Set(citiesForRegion(regions, nextRegionId).map((city) => city.id));
    const dropped = citiesForRegion(regions, serviceRegionId)
      .filter((city) => serviceCityIds.includes(city.id) && !allowed.has(city.id))
      .map((city) => city.nameHe);

    setServiceRegionId(nextRegionId);
    setServiceCityIds((prev) => prev.filter((id) => allowed.has(id)));
    setBaseCityId((prev) => (prev !== null && allowed.has(prev) ? prev : null));
    setDroppedCityNames(dropped);
  }

  /** Removing a city that happens to be the base city clears the base city rather than leaving
   *  a base city they no longer serve — which the backend would refuse on save. */
  function handleServiceCitiesChange(next: number[]) {
    setServiceCityIds(next);
    setBaseCityId((prev) => (prev !== null && next.includes(prev) ? prev : (next[0] ?? null)));
    setDroppedCityNames([]);
  }

  /** Dropping a category invalidates any sub-service that belonged only to it. Same rule, same
   *  reason, as the registration wizard: the backend refuses a sub-service outside the
   *  professional's own categories with `400 CATEGORY_MISMATCH`. */
  function handleCategoriesChange(next: number[]) {
    setSelectedCategoryIds(next);
    const stillValid = new Set(
      (categories ?? [])
        .filter((category) => next.includes(category.id))
        .flatMap((category) => category.subServices.map((subService) => subService.id)),
    );
    setSelectedSubServiceIds((prev) => new Set([...prev].filter((id) => stillValid.has(id))));
  }

  function toggleSubService(subServiceId: number) {
    setSelectedSubServiceIds((prev) => {
      const next = new Set(prev);
      if (next.has(subServiceId)) {
        next.delete(subServiceId);
      } else {
        next.add(subServiceId);
      }
      return next;
    });
  }

  async function handleSaveSubServices() {
    // Malformed prices are refused here so the professional gets a per-field message instead of a
    // generic failure banner. A MISSING price is fine and saves as "not stated" -- the same
    // distinction the backend draws.
    const priceError = firstSubServicePriceError(
      Array.from(selectedSubServiceIds),
      subServicePrices,
    );
    if (priceError) {
      setSubServicesSaveError(priceError);
      setSubServicesSavedAt(null);
      return;
    }
    setSubServicesSaveError(null);
    setSubServicesSavedAt(null);
    setIsSavingSubServices(true);
    try {
      const result = await updateMySubServices(
        toPriceSelections(Array.from(selectedSubServiceIds), subServicePrices),
      );
      setSelectedSubServiceIds(new Set(result.subServiceIds));
      // Re-seed from the server's answer rather than keeping the typed strings: it is what was
      // actually stored, normalised (420 saved comes back 420.00), so the form stops showing a
      // value that differs from the record.
      setSubServicePrices(pricesFromResponse(result));
      setSubServicesSavedAt(Date.now());
    } catch {
      setSubServicesSaveError(GENERIC_ERROR_MESSAGE);
    } finally {
      setIsSavingSubServices(false);
    }
  }

  function handlePhotoUpload(file: File) {
    setPhotoUploadError(undefined);
    setIsUploadingPhoto(true);
    uploadProfessionalProfileImage(file)
      .then((result) => {
        setProfile((prev) => (prev ? { ...prev, profileImageUrl: result.imageUrl } : prev));
      })
      .catch(() => {
        setPhotoUploadError('ההעלאה נכשלה, אפשר לנסות שוב.');
      })
      .finally(() => {
        setIsUploadingPhoto(false);
      });
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);
    setFieldErrors({});
    setSavedAt(null);

    const priceNumber = Number(basePrice);
    if (!fullName.trim() || !basePrice || Number.isNaN(priceNumber)) {
      setBannerError('יש למלא את כל השדות הנדרשים.');
      return;
    }
    // MS4: the three coverage fields and the category set are all required by the backend, and
    // saying which one is missing beats a 400 that names a field the professional cannot see.
    if (selectedCategoryIds.length === 0) {
      setBannerError('יש לבחור לפחות תחום שירות אחד.');
      return;
    }
    if (serviceRegionId === null) {
      setBannerError('יש לבחור אזור שירות.');
      return;
    }
    if (serviceCityIds.length === 0 || baseCityId === null) {
      setBannerError('יש לבחור לפחות עיר שירות אחת ועיר בסיס.');
      return;
    }

    setIsSaving(true);
    try {
      const updated = await updateMyProfessionalProfile({
        fullName: fullName.trim(),
        serviceRegionId,
        serviceCityIds,
        baseCityId,
        categoryIds: selectedCategoryIds,
        bio: bio.trim() || undefined,
        basePrice: priceNumber,
      });
      setProfile(updated);
      setServiceRegionId(updated.serviceRegionId);
      setServiceCityIds(updated.serviceCityIds);
      setBaseCityId(updated.baseCityId);
      setSelectedCategoryIds(updated.categoryIds);
      setDroppedCityNames([]);
      setSavedAt(Date.now());
      void refreshUser();
    } catch (error) {
      const fields = getFieldErrorMessages(error);
      if (fields) {
        setFieldErrors(fields);
      } else {
        setBannerError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSaving(false);
    }
  }

  // §7.1: a same-shaped object recomputed every render from local form state — no new API
  // call, this is what makes the preview column update live as the professional types.
  // `profileImageUrl`/`averageRating`/`reviewCount` aren't edited on this page, so they're read
  // straight from the last-fetched `profile` object.
  //
  // MS4: the coverage labels are resolved from the catalogue against the *unsaved* selection, so
  // switching region or ticking a city updates the preview immediately — the same reason the
  // rest of this object is built from form state rather than from `profile`.
  const previewProfessional: ProfessionalProfileDisplayProps['professional'] | null = profile
    ? {
        fullName,
        categoryIds: selectedCategoryIds,
        serviceRegionNameHe: regions?.find((region) => region.id === serviceRegionId)?.nameHe ?? null,
        serviceCityNamesHe: cityOptions
          .filter((option) => serviceCityIds.includes(option.value))
          .map((option) => option.label),
        city: baseCityOptions.find((option) => option.value === String(baseCityId))?.label ?? null,
        bio: bio.trim() ? bio : null,
        basePrice: Number.isNaN(Number(basePrice)) ? 0 : Number(basePrice),
        profileImageUrl: profile.profileImageUrl,
        averageRating: profile.averageRating,
        reviewCount: profile.reviewCount,
        // Trust signals aren't editable here either, but they are part of what a customer
        // sees — so the preview shows them rather than silently rendering a trust-less card.
        approvalStatus: profile.approvalStatus,
        createdAt: profile.createdAt,
      }
    : null;

  return (
    <div>
      <PageHeader title="פרופיל עסקי" description="הפרטים האלה מוצגים ללקוחות בתהליך ההזמנה." />

      {isLoading && <p>טוען…</p>}

      {!isLoading && loadError && (
        <div className={styles.banner} role="alert">
          <p>{loadError}</p>
        </div>
      )}

      {!isLoading && profile && (
        <Card className={styles.card}>
          <div className={styles.photoColumn}>
            <ProfilePhoto
              imageUrl={profile.profileImageUrl}
              fallbackInitial={profile.fullName.charAt(0)}
              onUpload={handlePhotoUpload}
              isUploading={isUploadingPhoto}
              uploadError={photoUploadError}
            />
          </div>

          <form className={styles.form} onSubmit={handleSubmit} noValidate>
            <Input
              label="שם מלא"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              error={fieldErrors.fullName}
              maxLength={FULL_NAME_MAX_LENGTH}
              required
            />
            {/* MS4 §18: categories and service coverage are editable here, not only at
                registration — the same closed catalogue, the same region→city filter, and the
                same components the wizard uses. */}
            <MultiSelectField
              label="תחומי שירות"
              options={categoryOptions}
              selected={selectedCategoryIds}
              onChange={handleCategoriesChange}
              placeholder="בחירת תחומים"
              error={fieldErrors.categoryIds}
              hint="אפשר לבחור יותר מתחום אחד. הסרת תחום מסירה גם את תת-השירותים ששייכים רק לו."
              required
            />

            <Select
              label="אזור שירות"
              value={serviceRegionId === null ? '' : String(serviceRegionId)}
              onChange={(e) => handleRegionChange(e.target.value ? Number(e.target.value) : null)}
              options={regionOptions}
              placeholder="בחירת אזור"
              error={fieldErrors.serviceRegionId}
              required
            />

            {droppedCityNames.length > 0 && (
              <div className={styles.warningBanner} role="status">
                <p>
                  שינוי האזור הסיר מהרשימה ערים ששייכות לאזור הקודם: {droppedCityNames.join(', ')}. אפשר לחזור
                  לאזור הקודם כדי לשחזר אותן — השינוי נשמר רק בלחיצה על "שמירת שינויים".
                </p>
              </div>
            )}

            <MultiSelectField
              label="ערים שבהן אני נותן שירות"
              options={cityOptions}
              selected={serviceCityIds}
              onChange={handleServiceCitiesChange}
              placeholder="בחירת ערים"
              emptyMessage="יש לבחור אזור שירות תחילה."
              searchable
              searchPlaceholder="חיפוש עיר…"
              error={fieldErrors.serviceCityIds}
              required
            />

            <Select
              label="עיר בסיס"
              value={baseCityId === null ? '' : String(baseCityId)}
              onChange={(e) => setBaseCityId(e.target.value ? Number(e.target.value) : null)}
              options={baseCityOptions}
              placeholder="בחירת עיר בסיס"
              error={fieldErrors.baseCityId}
              hint="העיר שממנה מחושב זמן ההגעה ללקוח. חייבת להיות אחת מערי השירות שנבחרו."
              required
            />
            <Textarea
              label="קצת עליי (לא חובה)"
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              error={fieldErrors.bio}
              maxLength={BIO_MAX_LENGTH}
            />
            <Input
              label="מחיר ביקור בסיסי"
              type="number"
              min="0"
              value={basePrice}
              onChange={(e) => setBasePrice(e.target.value)}
              error={fieldErrors.basePrice}
              required
            />

            <fieldset className={styles.subServicesFieldset}>
              <legend className={styles.subServicesLegend}>תחומי עיסוק</legend>

              {isLoadingSubServices && <p>טוען…</p>}

              {!isLoadingSubServices && subServicesLoadError && (
                <div className={styles.banner} role="alert">
                  <p>{subServicesLoadError}</p>
                </div>
              )}

              {!isLoadingSubServices && !subServicesLoadError && categories && (
                <>
                  {/* MS4: one group per category the professional serves, so a plumber-and-
                      handyman can tell which trade each sub-service belongs to. Scoped to the
                      *currently selected* categories, not the saved ones, so unticking a
                      category removes its group immediately. */}
                  {categories.filter((c) => selectedCategoryIds.includes(c.id)).every(
                    (c) => c.subServices.length === 0,
                  ) ? (
                    <p className={styles.subServicesEmptyState}>אין עדיין תת-שירותים מוגדרים עבור התחומים שלך.</p>
                  ) : (
                    categories
                      .filter((c) => selectedCategoryIds.includes(c.id) && c.subServices.length > 0)
                      .map((category) => (
                        <div key={category.id} className={styles.subServiceGroup}>
                          <p className={styles.subServiceGroupTitle}>{category.nameHe}</p>
                          <div className={styles.subServicesList}>
                            {category.subServices.map((subService) => (
                              <SubServicePriceRow
                                key={subService.id}
                                label={subService.nameHe}
                                checked={selectedSubServiceIds.has(subService.id)}
                                onToggle={() => toggleSubService(subService.id)}
                                price={subServicePrices[subService.id] ?? ''}
                                onPriceChange={(value) =>
                                  setSubServicePrices((prev) => ({ ...prev, [subService.id]: value }))
                                }
                                error={
                                  selectedSubServiceIds.has(subService.id)
                                    ? validateSubServicePrice(subServicePrices[subService.id] ?? '')
                                    : null
                                }
                              />
                            ))}
                          </div>
                        </div>
                      ))
                  )}

                  {subServicesSaveError && (
                    <div className={styles.banner} role="alert">
                      <p>{subServicesSaveError}</p>
                    </div>
                  )}
                  {subServicesSavedAt && (
                    <p className={styles.savedNotice} role="status">
                      תחומי העיסוק נשמרו בהצלחה.
                    </p>
                  )}

                  <Button type="button" variant="secondary" loading={isSavingSubServices} onClick={handleSaveSubServices}>
                    שמירת תחומי עיסוק
                  </Button>
                </>
              )}
            </fieldset>

            {bannerError && (
              <div className={styles.banner} role="alert">
                <p>{bannerError}</p>
              </div>
            )}
            {savedAt && (
              <p className={styles.savedNotice} role="status">
                הפרופיל נשמר בהצלחה.
              </p>
            )}

            <Button type="submit" loading={isSaving}>
              שמירת שינויים
            </Button>
          </form>

          {previewProfessional && (
            <div className={styles.previewColumn}>
              <p className={styles.previewLabel}>תצוגה מקדימה</p>
              <Card className={styles.previewCard}>
                <ProfessionalProfileDisplay
                  professional={previewProfessional}
                  onReviewsClick={() => setIsReviewsModalOpen(true)}
                />
              </Card>
            </div>
          )}
        </Card>
      )}

      {profile && (
        <ProfessionalReviewsModal
          isOpen={isReviewsModalOpen}
          onClose={() => setIsReviewsModalOpen(false)}
          professionalId={profile.id}
        />
      )}
    </div>
  );
}
