import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BookingSummary } from './BookingSummary';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';

/**
 * The Standard booking commit, and specifically the duplicate-Issue bug it used to have.
 *
 * `handleConfirm` makes two writes in order — `createIssue` then `createOrder` — and the component
 * has always documented the recovery as "if the second call fails the customer retries and the
 * first is reused via `issueId`". That was never true: `issueId` is a prop derived from
 * `draft.issueId`, and nothing wrote the newly created id back to the draft. So a failed
 * `createOrder` (a slot raced by another customer, an expired token, a dropped connection) left
 * the customer retrying into a *second* `createIssue`, with the first stranded `OPEN` — an orphan
 * carrying the same description, photos and clarification answers, indistinguishable from a real
 * unbooked request.
 *
 * The fix is `onIssueCreated`, called before `createOrder` is attempted. These tests pin the
 * ordering, because "created the issue" and "told the parent about it" being in the wrong order
 * would look identical on a happy path and fail in exactly the same way on a retry.
 */

const createIssue = vi.hoisted(() => vi.fn());
const createOrder = vi.hoisted(() => vi.fn());

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return { ...actual, createIssue, createOrder };
});

const PROFESSIONAL = {
  professionalId: 7,
  fullName: 'אבי כהן',
  basePrice: 250,
} as never;

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

let onIssueCreated: ReturnType<typeof vi.fn>;
let onConfirmed: ReturnType<typeof vi.fn>;
let onAuthRequired: ReturnType<typeof vi.fn>;

/** Always in the future, so the component's own stale-start pre-flight never short-circuits. */
function futureStart(): string {
  return new Date(Date.now() + 60 * 60 * 1000).toISOString();
}

function renderSummary(options: { issueId?: number; token?: string | null } = {}) {
  const auth = {
    user: { id: 42, role: 'CUSTOMER' },
    token: options.token === undefined ? 'jwt-abc' : options.token,
    isLoading: false,
  } as unknown as AuthContextValue;

  return render(
    <MemoryRouter>
      <AuthContext.Provider value={auth}>
        <BookingSummary
          issueId={options.issueId}
          issueDescription="הברז במטבח מטפטף כל הלילה"
          issueImageKeys={['customers/42/issues/temp/a.jpg']}
          issueClarificationAnswers={[{ question: 'מאיפה?', answer: 'מתחת לכיור' }]}
          onAuthRequired={onAuthRequired}
          onIssueCreated={onIssueCreated}
          categoryId={1}
          professional={PROFESSIONAL}
          bookedStart={futureStart()}
          defaultDurationMinutes={60}
          address={ADDRESS}
          onConfirmed={onConfirmed}
          onTimeUnavailable={vi.fn()}
        />
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

function confirmButton() {
  return screen.getByRole('button', { name: 'אישור הזמנה' });
}

beforeEach(() => {
  onIssueCreated = vi.fn();
  onConfirmed = vi.fn();
  onAuthRequired = vi.fn();
  createIssue.mockReset().mockResolvedValue({ id: 777, categoryId: 1, urgencyType: 'STANDARD' });
  createOrder.mockReset().mockResolvedValue({ id: 999, issueId: 777 });
});

afterEach(() => vi.clearAllMocks());

describe('the happy path', () => {
  it('creates the issue, reports it, then creates the order against it', async () => {
    const user = userEvent.setup();
    renderSummary();

    await user.click(confirmButton());

    await waitFor(() => expect(onConfirmed).toHaveBeenCalled());
    expect(createIssue).toHaveBeenCalledTimes(1);
    expect(onIssueCreated).toHaveBeenCalledWith(777);
    expect(createOrder.mock.calls[0][0].issueId).toBe(777);
  });

  it('reports the new issue BEFORE attempting the order', async () => {
    // The ordering is the fix. Reporting after `createOrder` would leave exactly the gap this bug
    // lived in, and would still pass the assertion above.
    const user = userEvent.setup();
    const sequence: string[] = [];
    onIssueCreated.mockImplementation(() => sequence.push('onIssueCreated'));
    createOrder.mockImplementation(async () => {
      sequence.push('createOrder');
      return { id: 999, issueId: 777 };
    });
    renderSummary();

    await user.click(confirmButton());

    await waitFor(() => expect(sequence).toEqual(['onIssueCreated', 'createOrder']));
  });

  it('does not create an issue when one already exists', async () => {
    const user = userEvent.setup();
    renderSummary({ issueId: 4242 });

    await user.click(confirmButton());

    await waitFor(() => expect(createOrder).toHaveBeenCalled());
    expect(createIssue).not.toHaveBeenCalled();
    expect(onIssueCreated).not.toHaveBeenCalled();
    expect(createOrder.mock.calls[0][0].issueId).toBe(4242);
  });
});

describe('a failed order does not strand an orphan issue', () => {
  it('reports the created issue even though the order call then fails', async () => {
    // The parent persists it to the draft on this callback, which is what makes the retry below
    // reuse the issue rather than create a second one.
    const user = userEvent.setup();
    createOrder.mockRejectedValueOnce(new Error('network'));
    renderSummary();

    await user.click(confirmButton());

    await waitFor(() => expect(onIssueCreated).toHaveBeenCalledWith(777));
    expect(createIssue).toHaveBeenCalledTimes(1);
  });

  it('retrying with the id the parent persisted creates no second issue', async () => {
    const user = userEvent.setup();
    createOrder.mockRejectedValueOnce(new Error('network'));
    const view = renderSummary();

    await user.click(confirmButton());
    await waitFor(() => expect(onIssueCreated).toHaveBeenCalledWith(777));

    // The parent re-renders with `draft.issueId` now set — which is precisely what did NOT happen
    // before the fix, and why the next press created a duplicate.
    view.unmount();
    createOrder.mockResolvedValueOnce({ id: 999, issueId: 777 });
    renderSummary({ issueId: 777 });

    await user.click(confirmButton());

    await waitFor(() => expect(onConfirmed).toHaveBeenCalled());
    expect(createIssue).toHaveBeenCalledTimes(1); // once across both attempts
    expect(createOrder.mock.calls.at(-1)![0].issueId).toBe(777);
  });
});

describe('the guest boundary is unchanged', () => {
  it('creates nothing at all and asks for authentication', async () => {
    const user = userEvent.setup();
    renderSummary({ token: null });

    await user.click(confirmButton());

    expect(onAuthRequired).toHaveBeenCalled();
    expect(createIssue).not.toHaveBeenCalled();
    expect(createOrder).not.toHaveBeenCalled();
    expect(onIssueCreated).not.toHaveBeenCalled();
  });
});
