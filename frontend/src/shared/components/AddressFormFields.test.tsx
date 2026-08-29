import { useState } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AddressFormFields } from './AddressFormFields';
import { EMPTY_ADDRESS, isAddressResolved, validateAddress } from './addressTypes';
import type { AddressValue } from './addressTypes';
import type { AddressParts, AddressSuggestionProvider } from './googlePlaces';

/**
 * A stand-in for Google, so these tests exercise the real component with no network, no API key
 * and no `window.google`. This is what the provider seam in `googlePlaces.ts` is for: the
 * behaviour worth testing — city, then street in that city, then a house number, then a
 * confirmation of the whole thing — is entirely ours.
 *
 * The fake knows about exactly one real address, דיזנגוף 100 in תל אביב-יפו. Any other house
 * number on that street resolves to nothing, which is what "Google cannot find this address"
 * looks like from this component's side.
 */
function fakeProvider(overrides: Partial<AddressSuggestionProvider> = {}): AddressSuggestionProvider {
  return {
    isConfigured: () => true,
    newSessionToken: async () => ({ token: 1 }),
    fetchCitySuggestions: async (query: string) =>
      query.trim().length < 2
        ? []
        : [
            { placeId: 'city-tlv', description: 'תל אביב-יפו, ישראל' },
            { placeId: 'city-haifa', description: 'חיפה, ישראל' },
          ],
    fetchStreetSuggestions: async (query: string, city: string) =>
      query.trim().length < 2 || !city
        ? []
        : [
            { placeId: 'street-dizengoff', description: `דיזנגוף, ${city}, ישראל` },
            { placeId: 'street-allenby', description: `אלנבי, ${city}, ישראל` },
          ],
    resolve: async (placeId: string) => ({
      placeId,
      formattedAddress: 'דיזנגוף 100, תל אביב-יפו',
      city: 'תל אביב-יפו',
      street: 'דיזנגוף',
      houseNumber: '100',
      latitude: 32.0811,
      longitude: 34.7739,
    }),
    resolveFullAddress: async (parts: AddressParts) =>
      parts.city === 'תל אביב-יפו' && parts.street === 'דיזנגוף' && parts.houseNumber === '100'
        ? {
            placeId: 'ChIJdizengoff100',
            formattedAddress: 'דיזנגוף 100, תל אביב-יפו, ישראל',
            city: 'תל אביב-יפו',
            street: 'דיזנגוף',
            houseNumber: '100',
            latitude: 32.0811,
            longitude: 34.7739,
          }
        : null,
    ...overrides,
  };
}

/** The component is controlled, so the test needs the state the real screens hold. */
function Harness({
  provider,
  onValue,
  initial = EMPTY_ADDRESS,
}: {
  provider: AddressSuggestionProvider;
  onValue?: (v: AddressValue) => void;
  initial?: AddressValue;
}) {
  const [value, setValue] = useState<AddressValue>(initial);
  return (
    <>
      <AddressFormFields
        value={value}
        provider={provider}
        onChange={(next) => {
          setValue(next);
          onValue?.(next);
        }}
      />
      <output data-testid="resolved">{String(isAddressResolved(value))}</output>
      <output data-testid="submittable">
        {String(Object.keys(validateAddress(value)).length === 0)}
      </output>
      <output data-testid="house-number">{value.houseNumber}</output>
      <output data-testid="apartment">{value.apartment}</output>
      <output data-testid="floor">{value.floor}</output>
      <output data-testid="entrance">{value.entrance}</output>
    </>
  );
}

/** City → street → house number, the happy path, as its own helper because most tests need to
 *  get to the end of it before asserting anything. */
async function enterFullAddress(houseNumber = '100') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/עיר/), 'תל אב');
  await user.click(await screen.findByRole('option', { name: /תל אביב-יפו/ }));

  await user.type(screen.getByLabelText(/רחוב/), 'דיזנ');
  await user.click(await screen.findByRole('option', { name: /דיזנגוף/ }));

  await user.type(screen.getByLabelText(/מספר בית/), houseNumber);
  return user;
}

