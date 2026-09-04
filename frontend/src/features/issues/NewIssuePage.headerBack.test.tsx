import { useContext } from 'react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import NewIssuePage from './NewIssuePage';
import { HeaderBackProvider, HeaderBackContext } from '../../shared/hooks';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';

/**
 * "יש לי תקלה" hoists its back control into the app bar instead of rendering a row of its own
 * under the header. Two things have to stay true for that to be a move rather than a loss: the
 * page must publish exactly one back action (not keep a second copy below the header), and that
 * action must still do what `PageHeader`'s `onBack` did — from step 1, leave the flow.
 *
 * `AppLayout` is stood in for by `HeaderSlot` below, which renders whatever the page registered.
 * The bar's own markup and placement are covered in `app/AppLayout.test.tsx`.
 */

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return { ...actual, classifyIssue: vi.fn(), getPresignedImageUrls: vi.fn() };
});

function HeaderSlot() {
  const { action } = useContext(HeaderBackContext);
  return (
    <div data-testid="header-slot">
      {action && (
        <button type="button" onClick={action.onBack}>
          {action.label}
        </button>
      )}
    </div>
  );
}

function renderFlow() {
  const draftValue = {
    draft: null,
    updateDraft: vi.fn(),
    clearDraft: vi.fn(),
  } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={['/issues/new']}>
      <BookingDraftContext.Provider value={draftValue}>
        <HeaderBackProvider>
          <HeaderSlot />
          <Routes>
            <Route path="/issues/new" element={<NewIssuePage />} />
            <Route path="/" element={<p>home-screen</p>} />
          </Routes>
        </HeaderBackProvider>
      </BookingDraftContext.Provider>
    </MemoryRouter>,
  );
}

describe('the back control is published to the header, not rendered under it', () => {
  it('registers exactly one "חזרה" control, and it lives in the header slot', () => {
    renderFlow();

    const backControls = screen.getAllByRole('button', { name: 'חזרה' });
    expect(backControls).toHaveLength(1);
    expect(within(screen.getByTestId('header-slot')).getByRole('button', { name: 'חזרה' })).toBe(
      backControls[0],
    );
    // The step title and its progress bar stay on the page, unchanged.
    expect(screen.getByRole('heading', { name: 'יש לי תקלה' })).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toBeInTheDocument();
  });

  it('still leaves the flow when used on step 1', async () => {
    renderFlow();

    await userEvent.click(screen.getByRole('button', { name: 'חזרה' }));

    expect(screen.getByText('home-screen')).toBeInTheDocument();
  });
});
