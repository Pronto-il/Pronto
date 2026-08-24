import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Heart } from 'lucide-react';
import { Card, Button, Input, AddressFormFields, EMPTY_ADDRESS, ProfilePhoto } from '../shared/components';
import type { AddressValue } from '../shared/components';
import { useAuth } from '../shared/hooks';
import {
  getCategoryNamesHe,
  deleteMe,
  updateMe,
  GENERIC_ERROR_MESSAGE,
  getFieldErrorMessages,
  type UserRole,
  type UserMeDefaultAddress,
} from '../shared/api';
import styles from './ProfilePage.module.css';

/**
 * **Operator label only.** This screen used to render a "סוג משתמש" row for every role. It no
 * longer does for end users: a customer being told they are a `לקוח`, or a professional that they
 * are a `בעל מקצוע`, is the system describing its own `users.role` column back at someone who
 * cannot act on it and did not ask. It is internal vocabulary on a personal-details screen.
 *
 * Presentation only — `role` itself is untouched, and so is every authorization decision that
 * reads it. `useAuth().user.role` still drives this component's own customer/professional branch
 * below, `RequireAuth`, and the nav.
 *
 * `ADMIN` keeps the row, because for an operator it is not trivia: an operator's screen is
 * otherwise near-identical to a professional's read-only one, and confirming which account a
 * privileged session is actually on is operationally useful. Hence `Partial` — the two end-user
 * roles have deliberately no entry, so re-adding one is a visible edit rather than a lookup that
 * quietly starts resolving again.
 */
const ROLE_LABELS: Partial<Record<UserRole, string>> = {
  ADMIN: 'מפעיל מערכת',
};

const ADDRESS_FIELD_KEYS: (keyof AddressValue)[] = [
  'city',
  'street',
  'houseNumber',
  'apartment',
  'floor',
  'entrance',
  'addressNotes',
];

function toAddressValue(address: UserMeDefaultAddress | null): AddressValue {
  if (!address) {
    return EMPTY_ADDRESS;
  }
  return {
    city: address.city,
    street: address.street,
    houseNumber: address.houseNumber,
    apartment: address.apartment ?? '',
    floor: address.floor ?? '',
    entrance: address.entrance ?? '',
    addressNotes: address.addressNotes ?? '',
  };
}

/**
 * `GET /api/users/me` — auth-required (mounted under `RequireAuth` in `router.tsx`, so
 * `user` is always populated here).
 *
 * **MS10 profile redesign (2026-08-19)**: a `CUSTOMER` caller now gets a real edit form
 * (`fullName`/`phone`/`defaultAddress` via `PUT /api/users/me`, §3.2/§4 of
 * `docs/architecture/product-ms10-profile-redesign-design.md`) — always-editable, same
 * "form with a save button" pattern `features/dashboard/ProfileEditorPage.tsx` already
 * uses, not a separate view/edit-mode toggle. A `PROFESSIONAL` caller keeps this screen
 * fully read-only (per §2.4's explicit scope decision — `fullName` is already editable at
 * the dedicated `/pro/profile` screen; no product ask for a second editing surface for the
 * same field). Both roles get `ProfilePhoto` at the top: a `CUSTOMER` gets a non-upload
 * initials avatar (§3.1 "Reading A" — styling parity only, no photo upload for a customer
 * in this milestone), a `PROFESSIONAL` gets their existing `/pro/profile` photo shown
 * read-only (`user.professional.profileImageUrl`, §6's small `ProfessionalInfo` addition).
 * The old `justify-content: space-between` label/value row layout (§1.6's finding) is
 * replaced by a compact "label directly above value" layout for the remaining read-only
 * rows, matching `Input`'s own label-above-control rhythm.
 *
 * Also the entry point to `/favorites` for a `CUSTOMER` — approved UX decision (Frontend
 * Milestone 8 correction): favorites is a secondary feature reached via "הפרופיל שלי" →
 * "מועדפים", not a primary top-nav destination.
 */
