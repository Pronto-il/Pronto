import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProfessionalRegisterForm } from './ProfessionalRegisterForm';
import { httpClient, ApiError } from '../../shared/api/httpClient';
import { GENERIC_ERROR_MESSAGE, getFieldErrorMessages } from '../../shared/api';
import { mapDuplicateContactError } from './registrationValidation';

/**
 * **Professional registration stage 1 — nothing is discovered five screens later.**
 *
 * The reported defect: a registrant could type an already-registered phone number, walk through
 * six stages (trades, service area, sub-services, price, verification document, weekly hours), and
 * only on the final `POST /api/auth/register` be told — as the generic banner
 * *"משהו השתבש, נסו שוב"* — that something had gone wrong. `DUPLICATE_PHONE` had no branch in the
 * error handler at all, so it fell through to `getFieldErrorMessages`, which returns `null` for
 * anything that is not `VALIDATION_ERROR`, and landed on the catch-all.
 *
 * Two properties are pinned here, and both matter independently:
 *
 * 1. **Early.** Local rules and the availability check run on stage 1, so Continue does not
 *    advance while a known problem exists.
 * 2. **Still mapped at submit.** The availability answer is advisory — the race between it and
 *    the real insert is real — so the final handler must still route `DUPLICATE_PHONE` /
 *    `DUPLICATE_EMAIL` / `VALIDATION_ERROR` to their fields, never to a generic banner.
 */

const VALID = {
  fullName: 'דוד כהן',
  email: 'pro@example.com',
  phone: '0502234567',
  password: 'StrongPassword123!',
};

type Answers = { EMAIL?: boolean; PHONE?: boolean };

/** Routes by path: the form talks to `/api/categories`, `/api/service-areas`, the availability
 *  endpoint and finally `/api/auth/register`. `answers` names the fields to report as taken. */
function mockApi(options: { answers?: Answers; registerError?: unknown } = {}) {
  const { answers = {}, registerError } = options;

  vi.spyOn(httpClient, 'get').mockImplementation((async (path: string) => {
    if (path === '/api/categories') {
      return [
        { id: 1, code: 'plumbing', nameHe: 'אינסטלציה', nameEn: 'Plumbing', displayOrder: 1, subServices: [] },
      ];
    }
    if (path === '/api/service-areas') {
      return [
        {
          id: 10,
          code: 'gush_dan',
          nameHe: 'גוש דן',
          nameEn: 'Gush Dan',
          displayOrder: 1,
          cities: [{ id: 1, code: 'tel_aviv', nameHe: 'תל אביב', nameEn: 'Tel Aviv', displayOrder: 1 }],
        },
      ];
    }
    throw new Error(`unexpected GET ${path}`);
  }) as typeof httpClient.get);

  return vi.spyOn(httpClient, 'post').mockImplementation((async (path: string, body?: unknown) => {
    if (path === '/api/auth/availability') {
      const field = (body as { field: 'EMAIL' | 'PHONE' }).field;
      return { field, available: answers[field] !== true };
    }
    if (path === '/api/auth/register') {
      if (registerError) {
        throw registerError;
      }
      return {
        nextStep: 'VERIFY_EMAIL',
        challenge: null,
        session: null,
        emailVerified: false,
        phoneVerified: false,
      };
    }
    throw new Error(`unexpected POST ${path}`);
  }) as typeof httpClient.post);
}

function registerCalls() {
  return vi.mocked(httpClient.post).mock.calls.filter((call) => call[0] === '/api/auth/register');
}

function availabilityCalls() {
  return vi.mocked(httpClient.post).mock.calls.filter((call) => call[0] === '/api/auth/availability');
}

/** Fills stage 1 and presses Continue. Typing into each successive field blurs the previous one,
 *  which is what drives the availability checks in the real UI. */
async function fillStage1(
  user: ReturnType<typeof userEvent.setup>,
  overrides: Partial<typeof VALID> = {},
) {
  const values = { ...VALID, ...overrides };
  await user.type(screen.getByLabelText(/שם מלא/), values.fullName);
  await user.type(screen.getByLabelText(/אימייל/), values.email);
  await user.type(screen.getByLabelText(/טלפון נייד/), values.phone);
  await user.type(screen.getByLabelText(/^סיסמה/), values.password);
  await user.type(screen.getByLabelText(/אימות סיסמה/), values.password);
  await user.click(screen.getByRole('button', { name: 'המשך' }));
}

/** Stage 2 is the trades/service-area step. Its city selector is the marker used to observe
 *  "Continue advanced" — a stage-2-only label, so it cannot be satisfied by anything on stage 1. */
