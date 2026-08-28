import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { LoginForm } from './LoginForm';
import type { AuthStepResponse } from '../../shared/api';

/**
 * The two shapes `POST /api/auth/login` can answer with, and the one rule that matters: which one
 * arrives is the server's decision, and this form must follow it without a client-side flag of its
 * own.
 *
 * <p>A backend with `AUTH_OTP_REQUIRED=true` answers `LOGIN_OTP` and the user goes to `/verify`; a
 * backend with `AUTH_OTP_REQUIRED=false` answers `AUTHENTICATED` and the user is signed in here.
 * Both are exercised against the same component with no configuration difference between them,
 * because there deliberately is none to make.
 */

const navigate = vi.fn();
const establishSession = vi.fn();
const login = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

vi.mock('../../shared/api', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api')>('../../shared/api');
  return { ...actual, login: (...args: unknown[]) => login(...args) };
});

vi.mock('../../shared/hooks', async () => {
  const actual = await vi.importActual<typeof import('../../shared/hooks')>('../../shared/hooks');
  // `useSessionLanding` now also reads the booking draft, so that a customer who was sent here by
  // the book button resumes their booking instead of landing on Home. Stubbed to "no draft" here,
  // which is the plain-login case these four tests are about — the resume behaviour has its own
  // coverage in useSessionLanding.test.tsx.
  return {
    ...actual,
    useAuth: () => ({ establishSession }),
    useBookingDraft: () => ({ draft: null, updateDraft: vi.fn(), clearDraft: vi.fn() }),
  };
});

const SESSION = {
  token: 'issued.jwt.token',
  tokenType: 'Bearer',
  expiresIn: 86400,
  user: { id: 1, fullName: 'Israel Israeli', email: 'customer@example.com', role: 'CUSTOMER' as const },
};

const CHALLENGE_RESPONSE: AuthStepResponse = {
  nextStep: 'LOGIN_OTP',
  challenge: {
    challengeId: 'c0ffee00-0000-4000-8000-000000000000',
    channel: 'EMAIL',
    destinationMasked: 'c***@example.com',
    expiresInSeconds: 600,
    delivered: true,
  },
  session: null,
  emailVerified: true,
  phoneVerified: true,
};

const AUTHENTICATED_RESPONSE: AuthStepResponse = {
  nextStep: 'AUTHENTICATED',
  challenge: null,
  session: SESSION,
  emailVerified: true,
  phoneVerified: true,
};

async function submitCredentials() {
  const user = userEvent.setup();
  render(
    <MemoryRouter>
      <LoginForm />
    </MemoryRouter>,
  );
  await user.type(screen.getByLabelText(/אימייל או טלפון/), 'customer@example.com');
  await user.type(screen.getByLabelText(/סיסמה/), 'StrongPassword123!');
  await user.click(screen.getByRole('button', { name: /המשך/ }));
}

describe('LoginForm', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    establishSession.mockResolvedValue({ role: 'CUSTOMER' });
  });

  it('routes to the OTP screen when the server asks for a second factor', async () => {
    login.mockResolvedValue(CHALLENGE_RESPONSE);

    await submitCredentials();

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/verify', expect.anything()));
    expect(establishSession).not.toHaveBeenCalled();
  });

  it('signs in directly when the server answers AUTHENTICATED', async () => {
    login.mockResolvedValue(AUTHENTICATED_RESPONSE);

    await submitCredentials();

    await waitFor(() => expect(establishSession).toHaveBeenCalledWith(SESSION));
    // The same destination a redeemed OTP reaches, and never the OTP screen.
    expect(navigate).toHaveBeenCalledWith('/', { replace: true });
    expect(navigate).not.toHaveBeenCalledWith('/verify', expect.anything());
  });

  it('lands a professional on the professional home, not the customer one', async () => {
    login.mockResolvedValue(AUTHENTICATED_RESPONSE);
    establishSession.mockResolvedValue({ role: 'PROFESSIONAL' });

    await submitCredentials();

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/pro', { replace: true }));
  });

  it('still surfaces invalid credentials without signing anyone in', async () => {
    const { ApiError } = await vi.importActual<typeof import('../../shared/api')>('../../shared/api');
    login.mockRejectedValue(new ApiError('INVALID_CREDENTIALS', 'Invalid credentials.', null, 401));

    await submitCredentials();

    expect(await screen.findByRole('alert')).toHaveTextContent('הפרטים שהוזנו אינם נכונים.');
    expect(establishSession).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });
});
