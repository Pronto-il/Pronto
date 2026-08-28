import { describe, expect, it } from 'vitest';
import {
  ADDRESS_NOT_SELECTED_MESSAGE,
  EMPTY_ADDRESS,
  isAddressResolved,
  toAddressValue,
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
