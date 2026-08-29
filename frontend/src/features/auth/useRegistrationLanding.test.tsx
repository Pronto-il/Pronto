import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { useRegistrationLanding } from './useRegistrationLanding';
import PhoneCapturePage from './PhoneCapturePage';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';
import type { AuthStepResponse, AuthSession, UserMeResponse } from '../../shared/api';

/**
 * **Where registration ends when there is no OTP.**
 *
 * The bug: both register pages navigated to `/verify` unconditionally. That was right while
 * registration always answered `VERIFY_EMAIL` with a challenge, and became wrong the moment
 * verification could be switched off — `OTP_VERIFICATION_ENABLED=false` makes the backend answer
 * `AUTHENTICATED` with a real session and `challenge: null`, and `AuthChallengePage` renders its
 * "no active flow" notice for a challengeless state. So the account was created, a valid session
 * was issued, and the frontend threw it away and showed the user "התהליך פג".
 *
 * These tests pin all three server answers, because the risk is not that the new branch fails —
 * that is visible on the first registration — it is that the challenge branch got lost on the way.
 */

const CUSTOMER = {
  id: 2,
  role: 'CUSTOMER',
  fullName: 'לקוח בדיקה',
  email: 'q@e.com',
  phoneVerified: false,
  phoneVerificationRequired: true,
} as unknown as UserMeResponse;

const SESSION: AuthSession = {
  token: 'issued.jwt.token',
  tokenType: 'Bearer',
  expiresIn: 86400,
  user: { id: 2, fullName: 'לקוח בדיקה', email: 'q@e.com', role: 'CUSTOMER' },
};

function authValue(user: UserMeResponse, establishSession = vi.fn().mockResolvedValue(user)) {
  return {
    token: 't',
    user,
    isLoading: false,
    establishSession,
    logout: vi.fn(),
    refreshUser: vi.fn(),
  } as unknown as AuthContextValue;
}

const NO_DRAFT = {
  draft: null,
  updateDraft: vi.fn(),
  clearDraft: vi.fn(),
} as unknown as BookingDraftContextValue;

/** Calls the hook with a canned registration response, the way a register page's `onSuccess` does. */
function Harness({ response }: { response: AuthStepResponse }) {
  const land = useRegistrationLanding();
  return (
    <button type="button" onClick={() => void land(response)}>
      finish
    </button>
  );
}

function renderHarness(response: AuthStepResponse, auth: AuthContextValue = authValue(CUSTOMER)) {
  return render(
    <MemoryRouter initialEntries={['/register/customer']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={NO_DRAFT}>
          <Routes>
            <Route path="/register/customer" element={<Harness response={response} />} />
            <Route path="/" element={<div data-testid="home">home</div>} />
            <Route path="/verify" element={<div data-testid="otp-screen">otp</div>} />
            <Route path="/login" element={<div data-testid="login">login</div>} />
          </Routes>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

describe('registration landing follows the server’s nextStep', () => {
  it('adopts the session and lands the user in the product when no OTP is required', async () => {
    const establishSession = vi.fn().mockResolvedValue(CUSTOMER);
    const user = userEvent.setup();
    renderHarness(
      {
        nextStep: 'AUTHENTICATED',
        challenge: null,
        session: SESSION,
        emailVerified: false,
        phoneVerified: false,
      },
      authValue(CUSTOMER, establishSession),
    );

    await user.click(screen.getByRole('button', { name: 'finish' }));

    await waitFor(() => expect(screen.getByTestId('home')).toBeInTheDocument());
    // The session is persisted, not merely navigated past — this is the assertion that would have
    // caught the original bug, in which a perfectly good token was discarded.
    expect(establishSession).toHaveBeenCalledWith(SESSION);
    expect(screen.queryByTestId('otp-screen')).not.toBeInTheDocument();
  });

  it('still goes to the OTP screen when the server issues a challenge', async () => {
    const user = userEvent.setup();
    renderHarness({
      nextStep: 'VERIFY_EMAIL',
      challenge: {
        challengeId: 'abc',
        channel: 'EMAIL',
        destinationMasked: 'q***@e.com',
        expiresInSeconds: 900,
        delivered: true,
      },
      session: null,
      emailVerified: false,
      phoneVerified: false,
    });

    await user.click(screen.getByRole('button', { name: 'finish' }));

    await waitFor(() => expect(screen.getByTestId('otp-screen')).toBeInTheDocument());
  });

  it('sends the user to sign in when the account is complete but no session came back', async () => {
    const user = userEvent.setup();
    renderHarness({
      nextStep: 'LOGIN',
      challenge: null,
      session: null,
      emailVerified: true,
      phoneVerified: false,
    });

    await user.click(screen.getByRole('button', { name: 'finish' }));

    await waitFor(() => expect(screen.getByTestId('login')).toBeInTheDocument());
  });

  it('never strands the user on the "process expired" screen with a session in hand', async () => {
    // The regression in one assertion: an AUTHENTICATED response must not reach /verify at all.
    const user = userEvent.setup();
    renderHarness({
      nextStep: 'AUTHENTICATED',
      challenge: null,
      session: SESSION,
      emailVerified: false,
      phoneVerified: false,
    });

    await user.click(screen.getByRole('button', { name: 'finish' }));

    await waitFor(() => expect(screen.getByTestId('home')).toBeInTheDocument());
  });
});

describe('the phone-capture screen respects the verification policy', () => {
  function renderCapture(user: UserMeResponse) {
    return render(
      <MemoryRouter initialEntries={['/verify-phone']}>
        <AuthContext.Provider value={authValue(user)}>
          <BookingDraftContext.Provider value={NO_DRAFT}>
            <Routes>
              <Route path="/verify-phone" element={<PhoneCapturePage />} />
              <Route path="/" element={<div data-testid="home">home</div>} />
            </Routes>
          </BookingDraftContext.Provider>
        </AuthContext.Provider>
      </MemoryRouter>,
    );
  }

  it('redirects away when nobody is being asked to prove a phone number', () => {
    // `phoneVerified` is still false and correctly so — the number genuinely was not proved. What
    // changed is that no one is asking, and offering the capture form would send the user looking
    // for an SMS the backend has switched off.
    renderCapture({ ...CUSTOMER, phoneVerified: false, phoneVerificationRequired: false });

    expect(screen.getByTestId('home')).toBeInTheDocument();
  });

  it('still shows the capture form when phone verification is required and unmet', () => {
    renderCapture({ ...CUSTOMER, phoneVerified: false, phoneVerificationRequired: true });

    expect(screen.queryByTestId('home')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /אימות מספר טלפון/ })).toBeInTheDocument();
  });

  it('still redirects an already-verified number away', () => {
    renderCapture({ ...CUSTOMER, phoneVerified: true, phoneVerificationRequired: true });

    expect(screen.getByTestId('home')).toBeInTheDocument();
  });
});