export default function ProfilePage() {
  const { user, logout, refreshUser } = useAuth();
  const navigate = useNavigate();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  const [fullName, setFullName] = useState(user?.fullName ?? '');
  const [phone, setPhone] = useState(user?.phone ?? '');
  const [address, setAddress] = useState<AddressValue>(toAddressValue(user?.defaultAddress ?? null));
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [addressErrors, setAddressErrors] = useState<Partial<Record<keyof AddressValue, string>>>({});
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  if (!user) {
    return null;
  }

  const isCustomer = user.role === 'CUSTOMER';

  function handleLogout() {
    logout();
    navigate('/login', { replace: true });
  }

  async function handleDeleteAccount() {
    setDeleteError(null);
    setIsDeleting(true);
    try {
      await deleteMe();
      logout();
      navigate('/login', { replace: true });
    } catch {
      setDeleteError(GENERIC_ERROR_MESSAGE);
    } finally {
      setIsDeleting(false);
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);
    setFieldErrors({});
    setAddressErrors({});
    setSavedAt(null);

    if (!fullName.trim() || !phone.trim() || !address.city.trim() || !address.street.trim() || !address.houseNumber.trim()) {
      setBannerError('יש למלא את כל השדות הנדרשים.');
      return;
    }

    setIsSaving(true);
    try {
      await updateMe({
        fullName: fullName.trim(),
        phone: phone.trim(),
        defaultAddress: {
          city: address.city.trim(),
          street: address.street.trim(),
          houseNumber: address.houseNumber.trim(),
          apartment: address.apartment.trim() || undefined,
          floor: address.floor.trim() || undefined,
          entrance: address.entrance.trim() || undefined,
          addressNotes: address.addressNotes.trim() || undefined,
        },
      });
      setSavedAt(Date.now());
      void refreshUser();
    } catch (error) {
      const fields = getFieldErrorMessages(error);
      if (fields) {
        const nextAddressErrors: Partial<Record<keyof AddressValue, string>> = {};
        const nextTopErrors: Record<string, string> = {};
        for (const [field, message] of Object.entries(fields)) {
          if ((ADDRESS_FIELD_KEYS as string[]).includes(field)) {
            nextAddressErrors[field as keyof AddressValue] = message;
          } else {
            nextTopErrors[field] = message;
          }
        }
        setFieldErrors(nextTopErrors);
        setAddressErrors(nextAddressErrors);
      } else {
        setBannerError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <div className="focused-page">
      {/* No page title: "הפרופיל שלי" repeated, word for word, the nav link that got you here —
          present in the desktop nav, and as the mobile top-bar profile icon whose `aria-label`
          carries the same string. `ProfilePhoto` and the user's own name open the screen. */}
      <Card className={styles.card}>
        <ProfilePhoto
          imageUrl={isCustomer ? null : user.professional?.profileImageUrl ?? null}
          fallbackInitial={user.fullName.charAt(0)}
        />

        {isCustomer ? (
          <form className={styles.form} onSubmit={handleSubmit} noValidate>
            <Input
              label="שם מלא"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              error={fieldErrors.fullName}
              required
            />
            <Input
              label="טלפון"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              error={fieldErrors.phone}
              required
            />
            <AddressFormFields value={address} onChange={setAddress} errors={addressErrors} />

            {/* Email only. The "סוג משתמש" row was removed here — see ROLE_LABELS. */}
            <dl className={`${styles.details} ${styles.readonlySection}`}>
              <div className={styles.row}>
                <dt>אימייל</dt>
                <dd>{user.email}</dd>
              </div>
            </dl>

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
        ) : (
          <dl className={styles.details}>
            <div className={styles.row}>
              <dt>שם מלא</dt>
              <dd>{user.fullName}</dd>
            </div>
            <div className={styles.row}>
              <dt>אימייל</dt>
              <dd>{user.email}</dd>
            </div>
            {/* Operators only — a `PROFESSIONAL` falls through this and sees their business
                details instead. See ROLE_LABELS. */}
            {user.role === 'ADMIN' && (
              <div className={styles.row}>
                <dt>סוג משתמש</dt>
                <dd>{ROLE_LABELS.ADMIN}</dd>
              </div>
            )}
            {user.professional && (
              <>
                <div className={styles.row}>
                  <dt>תחומי שירות</dt>
                  {/* MS4: several trades are possible now; this read-only page has room to
                      name them all rather than showing a primary and hiding the rest. */}
                  <dd>{getCategoryNamesHe(user.professional.categoryIds).join(' · ') || 'לא הוגדר'}</dd>
                </div>
                <div className={styles.row}>
                  <dt>אזור שירות</dt>
                  <dd>{user.professional.serviceRegion ?? 'לא הוגדר'}</dd>
                </div>
                <div className={styles.row}>
                  <dt>מחיר ביקור בסיסי</dt>
                  <dd>₪{user.professional.basePrice}</dd>
                </div>
              </>
            )}
          </dl>
        )}
      </Card>
      {user.role === 'CUSTOMER' && (
        <Link to="/favorites" className={styles.favoritesLink}>
          <Heart size={18} aria-hidden="true" />
          <span>מועדפים</span>
        </Link>
      )}
      <Button variant="secondary" onClick={handleLogout}>
        יציאה מהחשבון
      </Button>

      {deleteError && (
        <div className={styles.banner} role="alert">
          <p>{deleteError}</p>
        </div>
      )}

      {confirmingDelete ? (
        <div className={styles.deleteConfirm}>
          <p>מחיקת החשבון היא פעולה בלתי הפיכה. להמשיך?</p>
          <div className={styles.deleteConfirmActions}>
            <Button variant="destructive" loading={isDeleting} onClick={handleDeleteAccount}>
              כן, מחק את החשבון
            </Button>
            <Button variant="secondary" disabled={isDeleting} onClick={() => setConfirmingDelete(false)}>
              ביטול
            </Button>
          </div>
        </div>
      ) : (
        <Button variant="destructive" onClick={() => setConfirmingDelete(true)}>
          מחיקת חשבון
        </Button>
      )}
    </div>
  );
}
