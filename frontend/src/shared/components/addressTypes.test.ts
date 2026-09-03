import { describe, expect, it } from 'vitest';
import {
  ADDRESS_NOT_SELECTED_MESSAGE,
  APARTMENT_INVALID_MESSAGE,
  EMPTY_ADDRESS,
  ENTRANCE_INVALID_MESSAGE,
  FLOOR_INVALID_MESSAGE,
  HOUSE_NUMBER_INVALID_MESSAGE,
  isValidApartment,
  isValidEntrance,
  isValidFloor,
  sanitizeApartment,
  sanitizeEntrance,
  sanitizeFloor,
  isAddressComplete,
  isAddressResolved,
  isValidHouseNumber,
  sanitizeHouseNumber,
  toAddressValue,
  toServicePlaceFields,
  validateAddress,
  validateAddressTextOnly,
  withEditedAddressText,
  withSelectedPlace,
} from './addressTypes';
import type { AddressValue, ResolvedPlace } from './addressTypes';

const PLACE: ResolvedPlace = {
  placeId: 'ChIJdizengoff',
  formattedAddress: 'דיזנגוף 100, תל אביב-יפו',
  city: 'תל אביב-יפו',
  street: 'דיזנגוף',
  houseNumber: '100',
  latitude: 32.0811,
  longitude: 34.7739,
};

const selected: AddressValue = withSelectedPlace(EMPTY_ADDRESS, PLACE);

describe('selecting a place', () => {
  it('fills the locating fields and records the identity', () => {
    expect(selected.city).toBe('תל אביב-יפו');
    expect(selected.street).toBe('דיזנגוף');
    expect(selected.houseNumber).toBe('100');
    expect(selected.placeId).toBe('ChIJdizengoff');
    expect(selected.latitude).toBe(32.0811);
    expect(selected.longitude).toBe(34.7739);
    expect(isAddressResolved(selected)).toBe(true);
  });

  it('keeps a typed house number when the place has none', () => {
    // Google returns no street_number for a numberless street or a named building. Blanking a
    // number the customer already typed would be worse than keeping it, and the backend still
    // requires one.
    const typed = { ...EMPTY_ADDRESS, houseNumber: '7' };
    const numberless = withSelectedPlace(typed, { ...PLACE, houseNumber: '' });

    expect(numberless.houseNumber).toBe('7');
    expect(isAddressResolved(numberless)).toBe(true);
  });

  it('leaves the access fields alone', () => {
    const withAccess = { ...EMPTY_ADDRESS, apartment: '4', floor: '2', addressNotes: 'קוד 1234' };

    const result = withSelectedPlace(withAccess, PLACE);

    expect(result.apartment).toBe('4');
    expect(result.floor).toBe('2');
    expect(result.addressNotes).toBe('קוד 1234');
  });
});

describe('editing after selecting', () => {
  it.each(['city', 'street', 'houseNumber'] as const)(
    'editing %s invalidates the previous selection',
    (field) => {
      // The requirement, directly. Without this, a customer selects a real address, changes the
      // house number to one that does not exist, and submits last address's coordinates under
      // this address's text — which looks entirely fine on screen.
      const edited = withEditedAddressText(selected, field, '999');

      expect(edited.placeId).toBeNull();
      expect(edited.latitude).toBeNull();
      expect(edited.longitude).toBeNull();
      expect(edited.formattedAddress).toBeNull();
      expect(isAddressResolved(edited)).toBe(false);
    },
  );

  it.each(['apartment', 'floor', 'entrance', 'addressNotes'] as const)(
    'editing %s keeps the selection',
    (field) => {
      // These describe how to get into the building, not which building. Making somebody re-pick
      // their street because they corrected a gate code would be absurd.
      const edited = withEditedAddressText(selected, field, '5');

      expect(edited.placeId).toBe('ChIJdizengoff');
      expect(isAddressResolved(edited)).toBe(true);
    },
  );

  it('keeps the visible text when the selection is invalidated', () => {
    // Only the invisible proof-of-selection goes. A form that silently emptied itself would be
    // far more confusing than one that says "pick from the list".
    const edited = withEditedAddressText(selected, 'street', 'אלנבי');

    expect(edited.street).toBe('אלנבי');
    expect(edited.city).toBe('תל אביב-יפו');
  });
});

