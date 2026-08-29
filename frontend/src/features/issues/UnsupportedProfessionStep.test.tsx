import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { UnsupportedProfessionStep } from './UnsupportedProfessionStep';
import { httpClient } from '../../shared/api/httpClient';
import { CATEGORIES } from '../../shared/api/categories';

const navigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

/**
 * What `GET /api/categories` answers. Deliberately NOT the same values as the frontend's static
 * `CATEGORIES` mirror — one entry here is a category that array does not contain, which is what
 * proves the screen renders the server's answer rather than the local copy.
 */
const SERVER_CATEGORIES = [
  { id: 1, code: 'plumbing', nameHe: 'אינסטלציה', nameEn: 'Plumbing', displayOrder: 1, subServices: [] },
  { id: 2, code: 'electrical', nameHe: 'חשמל', nameEn: 'Electrical', displayOrder: 2, subServices: [] },
  { id: 99, code: 'gardening', nameHe: 'גינון', nameEn: 'Gardening', displayOrder: 9, subServices: [] },
];

function mockCategories(result: unknown = SERVER_CATEGORIES) {
  return vi.spyOn(httpClient, 'get').mockImplementation((async (path: string) => {
    if (path === '/api/categories') {
      return result;
    }
    throw new Error(`unexpected GET ${path}`);
  }) as typeof httpClient.get);
}

function renderStep(detectedProfession: string | null) {
  return render(
    <MemoryRouter>
      <UnsupportedProfessionStep detectedProfession={detectedProfession} />
    </MemoryRouter>,
  );
}

