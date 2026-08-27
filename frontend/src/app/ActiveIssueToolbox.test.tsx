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

function renderToolbox(selection: ActiveOrderSelection | null) {
  const activeOrder: ActiveOrderContextValue = {
    selection,
    hasLiveOrder: selection !== null && selection.state !== 'COMPLETED_UNACKNOWLEDGED',
    acknowledgeOrder,
    refetch: vi.fn(),
  };
  const bookingDraft = {
    draft: null, updateDraft: vi.fn(), clearDraft: vi.fn(),
  } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter>
      <BookingDraftContext.Provider value={bookingDraft}>
        <ActiveOrderContext.Provider value={activeOrder}>
          <ActiveIssueToolbox />
        </ActiveOrderContext.Provider>
      </BookingDraftContext.Provider>
    </MemoryRouter>,
  );
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
});

describe('request state', () => {
  it('renders ההזמנה שלי for an order with nobody on the way yet', () => {
    renderToolbox({ order: order({ orderStatus: 'CONFIRMED' }), state: 'PENDING_CONFIRMED' });

    expect(screen.getByText('ההזמנה שלי')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'ההזמנה שלי' })).toBeInTheDocument();
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

  it('records the latch under a key scoped to the order id', () => {
    renderToolbox({ order: order({ id: 77, orderStatus: 'ARRIVED' }), state: 'ARRIVED' });

    expect(window.sessionStorage.getItem('pronto.toolbox.celebrated.77')).not.toBeNull();
    expect(window.sessionStorage.getItem('pronto.toolbox.celebrated.1')).toBeNull();
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
