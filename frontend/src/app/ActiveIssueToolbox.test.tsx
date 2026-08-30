import { act, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActiveIssueToolbox } from './ActiveIssueToolbox';
import { ActiveOrderContext } from '../shared/hooks/activeOrderContext';
import type { ActiveOrderContextValue, ActiveOrderSelection } from '../shared/hooks/activeOrderContext';
import { BookingDraftContext } from '../shared/hooks/bookingDraftContext';
import type { BookingDraftContextValue } from '../shared/hooks/bookingDraftContext';
import type { OrderStatus, OrderSummary } from '../shared/api/bookings';

/**
 * The toolbox as the customer actually sees it across one order's life.
 *
 * `toolboxState.test.ts` covers the resolution rules as pure data; this file covers the two
 * things only a mounted component can show: that the countdown is a *live* read of an absolute
 * timestamp (so it ticks, and a remount does not restart it), and that the arrival celebration
 * fires exactly once per order rather than on every render that happens to occur while the
 * order sits in `ARRIVED`.
 */

const ARRIVAL_AT = '2026-08-28T09:12:00.000Z';
/** 12 minutes before {@link ARRIVAL_AT}. */
const T_MINUS_12 = Date.parse('2026-08-28T09:00:00.000Z');

function order(overrides: Partial<OrderSummary> & { orderStatus: OrderStatus }): OrderSummary {
  return {
    id: 1, issueId: 10, professionalId: 5, professionalName: 'ישראל ישראלי',
    bookedStart: '2026-08-28T09:00:00Z', bookedEnd: null, expectedArrivalAt: null,
    finalPrice: 350, createdAt: '2026-08-27T08:00:00Z', updatedAt: '2026-08-27T08:00:00Z',
    ...overrides,
  };
}

const acknowledgeOrder = vi.fn();

interface RenderOptions {
  /** The current route, so the §2 "hide on the active-order screen" rule can be exercised. */
  path?: string;
  /** A booking draft, for the no-order request state. */
  draft?: BookingDraftContextValue['draft'];
}

function renderToolbox(selection: ActiveOrderSelection | null, options: RenderOptions = {}) {
  const activeOrder: ActiveOrderContextValue = {
    selection,
    hasLiveOrder: selection !== null && selection.state !== 'COMPLETED_UNACKNOWLEDGED',
    acknowledgeOrder,
    refetch: vi.fn(),
  };
  const bookingDraft = {
    draft: options.draft ?? null, updateDraft: vi.fn(), clearDraft: vi.fn(),
  } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={[options.path ?? '/']}>
      <BookingDraftContext.Provider value={bookingDraft}>
        <ActiveOrderContext.Provider value={activeOrder}>
          <ActiveIssueToolbox />
        </ActiveOrderContext.Provider>
      </BookingDraftContext.Provider>
    </MemoryRouter>,
  );
}

/** Whether the floating toolbox is currently rendered (any state has an accessible-name button). */
function toolboxIsVisible(): boolean {
  return screen.queryByRole('button', { name: /.+/ }) !== null;
}

/** The `ToolboxGraphic` root, which is where the celebration class lands. */
function graphicRoot(): HTMLElement {
  const button = screen.getByRole('button', { name: /.+/ });
  return button.firstElementChild as HTMLElement;
}

beforeEach(() => {
  acknowledgeOrder.mockClear();
  window.sessionStorage.clear();
  vi.useFakeTimers();
  vi.setSystemTime(T_MINUS_12);
});

afterEach(() => {
  vi.useRealTimers();
  // Restores any `vi.spyOn(Storage.prototype, ...)` a "survives a sessionStorage that throws"
  // test installed — vitest does not auto-restore spies between tests, and a throwing mock left
  // in place would otherwise make every *later* test's real sessionStorage read/write throw too
  // (masked wherever the code under test already catches it, but not in a test's own direct
  // `sessionStorage.getItem(...)` assertions).
  vi.restoreAllMocks();
});

describe('request state', () => {
  it('renders ההזמנה שלי for an order with nobody on the way yet', () => {
    renderToolbox({ order: order({ orderStatus: 'CONFIRMED' }), state: 'PENDING_CONFIRMED' });

    expect(screen.getByText('ההזמנה שלי')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'ההזמנה שלי' })).toBeInTheDocument();
  });
});

