import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BookingDraftProvider } from './BookingDraftProvider';
import { useBookingDraft } from './useBookingDraft';

/**
 * The draft is what survives the trip through login, so these tests are really about one question:
 * **does the customer get their booking back?**
 *
 * Before deferred authentication the answer was no, twice over — `updateDraft` returned early when
 * nobody was signed in, so a guest's work was never written down at all, and the leakage guard
 * cleared the draft whenever `user` was absent, so anything that did get written was wiped on the
 * next render. Both were correct for a product where every screen required an account, and both
 * became bugs the moment the journey opened up.
 *
 * The adoption rule is the subtle one. "No session" and "a different session" look identical to a
 * single `!user || user.id !== ownerId` condition, and they call for opposite behaviour: one is the
 * customer coming back from the login screen we sent them to, the other is a stranger on a shared
 * browser.
 */

const auth = vi.hoisted(() => ({ user: null as { id: number } | null, isLoading: false }));

vi.mock('./useAuth', () => ({ useAuth: () => auth }));

const DRAFT_STORAGE_KEY = 'pronto_booking_draft';

function Harness() {
  const { draft, updateDraft } = useBookingDraft();
  return (
    <div>
      <button type="button" onClick={() => updateDraft({ description: 'המקרר לא מקרר' })}>
        write
      </button>
      <output data-testid="owner">{draft ? String(draft.ownerId) : 'no-draft'}</output>
      <output data-testid="description">{draft?.description ?? ''}</output>
    </div>
  );
}

function renderProvider() {
  return render(
    <BookingDraftProvider>
      <Harness />
    </BookingDraftProvider>,
  );
}

function storedDraft() {
  const raw = localStorage.getItem(DRAFT_STORAGE_KEY);
  return raw ? JSON.parse(raw) : null;
}

function seedDraft(ownerId: number | null) {
  localStorage.setItem(
    DRAFT_STORAGE_KEY,
    JSON.stringify({
      version: 2,
      ownerId,
      stage: 'BOOKING_CONFIRM',
      urgencyType: 'STANDARD',
      description: 'המקרר לא מקרר',
      photos: [],
      professionalId: 52,
      bookedStart: '2026-09-01T09:00:00Z',
      updatedAt: new Date().toISOString(),
    }),
  );
}

beforeEach(() => {
  localStorage.clear();
  auth.user = null;
  auth.isLoading = false;
});

describe('guest drafts', () => {
  it('a guest can write a draft', async () => {
    // The early `if (!user) return` this replaces is why nothing a guest entered ever survived.
    const user = userEvent.setup();
    renderProvider();

    await user.click(screen.getByRole('button', { name: 'write' }));

    expect(screen.getByTestId('description')).toHaveTextContent('המקרר לא מקרר');
    expect(storedDraft()?.ownerId).toBeNull();
  });

  it('a guest draft is not wiped while nobody is signed in', async () => {
    seedDraft(null);

    renderProvider();

    // The old guard cleared on every render whenever `user` was absent — which, for a guest, is
    // always. Nothing to wait for here except the effect having run.
    await waitFor(() => expect(screen.getByTestId('owner')).toHaveTextContent('null'));
    expect(storedDraft()).not.toBeNull();
  });
});

describe('adoption on sign-in', () => {
  it('a guest draft is adopted by whoever signs in', async () => {
    // The whole point. This guest IS this user — they just came back from the login screen the
    // book button sent them to.
    seedDraft(null);
    auth.user = { id: 42 };

    renderProvider();

    await waitFor(() => expect(screen.getByTestId('owner')).toHaveTextContent('42'));
    expect(storedDraft()?.ownerId).toBe(42);
    expect(screen.getByTestId('description')).toHaveTextContent('המקרר לא מקרר');
  });

  it('adoption preserves the booking selection, not just the description', async () => {
    seedDraft(null);
    auth.user = { id: 42 };

    renderProvider();

    await waitFor(() => expect(storedDraft()?.ownerId).toBe(42));
    expect(storedDraft()?.professionalId).toBe(52);
    expect(storedDraft()?.bookedStart).toBe('2026-09-01T09:00:00Z');
    expect(storedDraft()?.stage).toBe('BOOKING_CONFIRM');
  });
});

describe('the leakage guard still holds', () => {
  it("another account's draft is discarded, not adopted", async () => {
    // The original §4.6 rule, and the reason adoption had to be a separate branch rather than a
    // loosening of this one.
    seedDraft(7);
    auth.user = { id: 42 };

    renderProvider();

    await waitFor(() => expect(screen.getByTestId('owner')).toHaveTextContent('no-draft'));
    expect(storedDraft()).toBeNull();
  });

  it('an owned draft is discarded on logout', async () => {
    seedDraft(42);
    auth.user = null;

    renderProvider();

    await waitFor(() => expect(screen.getByTestId('owner')).toHaveTextContent('no-draft'));
    expect(storedDraft()).toBeNull();
  });

  it('nothing is discarded while auth is still rehydrating', async () => {
    // A legitimate draft must not be wiped before `user` has had a chance to resolve.
    seedDraft(42);
    auth.user = null;
    auth.isLoading = true;

    renderProvider();

    await waitFor(() => expect(screen.getByTestId('owner')).toHaveTextContent('42'));
    expect(storedDraft()).not.toBeNull();
  });
});
