import { useCallback, useMemo, useState } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BookingSummary } from './BookingSummary';
import { AuthGateModal } from '../auth';
import { AuthGateProvider } from '../../shared/hooks';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';
import type { ProfessionalCard } from '../../shared/api';

/**
 * **The deferred-authentication gate, at the one place the journey still needs an account.**
 *
 * A guest picks a professional, a day and a time and reaches this summary without ever being asked
 * to sign in — that part is not new, and the first test here pins it. What changed is what happens
 * when they press the final button: the account question is now asked *over* this screen instead of
 * by navigating to `/login`, and the confirmation they pressed is resumed the moment a session
 * exists. Nothing is created before that: a guest who dismisses the modal leaves no trace.
 *
 * <p>The register path is exercised as far as this file usefully can — the modal switches to the
 * real `CustomerRegisterForm` without navigating — and then joins the login path at the seam both
 * share: `useSessionLanding`, which consumes the gate rather than navigating (see
 * `useSessionLanding.ts` and the "resumes in place" test below, which drives a session through it).
 */

const login = vi.hoisted(() => vi.fn());
const createIssue = vi.hoisted(() => vi.fn());
const createOrder = vi.hoisted(() => vi.fn());

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return { ...actual, login, createIssue, createOrder };
});

const ADDRESS = {
  city: 'תל אביב-יפו',
  street: 'הרצל',
  houseNumber: '10',
  apartment: '',
  floor: '',
  entrance: '',
  addressNotes: '',
  placeId: 'place-abc',
  formattedAddress: 'הרצל 10, תל אביב-יפו',
  latitude: 32.06,
  longitude: 34.77,
};

const PROFESSIONAL = {
  professionalId: 7,
  fullName: 'אבי כהן',
  basePrice: 250,
  rating: 4.8,
  reviewCount: 12,
} as unknown as ProfessionalCard;

/** Tomorrow, so the summary's "that time has passed" pre-flight never fires. */
const BOOKED_START = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();

const CONFIRM_LABEL = /אישור|הזמנה|לאישור/;

let onConfirmed: ReturnType<typeof vi.fn>;
let onAuthRequired: ReturnType<typeof vi.fn>;
let onIssueCreated: ReturnType<typeof vi.fn>;

/** Shows the current path, so "we did not go to /login" is asserted on the router, not on a mock. */
function PathProbe() {
  const location = useLocation();
  return <span data-testid="path">{location.pathname}</span>;
}

/**
 * A real-enough auth context: `establishSession` flips the token the way `AuthProvider` does, which
 * is what the summary's resume waits for.
 */