describe('visibility (redesign §2)', () => {
  it('shows the shortcut when an active order exists and the user is on another screen', () => {
    // Active order is /orders/1; the user is browsing Home.
    renderToolbox({ order: order({ id: 1, orderStatus: 'CONFIRMED' }), state: 'PENDING_CONFIRMED' }, { path: '/' });

    expect(toolboxIsVisible()).toBe(true);
  });

  it('hides the shortcut while the user is already on the active order screen', () => {
    renderToolbox(
      { order: order({ id: 1, orderStatus: 'CONFIRMED' }), state: 'PENDING_CONFIRMED' },
      { path: '/orders/1' },
    );

    expect(toolboxIsVisible()).toBe(false);
  });

  it('still shows for a different order screen than the active one', () => {
    // Viewing a past order /orders/999 while the active order is /orders/1 — the shortcut back
    // to the active order is still useful here.
    renderToolbox(
      { order: order({ id: 1, orderStatus: 'CONFIRMED' }), state: 'PENDING_CONFIRMED' },
      { path: '/orders/999' },
    );

    expect(toolboxIsVisible()).toBe(true);
  });

  it('hides when there is no active order and no draft', () => {
    renderToolbox(null);

    expect(toolboxIsVisible()).toBe(false);
  });

  it('hides a draft-backed request while the user is already on the draft screen', () => {
    const draft = { version: 2, ownerId: 2, stage: 'ISSUE_DESCRIBE', issueId: 7 } as unknown as BookingDraftContextValue['draft'];

    // On /issues/new (where resolveDraftRoute sends an ISSUE_DESCRIBE draft) → hidden.
    const onDraftScreen = renderToolbox(null, { draft, path: '/issues/new' });
    expect(toolboxIsVisible()).toBe(false);
    onDraftScreen.unmount();

    // Elsewhere → visible.
    renderToolbox(null, { draft, path: '/' });
    expect(toolboxIsVisible()).toBe(true);
  });
});

