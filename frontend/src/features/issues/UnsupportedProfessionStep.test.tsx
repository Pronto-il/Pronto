import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { UnsupportedProfessionStep } from './UnsupportedProfessionStep';

const navigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

function renderStep(detectedProfession: string | null) {
  return render(
    <MemoryRouter>
      <UnsupportedProfessionStep detectedProfession={detectedProfession} />
    </MemoryRouter>,
  );
}

describe('UnsupportedProfessionStep', () => {
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
});
