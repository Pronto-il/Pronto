import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { PageHeader, Card, Input, Textarea, Button, ProfilePhoto, Checkbox } from '../../shared/components';
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
  getCategoryNameHe,
} from '../../shared/api';
import type { ProfessionalProfileResponse, CategoryWithSubServicesResponse } from '../../shared/api';
import { ProfessionalProfileDisplay } from '../professionals';
import type { ProfessionalProfileDisplayProps } from '../professionals';
import styles from './ProfileEditorPage.module.css';

/**
 * `/pro/profile` — PROFESSIONAL-only, the 4th `ProDashboardLayout` tab
 * (`frontend-ms8-design.md` §2.2/§4.3). Reads/writes `professionals/me` (bio/city/price/
 * photo) — a different DTO/concern than the shared, read-only `app/ProfilePage.tsx`
 * (`GET /api/users/me`), which is left completely untouched.
 *
 * `categoryId`/`approvalStatus` are shown read-only or not at all: the `Update...Request`
 * DTO has no field to change either (auto-approved in v1.0, no actionable approval status
 * to surface yet).
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
export default function ProfileEditorPage() {
  const { refreshUser } = useAuth();

  const [profile, setProfile] = useState<ProfessionalProfileResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [fullName, setFullName] = useState('');
  const [serviceArea, setServiceArea] = useState('');
  const [city, setCity] = useState('');
  const [bio, setBio] = useState('');
  const [basePrice, setBasePrice] = useState('');

  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  const [isUploadingPhoto, setIsUploadingPhoto] = useState(false);
  const [photoUploadError, setPhotoUploadError] = useState<string | undefined>();

  // Sub-services checklist (MS11) — fully independent state from the main profile form above.
  const [categories, setCategories] = useState<CategoryWithSubServicesResponse[] | null>(null);
  const [selectedSubServiceIds, setSelectedSubServiceIds] = useState<Set<number>>(new Set());
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
        setServiceArea(result.serviceArea);
        setCity(result.city ?? '');
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
    Promise.all([getCategoriesWithSubServices(), getMySubServices()])
      .then(([categoriesResult, mineResult]) => {
        if (cancelled) return;
        setCategories(categoriesResult);
        setSelectedSubServiceIds(new Set(mineResult.subServiceIds));
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
    setSubServicesSaveError(null);
    setSubServicesSavedAt(null);
    setIsSavingSubServices(true);
    try {
      const result = await updateMySubServices(Array.from(selectedSubServiceIds));
      setSelectedSubServiceIds(new Set(result.subServiceIds));
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
    if (!fullName.trim() || !serviceArea.trim() || !city.trim() || !basePrice || Number.isNaN(priceNumber)) {
      setBannerError('יש למלא את כל השדות הנדרשים.');
      return;
    }

    setIsSaving(true);
    try {
      const updated = await updateMyProfessionalProfile({
        fullName: fullName.trim(),
        serviceArea: serviceArea.trim(),
        city: city.trim(),
        bio: bio.trim() || undefined,
        basePrice: priceNumber,
      });
      setProfile(updated);
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
  // `profile.categoryId`/`profileImageUrl`/`averageRating`/`reviewCount` aren't edited on this
  // page, so they're read straight from the last-fetched `profile` object.
  const previewProfessional: ProfessionalProfileDisplayProps['professional'] | null = profile
    ? {
        fullName,
        categoryId: profile.categoryId,
        serviceArea,
        city,
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
            <div className={styles.readonlyRow}>
              <span className={styles.readonlyLabel}>תחום שירות</span>
              <span className={styles.readonlyValue}>{getCategoryNameHe(profile.categoryId)}</span>
            </div>
          </div>

          <form className={styles.form} onSubmit={handleSubmit} noValidate>
            <Input
              label="שם מלא"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              error={fieldErrors.fullName}
              required
            />
            <Input
              label="אזור שירות"
              value={serviceArea}
              onChange={(e) => setServiceArea(e.target.value)}
              error={fieldErrors.serviceArea}
              required
            />
            <Input label="עיר" value={city} onChange={(e) => setCity(e.target.value)} error={fieldErrors.city} required />
            <Textarea
              label="קצת עליי (לא חובה)"
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              error={fieldErrors.bio}
              hint="עד 2000 תווים."
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
                  {(categories.find((c) => c.id === profile.categoryId)?.subServices.length ?? 0) === 0 ? (
                    <p className={styles.subServicesEmptyState}>אין עדיין תת-שירותים מוגדרים עבור התחום שלך.</p>
                  ) : (
                    <div className={styles.subServicesList}>
                      {categories
                        .find((c) => c.id === profile.categoryId)
                        ?.subServices.map((subService) => (
                          <Checkbox
                            key={subService.id}
                            label={subService.nameHe}
                            checked={selectedSubServiceIds.has(subService.id)}
                            onChange={() => toggleSubService(subService.id)}
                          />
                        ))}
                    </div>
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
                <ProfessionalProfileDisplay professional={previewProfessional} />
              </Card>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