describe('hidden throughout booking creation, regardless of state', () => {
  const activeOrder = { order: order({ id: 1, orderStatus: 'CONFIRMED' }), state: 'PENDING_CONFIRMED' } as const;

  /**
   * The Production regression, asserted against the routes the router actually serves.
   *
   * An active order existing is NOT enough to show the widget — route context decides. This block
   * previously covered only `/issues/:id/...`, so when deferred authentication flattened those
   * paths the rule stopped firing and the toolbox reappeared over the whole booking flow while
   * every test stayed green.
   */
  it.each([
    ['/issues/new', 'issue description + AI classification'],
    ['/matching', 'profession matching'],
    ['/booking', 'address / professionals / slot / confirm'],
    ['/sos-booking', 'SOS address, activation and live scan'],
  ])('hides the shortcut on %s (%s) even though an active order exists', (path) => {
    const view = renderToolbox(activeOrder, { path });
    expect(toolboxIsVisible()).toBe(false);
    view.unmount();
  });

  it.each(['/booking/confirm', '/issues/new/review', '/sos-booking/scan'])(
    'stays hidden on the nested route %s, so moving between steps never reveals it',
    (path) => {
      const view = renderToolbox(activeOrder, { path });
      expect(toolboxIsVisible()).toBe(false);
      view.unmount();
    },
  );

  it.each(['/issues/42/matching', '/issues/42/booking', '/issues/42/sos-booking'])(
    'still hides on the legacy path %s, which stale links elsewhere continue to target',
    (path) => {
      const view = renderToolbox(activeOrder, { path });
      expect(toolboxIsVisible()).toBe(false);
      view.unmount();
    },
  );

  it('hides on a deep link / refresh straight onto a booking route, with no first-paint flash', () => {
    // `MemoryRouter` with a single initial entry is exactly a cold load on that URL — no prior
    // navigation, no effect has run. The rule is evaluated during render, so the very first
    // committed frame already has nothing; there is no state to settle into.
    renderToolbox(activeOrder, { path: '/sos-booking' });

    expect(toolboxIsVisible()).toBe(false);
  });

  it('hides the REVIEW state too, which the active-order-screen rule alone never suppresses', () => {
    // REVIEW has no `route`, so only the route-classified booking-flow rule can hide it here.
    renderToolbox({ order: order({ orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' }, { path: '/booking' });

    expect(toolboxIsVisible()).toBe(false);
  });
});

describe('visible again once the customer leaves booking creation', () => {
  const activeOrder = { order: order({ id: 1, orderStatus: 'CONFIRMED' }), state: 'PENDING_CONFIRMED' } as const;

  it.each(['/', '/orders', '/favorites', '/profile'])(
    'shows the shortcut on %s when an active order exists',
    (path) => {
      const view = renderToolbox(activeOrder, { path });
      expect(toolboxIsVisible()).toBe(true);
      view.unmount();
    },
  );

  it('reappears when navigating out of the booking flow with the same order', () => {
    // The pair that matters: identical state, different route, opposite outcome — which is the
    // "existence of an active order alone must not decide visibility" rule stated as a test.
    const inFlow = renderToolbox(activeOrder, { path: '/booking' });
    expect(toolboxIsVisible()).toBe(false);
    inFlow.unmount();

    renderToolbox(activeOrder, { path: '/' });
    expect(toolboxIsVisible()).toBe(true);
  });

  it('a completed, acknowledged order stays hidden on a normal route — unchanged rule', () => {
    // The existing completion behaviour must survive both fixes: no active order, nothing to show,
    // regardless of where the customer is.
    renderToolbox(null, { path: '/' });

    expect(toolboxIsVisible()).toBe(false);
  });
});

describe('ETA state', () => {
  it('derives the figure from the order timestamp rather than a component counter', () => {
    renderToolbox({
      order: order({ orderStatus: 'ON_THE_WAY', expectedArrivalAt: ARRIVAL_AT }),
      state: 'ON_THE_WAY',
    });

    expect(screen.getByText('12 דק׳')).toBeInTheDocument();
    expect(screen.getByText('עד ההגעה')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'בעל המקצוע צפוי להגיע בעוד 12 דקות' }),
    ).toBeInTheDocument();
  });

  it('ticks down as real time passes', () => {
    renderToolbox({
      order: order({ orderStatus: 'ON_THE_WAY', expectedArrivalAt: ARRIVAL_AT }),
      state: 'ON_THE_WAY',
    });

    act(() => {
      vi.advanceTimersByTime(5 * 60_000);
    });

    expect(screen.getByText('7 דק׳')).toBeInTheDocument();
  });

  it('does not restart on a remount — the countdown is a read of an absolute time', () => {
    const first = renderToolbox({
      order: order({ orderStatus: 'ON_THE_WAY', expectedArrivalAt: ARRIVAL_AT }),
      state: 'ON_THE_WAY',
    });
    act(() => {
      vi.advanceTimersByTime(9 * 60_000);
    });
    expect(screen.getByText('3 דק׳')).toBeInTheDocument();

    // Simulates a page navigation/refresh: the component is thrown away and rebuilt.
    first.unmount();
    renderToolbox({
      order: order({ orderStatus: 'ON_THE_WAY', expectedArrivalAt: ARRIVAL_AT }),
      state: 'ON_THE_WAY',
    });

    // Still 3, not back to 12.
    expect(screen.getByText('3 דק׳')).toBeInTheDocument();
  });

  it('never shows a negative figure once the promised minute has passed', () => {
    renderToolbox({
      order: order({ orderStatus: 'ON_THE_WAY', expectedArrivalAt: ARRIVAL_AT }),
      state: 'ON_THE_WAY',
    });

    act(() => {
      vi.advanceTimersByTime(30 * 60_000); // 18 minutes past the promise
    });

    expect(screen.getByText('מגיע/ה עכשיו')).toBeInTheDocument();
    expect(screen.queryByText(/-\d/)).not.toBeInTheDocument();
  });

  it('stays in the ETA state when the timer expires — it does not fake an arrival', () => {
    renderToolbox({
      order: order({ orderStatus: 'ON_THE_WAY', expectedArrivalAt: ARRIVAL_AT }),
      state: 'ON_THE_WAY',
    });

    act(() => {
      vi.advanceTimersByTime(30 * 60_000);
    });

    expect(screen.queryByText('השאר ביקורת')).not.toBeInTheDocument();
    expect(screen.queryByText('הגיע אליך')).not.toBeInTheDocument();
  });
});

describe('arrival celebration', () => {
  it('plays once on entering ARRIVED and settles', () => {
    const view = renderToolbox({ order: order({ orderStatus: 'ARRIVED' }), state: 'ARRIVED' });

    expect(graphicRoot().className).toContain('celebrating');

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(graphicRoot().className).not.toContain('celebrating');

    // A re-render with the same order must not restart it.
    view.rerender(
      <MemoryRouter>
        <BookingDraftContext.Provider
          value={{ draft: null, updateDraft: vi.fn(), clearDraft: vi.fn() } as unknown as BookingDraftContextValue}
        >
          <ActiveOrderContext.Provider
            value={{
              selection: { order: order({ orderStatus: 'ARRIVED' }), state: 'ARRIVED' },
              hasLiveOrder: true,
              acknowledgeOrder,
              refetch: vi.fn(),
            }}
          >
            <ActiveIssueToolbox />
          </ActiveOrderContext.Provider>
        </BookingDraftContext.Provider>
      </MemoryRouter>,
    );
    expect(graphicRoot().className).not.toContain('celebrating');
  });

  it('does not play for the request or ETA states', () => {
    renderToolbox({ order: order({ orderStatus: 'CONFIRMED' }), state: 'PENDING_CONFIRMED' });
    expect(graphicRoot().className).not.toContain('celebrating');
  });

  it('does not replay after a hard refresh within the same browser session', () => {
    // The whole point of latching in sessionStorage: a refresh while the professional is standing
    // at the door must not re-congratulate the customer.
    const first = renderToolbox({ order: order({ orderStatus: 'ARRIVED' }), state: 'ARRIVED' });
    expect(graphicRoot().className).toContain('celebrating');
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    first.unmount();

    // A fresh page load: new component instance, same sessionStorage.
    renderToolbox({ order: order({ orderStatus: 'ARRIVED' }), state: 'ARRIVED' });

    expect(graphicRoot().className).not.toContain('celebrating');
  });

  it('records the latch under a key scoped to the order id AND the celebration kind', () => {
    renderToolbox({ order: order({ id: 77, orderStatus: 'ARRIVED' }), state: 'ARRIVED' });

    expect(window.sessionStorage.getItem('pronto.toolbox.celebrated.77.ARRIVED')).not.toBeNull();
    expect(window.sessionStorage.getItem('pronto.toolbox.celebrated.1.ARRIVED')).toBeNull();
    // The completion kind is a separate key — this order's arrival celebration must not also
    // mark its (not-yet-reached) completion celebration as already played.
    expect(window.sessionStorage.getItem('pronto.toolbox.celebrated.77.COMPLETED')).toBeNull();
  });

  it('still celebrates a different order later in the same session', () => {
    const first = renderToolbox({ order: order({ id: 1, orderStatus: 'ARRIVED' }), state: 'ARRIVED' });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    first.unmount();

    renderToolbox({ order: order({ id: 2, orderStatus: 'ARRIVED' }), state: 'ARRIVED' });

    expect(graphicRoot().className).toContain('celebrating');
  });

  it('plays again in a new browser session', () => {
    const first = renderToolbox({ order: order({ orderStatus: 'ARRIVED' }), state: 'ARRIVED' });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    first.unmount();

    window.sessionStorage.clear(); // what closing and reopening the tab does

    renderToolbox({ order: order({ orderStatus: 'ARRIVED' }), state: 'ARRIVED' });

    expect(graphicRoot().className).toContain('celebrating');
  });

  it('survives a sessionStorage that throws rather than taking the toolbox down', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });

    expect(() =>
      renderToolbox({ order: order({ orderStatus: 'ARRIVED' }), state: 'ARRIVED' }),
    ).not.toThrow();
    expect(screen.getByText('הגיע אליך')).toBeInTheDocument();
  });
});

