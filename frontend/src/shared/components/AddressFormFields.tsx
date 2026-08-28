import { Input } from './Input';
import { AddressAutocompleteField } from './AddressAutocompleteField';
import type { AddressValue } from './addressTypes';
import { EMPTY_ADDRESS, withEditedAddressText, withSelectedPlace } from './addressTypes';
import type { AddressSuggestionProvider } from './googlePlaces';
import styles from './AddressFormFields.module.css';

export interface AddressFormFieldsProps {
  value: AddressValue;
  onChange: (value: AddressValue) => void;
  errors?: Partial<Record<keyof AddressValue, string>>;
  /** Injected in tests. Production uses the real Google provider. */
  provider?: AddressSuggestionProvider;
}

/**
 * Self-contained, reusable address field group. Used unmodified by customer registration, the
 * profile screen, the booking flow's "another address" step and the SOS entry screen.
 *
 * ## Two kinds of field, and the split is the whole design
 *
 * **Which building** — city, street, house number — is no longer typed. It comes from
 * `AddressAutocompleteField`, is filled from the selected place, and is shown back read-only as
 * confirmation. Making these inputs would defeat the point: a customer could select a real
 * address and then edit the house number to one that does not exist, and the payload would still
 * carry a valid-looking place id.
 *
 * **How to get in** — apartment, floor, entrance, access notes — stays free text, because no
 * geocoder resolves "דירה 4, קומה 2" and a professional standing at the door genuinely needs it.
 * Editing these deliberately does NOT invalidate the selection (see `withEditedAddressText`);
 * making somebody re-pick their street because they corrected a gate code would be absurd.
 */
export function AddressFormFields({ value, onChange, errors, provider }: AddressFormFieldsProps) {
  function handleField(field: keyof AddressValue, fieldValue: string) {
    onChange(withEditedAddressText(value, field, fieldValue));
  }

  return (
    <div className={styles.grid}>
      <div className={styles.fullRow}>
        <AddressAutocompleteField
          value={value}
          provider={provider}
          onSelect={(place) => onChange(withSelectedPlace(value, place))}
          // Clearing the resolution keeps the text the customer can see, so they are not left
          // staring at a form that silently emptied itself; only the invisible proof-of-selection
          // goes, which is what the error message then explains.
          onClear={() =>
            onChange({
              ...value,
              placeId: EMPTY_ADDRESS.placeId,
              formattedAddress: EMPTY_ADDRESS.formattedAddress,
              latitude: EMPTY_ADDRESS.latitude,
              longitude: EMPTY_ADDRESS.longitude,
            })
          }
          error={errors?.placeId ?? errors?.city ?? errors?.street}
        />
      </div>

      {/* Read-only confirmation of what the selection resolved to. Rendered only once there is
          something to confirm, so an empty form is not three empty disabled boxes. */}
      {value.placeId && (
        <>
          <Input label="עיר" value={value.city} readOnly disabled />
          <Input label="רחוב" value={value.street} readOnly disabled />
        </>
      )}

      {/* House number stays editable even after a selection: Google does not return a street
          number for every place (a numberless street, a named building), and the backend still
          requires one. An edit here re-opens the selection, exactly as the street would. */}
      {value.placeId && (
        <Input
          label="מספר בית"
          value={value.houseNumber}
          onChange={(e) => handleField('houseNumber', e.target.value)}
          error={errors?.houseNumber}
          required
        />
      )}

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