describe('AddressFormFields — city, street, house number', () => {
  it('asks for the city first and leaves street and house number disabled', () => {
    render(<Harness provider={fakeProvider()} />);

    expect(screen.getByLabelText(/רחוב/)).toBeDisabled();
    expect(screen.getByLabelText(/מספר בית/)).toBeDisabled();
  });

  it('offers only cities for the city field', async () => {
    const fetchCitySuggestions = vi.fn(async () => [
      { placeId: 'city-tlv', description: 'תל אביב-יפו, ישראל' },
    ]);
    const fetchStreetSuggestions = vi.fn(async () => []);
    render(<Harness provider={fakeProvider({ fetchCitySuggestions, fetchStreetSuggestions })} />);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/עיר/), 'תל אב');

    await waitFor(() => expect(fetchCitySuggestions).toHaveBeenCalled());
    expect(fetchStreetSuggestions).not.toHaveBeenCalled();
  });

  it('enables the street field once a city is chosen, and searches within that city', async () => {
    const fetchStreetSuggestions = vi.fn(async (_query: string, _city: string) => [
      { placeId: 'street-dizengoff', description: 'דיזנגוף, תל אביב-יפו, ישראל' },
    ]);
    render(<Harness provider={fakeProvider({ fetchStreetSuggestions })} />);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/עיר/), 'תל אב');
    await user.click(await screen.findByRole('option', { name: /תל אביב-יפו/ }));

    await waitFor(() => expect(screen.getByLabelText(/רחוב/)).toBeEnabled());
    await user.type(screen.getByLabelText(/רחוב/), 'דיזנ');

    await waitFor(() => expect(fetchStreetSuggestions).toHaveBeenCalled());
    // The city is passed to the provider — that is what makes "streets in this city" possible at
    // all, and what the real provider filters and biases on.
    expect(fetchStreetSuggestions.mock.calls[0][1]).toBe('תל אביב-יפו');
  });

  it('changing the city clears the street, so the two can never be a mismatched pair', async () => {
    render(<Harness provider={fakeProvider()} />);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/עיר/), 'תל אב');
    await user.click(await screen.findByRole('option', { name: /תל אביב-יפו/ }));
    await user.type(screen.getByLabelText(/רחוב/), 'דיזנ');
    await user.click(await screen.findByRole('option', { name: /דיזנגוף/ }));
    await waitFor(() => expect(screen.getByLabelText(/רחוב/)).toHaveValue('דיזנגוף'));

    await user.clear(screen.getByLabelText(/עיר/));
    await user.type(screen.getByLabelText(/עיר/), 'חיפ');
    await user.click(await screen.findByRole('option', { name: /חיפה/ }));

    await waitFor(() => expect(screen.getByLabelText(/רחוב/)).toHaveValue(''));
  });

  it('typing alone never produces a submittable address', async () => {
    // The headline requirement, unchanged by the redesign: a city and street that were typed but
    // never chosen carry no place id, and the whole address is never confirmed.
    render(<Harness provider={fakeProvider()} />);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/עיר/), 'עיר שלא קיימת');

    expect(screen.getByTestId('resolved')).toHaveTextContent('false');
    expect(screen.getByTestId('submittable')).toHaveTextContent('false');
  });
});

describe('AddressFormFields — the house number', () => {
  it('accepts digits', async () => {
    render(<Harness provider={fakeProvider()} />);
    await enterFullAddress('100');

    expect(screen.getByTestId('house-number')).toHaveTextContent('100');
  });

  it('refuses letters at the keystroke', async () => {
    // Not "shows an error afterwards" — the character never lands in the field. `12א` is a real
    // Israeli spelling and is deliberately not accepted; the letter belongs in דירה/כניסה.
    render(<Harness provider={fakeProvider()} />);
    await enterFullAddress('12א');

    expect(screen.getByLabelText(/מספר בית/)).toHaveValue('12');
  });

  it('refuses symbols and spaces at the keystroke', async () => {
    render(<Harness provider={fakeProvider()} />);
    await enterFullAddress('12/3 4');

    expect(screen.getByLabelText(/מספר בית/)).toHaveValue('1234');
  });

  it('uses a numeric input mode, for a numeric keypad on a phone', async () => {
    render(<Harness provider={fakeProvider()} />);

    expect(screen.getByLabelText(/מספר בית/)).toHaveAttribute('inputmode', 'numeric');
  });
});

