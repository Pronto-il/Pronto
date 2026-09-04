import { useEffect, useRef, useState } from 'react';
import { Input } from './Input';
import { PlaceSuggestionField } from './PlaceSuggestionField';
import type { AddressValue } from './addressTypes';
import {
  sanitizeApartment,
  sanitizeEntrance,
  sanitizeFloor,
  sanitizeHouseNumber,
  isValidHouseNumber,
  withEditedAddressText,
  withSelectedPlace,
} from './addressTypes';
import { ADDRESS_MAX_LENGTHS } from '../api/fieldLimits';
import { googlePlacesProvider } from './googlePlaces';
import type { AddressSuggestion, AddressSuggestionProvider } from './googlePlaces';
import styles from './AddressFormFields.module.css';

export interface AddressFormFieldsProps {
  value: AddressValue;
  onChange: (value: AddressValue) => void;
  errors?: Partial<Record<keyof AddressValue, string>>;
  /** Injected in tests. Production uses the real Google provider. */
  provider?: AddressSuggestionProvider;
}

const CONFIRMING_MESSAGE = 'מאמתים את הכתובת מול Google…';
export const ADDRESS_UNVERIFIABLE_MESSAGE =
  'לא מצאנו את הכתובת הזו. יש לבדוק את הרחוב ואת מספר הבית ולנסות שוב.';
export const ADDRESS_PROVIDER_ERROR_MESSAGE =
  'לא הצלחנו לאמת את הכתובת כרגע. יש לנסות שוב בעוד רגע.';

/** How long after the last keystroke in the house-number field the complete address is sent for
 *  confirmation. Longer than the autocomplete debounce because this fires a details lookup, and
 *  a customer typing "1", "12", "125" should pay for one confirmation, not three. */
const CONFIRM_DEBOUNCE_MS = 450;

/**
 * Self-contained, reusable address field group. Used unmodified by the profile screen, the
 * booking flow's address step and the SOS entry screen.
 *
 * ## Three fields, in order, because that is what "a validated address" needs
 *
 * **City** — chosen from Google's locality suggestions. Not typed.
 *
 * **Street** — chosen from Google's street suggestions *within that city*, which is why it is
 * disabled until a city exists. The provider filters the list to results that name the selected
 * city, so a street can never be paired with a town it does not belong to.
 *
 * **House number** — typed, digits only, filtered at the keystroke (`sanitizeHouseNumber`) and
 * re-checked on submit. It is the one part of an address that cannot come from a list: a street's
 * suggestions have one entry per street, not per building.
 *
 * Once all three are present, the **complete** address is sent back to Google for confirmation
 * (`resolveFullAddress`), and only a confirmed result carries a place id and coordinates. That
 * last step is what the previous single-box design could not do: it resolved whatever the customer
 * picked — routinely a street with no number — and then trusted a house number appended to it
 * afterwards. An address that Google cannot resolve to a building now leaves the form unresolved,
 * and `validateAddress` refuses to submit it.
 *
 * Editing any of the three re-opens the question and clears the resolution
 * (`withEditedAddressText`), so the confirmed coordinates can never belong to a different address
 * than the text on screen.
 *
 * **How to get in** — apartment, floor, entrance, access notes — stays free text, because no
 * geocoder resolves "דירה 4, קומה 2" and a professional standing at the door genuinely needs it.
 * Editing these deliberately does NOT invalidate the confirmation.
 */