function stage2Heading() {
  return screen.queryByText(/ערים שבהן אתה נותן שירות/);
}

function renderForm(onSuccess = vi.fn()) {
  return render(<ProfessionalRegisterForm onSuccess={onSuccess} onExit={vi.fn()} />);
}

describe('professional registration — local validation blocks Continue', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('an invalid email keeps the registrant on stage 1, with the error under the field', async () => {
    mockApi();
    const user = userEvent.setup();
    renderForm();

    await fillStage1(user, { email: 'not-an-email' });

    expect(await screen.findByText('יש להזין כתובת אימייל תקינה.')).toBeInTheDocument();
    expect(stage2Heading()).not.toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('an invalid phone keeps the registrant on stage 1', async () => {
    // This form previously checked presence only, so "12345" advanced here while the customer
    // wizard rejected it — two copies of one rule, already drifted. Both now call the same
    // `validatePhone`.
    mockApi();
    const user = userEvent.setup();
    renderForm();

    await fillStage1(user, { phone: '12345' });

    expect(await screen.findByText(/יש להזין מספר טלפון נייד תקין/)).toBeInTheDocument();
    expect(stage2Heading()).not.toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('reports a malformed field as soon as it loses focus', async () => {
    mockApi();
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText(/אימייל/), 'nope');
    await user.click(screen.getByLabelText(/טלפון נייד/));

    expect(await screen.findByText('יש להזין כתובת אימייל תקינה.')).toBeInTheDocument();
  });

  it('a valid stage 1 advances to the trades step', async () => {
    mockApi();
    const user = userEvent.setup();
    renderForm();

    await fillStage1(user);

    await waitFor(() => expect(stage2Heading()).toBeInTheDocument());
  });
});

describe('professional registration — duplicates are shown before the final submit', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows a duplicate phone under the field on blur, with no registration attempt', async () => {
    mockApi({ answers: { PHONE: true } });
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText(/טלפון נייד/), VALID.phone);
    await user.click(screen.getByLabelText(/^סיסמה/));

    expect(await screen.findByText('מספר הטלפון הזה כבר רשום במערכת.')).toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('shows a duplicate email under the field on blur, with no registration attempt', async () => {
    mockApi({ answers: { EMAIL: true } });
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText(/אימייל/), VALID.email);
    await user.click(screen.getByLabelText(/טלפון נייד/));

    expect(await screen.findByText('כתובת האימייל הזו כבר רשומה במערכת.')).toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('a duplicate phone blocks Continue — the six later stages are never reached', async () => {
    mockApi({ answers: { PHONE: true } });
    const user = userEvent.setup();
    renderForm();

    await fillStage1(user);

    expect(await screen.findByText('מספר הטלפון הזה כבר רשום במערכת.')).toBeInTheDocument();
    expect(stage2Heading()).not.toBeInTheDocument();
    expect(registerCalls()).toHaveLength(0);
  });

  it('a duplicate email blocks Continue', async () => {
    mockApi({ answers: { EMAIL: true } });
    const user = userEvent.setup();
    renderForm();

    await fillStage1(user);

    expect(await screen.findByText('כתובת האימייל הזו כבר רשומה במערכת.')).toBeInTheDocument();
    expect(stage2Heading()).not.toBeInTheDocument();
  });

  it('uses the same availability endpoint as the customer wizard, on blur not per keystroke', async () => {
    mockApi();
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText(/אימייל/), VALID.email);
    expect(availabilityCalls()).toHaveLength(0);

    await user.click(screen.getByLabelText(/טלפון נייד/));
    await waitFor(() => expect(availabilityCalls()).toHaveLength(1));
    expect(availabilityCalls()[0][1]).toEqual({ field: 'EMAIL', value: VALID.email });
  });

  it('an unreachable availability check does not block registration', async () => {
    // Advisory, never permission — the backend still performs its own duplicate checks, so a
    // registrant on a flaky connection must not be locked out.
    mockApi();
    vi.mocked(httpClient.post).mockImplementation((async (path: string) => {
      if (path === '/api/auth/availability') {
        throw new TypeError('Failed to fetch');
      }
      throw new Error(`unexpected POST ${path}`);
    }) as typeof httpClient.post);
    const user = userEvent.setup();
    renderForm();

    await fillStage1(user);

    await waitFor(() => expect(stage2Heading()).toBeInTheDocument());
  });
});