describe('AddressFormFields — confirming the complete address with Google', () => {
  it('confirms city + street + house number together and resolves the address', async () => {
    const resolveFullAddress = vi.fn(fakeProvider().resolveFullAddress);
    const onValue = vi.fn();
    render(<Harness provider={fakeProvider({ resolveFullAddress })} onValue={onValue} />);

    await enterFullAddress('100');

    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('true'));
    expect(screen.getByTestId('submittable')).toHaveTextContent('true');
    // All three parts went to Google as one address — not a street resolved on its own with a
    // house number appended to it afterwards, which is what the previous single-box design did.
    expect(resolveFullAddress).toHaveBeenCalledWith(
      { city: 'תל אביב-יפו', street: 'דיזנגוף', houseNumber: '100' },
      expect.anything(),
    );

    const committed = onValue.mock.calls.at(-1)?.[0] as AddressValue;
    expect(committed.placeId).toBe('ChIJdizengoff100');
    expect(committed.latitude).toBe(32.0811);
    expect(committed.longitude).toBe(34.7739);
  });

  it('shows the confirmed address back', async () => {
    render(<Harness provider={fakeProvider()} />);
    await enterFullAddress('100');

    expect(await screen.findByTestId('address-confirmed')).toHaveTextContent(
      'דיזנגוף 100, תל אביב-יפו, ישראל',
    );
  });

  it('an address Google cannot resolve cannot proceed, and says why', async () => {
    // The requirement in one test: a real street with a house number that does not exist on it.
    // Everything looks filled in; nothing is submittable.
    render(<Harness provider={fakeProvider()} />);

    await enterFullAddress('9999');

    expect(await screen.findByText(/לא מצאנו את הכתובת הזו/)).toBeInTheDocument();
    expect(screen.getByTestId('resolved')).toHaveTextContent('false');
    expect(screen.getByTestId('submittable')).toHaveTextContent('false');
  });

  it('distinguishes "that address does not exist" from "we could not ask"', async () => {
    render(
      <Harness
        provider={fakeProvider({
          resolveFullAddress: async () => {
            throw new Error('provider down');
          },
        })}
      />,
    );

    await enterFullAddress('100');

    expect(await screen.findByText(/לא הצלחנו לאמת את הכתובת כרגע/)).toBeInTheDocument();
    expect(screen.getByTestId('resolved')).toHaveTextContent('false');
  });

  it('editing the house number after confirmation invalidates it and re-confirms', async () => {
    // The invalidation rule, on the field most likely to be corrected: the coordinates must never
    // outlive the text they described.
    render(<Harness provider={fakeProvider()} />);
    const user = await enterFullAddress('100');
    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('true'));

    await user.clear(screen.getByLabelText(/מספר בית/));
    await user.type(screen.getByLabelText(/מספר בית/), '9999');

    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('false'));
    expect(await screen.findByText(/לא מצאנו את הכתובת הזו/)).toBeInTheDocument();
  });

  it('does not ask Google until all three parts are present', async () => {
    const resolveFullAddress = vi.fn(fakeProvider().resolveFullAddress);
    render(<Harness provider={fakeProvider({ resolveFullAddress })} />);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/עיר/), 'תל אב');
    await user.click(await screen.findByRole('option', { name: /תל אביב-יפו/ }));
    await user.type(screen.getByLabelText(/רחוב/), 'דיזנ');
    await user.click(await screen.findByRole('option', { name: /דיזנגוף/ }));

    await new Promise((r) => setTimeout(r, 600));
    expect(resolveFullAddress).not.toHaveBeenCalled();
  });

  it('editing an access field does NOT invalidate the confirmation', async () => {
    render(<Harness provider={fakeProvider()} />);
    const user = await enterFullAddress('100');
    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('true'));

    await user.type(screen.getByLabelText(/^דירה/), '4');

    expect(screen.getByTestId('resolved')).toHaveTextContent('true');
    expect(screen.getByTestId('submittable')).toHaveTextContent('true');
  });

  it('says so when address search is not configured at all', async () => {
    render(<Harness provider={fakeProvider({ isConfigured: () => false })} />);

    expect(screen.getByText(/חיפוש הכתובות אינו זמין כרגע/)).toBeInTheDocument();
  });

  it('a legacy saved address opens showing its text but unconfirmed', async () => {
    // The profile screen's case: the address is displayed so the customer sees what they have,
    // but saving it requires it to be re-confirmed against Google.
    render(
      <Harness
        provider={fakeProvider()}
        initial={{ ...EMPTY_ADDRESS, city: 'חיפה', street: 'הרצל', houseNumber: '5' }}
      />,
    );

    expect(screen.getByLabelText(/עיר/)).toHaveValue('חיפה');
    expect(screen.getByLabelText(/רחוב/)).toHaveValue('הרצל');
    expect(screen.getByLabelText(/מספר בית/)).toHaveValue('5');
    await waitFor(() => expect(screen.getByTestId('submittable')).toHaveTextContent('false'));
  });
});