export function AddressFormFields({ value, onChange, errors, provider }: AddressFormFieldsProps) {
  const places = provider ?? googlePlacesProvider;

  const [isConfirming, setIsConfirming] = useState(false);
  /** Set when Google was asked about the complete address and could not confirm it, or could not
   *  be reached. Cleared by any edit, because the answer belonged to the previous text. */
  const [confirmError, setConfirmError] = useState<string | null>(null);

  const sessionTokenRef = useRef<unknown>(null);
  /** Latest state, read inside the debounced confirmation so it cannot act on a stale closure. */
  const valueRef = useRef(value);
  valueRef.current = value;
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  async function sessionToken(): Promise<unknown> {
    if (sessionTokenRef.current === null) {
      try {
        sessionTokenRef.current = await places.newSessionToken();
      } catch {
        // Non-fatal: a tokenless search still works, it is only billed less favourably.
        sessionTokenRef.current = undefined;
      }
    }
    return sessionTokenRef.current;
  }

  const isResolved = value.placeId !== null;
  const hasAllParts =
    value.city.trim() !== '' && value.street.trim() !== '' && isValidHouseNumber(value.houseNumber);

  /**
   * Confirms the complete address as soon as all three parts exist and none of them is already
   * confirmed. Runs from an effect rather than a button so the customer is never left looking at
   * a filled-in form with a Continue button that silently refuses — by the time they reach it,
   * the address is either confirmed or explained.
   */
  useEffect(() => {
    if (!hasAllParts || isResolved) {
      return;
    }
    let cancelled = false;
    setConfirmError(null);
    const timer = setTimeout(async () => {
      const parts = {
        city: valueRef.current.city,
        street: valueRef.current.street,
        houseNumber: valueRef.current.houseNumber,
      };
      setIsConfirming(true);
      try {
        const place = await places.resolveFullAddress(parts, await sessionToken());
        if (cancelled) {
          return;
        }
        if (!place) {
          setConfirmError(ADDRESS_UNVERIFIABLE_MESSAGE);
          return;
        }
        // Re-read rather than closing over `value`: the customer may have typed on while the
        // lookup was in flight, and applying a confirmation to text it did not describe is the
        // exact defect this whole module exists to prevent.
        const current = valueRef.current;
        if (
          current.city !== parts.city ||
          current.street !== parts.street ||
          current.houseNumber !== parts.houseNumber
        ) {
          return;
        }
        onChangeRef.current(withSelectedPlace(current, place));
      } catch {
        if (!cancelled) {
          setConfirmError(ADDRESS_PROVIDER_ERROR_MESSAGE);
        }
      } finally {
        if (!cancelled) {
          setIsConfirming(false);
        }
      }
    }, CONFIRM_DEBOUNCE_MS);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
    // `places` is stable in production (module singleton) and per-test in tests; `hasAllParts`
    // and `isResolved` are the derived facts that actually decide whether to ask.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasAllParts, isResolved, value.city, value.street, value.houseNumber]);

  function handleAccessField(field: keyof AddressValue, fieldValue: string) {
    onChange(withEditedAddressText(value, field, fieldValue));
  }

  /**
   * Apartment, floor and entrance are filtered at the keystroke exactly as the house number is —
   * a disallowed character never lands in the field rather than landing and then being complained
   * about. `addressNotes` is deliberately not in here: it is the free-text escape hatch these
   * three fields' rules assume exists.
   */
  function handleSanitizedAccessField(
    field: 'apartment' | 'floor' | 'entrance',
    raw: string,
    sanitize: (input: string) => string,
  ) {
    handleAccessField(field, sanitize(raw));
  }

  function handleCitySelect(suggestion: AddressSuggestion) {
    setConfirmError(null);
    // The city's own name, not the full prediction line ("תל אביב-יפו, ישראל"): this value is
    // stored, sent to the backend and read by a professional.
    const city = cityNameFrom(suggestion.description);
    // Choosing a different city invalidates the street too — a street belongs to a city, so
    // keeping the previous one would produce exactly the mismatched pair this design prevents.
    const base = city === value.city ? value : { ...value, street: '' };
    onChange(withEditedAddressText(base, 'city', city));
  }

  function handleStreetSelect(suggestion: AddressSuggestion) {
    setConfirmError(null);
    onChange(withEditedAddressText(value, 'street', streetNameFrom(suggestion.description)));
  }

  function handleHouseNumberChange(raw: string) {
    setConfirmError(null);
    onChange(withEditedAddressText(value, 'houseNumber', sanitizeHouseNumber(raw)));
  }

  const unavailable = !places.isConfigured();

  return (
    <div className={styles.grid}>
      <div className={styles.fullRow}>
        <PlaceSuggestionField
          label="עיר"
          value={value.city}
          isSelected={value.city.trim() !== ''}
          fetchSuggestions={async (query) => places.fetchCitySuggestions(query, await sessionToken())}
          onSelect={handleCitySelect}
          onClear={() => onChange(withEditedAddressText(value, 'city', ''))}
          error={errors?.city}
          maxLength={ADDRESS_MAX_LENGTHS.city}
          hint={unavailable ? 'חיפוש הכתובות אינו זמין כרגע.' : 'יש לבחור עיר מתוך הרשימה'}
        />
      </div>

      <div className={styles.fullRow}>
        <PlaceSuggestionField
          label="רחוב"
          value={value.street}
          isSelected={value.street.trim() !== ''}
          disabled={value.city.trim() === ''}
          fetchSuggestions={async (query) =>
            places.fetchStreetSuggestions(query, valueRef.current.city, await sessionToken())
          }
          onSelect={handleStreetSelect}
          onClear={() => onChange(withEditedAddressText(value, 'street', ''))}
          error={errors?.street}
          maxLength={ADDRESS_MAX_LENGTHS.street}
          hint={
            value.city.trim() === ''
              ? 'יש לבחור עיר תחילה'
              : `יש לבחור רחוב מתוך הרשימה ב${value.city}`
          }
        />
      </div>

      <Input
        label="מספר בית"
        value={value.houseNumber}
        onChange={(e) => handleHouseNumberChange(e.target.value)}
        disabled={value.street.trim() === ''}
        error={errors?.houseNumber}
        // Numeric keypad on mobile; `type="text"` + `inputMode` rather than `type="number"`,
        // which brings spinners, accepts `e`/`+`/`-` and reports an empty string for anything it
        // considers invalid — none of which is wanted for a house number.
        inputMode="numeric"
        pattern="[0-9]*"
        autoComplete="off"
        maxLength={ADDRESS_MAX_LENGTHS.houseNumber}
        hint="ספרות בלבד"
        required
      />

      <div className={styles.fullRow}>
        {isConfirming && <p className={styles.status}>{CONFIRMING_MESSAGE}</p>}
        {!isConfirming && confirmError && (
          <p className={styles.error} role="alert">
            {confirmError}
          </p>
        )}
        {!isConfirming && !confirmError && isResolved && (
          <p className={styles.confirmed} data-testid="address-confirmed">
            <span aria-hidden="true">✓</span>{' '}
            {value.formattedAddress || `${value.street} ${value.houseNumber}, ${value.city}`}
          </p>
        )}
      </div>

      <Input
        label="דירה"
        value={value.apartment}
        onChange={(e) => handleSanitizedAccessField('apartment', e.target.value, sanitizeApartment)}
        error={errors?.apartment}
        maxLength={ADDRESS_MAX_LENGTHS.apartment}
        // `type="text"` + `inputMode`, never `type="number"` — same reasoning as the house number
        // above: spinners, an accepted `e`/`+`/`-`, and an empty string reported for anything the
        // browser dislikes are all wrong for a field whose value we sanitize ourselves.
        inputMode="numeric"
        pattern="[0-9]*"
        autoComplete="off"
        hint="לא חובה, ספרות בלבד"
      />
      <Input
        label="קומה"
        value={value.floor}
        onChange={(e) => handleSanitizedAccessField('floor', e.target.value, sanitizeFloor)}
        error={errors?.floor}
        maxLength={ADDRESS_MAX_LENGTHS.floor}
        inputMode="numeric"
        pattern="[0-9]*"
        autoComplete="off"
        hint="לא חובה, ספרות בלבד"
      />
      <Input
        label="כניסה"
        value={value.entrance}
        onChange={(e) => handleSanitizedAccessField('entrance', e.target.value, sanitizeEntrance)}
        error={errors?.entrance}
        // `maxLength` is a convenience for the caret, not the rule: `sanitizeEntrance` truncates
        // and `validateAddressTextOnly` re-checks, because an autofill or a paste can set a value
        // without the browser ever enforcing this attribute.
        maxLength={ADDRESS_MAX_LENGTHS.entrance}
        autoComplete="off"
        hint="לא חובה, עד 2 אותיות או ספרות"
      />
      <div className={styles.fullRow}>
        <Input
          label="הערות לגישה לבית"
          value={value.addressNotes}
          onChange={(e) => handleAccessField('addressNotes', e.target.value)}
          error={errors?.addressNotes}
          maxLength={ADDRESS_MAX_LENGTHS.addressNotes}
          hint="לא חובה, למשל: קוד לשער"
        />
      </div>
    </div>
  );
}

/**
 * A city prediction reads "תל אביב-יפו, ישראל"; the stored city is the first segment.
 *
 * Taken from the description rather than from a details lookup on purpose: a `(cities)`
 * prediction's leading segment *is* the locality name, and resolving the place to read its
 * `locality` component back would spend a billable details request to learn what is already in
 * hand. The final full-address confirmation resolves the real components anyway, and
 * `withSelectedPlace` overwrites the city with Google's canonical spelling at that point.
 */
function cityNameFrom(description: string): string {
  return description.split(',')[0]?.trim() ?? description.trim();
}

/** Same reasoning for "הרצל, תל אביב-יפו, ישראל". */
function streetNameFrom(description: string): string {
  return description.split(',')[0]?.trim() ?? description.trim();
}
