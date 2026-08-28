import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import AppLayout from './AppLayout';
import { AuthContext } from '../shared/hooks/authContext';
import type { AuthContextValue } from '../shared/hooks/authContext';
import { ActiveOrderContext } from '../shared/hooks/activeOrderContext';
import type { ActiveOrderContextValue } from '../shared/hooks/activeOrderContext';
import { BookingDraftContext } from '../shared/hooks/bookingDraftContext';
import type { BookingDraftContextValue } from '../shared/hooks/bookingDraftContext';
import type { UserMeResponse } from '../shared/api/users';

/**
 * The application shell, after the mobile nav redesign. The one behaviour asserted here is §3's:
 * the oversized "יש לך תקלה? בוא נטפל בזה" header banner is gone — genuinely not rendered, not
 * merely hidden by a media query. The new-issue entry now lives in `BottomNav`'s centre action
 * (covered by `BottomNav.test.tsx`) and, on desktop, in a compact "תקלה חדשה" nav link.
 *
 * `NotificationBell` is stubbed: it owns its own polling/network and is irrelevant to what this
 * file checks.
 */
vi.mock('../features/notifications', () => ({ NotificationBell: () => null }));

const customer = {
  id: 2,
  role: 'CUSTOMER',
  fullName: 'QA',
  email: 'q@e.com',
} as unknown as UserMeResponse;

function renderShell() {
  const auth = {
    token: 't',
    user: customer,
    isLoading: false,
    establishSession: vi.fn(),
    logout: vi.fn(),
    refreshUser: vi.fn(),
  } as unknown as AuthContextValue;
  const activeOrder = {
    selection: null,
    hasLiveOrder: false,
    acknowledgeOrder: vi.fn(),
    refetch: vi.fn(),
  } as unknown as ActiveOrderContextValue;
  const bookingDraft = {
    draft: null,
    updateDraft: vi.fn(),
    clearDraft: vi.fn(),
  } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter>
      <AuthContext.Provider value={auth}>
        <ActiveOrderContext.Provider value={activeOrder}>
          <BookingDraftContext.Provider value={bookingDraft}>
            <AppLayout />
          </BookingDraftContext.Provider>
        </ActiveOrderContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

describe('mobile nav redesign §3 — the oversized header CTA is removed', () => {
  it('no longer renders the "יש לך תקלה? בוא נטפל בזה" banner', () => {
    renderShell();

    expect(screen.queryByText('יש לך תקלה? בוא נטפל בזה')).not.toBeInTheDocument();
  });

  it('keeps a compact desktop new-issue entry pointing at the existing route', () => {
    renderShell();

    // Rendered in `.desktopOnlyNav` (CSS-hidden below 640px, present in the DOM). Same
    // destination the old banner used, so there is one flow.
    const link = screen.getByRole('link', { name: 'תקלה חדשה' });
    expect(link).toHaveAttribute('href', '/issues/new');
  });
});
