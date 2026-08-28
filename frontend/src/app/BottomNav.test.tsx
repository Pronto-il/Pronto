import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { BottomNav } from './BottomNav';

/**
 * The mobile bottom navigation, after the redesign that added the central "start a new issue"
 * action (§4). The four tab destinations are unchanged; what is new and worth pinning down is
 * that the centre action exists, is a real link to the *existing* new-issue route (§5/§6, one
 * flow — not a second one), and sits between הזמנות and מועדפים in RTL reading order.
 *
 * The bar is `display: none` above 640px (it is mobile-only), and jsdom's default viewport is
 * ~1024px, so its links count as "hidden" for the accessibility tree. `hidden: true` on the role
 * queries is therefore correct here — these are real, navigable links that are simply
 * CSS-hidden at desktop widths, which is exactly the responsive behaviour intended.
 */

function renderNav(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <BottomNav />
    </MemoryRouter>,
  );
}

function link(name: string) {
  return screen.getByRole('link', { name, hidden: true });
}

describe('the four tab destinations still work (§7)', () => {
  it('renders בית / הזמנות / מועדפים / פרופיל pointing at their routes', () => {
    renderNav();

    const expected: Record<string, string> = {
      בית: '/',
      הזמנות: '/orders',
      מועדפים: '/favorites',
      פרופיל: '/profile',
    };
    for (const [label, href] of Object.entries(expected)) {
      expect(link(label)).toHaveAttribute('href', href);
    }
  });

  it('marks the current tab active via aria-current', () => {
    renderNav('/orders');

    expect(link('הזמנות')).toHaveAttribute('aria-current', 'page');
    expect(link('בית')).not.toHaveAttribute('aria-current');
  });
});

describe('central new-issue action (§4/§5/§6)', () => {
  it('renders the circular centre action with the compact label', () => {
    renderNav();

    const cta = link('תקלה חדשה');
    expect(cta).toBeInTheDocument();
    expect(cta).toHaveTextContent('תקלה חדשה');
  });

  it('links to the existing new-issue route, not a new one', () => {
    renderNav();

    expect(link('תקלה חדשה')).toHaveAttribute('href', '/issues/new');
  });

  it('is not a NavLink active-state target — "start new" is not a place you can be at', () => {
    renderNav('/issues/new');

    // Even while on /issues/new the centre action carries no aria-current (it is a plain Link).
    expect(link('תקלה חדשה')).not.toHaveAttribute('aria-current');
  });

  it('sits between הזמנות and מועדפים in reading order', () => {
    const { container } = renderNav();

    const labels = within(container)
      .getAllByRole('link', { hidden: true })
      .map((el) => el.textContent?.trim())
      .filter(Boolean);

    expect(labels).toEqual(['בית', 'הזמנות', 'תקלה חדשה', 'מועדפים', 'פרופיל']);
  });
});