function Harness({ startSignedIn = false }: { startSignedIn?: boolean }) {
  const [token, setToken] = useState<string | null>(startSignedIn ? 'jwt-existing' : null);

  const establishSession = useCallback(async () => {
    setToken('jwt-new');
    return { id: 42, role: 'CUSTOMER', fullName: 'דנה' };
  }, []);

  const auth = useMemo(
    () =>
      ({
        token,
        user: token ? { id: 42, role: 'CUSTOMER', fullName: 'דנה' } : null,
        isLoading: false,
        establishSession,
        logout: vi.fn(),
        refreshUser: vi.fn(),
      }) as unknown as AuthContextValue,
    [token, establishSession],
  );

  const draftValue = useMemo(
    () => ({ draft: null, updateDraft: vi.fn(), clearDraft: vi.fn() }) as unknown as BookingDraftContextValue,
    [],
  );

  return (
    <MemoryRouter initialEntries={['/booking']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={draftValue}>
          <AuthGateProvider>
            <PathProbe />
            <Routes>
              <Route
                path="/booking"
                element={
                  <>
                    <BookingSummary
                      issueDescription="נזילה מתחת לכיור במטבח"
                      issueImageKeys={['guests/abc/1.jpg']}
                      issueClarificationAnswers={[]}
                      categoryId={1}
                      professional={PROFESSIONAL}
                      bookedStart={BOOKED_START}
                      defaultDurationMinutes={60}
                      address={ADDRESS}
                      onAuthRequired={onAuthRequired}
                      onIssueCreated={onIssueCreated}
                      onConfirmed={onConfirmed}
                      onTimeUnavailable={vi.fn()}
                    />
                    <AuthGateModal />
                  </>
                }
              />
              <Route path="/login" element={<div>login-page</div>} />
            </Routes>
          </AuthGateProvider>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>
  );
}

function confirmButton() {
  return screen.getByRole('button', { name: CONFIRM_LABEL });
}

/** The modal, identified by its dialog role rather than by any of its contents. */
function authModal() {
  return screen.queryByRole('dialog');
}

beforeEach(() => {
  onConfirmed = vi.fn();
  onAuthRequired = vi.fn();
  onIssueCreated = vi.fn();
  login.mockReset();
  createIssue.mockReset().mockResolvedValue({ id: 777, categoryId: 1, urgencyType: 'STANDARD' });
  createOrder.mockReset().mockResolvedValue({ id: 555, orderStatus: 'PENDING' });
});

afterEach(() => vi.clearAllMocks());

describe('a guest reaches and reads the summary with no account', () => {
  it('is not asked to sign in just for being here', () => {
    render(<Harness />);

    // Choosing a professional, a day and a time got them here with no gate in the way; the summary
    // renders in full, and nothing is open over it.
    expect(screen.getByRole('heading', { name: 'סיכום ההזמנה' })).toBeInTheDocument();
    expect(screen.getByText('אבי כהן')).toBeInTheDocument();
    expect(authModal()).not.toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/booking');
  });
});

describe('the final confirmation asks for an account, over this screen', () => {
  it('opens the auth modal instead of navigating to /login', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(confirmButton());

    expect(authModal()).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/booking');
    expect(screen.queryByText('login-page')).not.toBeInTheDocument();
  });

  it('keeps the booking summary on screen behind it', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(confirmButton());

    // The reason this is a modal and not a route: what they are signing in *for* is still visible.
    expect(screen.getByRole('heading', { name: 'סיכום ההזמנה' })).toBeInTheDocument();
    expect(screen.getByText('אבי כהן')).toBeInTheDocument();
  });

  it('creates nothing before there is a session', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(confirmButton());

    expect(createIssue).not.toHaveBeenCalled();
    expect(createOrder).not.toHaveBeenCalled();
    // The parent still persists the draft — that is what survives a closed tab.
    expect(onAuthRequired).toHaveBeenCalledTimes(1);
  });

  it('dismissing leaves the summary, and the booking, exactly as they were', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(confirmButton());

    await user.click(screen.getByRole('button', { name: 'סגירה' }));

    await waitFor(() => expect(authModal()).not.toBeInTheDocument());
    expect(screen.getByRole('heading', { name: 'סיכום ההזמנה' })).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/booking');
    expect(createIssue).not.toHaveBeenCalled();
    expect(createOrder).not.toHaveBeenCalled();
    // And the button still works: dismissing is "not now", not "start again".
    expect(confirmButton()).toBeEnabled();
  });

  it('offers registration in the same modal, without leaving the page', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(confirmButton());

    await user.click(within(authModal()!).getByRole('button', { name: 'הרשמה' }));

    // The real `CustomerRegisterForm`, in place — same component `/register/customer` renders.
    expect(within(authModal()!).getByLabelText(/שם מלא/)).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/booking');
  });
});

describe('a session resumes the confirmation that was refused', () => {
  it('signs in, closes the modal and books — once', async () => {
    const user = userEvent.setup();
    // The OTP-disabled shape: a correct password answers with a session directly. The OTP-enabled
    // shape goes through `AuthChallengeStep`, which lands through the very same hook.
    login.mockResolvedValue({ nextStep: 'AUTHENTICATED', session: { token: 'jwt-new' } });
    render(<Harness />);
    await user.click(confirmButton());

    const modal = authModal()!;
    await user.type(within(modal).getByLabelText(/אימייל או טלפון/), 'dana@example.com');
    await user.type(within(modal).getByLabelText(/סיסמה/), 'password123');
    await user.click(within(modal).getByRole('button', { name: 'המשך' }));

    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));
    // Exactly one of each: the resume runs the same commit the customer pressed, not a second one.
    expect(createIssue).toHaveBeenCalledTimes(1);
    expect(onConfirmed).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(authModal()).not.toBeInTheDocument());
    // Never went anywhere: no /login, and no re-landing on a fresh copy of this screen.
    expect(screen.getByTestId('path')).toHaveTextContent('/booking');
  });
});

describe('an authenticated confirmation is unchanged', () => {
  it('books immediately, with no modal in the way', async () => {
    const user = userEvent.setup();
    render(<Harness startSignedIn />);

    await user.click(confirmButton());

    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));
    expect(createIssue).toHaveBeenCalledTimes(1);
    expect(onConfirmed).toHaveBeenCalledTimes(1);
    expect(authModal()).not.toBeInTheDocument();
    expect(onAuthRequired).not.toHaveBeenCalled();
  });
});
