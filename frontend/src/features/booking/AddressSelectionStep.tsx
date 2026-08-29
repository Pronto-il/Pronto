import { useState } from 'react';
import {
  AddressFormFields,
  Button,
  Card,
  Checkbox,
  FilterChipGroup,
  isAddressResolved,
  toAddressValue,
} from '../../shared/components';
import type { AddressValue, FilterChipOption } from '../../shared/components';
import { saveDefaultAddress, toCustomerAddressPayload } from '../../shared/api';
import { useAuth } from '../../shared/hooks';
import styles from './AddressSelectionStep.module.css';

export type AddressMode = 'DEFAULT' | 'CUSTOM';

export interface AddressSelectionStepProps {
  value: AddressValue;
  onChange: (value: AddressValue) => void;
  /** Selected address source, lifted to the parent flow page so it can be written through to
   *  the booking draft (`BookingDraft.addressMode`). */
  mode: AddressMode;
  onModeChange: (mode: AddressMode) => void;
  errors?: Partial<Record<keyof AddressValue, string>>;
  onContinue: () => void;
  /**
   * Whether to offer "הפוך את זה לכתובת הבית" for a signed-in customer entering a new address.
   *
   * Opt-in rather than always-on because the SOS entry screen also renders this step, and there
   * `onContinue` dispatches an emergency: adding a profile write in front of that would delay a
   * customer with a burst pipe to save a preference they can set at any calmer moment.
   */
  offerSaveAsHome?: boolean;
}

const ADDRESS_MODE_OPTIONS: FilterChipOption<AddressMode>[] = [
  { value: 'DEFAULT', label: 'כתובת ברירת המחדל שלי' },
  { value: 'CUSTOM', label: 'כתובת אחרת לפעם הזו' },
];

export const SAVE_AS_HOME_LABEL = 'הפוך את זה לכתובת הבית';
export const SAVE_AS_HOME_FAILED_MESSAGE =
  'לא הצלחנו לשמור את הכתובת בפרופיל. אפשר לנסות שוב, או להסיר את הסימון ולהמשיך.';

/**
 * Booking-flow address step (design doc §2.7): a two-option chooser between the customer's
 * saved home address (read-only, for confirmation) and a one-off address for this job (the
 * shared `AddressFormFields` — city, then street, then house number, each validated against
 * Google). The saved option is only offered when `useAuth().user?.defaultAddress` is non-null —
 * otherwise this renders exactly like the plain `AddressFormFields` step it replaces, no dead
 * chip.
 *
 * ## "הפוך את זה לכתובת הבית"
 *
 * Offered only to a signed-in customer (a guest has no profile to write to), only for a new
 * address (`CUSTOM`), and only once that address has actually been confirmed by Google — an
 * unresolved address is not something to persist. It is **opt-in and unticked**: the entire
 * reason this screen exists is that the address for one job is frequently not the customer's
 * home, so saving by default would silently overwrite a home address every time somebody books
 * for their parents.
 *
 * The write happens on Continue rather than on tick, so a customer who ticks and then changes
 * their mind — or edits the address afterwards — saves the address they actually proceeded with,
 * once. A failed save stops the step with an explanation rather than continuing silently: the
 * customer asked for something and it did not happen, and unticking the box is a one-tap way past
 * it.
 *
 * Never writes to `users.default_*` in any other circumstance — picking a one-off address for a
 * booking still leaves the profile untouched, which is the guarantee this component has always
 * made.
 */
export function AddressSelectionStep({
  value,
  onChange,
  mode,
  onModeChange,
  errors,
  onContinue,
  offerSaveAsHome = false,
}: AddressSelectionStepProps) {
  const { user, refreshUser } = useAuth();
  const defaultAddress = user?.defaultAddress ?? null;
  const effectiveMode: AddressMode = defaultAddress ? mode : 'CUSTOM';

  const [saveAsHome, setSaveAsHome] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const canOfferSaveAsHome =
    offerSaveAsHome && Boolean(user) && effectiveMode === 'CUSTOM' && isAddressResolved(value);

  function handleModeChange(nextMode: AddressMode) {
    onModeChange(nextMode);
    setSaveError(null);
    if (nextMode === 'DEFAULT' && defaultAddress) {
      onChange(toAddressValue(defaultAddress));
    }
  }

  async function handleContinue() {
    if (!canOfferSaveAsHome || !saveAsHome) {
      onContinue();
      return;
    }
    setIsSaving(true);
    setSaveError(null);
    try {
      await saveDefaultAddress(toCustomerAddressPayload(value));
      // Keeps `useAuth().user.defaultAddress` in step, so the "my saved address" chip appears on
      // the very next screen that renders this step rather than after the next full page load.
      await refreshUser();
      onContinue();
    } catch {
      setSaveError(SAVE_AS_HOME_FAILED_MESSAGE);
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <div className={styles.wrapper}>
      {defaultAddress && (
        <FilterChipGroup
          options={ADDRESS_MODE_OPTIONS}
          value={effectiveMode}
          onChange={handleModeChange}
          aria-label="מקור הכתובת"
        />
      )}

      {effectiveMode === 'DEFAULT' && defaultAddress ? (
        <Card className={styles.readonlyCard}>
          <div className={styles.row}>
            <span className={styles.rowLabel}>עיר</span>
            <span className={styles.rowValue}>{defaultAddress.city}</span>
          </div>
          <div className={styles.row}>
            <span className={styles.rowLabel}>רחוב</span>
            <span className={styles.rowValue}>{defaultAddress.street}</span>
          </div>
          <div className={styles.row}>
            <span className={styles.rowLabel}>מספר בית</span>
            <span className={styles.rowValue}>{defaultAddress.houseNumber}</span>
          </div>
          {defaultAddress.apartment && (
            <div className={styles.row}>
              <span className={styles.rowLabel}>דירה</span>
              <span className={styles.rowValue}>{defaultAddress.apartment}</span>
            </div>
          )}
          {defaultAddress.floor && (
            <div className={styles.row}>
              <span className={styles.rowLabel}>קומה</span>
              <span className={styles.rowValue}>{defaultAddress.floor}</span>
            </div>
          )}
          {defaultAddress.entrance && (
            <div className={styles.row}>
              <span className={styles.rowLabel}>כניסה</span>
              <span className={styles.rowValue}>{defaultAddress.entrance}</span>
            </div>
          )}
          {defaultAddress.addressNotes && (
            <div className={styles.row}>
              <span className={styles.rowLabel}>הערות לגישה לבית</span>
              <span className={styles.rowValue}>{defaultAddress.addressNotes}</span>
            </div>
          )}
        </Card>
      ) : (
        <AddressFormFields value={value} onChange={onChange} errors={errors} />
      )}

      {canOfferSaveAsHome && (
        <Checkbox
          label={SAVE_AS_HOME_LABEL}
          checked={saveAsHome}
          onChange={(event) => {
            setSaveAsHome(event.target.checked);
            setSaveError(null);
          }}
        />
      )}

      {saveError && (
        <p className={styles.saveError} role="alert">
          {saveError}
        </p>
      )}

      <Button onClick={handleContinue} loading={isSaving} fullWidth>
        המשך
      </Button>
    </div>
  );
}
