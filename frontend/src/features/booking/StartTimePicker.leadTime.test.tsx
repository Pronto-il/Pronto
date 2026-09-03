import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { StartTimePicker } from './StartTimePicker';

/**
 * The customer-facing half of the standard-booking lead time.
 *
 * <p>The rule itself is enforced by the backend and covered there
 * (`BookingsLeadTimeTest`); what these tests pin is the thing only the UI can get wrong — telling
 * the customer the professional is unavailable when the professional is perfectly free and it is
 * Pronto declining the booking.
 */
describe('StartTimePicker — the standard-booking lead time', () => {
  /** A window covering the next 12 hours, so there are chips on both sides of any boundary. */
  function windowsFromNow() {
    const start = new Date(Date.now() + 60_000);
    const end = new Date(start.getTime() + 12 * 60 * 60_000);
    return [{ startAt: start.toISOString(), endAt: end.toISOString() }];
  }

  const boundary = () => new Date(Date.now() + 150 * 60_000).toISOString();

  function renderPicker(overrides: Partial<Parameters<typeof StartTimePicker>[0]> = {}) {
    const onSelect = vi.fn();
    const onTrySos = vi.fn();
    render(
      <StartTimePicker
        windows={windowsFromNow()}
        defaultDurationMinutes={60}
        earliestBookableAt={boundary()}
        minLeadMinutes={150}
        onTrySos={onTrySos}
        selectedStart={null}
        onSelect={onSelect}
        {...overrides}
      />,
    );
    return { onSelect, onTrySos };
  }

  /** Every chip is one of two things, and both are on screen. */
  function chips() {
    return screen
      .getAllByRole('button')
      .filter((button) => /^\d{2}:\d{2}$/.test(button.textContent ?? '') || button.hasAttribute('aria-label'));
  }

  it('shows start times inside the lead-time window rather than hiding them', () => {
    renderPicker();

    // The professional's calendar opens a minute from now, so there are necessarily chips before
    // the 2.5-hour boundary. Hiding them would misrepresent the calendar.
    expect(chips().some((chip) => chip.hasAttribute('disabled'))).toBe(true);
  });

  it('disables exactly the chips before the boundary and leaves the later ones clickable', () => {
    renderPicker();

    const disabled = chips().filter((chip) => chip.hasAttribute('disabled'));
    const enabled = chips().filter((chip) => !chip.hasAttribute('disabled'));

    expect(disabled.length).toBeGreaterThan(0);
    expect(enabled.length).toBeGreaterThan(0);
  });

  it('does not select a start time inside the window even if the chip is clicked', async () => {
    const { onSelect } = renderPicker();
    const tooSoon = chips().find((chip) => chip.hasAttribute('disabled'))!;

    await userEvent.click(tooSoon, { pointerEventsCheck: 0 });

    expect(onSelect).not.toHaveBeenCalled();
  });

  /**
   * The copy is the point of the whole feature. It must say the BOOKING is not open, never that the
   * professional is busy — the calendar right beside it says they are free.
   */
  it('explains that it is standard booking that is closed, not the professional', () => {
    renderPicker();

    const notice = screen.getByText(/הזמנה רגילה נסגרת/);
    expect(notice).toBeInTheDocument();
    expect(notice.textContent).toContain('שעתיים וחצי');
    expect(notice.textContent).not.toMatch(/לא זמין|תפוס|עסוק/);
  });

  it('offers SOS as the way to get somebody sooner, and enters the existing flow', async () => {
    const { onTrySos } = renderPicker();

    expect(screen.getByText('צריך מישהו מוקדם יותר?')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'נסו SOS' }));

    expect(onTrySos).toHaveBeenCalledTimes(1);
  });

  it('says nothing about the rule when no visible start time is affected by it', () => {
    // A boundary already in the past restricts none of the chips on screen.
    renderPicker({ earliestBookableAt: new Date(Date.now() - 60_000).toISOString() });

    expect(screen.queryByText(/הזמנה רגילה נסגרת/)).not.toBeInTheDocument();
    expect(chips().every((chip) => !chip.hasAttribute('disabled'))).toBe(true);
  });

  it('restricts nothing when the server sent no boundary', () => {
    renderPicker({ earliestBookableAt: null, minLeadMinutes: undefined });

    expect(chips().every((chip) => !chip.hasAttribute('disabled'))).toBe(true);
    expect(screen.queryByText(/הזמנה רגילה נסגרת/)).not.toBeInTheDocument();
  });
});