describe('UnsupportedProfessionStep', () => {
  beforeEach(() => {
    mockCategories();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('names the profession Pronto identified', () => {
    // The whole reason `detectedProfession` is the one customer-facing field added to the classify
    // response: "we can't help" is unactionable, "we identified you need a gas technician" tells
    // the customer both that they were understood and what to search for elsewhere.
    renderStep('טכנאי גז');

    expect(screen.getByText(/טכנאי גז/)).toBeInTheDocument();
    expect(screen.getByText(/אנחנו עדיין לא עובדים עם בעלי מקצוע בתחום הזה/)).toBeInTheDocument();
  });

  it('degrades to an honest generic message when no profession was returned', () => {
    // The model may omit the label. Rendering "בעל מקצוע מסוג null" would be worse than saying
    // less, and the customer is no worse off than before the field existed.
    renderStep(null);

    expect(screen.getByText(/בתחום שאנחנו עדיין לא עובדים איתו/)).toBeInTheDocument();
    expect(screen.queryByText(/null/)).not.toBeInTheDocument();
    expect(screen.queryByText(/undefined/)).not.toBeInTheDocument();
  });

  it('does not offer a way to continue into a professional search', () => {
    // The absence IS the feature. A "continue anyway" button would route the customer into the
    // general-handyman search this change exists to prevent — the same forcing, just customer-
    // initiated instead of silent.
    renderStep('מדביר');

    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveTextContent('חזרה לדף הבית');
  });

  it('sends the customer home, replacing history so back does not re-enter the dead end', async () => {
    const user = userEvent.setup();
    renderStep('זגג');

    await user.click(screen.getByRole('button', { name: 'חזרה לדף הבית' }));

    expect(navigate).toHaveBeenCalledWith('/', { replace: true });
  });

  it('does not use the "no professionals available" wording', () => {
    // The distinction this screen exists to draw. "לא נמצאו בעלי מקצוע פנויים" (ProfessionalList's
    // empty state) means "try later"; this means "trying later changes nothing". Sharing wording
    // would put the customer back where they started.
    renderStep('גנן');

    expect(screen.queryByText(/פנויים/)).not.toBeInTheDocument();
    expect(screen.queryByText(/לנסות שוב מאוחר יותר/)).not.toBeInTheDocument();
  });

  // ---- "כרגע אנחנו תומכים ב:" --------------------------------------------------------------

  it('shows what Pronto does support, alongside the rejection', async () => {
    renderStep('טכנאי גז');

    expect(await screen.findByText('כרגע אנחנו תומכים ב:')).toBeInTheDocument();
    expect(await screen.findByText('אינסטלציה')).toBeInTheDocument();
    expect(screen.getByText('חשמל')).toBeInTheDocument();
    // ...without losing the original message.
    expect(screen.getByText(/אנחנו עדיין לא עובדים עם בעלי מקצוע בתחום הזה/)).toBeInTheDocument();
  });

  it('reads the list from GET /api/categories, the platform source of truth', async () => {
    renderStep('טכנאי גז');

    await waitFor(() => expect(httpClient.get).toHaveBeenCalledWith('/api/categories'));
  });

  it('renders the server list rather than the static CATEGORIES mirror', async () => {
    // The assertion that makes "do not duplicate the category list" testable. `גינון` is in the
    // server's answer and is not in `shared/api/categories.ts`; `הנדימן` is in the static mirror
    // and not in this server answer. A screen rendering the local copy fails both halves.
    renderStep('טכנאי גז');

    expect(await screen.findByText('גינון')).toBeInTheDocument();
    expect(CATEGORIES.some((category) => category.nameHe === 'גינון')).toBe(false);
    expect(CATEGORIES.some((category) => category.nameHe === 'הנדימן')).toBe(true);
    expect(screen.queryByText('הנדימן')).not.toBeInTheDocument();
  });

  it('reflects a category the backend added, with no frontend change', async () => {
    // The requirement in one test: categories added or removed later must show up here
    // automatically.
    vi.restoreAllMocks();
    mockCategories([
      ...SERVER_CATEGORIES,
      { id: 100, code: 'pest', nameHe: 'הדברה', nameEn: 'Pest', displayOrder: 10, subServices: [] },
    ]);
    renderStep('טכנאי גז');

    expect(await screen.findByText('הדברה')).toBeInTheDocument();
  });

  it('shows nothing extra while the categories are still loading', async () => {
    // The rejection message is complete on its own. A skeleton would push it around the screen
    // and imply something important is still on its way.
    renderStep('טכנאי גז');

    expect(screen.queryByText('כרגע אנחנו תומכים ב:')).not.toBeInTheDocument();
    expect(screen.getByText(/אנחנו עדיין לא עובדים עם בעלי מקצוע בתחום הזה/)).toBeInTheDocument();

    // Let the in-flight request settle before the test ends, so the state update it causes is
    // attributed to this test rather than surfacing as a stray act() warning in the next one.
    await screen.findByText('כרגע אנחנו תומכים ב:');
  });

  it('degrades silently when the categories request fails', async () => {
    // A customer who has just been told Pronto cannot help them is not helped by an error about
    // a second request they never made. The screen stays whole and simply omits the section.
    vi.restoreAllMocks();
    vi.spyOn(httpClient, 'get').mockRejectedValue(new Error('offline'));
    renderStep('טכנאי גז');

    await waitFor(() => expect(httpClient.get).toHaveBeenCalled());
    expect(screen.queryByText('כרגע אנחנו תומכים ב:')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'חזרה לדף הבית' })).toBeInTheDocument();
  });

  it('omits the section entirely when the catalogue is empty', async () => {
    vi.restoreAllMocks();
    mockCategories([]);
    renderStep('טכנאי גז');

    await waitFor(() => expect(httpClient.get).toHaveBeenCalled());
    expect(screen.queryByText('כרגע אנחנו תומכים ב:')).not.toBeInTheDocument();
  });

  it('keeps the supported categories informational — nothing is clickable', async () => {
    // Tapping one would have to mean "book a plumber", and this customer does not need a plumber.
    // Re-entering the flow under a category picked to get PAST a rejection is how a locksmith
    // gets dispatched to a gas leak.
    renderStep('טכנאי גז');

    await screen.findByText('אינסטלציה');
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });
});
