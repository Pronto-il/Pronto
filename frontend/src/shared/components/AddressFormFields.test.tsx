import { useState } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AddressFormFields } from './AddressFormFields';
import { EMPTY_ADDRESS, isAddressResolved, validateAddress } from './addressTypes';
import type { AddressValue } from './addressTypes';
import type { AddressSuggestionProvider } from './googlePlaces';

/**
 * A stand-in for Google, so these tests exercise the real component with no network, no API key
 * and no `window.google`. This is what the provider seam in `googlePlaces.ts` is for: the
 * behaviour worth testing is "typing is not selecting", and that is entirely ours.
 */
function fakeProvider(overrides: Partial<AddressSuggestionProvider> = {}): AddressSuggestionProvider {
  return {
    isConfigured: () => true,
    newSessionToken: async () => ({ token: 1 }),
    fetchSuggestions: async (query: string) =>
      query.trim().length < 2
        ? []
        : [
            { placeId: 'ChIJdizengoff', description: 'דיזנגוף 100, תל אביב-יפו' },
            { placeId: 'ChIJallenby', description: 'אלנבי 5, תל אביב-יפו' },
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
    </>
  );
}

async function typeAddress(text: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/חיפוש כתובת/), text);
  return user;
}

describe('AddressFormFields', () => {
  it('typing alone never produces a submittable address', async () => {
    // The headline requirement. Before this change, this exact interaction produced a fully
    // valid address that the backend accepted.
    render(<Harness provider={fakeProvider()} />);

    await typeAddress('דיזנגוף 100');

    await waitFor(() => expect(screen.getByRole('listbox')).toBeInTheDocument());
    expect(screen.getByTestId('resolved')).toHaveTextContent('false');
    expect(screen.getByTestId('submittable')).toHaveTextContent('false');
  });

  it('selecting a suggestion resolves the address and fills the structured fields', async () => {
    const onValue = vi.fn();
    render(<Harness provider={fakeProvider()} onValue={onValue} />);

    const user = await typeAddress('דיזנגוף');
    await user.click(await screen.findByRole('option', { name: /דיזנגוף 100/ }));

    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('true'));
    expect(screen.getByTestId('submittable')).toHaveTextContent('true');

    const committed = onValue.mock.calls.at(-1)?.[0] as AddressValue;
    expect(committed.placeId).toBe('ChIJdizengoff');
    expect(committed.city).toBe('תל אביב-יפו');
    expect(committed.street).toBe('דיזנגוף');
    expect(committed.houseNumber).toBe('100');
    expect(committed.latitude).toBe(32.0811);
    expect(committed.longitude).toBe(34.7739);
  });

  it('shows the resolved address back as confirmation', async () => {
    render(<Harness provider={fakeProvider()} />);

    const user = await typeAddress('דיזנגוף');
    await user.click(await screen.findByRole('option', { name: /דיזנגוף 100/ }));

    expect(await screen.findByTestId('address-confirmed')).toHaveTextContent(
      'דיזנגוף 100, תל אביב-יפו',
    );
  });

  it('editing the search text after selecting invalidates the selection', async () => {
    // The second requirement, end to end through the real component rather than the pure helper.
    render(<Harness provider={fakeProvider()} />);

    const user = await typeAddress('דיזנגוף');
    await user.click(await screen.findByRole('option', { name: /דיזנגוף 100/ }));
    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('true'));

    await user.type(screen.getByLabelText(/חיפוש כתובת/), 'x');

    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('false'));
    expect(screen.getByTestId('submittable')).toHaveTextContent('false');
  });

  it('editing the house number after selecting also invalidates it', async () => {
    // House number stays editable because Google does not always return one — so it is also a
    // way to end up with a place id describing a different building.
    render(<Harness provider={fakeProvider()} />);

    const user = await typeAddress('דיזנגוף');
    await user.click(await screen.findByRole('option', { name: /דיזנגוף 100/ }));
    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('true'));

    await user.type(screen.getByLabelText(/מספר בית/), '9');

    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('false'));
  });

  it('editing an access field does NOT invalidate the selection', async () => {
    render(<Harness provider={fakeProvider()} />);

    const user = await typeAddress('דיזנגוף');
    await user.click(await screen.findByRole('option', { name: /דיזנגוף 100/ }));
    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('true'));

    await user.type(screen.getByLabelText(/^דירה/), '4');

    expect(screen.getByTestId('resolved')).toHaveTextContent('true');
    expect(screen.getByTestId('submittable')).toHaveTextContent('true');
  });

  it('re-selecting after an edit makes the address submittable again', async () => {
    render(<Harness provider={fakeProvider()} />);

    const user = await typeAddress('דיזנגוף');
    await user.click(await screen.findByRole('option', { name: /דיזנגוף 100/ }));
    await user.type(screen.getByLabelText(/חיפוש כתובת/), 'x');
    await waitFor(() => expect(screen.getByTestId('resolved')).toHaveTextContent('false'));

    await user.click(await screen.findByRole('option', { name: /דיזנגוף 100/ }));

    await waitFor(() => expect(screen.getByTestId('submittable')).toHaveTextContent('true'));
  });

  it('does not query the provider for a one-character query', async () => {
    const fetchSuggestions = vi.fn(async () => []);
    render(<Harness provider={fakeProvider({ fetchSuggestions })} />);

    await typeAddress('ד');

    await new Promise((r) => setTimeout(r, 400));
    expect(fetchSuggestions).not.toHaveBeenCalled();
  });

  it('leaves the address unresolved when the provider cannot resolve the selection', async () => {
    // A failed details lookup must not half-commit: no place id, no coordinates, and a message
    // rather than a silently unsubmittable form.
    render(
      <Harness
        provider={fakeProvider({
          resolve: async () => {
            throw new Error('provider down');
          },
        })}
      />,
    );

    const user = await typeAddress('דיזנגוף');
    await user.click(await screen.findByRole('option', { name: /דיזנגוף 100/ }));

    expect(await screen.findByText(/לא הצלחנו לאמת את הכתובת/)).toBeInTheDocument();
    expect(screen.getByTestId('resolved')).toHaveTextContent('false');
  });

  it('says so when address search is not configured at all', async () => {
    render(<Harness provider={fakeProvider({ isConfigured: () => false })} />);

    expect(screen.getByText(/חיפוש הכתובות אינו זמין כרגע/)).toBeInTheDocument();
  });

  it('a legacy saved address opens showing its text but unresolved', async () => {
    // The profile screen's case: the address is displayed so the customer sees what they have,
    // but saving requires picking a real suggestion.
    render(
      <Harness
        provider={fakeProvider()}
        initial={{ ...EMPTY_ADDRESS, city: 'חיפה', street: 'הרצל', houseNumber: '5' }}
      />,
    );

    expect(screen.getByLabelText(/חיפוש כתובת/)).toHaveValue('הרצל 5, חיפה');
    expect(screen.getByTestId('resolved')).toHaveTextContent('false');
    expect(screen.getByTestId('submittable')).toHaveTextContent('false');
  });
});
