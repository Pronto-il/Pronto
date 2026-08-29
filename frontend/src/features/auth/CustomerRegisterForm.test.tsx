import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CustomerRegisterForm } from './CustomerRegisterForm';
import { httpClient, ApiError } from '../../shared/api/httpClient';

/**
 * Two things are asserted here, and they were separate pieces of work.
 *
 * **Customer registration collects no address.** An address is a property of a job, not of an
 * account: the booking flow asks for it after AI classification and immediately before it is
 * needed, and the customer saves it to their profile only if they choose to. Registration used to
 * demand one, which was a mandatory extra screen and — for anyone booking on a parent's behalf — a
 * saved default that was wrong on day one. These tests assert the payload as well as the screens,
 * because "the address stage is gone from the UI" and "no address is sent" are different claims and
 * only the second one is the contract.
 *
 * **Nothing is discovered on the confirmation screen.** Every field is settled before stage 2 is
 * reachable — shape rules locally, uniqueness by asking `POST /api/auth/availability` on blur. The
 * tests below therefore care about two negatives as much as the positives: the summary must not
 * render while anything is wrong, and `POST /api/auth/register` must not be reached.
 */

const OK_REGISTER_RESPONSE = {
  nextStep: 'VERIFY_EMAIL',
  challenge: null,
  session: null,
  emailVerified: false,
  phoneVerified: false,
};

type Answers = { EMAIL?: boolean; PHONE?: boolean };

/**
 * One `httpClient.post` stub that routes by path, because the form now talks to two endpoints and
 * a single `mockResolvedValue` would answer the availability check with a registration response.
 *
 * `answers` names the fields the backend should report as **taken**; anything unnamed is free.
 * `availabilityError` makes the availability endpoint fail, which is how the "an unreachable check
 * must not block registration" case is exercised.
 */
function mockApi(options: { answers?: Answers; availabilityError?: unknown } = {}) {
  const { answers = {}, availabilityError } = options;
  return vi.spyOn(httpClient, 'post').mockImplementation((async (path: string, body?: unknown) => {
    if (path === '/api/auth/availability') {
      if (availabilityError) {
        throw availabilityError;
      }
      const field = (body as { field: 'EMAIL' | 'PHONE' }).field;
      return { field, available: answers[field] !== true };
    }
    return OK_REGISTER_RESPONSE;
  }) as typeof httpClient.post);
}

