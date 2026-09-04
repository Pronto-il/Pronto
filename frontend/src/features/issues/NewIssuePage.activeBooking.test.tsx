import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import NewIssuePage from './NewIssuePage';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraft, BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';

/**
 * **The way back to a booking already in progress — for a guest as much as for anybody.**
 *
 * The banner warning that starting a new fault will cancel an active booking used to offer only
 * "הבנתי". For a signed-in customer that was merely unhelpful: the header's draft indicator and the
 * floating toolbox both link to the draft. For a **guest** it was a dead end — both of those
 * surfaces are rendered only for a signed-in CUSTOMER, so a visitor who wandered back into
 * "יש לי תקלה" had no route to their own booking at all and could only destroy it.
 *
 * <p>The action needs no account and must not ask for one: all four booking routes are public
 * (`router.tsx`), the draft is the state, and a guest's own draft is theirs to continue.
 */

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return { ...actual, classifyIssue: vi.fn(), getPresignedImageUrls: vi.fn() };
});

/** A booking in progress — past the issue stages, which is what the banner keys on. */
function activeBookingDraft(overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: null,
    stage: 'PROFESSIONAL_SELECTION',
    urgencyType: 'STANDARD',
    description: 'נזילה מתחת לכיור',
    photos: [{ imageKey: 'guests/abc/1.jpg' }],
    categoryId: 1,
    updatedAt: new Date().toISOString(),
    ...overrides,
  } as BookingDraft;
}

let updateDraft: ReturnType<typeof vi.fn>;

function renderPage(options: { draft: BookingDraft; signedIn?: boolean }) {
  updateDraft = vi.fn();
  const auth = {
    user: options.signedIn ? { id: 42, role: 'CUSTOMER' } : null,
    token: options.signedIn ? 'jwt-abc' : null,
    isLoading: false,
  } as unknown as AuthContextValue;
  const draftValue = {
    draft: options.draft,
    updateDraft,
    clearDraft: vi.fn(),
  } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={['/issues/new']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={draftValue}>
          <Routes>
            <Route path="/issues/new" element={<NewIssuePage />} />
            <Route path="/booking" element={<div>standard-booking-screen</div>} />
            <Route path="/sos-booking" element={<div>sos-booking-screen</div>} />
            <Route path="/login" element={<div>login-page</div>} />
          </Routes>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  updateDraft = vi.fn();
});

describe('the active-booking banner offers a way back', () => {
  it('shows "להזמנה הקיימת" alongside the warning', () => {
    renderPage({ draft: activeBookingDraft(), signedIn: true });

    expect(screen.getByText(/יש לך בקשה פעילה בתהליך הזמנה/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'להזמנה הקיימת' })).toBeInTheDocument();
    // The dismissal it always had is still there, and still just a dismissal.
    expect(screen.getByRole('button', { name: 'הבנתי' })).toBeInTheDocument();
  });

  it('takes an authenticated customer to their booking', async () => {
    const user = userEvent.setup();
    renderPage({ draft: activeBookingDraft(), signedIn: true });

    await user.click(screen.getByRole('button', { name: 'להזמנה הקיימת' }));

    expect(screen.getByText('standard-booking-screen')).toBeInTheDocument();
  });

  it('takes a guest to the same booking, with no account and no gate', async () => {
    const user = userEvent.setup();
    renderPage({ draft: activeBookingDraft() });

    await user.click(screen.getByRole('button', { name: 'להזמנה הקיימת' }));

    expect(screen.getByText('standard-booking-screen')).toBeInTheDocument();
    // Not to login, and not to the deferred-auth modal either: continuing your own draft is not a
    // write, and nothing here needs an account.
    expect(screen.queryByText('login-page')).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('follows the draft into the SOS flow when that is the one in progress', async () => {
    const user = userEvent.setup();
    renderPage({ draft: activeBookingDraft({ urgencyType: 'SOS' }) });

    await user.click(screen.getByRole('button', { name: 'להזמנה הקיימת' }));

    expect(screen.getByText('sos-booking-screen')).toBeInTheDocument();
  });

  it('leaves the draft exactly as it found it', async () => {
    const user = userEvent.setup();
    renderPage({ draft: activeBookingDraft() });

    await user.click(screen.getByRole('button', { name: 'להזמנה הקיימת' }));

    // Navigating to a booking is not a step transition — the screen it lands on owns that. If this
    // wrote to the draft it would be rewriting the very thing the customer asked to go back to.
    expect(updateDraft).not.toHaveBeenCalled();
  });
});