describe('validateAddress', () => {
  it('accepts a selected address', () => {
    expect(validateAddress(selected)).toEqual({});
  });

  it('refuses free text with no selection, naming the reason', () => {
    const typedOnly: AddressValue = {
      ...EMPTY_ADDRESS,
      city: 'תל אביב',
      street: 'רחוב שלא קיים',
      houseNumber: '9999',
    };

    expect(validateAddress(typedOnly)).toEqual({ placeId: ADDRESS_NOT_SELECTED_MESSAGE });
  });

  it('refuses an address whose selection was invalidated by an edit', () => {
    const edited = withEditedAddressText(selected, 'houseNumber', '101');

    expect(validateAddress(edited).placeId).toBe(ADDRESS_NOT_SELECTED_MESSAGE);
  });

  it('reports missing required fields before the selection rule', () => {
    // Telling somebody with an empty form to "choose from the list" would be unhelpful; the
    // missing-field messages are the actionable ones first.
    const errors = validateAddress(EMPTY_ADDRESS);

    expect(errors.city).toBe('יש להזין עיר.');
    expect(errors.street).toBe('יש להזין רחוב.');
    expect(errors.houseNumber).toBe('יש להזין מספר בית.');
    expect(errors.placeId).toBeUndefined();
  });
});

describe('validateAddressTextOnly — the grandfathering path', () => {
  it('accepts a legacy saved address that was never selected', () => {
    // A customer whose address predates autocomplete books exactly as before. The backend
    // grandfathers it by recognising their own stored default; requiring a re-selection in the
    // UI would block a booking the server would have accepted.
    const legacy = toAddressValue({ city: 'חיפה', street: 'הרצל', houseNumber: '5' });

    expect(isAddressResolved(legacy)).toBe(false);
    expect(validateAddressTextOnly(legacy)).toEqual({});
    // ...and the strict rule, which every other surface uses, still refuses it.
    expect(validateAddress(legacy).placeId).toBe(ADDRESS_NOT_SELECTED_MESSAGE);
  });

  it('still requires the text fields', () => {
    expect(validateAddressTextOnly(EMPTY_ADDRESS).city).toBe('יש להזין עיר.');
  });
});

describe('toAddressValue', () => {
  it('carries a saved place id through, so a validated address stays validated', () => {
    const saved = toAddressValue({
      city: 'תל אביב-יפו',
      street: 'דיזנגוף',
      houseNumber: '100',
      placeId: 'ChIJdizengoff',
      formattedAddress: 'דיזנגוף 100, תל אביב-יפו',
    });

    expect(saved.placeId).toBe('ChIJdizengoff');
    // Not resolved for form purposes, because `/me` deliberately returns no coordinates — the
    // backend resolves the saved address server-side and nothing here needs them.
    expect(saved.latitude).toBeNull();
  });

  it('returns a legacy address unresolved', () => {
    const legacy = toAddressValue({ city: 'חיפה', street: 'הרצל', houseNumber: '5' });

    expect(legacy.placeId).toBeNull();
    expect(legacy.formattedAddress).toBeNull();
  });
});