/**
 * Apartment, floor and entrance. They are optional and they are still constrained — see
 * `addressTypes.ts` for why, and `backend/.../maps/AddressAccessFields.java` for the server half
 * that holds when nothing renders this component at all.
 *
 * These assert the *keystroke filter*, the same way the house-number tests do: the disallowed
 * character never lands in the field rather than landing and then being complained about.
 * `addressTypes.test.ts` covers the submit-time rules on values this form never produced.
 */
describe('AddressFormFields — apartment, floor and entrance', () => {
  it('accepts digits in the apartment field', async () => {
    render(<Harness provider={fakeProvider()} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/^דירה/), '12');

    expect(screen.getByTestId('apartment')).toHaveTextContent('12');
  });

  it('refuses letters and symbols in the apartment field at the keystroke', async () => {
    render(<Harness provider={fakeProvider()} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/^דירה/), '4א/2');

    expect(screen.getByLabelText(/^דירה/)).toHaveValue('42');
  });

  it('accepts digits in the floor field', async () => {
    render(<Harness provider={fakeProvider()} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/^קומה/), '14');

    expect(screen.getByTestId('floor')).toHaveTextContent('14');
  });

  it('refuses a minus sign in the floor field — negative floors are not supported', async () => {
    // The decision, at the keystroke: nothing ever intentionally accepted `-1`, and a basement is
    // described in the access-notes field. See `addressTypes.ts`.
    render(<Harness provider={fakeProvider()} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/^קומה/), '-1');

    expect(screen.getByLabelText(/^קומה/)).toHaveValue('1');
  });

  it('uses a numeric input mode for apartment and floor, for a numeric keypad on a phone', () => {
    render(<Harness provider={fakeProvider()} />);

    expect(screen.getByLabelText(/^דירה/)).toHaveAttribute('inputmode', 'numeric');
    expect(screen.getByLabelText(/^קומה/)).toHaveAttribute('inputmode', 'numeric');
  });

  it.each(['A', 'ב', '1', '12', 'A1', 'ב2'])(
    'accepts %j as an entrance',
    async (entrance) => {
      render(<Harness provider={fakeProvider()} />);
      const user = userEvent.setup();

      await user.type(screen.getByLabelText(/^כניסה/), entrance);

      expect(screen.getByLabelText(/^כניסה/)).toHaveValue(entrance);
    },
  );

  it('refuses a third entrance character', async () => {
    render(<Harness provider={fakeProvider()} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/^כניסה/), 'ABC');

    expect(screen.getByLabelText(/^כניסה/)).toHaveValue('AB');
    expect(screen.getByTestId('entrance')).toHaveTextContent('AB');
  });

  it.each([
    ['A-1', 'A1'],
    ['א ב', 'אב'],
    ['@1', '1'],
  ])('strips symbols and spaces from the entrance: %j becomes %j', async (typed, expected) => {
    render(<Harness provider={fakeProvider()} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/^כניסה/), typed);

    expect(screen.getByLabelText(/^כניסה/)).toHaveValue(expected);
  });

  it('leaves all three empty and submittable when the customer skips them', async () => {
    // The rule that matters most about these fields: they are optional, and every constraint above
    // is a constraint on a value that is present.
    render(<Harness provider={fakeProvider()} />);
    await enterFullAddress('100');

    await waitFor(() => expect(screen.getByTestId('submittable')).toHaveTextContent('true'));
    expect(screen.getByLabelText(/^דירה/)).toHaveValue('');
    expect(screen.getByLabelText(/^קומה/)).toHaveValue('');
    expect(screen.getByLabelText(/^כניסה/)).toHaveValue('');
  });
});