/**
 * The completion celebration (final active-order toolbox completion animation, 2026-08-28) — an
 * independent one-shot celebration from arrival's (own `sessionStorage` key, own CSS class), that
 * plays when an order transitions into `COMPLETED` (the `REVIEW` toolbox state) and then settles
 * into the existing "השאר ביקورת" review prompt exactly as before — it does NOT hide the toolbox,
 * per the product decision made while scoping this feature: the toolbox's only route to
 * `ReviewPromptModal` is a tap on it, so removing it the moment the celebration ends would remove
 * that entry point entirely.
 */
describe('completion celebration', () => {
  it('ACTIVE -> COMPLETED triggers the animation once', () => {
    // A live re-render on the *same instance*, ON_THE_WAY -> COMPLETED_UNACKNOWLEDGED — what a
    // real poll tick that observes the order finishing looks like. Distinct from arrival: this
    // order never passed through ARRIVED, so only the completion celebration should fire.
    const view = render(
      <MemoryRouter>
        <BookingDraftContext.Provider
          value={{ draft: null, updateDraft: vi.fn(), clearDraft: vi.fn() } as unknown as BookingDraftContextValue}
        >
          <ActiveOrderContext.Provider
            value={{
              selection: { order: order({ orderStatus: 'ON_THE_WAY' }), state: 'ON_THE_WAY' },
              hasLiveOrder: true,
              acknowledgeOrder,
              refetch: vi.fn(),
            }}
          >
            <ActiveIssueToolbox />
          </ActiveOrderContext.Provider>
        </BookingDraftContext.Provider>
      </MemoryRouter>,
    );
    expect(graphicRoot().className).not.toContain('celebrating');

    view.rerender(
      <MemoryRouter>
        <BookingDraftContext.Provider
          value={{ draft: null, updateDraft: vi.fn(), clearDraft: vi.fn() } as unknown as BookingDraftContextValue}
        >
          <ActiveOrderContext.Provider
            value={{
              selection: { order: order({ orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' },
              hasLiveOrder: false,
              acknowledgeOrder,
              refetch: vi.fn(),
            }}
          >
            <ActiveIssueToolbox />
          </ActiveOrderContext.Provider>
        </BookingDraftContext.Provider>
      </MemoryRouter>,
    );

    expect(graphicRoot().className).toContain('celebratingCompletion');
    expect(graphicRoot().className).not.toContain('celebratingArrival');
  });

  it('settles after the animation into the existing review prompt — the toolbox is not hidden', () => {
    renderToolbox({ order: order({ orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' });
    expect(graphicRoot().className).toContain('celebratingCompletion');

    act(() => {
      // Longer than the completion window (1300ms) but short of anything else expiring.
      vi.advanceTimersByTime(1400);
    });

    expect(graphicRoot().className).not.toContain('celebrating');
    // Settled state: still visible, still the review prompt — not hidden.
    expect(toolboxIsVisible()).toBe(true);
    expect(screen.getByText('השאר ביקורת')).toBeInTheDocument();
  });

  it('does not replay when COMPLETED is already the state on mount (e.g. a fresh page load)', () => {
    // The whole point of latching in sessionStorage: reloading the page while looking at "the job
    // is done" must not re-play the celebration.
    const first = renderToolbox({ order: order({ orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' });
    expect(graphicRoot().className).toContain('celebratingCompletion');
    act(() => {
      vi.advanceTimersByTime(1400);
    });
    first.unmount();

    // A fresh page load: new component instance, same sessionStorage, same already-COMPLETED order.
    renderToolbox({ order: order({ orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' });

    expect(graphicRoot().className).not.toContain('celebrating');
    expect(toolboxIsVisible()).toBe(true); // still shows the review prompt, just without the animation
  });

  it('does not retrigger on repeated polling of the same COMPLETED order', () => {
    // Simulates several poll ticks that all still resolve to the same completed, unacknowledged
    // order — each tick hands down a *new* object (a fresh poll response), same id/state.
    const view = renderToolbox({ order: order({ id: 1, orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' });
    expect(graphicRoot().className).toContain('celebratingCompletion');
    act(() => {
      vi.advanceTimersByTime(1400);
    });
    expect(graphicRoot().className).not.toContain('celebrating');

    for (let tick = 0; tick < 3; tick++) {
      view.rerender(
        <MemoryRouter>
          <BookingDraftContext.Provider
            value={{ draft: null, updateDraft: vi.fn(), clearDraft: vi.fn() } as unknown as BookingDraftContextValue}
          >
            <ActiveOrderContext.Provider
              value={{
                selection: { order: order({ id: 1, orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' },
                hasLiveOrder: false,
                acknowledgeOrder,
                refetch: vi.fn(),
              }}
            >
              <ActiveIssueToolbox />
            </ActiveOrderContext.Provider>
          </BookingDraftContext.Provider>
        </MemoryRouter>,
      );
      expect(graphicRoot().className).not.toContain('celebrating');
    }
  });

  it('fires independently of an already-played arrival celebration for the same order', () => {
    // Realistic full lifecycle: ARRIVED celebrates, THEN — later — COMPLETED celebrates too. The
    // second is not suppressed by the first having already used the (order-only) latch a prior
    // design used; each kind has its own.
    const view = render(
      <MemoryRouter>
        <BookingDraftContext.Provider
          value={{ draft: null, updateDraft: vi.fn(), clearDraft: vi.fn() } as unknown as BookingDraftContextValue}
        >
          <ActiveOrderContext.Provider
            value={{
              selection: { order: order({ id: 5, orderStatus: 'ARRIVED' }), state: 'ARRIVED' },
              hasLiveOrder: true,
              acknowledgeOrder,
              refetch: vi.fn(),
            }}
          >
            <ActiveIssueToolbox />
          </ActiveOrderContext.Provider>
        </BookingDraftContext.Provider>
      </MemoryRouter>,
    );
    expect(graphicRoot().className).toContain('celebratingArrival');
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(graphicRoot().className).not.toContain('celebrating');

    view.rerender(
      <MemoryRouter>
        <BookingDraftContext.Provider
          value={{ draft: null, updateDraft: vi.fn(), clearDraft: vi.fn() } as unknown as BookingDraftContextValue}
        >
          <ActiveOrderContext.Provider
            value={{
              selection: { order: order({ id: 5, orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' },
              hasLiveOrder: false,
              acknowledgeOrder,
              refetch: vi.fn(),
            }}
          >
            <ActiveIssueToolbox />
          </ActiveOrderContext.Provider>
        </BookingDraftContext.Provider>
      </MemoryRouter>,
    );

    expect(graphicRoot().className).toContain('celebratingCompletion');
  });

  it('records the completion latch under its own key, independent of the arrival key', () => {
    renderToolbox({ order: order({ id: 42, orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' });

    expect(window.sessionStorage.getItem('pronto.toolbox.celebrated.42.COMPLETED')).not.toBeNull();
    expect(window.sessionStorage.getItem('pronto.toolbox.celebrated.42.ARRIVED')).toBeNull();
  });

  it('is JS-behavior-identical whether or not the browser prefers reduced motion', () => {
    // The reduced-motion treatment (skip the lid/sparkle motion, a brief opacity fade instead) is
    // implemented entirely in CSS (`ToolboxGraphic.module.css`'s own `@media` block) — this
    // component's trigger/latch/settle logic does not branch on it at all, so the one-shot
    // behavior above is identical either way. This pins that contract: the `celebration` class is
    // still applied (and still clears on schedule) even when `matchMedia` reports reduced motion.
    vi.spyOn(window, 'matchMedia').mockReturnValue({
      matches: true,
      media: '(prefers-reduced-motion: reduce)',
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    } as unknown as MediaQueryList);

    renderToolbox({ order: order({ orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' });
    expect(graphicRoot().className).toContain('celebratingCompletion');

    act(() => {
      vi.advanceTimersByTime(1400);
    });
    expect(graphicRoot().className).not.toContain('celebrating');
    expect(toolboxIsVisible()).toBe(true);
  });

  it('survives a sessionStorage that throws rather than taking the toolbox down', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });

    expect(() =>
      renderToolbox({ order: order({ orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' }),
    ).not.toThrow();
    expect(screen.getByText('השאר ביקורת')).toBeInTheDocument();
  });
});

describe('review state', () => {
  it('renders השאר ביקורת with its own accessible name', () => {
    renderToolbox({ order: order({ orderStatus: 'COMPLETED' }), state: 'COMPLETED_UNACKNOWLEDGED' });

    expect(screen.getByText('השאר ביקורת')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'השאר ביקורת על הביקור' })).toBeInTheDocument();
  });

  it('disappears entirely once the existing selection rule stops offering the order', () => {
    // `acknowledgeOrder` feeds `selectActiveOrder`, which then yields null — reproduced here as
    // the null selection the provider would hand down on the next render.
    renderToolbox(null);

    expect(screen.queryByRole('button', { name: /התקלה|הזמנה|ביקורת|הגיע/ })).not.toBeInTheDocument();
  });
});