/** `Blob.text()` does not exist in jsdom, hence `FileReader`. */
function readBlob(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

function registerCalls() {
  return vi.mocked(httpClient.post).mock.calls.filter((call) => call[0] === '/api/auth/register');
}

function availabilityCalls() {
  return vi.mocked(httpClient.post).mock.calls.filter((call) => call[0] === '/api/auth/availability');
}

async function registerPayload(): Promise<Record<string, unknown>> {
  const formData = registerCalls()[0][1] as FormData;
  return JSON.parse(await readBlob(formData.get('data') as Blob));
}

const VALID = {
  fullName: 'ישראל ישראלי',
  email: 'customer@example.com',
  phone: '0502234567',
  password: 'StrongPassword123!',
};

/** Fills stage 1 with the given overrides and presses Continue. Typing into each successive field
 *  blurs the previous one, which is exactly what drives the availability checks in the real UI. */
async function fillStage1(
  user: ReturnType<typeof userEvent.setup>,
  overrides: Partial<typeof VALID> = {},
) {
  const values = { ...VALID, ...overrides };
  await user.type(screen.getByLabelText(/שם מלא/), values.fullName);
  await user.type(screen.getByLabelText(/אימייל/), values.email);
  await user.type(screen.getByLabelText(/טלפון/), values.phone);
  await user.type(screen.getByLabelText(/^סיסמה/), values.password);
  await user.type(screen.getByLabelText(/אימות סיסמה/), values.password);
  await user.click(screen.getByRole('button', { name: 'המשך' }));
}

function summaryButton() {
  return screen.queryByRole('button', { name: 'יצירת חשבון' });
}

describe('customer registration without an address', () => {
  beforeEach(() => {
    mockApi();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('has no address fields anywhere in the wizard', async () => {
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    expect(screen.queryByLabelText(/עיר/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/רחוב/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/מספר בית/)).not.toBeInTheDocument();

    await fillStage1(user);

    expect(screen.queryByLabelText(/עיר/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/רחוב/)).not.toBeInTheDocument();
  });

  it('goes straight from account details to the confirmation stage', async () => {
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await fillStage1(user);

    // Two stages now, not three, and the second is the summary with the real submit on it.
    expect(await screen.findByRole('button', { name: 'יצירת חשבון' })).toBeInTheDocument();
    expect(await screen.findByText('customer@example.com')).toBeInTheDocument();
  });

  it('submits successfully with no address, and sends `customer: null`', async () => {
    const onSuccess = vi.fn();
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={onSuccess} onExit={vi.fn()} />);

    await fillStage1(user);
    await user.click(await screen.findByRole('button', { name: 'יצירת חשבון' }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    const payload = await registerPayload();
    expect(payload.role).toBe('CUSTOMER');
    expect(payload.fullName).toBe('ישראל ישראלי');
    expect(payload.customer).toBeNull();
  });

  it('the payload carries no city, street or house number under any key', async () => {
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await fillStage1(user);
    await user.click(await screen.findByRole('button', { name: 'יצירת חשבון' }));

    await waitFor(() => expect(registerCalls()).toHaveLength(1));
    const serialised = JSON.stringify(await registerPayload());
    expect(serialised).not.toContain('city');
    expect(serialised).not.toContain('street');
    expect(serialised).not.toContain('houseNumber');
  });

  it('still validates the account details it does collect', async () => {
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await user.type(screen.getByLabelText(/שם מלא/), 'א');
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(await screen.findByText(/יש להזין שם מלא/)).toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });
});

describe('local validation gates the confirmation screen', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('an invalid email blocks moving forward, and says so under the field', async () => {
    mockApi();
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await fillStage1(user, { email: 'not-an-email' });

    expect(await screen.findByText('יש להזין כתובת אימייל תקינה.')).toBeInTheDocument();
    expect(summaryButton()).not.toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('an invalid phone blocks moving forward', async () => {
    mockApi();
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await fillStage1(user, { phone: '12345' });

    expect(await screen.findByText(/יש להזין מספר טלפון נייד תקין/)).toBeInTheDocument();
    expect(summaryButton()).not.toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('reports an invalid field as soon as it loses focus, before Continue is pressed', async () => {
    // The headline UX change: the error is attached to the field the customer just left, not
    // discovered three screens later.
    mockApi();
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await user.type(screen.getByLabelText(/אימייל/), 'nope');
    await user.click(screen.getByLabelText(/טלפון/));

    expect(await screen.findByText('יש להזין כתובת אימייל תקינה.')).toBeInTheDocument();
  });

  // The property, one field at a time: a form that is otherwise perfectly filled in still cannot
  // reach the summary while a single value is wrong.
  it.each<[string, Partial<typeof VALID>]>([
    ['a too-short name', { fullName: 'א' }],
    ['a malformed email', { email: 'not-an-email' }],
    ['a malformed phone', { phone: '12345' }],
    ['a too-short password', { password: 'short' }],
  ])('the confirmation screen is unreachable with %s', async (_label, variant) => {
    mockApi();
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await fillStage1(user, variant);

    expect(summaryButton()).not.toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('a mismatched password confirmation blocks moving forward', async () => {
    mockApi();
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await user.type(screen.getByLabelText(/שם מלא/), VALID.fullName);
    await user.type(screen.getByLabelText(/אימייל/), VALID.email);
    await user.type(screen.getByLabelText(/טלפון/), VALID.phone);
    await user.type(screen.getByLabelText(/^סיסמה/), VALID.password);
    await user.type(screen.getByLabelText(/אימות סיסמה/), 'SomethingElse123!');
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(await screen.findByText(/אימות הסיסמה אינו תואם/)).toBeInTheDocument();
    expect(summaryButton()).not.toBeInTheDocument();
  });
});

describe('duplicate email and phone are reported before the final submit', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows a duplicate email under the field on blur, with no registration attempt', async () => {
    mockApi({ answers: { EMAIL: true } });
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await user.type(screen.getByLabelText(/אימייל/), VALID.email);
    await user.click(screen.getByLabelText(/טלפון/));

    expect(await screen.findByText('כתובת האימייל הזו כבר רשומה במערכת.')).toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('shows a duplicate phone under the field on blur, with no registration attempt', async () => {
    mockApi({ answers: { PHONE: true } });
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await user.type(screen.getByLabelText(/טלפון/), VALID.phone);
    await user.click(screen.getByLabelText(/^סיסמה/));

    expect(await screen.findByText('מספר הטלפון הזה כבר רשום במערכת.')).toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('a duplicate phone keeps the confirmation screen out of reach', async () => {
    mockApi({ answers: { PHONE: true } });
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await fillStage1(user);

    expect(await screen.findByText('מספר הטלפון הזה כבר רשום במערכת.')).toBeInTheDocument();
    expect(summaryButton()).not.toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('a duplicate email keeps the confirmation screen out of reach', async () => {
    mockApi({ answers: { EMAIL: true } });
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await fillStage1(user);

    expect(await screen.findByText('כתובת האימייל הזו כבר רשומה במערכת.')).toBeInTheDocument();
    expect(summaryButton()).not.toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('reports the backend verdict on a number the local shape rule accepts', async () => {
    // 03-6231234 is a well-formed Israeli number and a landline, so it passes the local shape
    // check and is refused by libphonenumber server-side. The customer learns that on the field.
    mockApi({ availabilityError: new ApiError('VALIDATION_ERROR', 'bad', [], 400) });
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await user.type(screen.getByLabelText(/טלפון/), '036231234');
    await user.click(screen.getByLabelText(/^סיסמה/));

    expect(await screen.findByText(/יש להזין מספר טלפון נייד תקין/)).toBeInTheDocument();
  });

  it('asks the backend once per field per value, on blur rather than per keystroke', async () => {
    // The endpoint is rate limited at 20 per 10 minutes precisely because it is a cheap
    // account-existence disclosure; a per-keystroke caller would exhaust that on one slow typist.
    mockApi();
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await user.type(screen.getByLabelText(/אימייל/), VALID.email);
    expect(availabilityCalls()).toHaveLength(0);

    await user.click(screen.getByLabelText(/טלפון/));
    await waitFor(() => expect(availabilityCalls()).toHaveLength(1));

    // Back and forth over an unchanged value: the answer is remembered, not re-fetched.
    await user.click(screen.getByLabelText(/אימייל/));
    await user.click(screen.getByLabelText(/טלפון/));
    await waitFor(() => expect(availabilityCalls()).toHaveLength(1));
  });

  it('an unreachable availability check does NOT block registration', async () => {
    // Advisory, never permission: the backend still performs its own duplicate checks, so a
    // customer on a flaky connection must not be locked out of registering.
    mockApi({ availabilityError: new TypeError('Failed to fetch') });
    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await fillStage1(user);

    expect(await screen.findByRole('button', { name: 'יצירת חשבון' })).toBeInTheDocument();
  });

  it('still handles DUPLICATE_PHONE at submit, and routes it back to the field', async () => {
    // The race the early check cannot close: the number was free on blur and taken by the time
    // the account was created. The 409 handling is not redundant with the availability check.
    vi.spyOn(httpClient, 'post').mockImplementation((async (path: string, body?: unknown) => {
      if (path === '/api/auth/availability') {
        return { field: (body as { field: string }).field, available: true };
      }
      throw new ApiError('DUPLICATE_PHONE', 'Phone number is already registered.', null, 409);
    }) as typeof httpClient.post);

    const user = userEvent.setup();
    render(<CustomerRegisterForm onSuccess={vi.fn()} onExit={vi.fn()} />);

    await fillStage1(user);
    await user.click(await screen.findByRole('button', { name: 'יצירת חשבון' }));

    expect(await screen.findByText('מספר הטלפון הזה כבר רשום במערכת.')).toBeInTheDocument();
    // Routed back to stage 1, where the field it is about lives.
    expect(screen.getByRole('button', { name: 'המשך' })).toBeInTheDocument();
  });
});