describe('the house number: digits only', () => {
  it.each(['1', '12', '100', '9999'])('accepts %s', (value) => {
    expect(isValidHouseNumber(value)).toBe(true);
  });

  it.each(['12א', '12/3', '12-3', '12 3', 'א', '', '  '])('refuses %s', (value) => {
    expect(isValidHouseNumber(value)).toBe(false);
  });

  it('strips everything that is not a digit, for use as a keystroke filter', () => {
    // The field never contains an invalid character in the first place — which is a better
    // experience than accepting one and complaining about it afterwards.
    expect(sanitizeHouseNumber('12א')).toBe('12');
    expect(sanitizeHouseNumber('12/3')).toBe('123');
    expect(sanitizeHouseNumber('בית 7')).toBe('7');
  });

  it('names the reason when an invalid number arrives from somewhere the filter does not run', () => {
    // A restored draft, a saved address or a hand-edited localStorage entry can all carry text
    // this form never produced, so the rule is enforced again on submit.
    const legacy: AddressValue = {
      ...EMPTY_ADDRESS,
      city: 'חיפה',
      street: 'הרצל',
      houseNumber: '12א',
    };

    expect(validateAddressTextOnly(legacy).houseNumber).toBe(HOUSE_NUMBER_INVALID_MESSAGE);
    expect(validateAddress(legacy).houseNumber).toBe(HOUSE_NUMBER_INVALID_MESSAGE);
  });
});

describe('isAddressComplete — the gate on asking for professionals', () => {
  it('refuses an empty address object', () => {
    // THE bug. `EMPTY_ADDRESS` is a perfectly non-null object, and screens that asked
    // "is there an address?" rather than "is there an address in it?" fired
    // `GET /api/bookings/professionals?...&city=&street=&houseNumber=` and got a 400 back.
    expect(EMPTY_ADDRESS).not.toBeNull();
    expect(isAddressComplete(EMPTY_ADDRESS)).toBe(false);
  });

  it('refuses null and undefined', () => {
    expect(isAddressComplete(null)).toBe(false);
    expect(isAddressComplete(undefined)).toBe(false);
  });

  it.each([
    ['no city', { city: '', street: 'הרצל', houseNumber: '5' }],
    ['no street', { city: 'חיפה', street: '', houseNumber: '5' }],
    ['no house number', { city: 'חיפה', street: 'הרצל', houseNumber: '' }],
    ['whitespace city', { city: '   ', street: 'הרצל', houseNumber: '5' }],
    ['non-numeric house number', { city: 'חיפה', street: 'הרצל', houseNumber: '12א' }],
  ])('refuses an address with %s', (_label, parts) => {
    expect(isAddressComplete({ ...EMPTY_ADDRESS, ...parts })).toBe(false);
  });

  it('accepts a complete address', () => {
    expect(isAddressComplete({ ...EMPTY_ADDRESS, city: 'חיפה', street: 'הרצל', houseNumber: '5' })).toBe(
      true,
    );
  });

  it('accepts a legacy saved address that was never selected', () => {
    // Deliberately NOT the same question as `isAddressResolved`: the backend grandfathers the
    // caller's own saved default address, so requiring a place id here would block a listing the
    // server would have served.
    const legacy = toAddressValue({ city: 'חיפה', street: 'הרצל', houseNumber: '5' });

    expect(isAddressResolved(legacy)).toBe(false);
    expect(isAddressComplete(legacy)).toBe(true);
  });
});

/**
 * The three optional "how do I get in" fields. Optional and unconstrained are different things:
 * left as free text they become a second address line, which is where a house number refused as
 * `12א` reappears and where a whole sentence lands in a field rendered as a two-character chip.
 *
 * Mirrors `backend/.../maps/AddressAccessFields.java`, which enforces the same three patterns on
 * every write path. These tests are the browser half; the Java DTO tests are the half that holds
 * when somebody uses `curl`.
 */
