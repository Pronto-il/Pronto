import { Input } from './Input';
import type { AddressValue } from './addressTypes';
import styles from './AddressFormFields.module.css';

export interface AddressFormFieldsProps {
  value: AddressValue;
  onChange: (value: AddressValue) => void;
  errors?: Partial<Record<keyof AddressValue, string>>;
}

/**
 * Self-contained, reusable address field group (city, street, house number, apartment,
 * floor, entrance, access notes — all beyond city/street/house-number are optional). Not
 * coupled to "registration" in naming or behavior — Milestone 3 reuses this unmodified for
 * a per-request service address.
 */
export function AddressFormFields({ value, onChange, errors }: AddressFormFieldsProps) {
  function handleField(field: keyof AddressValue, fieldValue: string) {
    onChange({ ...value, [field]: fieldValue });
  }

  return (
    <div className={styles.grid}>
      <Input
        label="עיר"
        value={value.city}
        onChange={(e) => handleField('city', e.target.value)}
        error={errors?.city}
        required
      />
      <Input
        label="רחוב"
        value={value.street}
        onChange={(e) => handleField('street', e.target.value)}
        error={errors?.street}
        required
      />
      <Input
        label="מספר בית"
        value={value.houseNumber}
        onChange={(e) => handleField('houseNumber', e.target.value)}
        error={errors?.houseNumber}
        required
      />
      <Input
        label="דירה"
        value={value.apartment}
        onChange={(e) => handleField('apartment', e.target.value)}
        error={errors?.apartment}
        hint="לא חובה"
      />
      <Input
        label="קומה"
        value={value.floor}
        onChange={(e) => handleField('floor', e.target.value)}
        error={errors?.floor}
        hint="לא חובה"
      />
      <Input
        label="כניסה"
        value={value.entrance}
        onChange={(e) => handleField('entrance', e.target.value)}
        error={errors?.entrance}
        hint="לא חובה"
      />
      <div className={styles.fullRow}>
        <Input
          label="הערות לגישה לבית"
          value={value.addressNotes}
          onChange={(e) => handleField('addressNotes', e.target.value)}
          error={errors?.addressNotes}
          hint="לא חובה, למשל: קוד לשער"
        />
      </div>
    </div>
  );
}
