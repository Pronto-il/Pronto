import { AddressFormFields, Button, Card, FilterChipGroup, toAddressValue } from '../../shared/components';
import type { AddressValue, FilterChipOption } from '../../shared/components';
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
}

const ADDRESS_MODE_OPTIONS: FilterChipOption<AddressMode>[] = [
  { value: 'DEFAULT', label: 'כתובת ברירת המחדל שלי' },
  { value: 'CUSTOM', label: 'כתובת אחרת לפעם הזו' },
];

/**
 * Booking-flow address step (design doc §2.7): a two-option chooser between the customer's
 * saved default address (read-only, for confirmation) and a one-off custom address (the
 * existing `AddressFormFields`, unmodified). The default option is only offered when
 * `useAuth().user?.defaultAddress` is non-null — otherwise this renders exactly like the
 * plain `AddressFormFields` step it replaces, no dead radio option.
 *
 * Never calls any endpoint that would mutate `users.default_*` — there isn't one in this
 * design (see `ms3-ms4-corrections-design.md` §1.5/§2.7), and this component doesn't add one.
 */
export function AddressSelectionStep({
  value,
  onChange,
  mode,
  onModeChange,
  errors,
  onContinue,
}: AddressSelectionStepProps) {
  const { user } = useAuth();
  const defaultAddress = user?.defaultAddress ?? null;
  const effectiveMode: AddressMode = defaultAddress ? mode : 'CUSTOM';

  function handleModeChange(nextMode: AddressMode) {
    onModeChange(nextMode);
    if (nextMode === 'DEFAULT' && defaultAddress) {
      onChange(toAddressValue(defaultAddress));
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

      <Button onClick={onContinue} fullWidth>
        המשך
      </Button>
    </div>
  );
}