describe('apartment, floor and entrance', () => {
  function withAccess(parts: Partial<AddressValue>): AddressValue {
    return {
      ...EMPTY_ADDRESS,
      city: 'חיפה',
      street: 'הרצל',
      houseNumber: '5',
      ...parts,
    };
  }

  describe('apartment — digits only, optional', () => {
    it.each(['', '4', '12', '1234'])('accepts %j', (apartment) => {
      expect(isValidApartment(apartment)).toBe(true);
      expect(validateAddressTextOnly(withAccess({ apartment })).apartment).toBeUndefined();
    });

    it.each(['4א', 'A', '4/2', '4 2', '-1', '@'])('rejects %j', (apartment) => {
      expect(isValidApartment(apartment)).toBe(false);
      expect(validateAddressTextOnly(withAccess({ apartment })).apartment).toBe(
        APARTMENT_INVALID_MESSAGE,
      );
    });

    it('filters non-digits at the keystroke', () => {
      expect(sanitizeApartment('4א')).toBe('4');
      expect(sanitizeApartment('4/2')).toBe('42');
      expect(sanitizeApartment('abc')).toBe('');
    });
  });

  describe('floor — digits only, optional', () => {
    it.each(['', '0', '2', '14'])('accepts %j', (floor) => {
      expect(isValidFloor(floor)).toBe(true);
      expect(validateAddressTextOnly(withAccess({ floor })).floor).toBeUndefined();
    });

    it.each(['2ב', 'קרקע', '2.5', '2 '])('rejects %j', (floor) => {
      expect(isValidFloor(floor)).toBe(false);
      expect(validateAddressTextOnly(withAccess({ floor })).floor).toBe(FLOOR_INVALID_MESSAGE);
    });

    it('rejects a negative floor, deliberately', () => {
      // Decided rather than overlooked. Nothing ever intentionally supported `-1` — the column
      // took it exactly as it took "ליד המעלית" — and digits-only is the rule. A basement is
      // described in the access-notes field, which exists for what a structured field cannot hold.
      expect(isValidFloor('-1')).toBe(false);
      expect(sanitizeFloor('-1')).toBe('1');
    });
  });

  describe('entrance — at most two letters or digits, optional', () => {
    it.each(['', 'A', 'ב', '1', '12', 'A1', 'ב2'])('accepts %j', (entrance) => {
      expect(isValidEntrance(entrance)).toBe(true);
      expect(validateAddressTextOnly(withAccess({ entrance })).entrance).toBeUndefined();
    });

    it.each(['ABC', '123', 'אבג'])('rejects %j — longer than two characters', (entrance) => {
      expect(isValidEntrance(entrance)).toBe(false);
      expect(validateAddressTextOnly(withAccess({ entrance })).entrance).toBe(
        ENTRANCE_INVALID_MESSAGE,
      );
    });

    it.each(['A-1', 'א ב', '@1', 'A.', ' A'])('rejects %j — symbol or space', (entrance) => {
      expect(isValidEntrance(entrance)).toBe(false);
    });

    it('filters and truncates at the keystroke', () => {
      expect(sanitizeEntrance('A-1')).toBe('A1');
      expect(sanitizeEntrance('א ב')).toBe('אב');
      expect(sanitizeEntrance('ABC')).toBe('AB');
      expect(sanitizeEntrance('@1')).toBe('1');
    });
  });

  it('a legacy value the form never produced is caught on submit, not silently sent', () => {
    // The reason these live in `validateAddressTextOnly` and not only in the input filters: a
    // saved address or a restored draft can carry text no keystroke of this form ever passed.
    // `CreateOrderRequest` now applies the same patterns, so an unvalidated legacy value would be
    // a 400 in the middle of a booking rather than a field error the customer can fix.
    const legacy = withAccess({ apartment: '4א', floor: '-1', entrance: 'כניסה א' });

    expect(validateAddressTextOnly(legacy)).toMatchObject({
      apartment: APARTMENT_INVALID_MESSAGE,
      floor: FLOOR_INVALID_MESSAGE,
      entrance: ENTRANCE_INVALID_MESSAGE,
    });
  });
});