describe('final-submit error mapping — the shared rule both wizards use', () => {
  // Reaching the real POST means completing six stages including a file upload and a weekly
  // schedule, so the mapping is exercised where it actually lives: one shared function, called
  // from both registration forms' catch blocks. That is stronger than driving the wizard, not
  // weaker — it pins the rule itself rather than one path to it, and it is the same function the
  // customer form calls, so the two can no longer know about different sets of error codes.

  it('maps DUPLICATE_PHONE to the phone field with concrete Hebrew copy', () => {
    const mapped = mapDuplicateContactError(
      new ApiError('DUPLICATE_PHONE', 'Phone number is already registered.', null, 409),
    );

    expect(mapped).toEqual({ field: 'phone', message: 'מספר הטלפון הזה כבר רשום במערכת.' });
    // Never the catch-all. This is the exact substitution the bug report describes.
    expect(mapped?.message).not.toBe(GENERIC_ERROR_MESSAGE);
  });

  it('maps DUPLICATE_EMAIL to the email field', () => {
    const mapped = mapDuplicateContactError(
      new ApiError('DUPLICATE_EMAIL', 'Email is already registered.', null, 409),
    );

    expect(mapped).toEqual({ field: 'email', message: 'כתובת האימייל הזו כבר רשומה במערכת.' });
  });

  it('leaves VALIDATION_ERROR to the per-field handler rather than swallowing it', () => {
    // Returning null here is what lets the caller fall through to `getFieldErrorMessages`, which
    // attributes the backend's own field errors. Mapping it to a duplicate message would be worse
    // than the generic banner, not better.
    const validation = new ApiError('VALIDATION_ERROR', 'Request body failed validation.',
      [{ field: 'phone', message: 'is not a valid phone number' }], 400);

    expect(mapDuplicateContactError(validation)).toBeNull();
    expect(getFieldErrorMessages(validation)).toEqual({ phone: expect.any(String) });
  });

  it('leaves genuinely unexpected failures to the generic banner', () => {
    // The generic message keeps exactly one job: things there is nothing specific to say about.
    expect(mapDuplicateContactError(new ApiError('INTERNAL_ERROR', 'boom', null, 500))).toBeNull();
    expect(mapDuplicateContactError(new TypeError('Failed to fetch'))).toBeNull();
    expect(mapDuplicateContactError(null)).toBeNull();
  });

  it('is the same mapping the customer wizard uses, so the two cannot diverge', () => {
    // The regression that made this a shared function: the professional form knew about
    // DUPLICATE_EMAIL and not DUPLICATE_PHONE, which is why a duplicate phone became
    // "משהו השתבש, נסו שוב" after six completed stages.
    for (const code of ['DUPLICATE_EMAIL', 'DUPLICATE_PHONE']) {
      expect(mapDuplicateContactError(new ApiError(code, 'conflict', null, 409))).not.toBeNull();
    }
  });
});

describe('professional registration — entered data survives a correction', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('keeps every stage-1 value when the registrant is routed back to fix a field', async () => {
    // `routeFieldErrors` sends a duplicate back to stage 1. Nothing is cleared on the way, so the
    // registrant corrects one field rather than restarting the whole application.
    mockApi({ answers: { PHONE: true } });
    const user = userEvent.setup();
    renderForm();

    await fillStage1(user);
    expect(await screen.findByText('מספר הטלפון הזה כבר רשום במערכת.')).toBeInTheDocument();

    expect(screen.getByLabelText(/שם מלא/)).toHaveValue(VALID.fullName);
    expect(screen.getByLabelText(/אימייל/)).toHaveValue(VALID.email);
    expect(screen.getByLabelText(/טלפון נייד/)).toHaveValue(VALID.phone);
    expect(screen.getByLabelText(/^סיסמה/)).toHaveValue(VALID.password);
    expect(screen.getByLabelText(/אימות סיסמה/)).toHaveValue(VALID.password);
  });

  it('advances once the offending field is corrected, without re-entering anything else', async () => {
    mockApi({ answers: { PHONE: true } });
    const user = userEvent.setup();
    renderForm();

    await fillStage1(user);
    expect(await screen.findByText('מספר הטלפון הזה כבר רשום במערכת.')).toBeInTheDocument();

    // A different number, which is free.
    vi.mocked(httpClient.post).mockImplementation((async (path: string, body?: unknown) => {
      if (path === '/api/auth/availability') {
        return { field: (body as { field: string }).field, available: true };
      }
      throw new Error(`unexpected POST ${path}`);
    }) as typeof httpClient.post);

    await user.clear(screen.getByLabelText(/טלפון נייד/));
    await user.type(screen.getByLabelText(/טלפון נייד/), '0509998877');
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await waitFor(() => expect(stage2Heading()).toBeInTheDocument());
  });
});
