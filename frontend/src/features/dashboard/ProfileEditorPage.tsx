import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { PageHeader, Card, Input, Textarea, Button } from '../../shared/components';
import { useAuth } from '../../shared/hooks';
import {
  getMyProfessionalProfile,
  updateMyProfessionalProfile,
  GENERIC_ERROR_MESSAGE,
  getFieldErrorMessages,
  getCategoryNameHe,
} from '../../shared/api';
import type { ProfessionalProfileResponse } from '../../shared/api';
import { ProfessionalProfileImageField } from './ProfessionalProfileImageField';
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
          <ProfessionalProfileImageField
            currentImageUrl={profile.profileImageUrl}
            onUploaded={(imageUrl) => setProfile((prev) => (prev ? { ...prev, profileImageUrl: imageUrl } : prev))}
          />

          <form className={styles.form} onSubmit={handleSubmit} noValidate>
            <div className={styles.readonlyRow}>
              <span className={styles.readonlyLabel}>תחום שירות</span>
              <span className={styles.readonlyValue}>{getCategoryNameHe(profile.categoryId)}</span>
            </div>

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
        </Card>
      )}
    </div>
  );
}