describe('isAddressComplete tracks what the listing endpoint actually rejects', () => {
  const base = { ...EMPTY_ADDRESS, city: 'חיפה', street: 'הרצל', houseNumber: '5' };

  it('accepts an address with no apartment, and one with a numeric apartment', () => {
    expect(isAddressComplete(base)).toBe(true);
    expect(isAddressComplete({ ...base, apartment: '12' })).toBe(true);
  });

  it('refuses a malformed apartment, because the endpoint does', () => {
    // `apartment` is an optional query param on `GET /api/bookings/professionals`, and
    // `BookingsController` refuses a non-numeric one alongside the house number. Catching it here
    // turns a 400 mid-flow into a field error on a screen that can still fix it.
    expect(isAddressComplete({ ...base, apartment: '4א' })).toBe(false);
  });

  it('ignores floor and entrance, which this endpoint is never sent', () => {
    // They are checked where they are actually sent — `validateAddressTextOnly` and order
    // creation. Gating a listing on a field the listing never sees would be a rule with no
    // referent.
    expect(isAddressComplete({ ...base, floor: '-1', entrance: 'כניסה א' })).toBe(true);
  });
});

describe('toServicePlaceFields sends a whole place claim or none of one', () => {
  const picked: AddressValue = {
    ...EMPTY_ADDRESS,
    city: 'תל אביב-יפו',
    street: 'דיזנגוף',
    houseNumber: '100',
    placeId: 'ChIJdizengoff',
    formattedAddress: 'דיזנגוף 100, תל אביב-יפו',
    latitude: 32.0785,
    longitude: 34.7741,
  };

  it('sends the place id together with the coordinates for a picked address', () => {
    expect(toServicePlaceFields(picked)).toEqual({
      servicePlaceId: 'ChIJdizengoff',
      serviceFormattedAddress: 'דיזנגוף 100, תל אביב-יפו',
      serviceLatitude: 32.0785,
      serviceLongitude: 34.7741,
    });
  });

  it('sends nothing at all for a saved default address, which has a place id and no coordinates', () => {
    // The reported SOS regression, at its source. `GET /api/users/me` deliberately withholds the
    // saved address's coordinates, so `toAddressValue` yields exactly this shape -- and spreading
    // its `placeId` into a request body without them is what `SelectedPlaceValidator` rejects with
    // "latitude and longitude are both required when a placeId is supplied".
    const saved = toAddressValue({
      city: 'תל אביב-יפו',
      street: 'דיזנגוף',
      houseNumber: '100',
      placeId: 'ChIJdizengoff',
      formattedAddress: 'דיזנגוף 100, תל אביב-יפו',
    });

    expect(saved.placeId).toBe('ChIJdizengoff');
    expect(saved.latitude).toBeNull();
    expect(toServicePlaceFields(saved)).toEqual({});
  });

  it('never emits a key at all, so no `undefined` is serialised into the body', () => {
    // `JSON.stringify` drops `undefined` values, but an explicit `servicePlaceId: undefined` is
    // still a property the object carries -- and a caller building a payload by spread would see
    // it shadow a value set earlier. Returning {} keeps the omission total.
    const saved = { ...picked, latitude: null, longitude: null };
    expect(Object.keys(toServicePlaceFields(saved))).toEqual([]);
  });

  it('agrees with isAddressResolved, so the two can never drift apart', () => {
    const partials: AddressValue[] = [
      { ...picked, placeId: null },
      { ...picked, latitude: null },
      { ...picked, longitude: null },
    ];
    for (const partial of partials) {
      expect(isAddressResolved(partial)).toBe(false);
      expect(toServicePlaceFields(partial)).toEqual({});
    }
    expect(isAddressResolved(picked)).toBe(true);
    expect(toServicePlaceFields(picked)).not.toEqual({});
  });

  it('omits only the formatted address when the selection had none', () => {
    expect(toServicePlaceFields({ ...picked, formattedAddress: null })).toEqual({
      servicePlaceId: 'ChIJdizengoff',
      serviceFormattedAddress: undefined,
      serviceLatitude: 32.0785,
      serviceLongitude: 34.7741,
    });
  });
});
