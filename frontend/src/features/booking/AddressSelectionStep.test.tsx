import { useState } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AddressSelectionStep, SAVE_AS_HOME_LABEL } from './AddressSelectionStep';
import type { AddressMode } from './AddressSelectionStep';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { EMPTY_ADDRESS } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { httpClient } from '../../shared/api/httpClient';
import type { UserMeResponse } from '../../shared/api/users';

/**
 * "הפוך את זה לכתובת הבית" — the optional home-address save on the booking address step.
 *
 * The three properties that matter, and the reason each one is a rule rather than a default:
 * it is offered only to somebody who has a profile to write to, it is **opt-in** (booking for a
 * parent must not silently rewrite your own home address), and not ticking it must write nothing
 * at all.
 */
const RESOLVED: AddressValue = {
  ...EMPTY_ADDRESS,
  city: 'תל אביב-יפו',
  street: 'דיזנגוף',
  houseNumber: '100',
  placeId: 'ChIJdizengoff100',
  formattedAddress: 'דיזנגוף 100, תל אביב-יפו',
  latitude: 32.0811,
  longitude: 34.7739,
};

const customer = {
  id: 2,
  role: 'CUSTOMER',
  fullName: 'לקוח בדיקה',
  email: 'q@e.com',
  defaultAddress: null,
} as unknown as UserMeResponse;

function Harness({
  user,
  initial = RESOLVED,
  onContinue = vi.fn(),
  refreshUser = vi.fn(),
}: {
  user: UserMeResponse | null;
  initial?: AddressValue;
  onContinue?: () => void;
  refreshUser?: () => Promise<void>;
}) {
  const [value, setValue] = useState<AddressValue>(initial);
  const [mode, setMode] = useState<AddressMode>('CUSTOM');
  const auth = {
    token: user ? 't' : null,
    user,
    isLoading: false,
    establishSession: vi.fn(),
    logout: vi.fn(),
    refreshUser,
  } as unknown as AuthContextValue;

  return (
    <AuthContext.Provider value={auth}>
      <AddressSelectionStep
        value={value}
        onChange={setValue}
        mode={mode}
        onModeChange={setMode}
        onContinue={onContinue}
        offerSaveAsHome
      />
    </AuthContext.Provider>
  );
}

describe('who is offered the home-address option', () => {
  beforeEach(() => {
    vi.spyOn(httpClient, 'put').mockResolvedValue(customer);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('offers it to a signed-in customer with a validated address', () => {
    render(<Harness user={customer} />);

    expect(screen.getByLabelText(SAVE_AS_HOME_LABEL)).toBeInTheDocument();
  });

  it('does NOT offer it to a guest', () => {
    // There is no profile to write to. The address still works for this booking.
    render(<Harness user={null} />);

    expect(screen.queryByLabelText(SAVE_AS_HOME_LABEL)).not.toBeInTheDocument();
  });

  it('does NOT offer it before the address has been validated by Google', () => {
    // An unresolved address is not something to persist — it is text nobody has confirmed.
    render(<Harness user={customer} initial={{ ...EMPTY_ADDRESS, city: 'חיפה', street: 'הרצל' }} />);

    expect(screen.queryByLabelText(SAVE_AS_HOME_LABEL)).not.toBeInTheDocument();
  });

  it('is unticked by default', () => {
    render(<Harness user={customer} />);

    expect(screen.getByLabelText(SAVE_AS_HOME_LABEL)).not.toBeChecked();
  });
});

describe('what ticking it does', () => {
  beforeEach(() => {
    vi.spyOn(httpClient, 'put').mockResolvedValue(customer);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('saves the validated address to the profile and then continues', async () => {
    const onContinue = vi.fn();
    const refreshUser = vi.fn(async () => undefined);
    render(<Harness user={customer} onContinue={onContinue} refreshUser={refreshUser} />);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText(SAVE_AS_HOME_LABEL));
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await waitFor(() => expect(onContinue).toHaveBeenCalled());
    expect(httpClient.put).toHaveBeenCalledWith('/api/users/me/default-address', expect.anything());
    // The cached user is refreshed, so the "my saved address" chip is there on the next screen
    // rather than after a full page load.
    expect(refreshUser).toHaveBeenCalled();
  });

  it('saves the normalised Google result, not raw text', async () => {
    render(<Harness user={customer} />);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText(SAVE_AS_HOME_LABEL));
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await waitFor(() => expect(httpClient.put).toHaveBeenCalled());
    const body = vi.mocked(httpClient.put).mock.calls[0][1] as Record<string, unknown>;
    expect(body.placeId).toBe('ChIJdizengoff100');
    expect(body.formattedAddress).toBe('דיזנגוף 100, תל אביב-יפו');
    expect(body.latitude).toBe(32.0811);
    expect(body.longitude).toBe(34.7739);
    expect(body.city).toBe('תל אביב-יפו');
    expect(body.houseNumber).toBe('100');
  });

  it('does not continue when the save fails, and explains why', async () => {
    // The customer asked for something and it did not happen. Unticking the box is the one-tap
    // way past it, which is better than continuing silently and leaving them believing it saved.
    vi.mocked(httpClient.put).mockRejectedValue(new Error('boom'));
    const onContinue = vi.fn();
    render(<Harness user={customer} onContinue={onContinue} />);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText(SAVE_AS_HOME_LABEL));
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/לא הצלחנו לשמור את הכתובת/);
    expect(onContinue).not.toHaveBeenCalled();
  });
});

describe('what NOT ticking it does', () => {
  beforeEach(() => {
    vi.spyOn(httpClient, 'put').mockResolvedValue(customer);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('writes nothing to the profile — the existing home address is untouched', async () => {
    // The guarantee this step has always made: choosing a one-off address for a booking never
    // rewrites the saved one.
    const onContinue = vi.fn();
    render(<Harness user={customer} onContinue={onContinue} />);

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await waitFor(() => expect(onContinue).toHaveBeenCalled());
    expect(httpClient.put).not.toHaveBeenCalled();
  });

  it('writes nothing for a guest either', async () => {
    const onContinue = vi.fn();
    render(<Harness user={null} onContinue={onContinue} />);

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await waitFor(() => expect(onContinue).toHaveBeenCalled());
    expect(httpClient.put).not.toHaveBeenCalled();
  });
});
