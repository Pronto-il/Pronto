import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ClarifyQuestionsStep } from './ClarifyQuestionsStep';
import type { ClassifyIssueResponse, ClassifyQuestion } from '../../shared/api';

/**
 * The customer's side of the clarification loop (Production MS3).
 *
 * <p>What these tests exist to pin down is that this component stays a <b>renderer of one
 * round</b>. The question budget is enforced by the backend — the loop ends because
 * `classifyIssue` returns `CLASSIFIED`, never because the UI counted to two — and the failure
 * mode worth guarding against is a future change that makes the frontend believe it owns that
 * rule. So the tests drive the component the way the backend actually drives it: a round at a
 * time, with the accumulated conversation resubmitted each time.
 */

vi.mock('../../shared/api', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api')>('../../shared/api');
  return { ...actual, classifyIssue: vi.fn() };
});

const { classifyIssue } = await import('../../shared/api');
const classifyIssueMock = vi.mocked(classifyIssue);

function question(overrides: Partial<ClassifyQuestion> = {}): ClassifyQuestion {
  return {
    id: 'q1',
    question: 'מאיפה מגיעים המים?',
    options: ['מהמזגן', 'מהכיור', 'לא בטוח'],
    ...overrides,
  };
}

function classified(): ClassifyIssueResponse {
  return {
    status: 'CLASSIFIED',
    detectedProfession: 'אינסטלטור',
    suggestedCategoryId: 1,
    suggestedCategoryCode: 'plumbing',
    questions: [],
  };
}

function asking(next: ClassifyQuestion): ClassifyIssueResponse {
  return {
    status: 'QUESTIONS',
    detectedProfession: null,
    suggestedCategoryId: null,
    suggestedCategoryCode: null,
    questions: [next],
  };
}

function renderStep(props: Partial<Parameters<typeof ClarifyQuestionsStep>[0]> = {}) {
  const onClassified = vi.fn();
  render(
    <ClarifyQuestionsStep
      description="יש מים על הרצפה במטבח"
      photos={[]}
      questions={[question()]}
      previousAnswers={[]}
      onClassified={onClassified}
      onAnalyzingChange={vi.fn()}
      {...props}
    />,
  );
  return { onClassified };
}

beforeEach(() => {
  classifyIssueMock.mockReset();
});

describe('ClarifyQuestionsStep', () => {
  it('renders exactly one question with its answer options as buttons', () => {
    renderStep();

    expect(screen.getByText('מאיפה מגיעים המים?')).toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(3);
    expect(screen.getByRole('radio', { name: 'מהמזגן' })).toBeInTheDocument();
  });

  /** Roadmap §11: the customer must never be forced to guess. */
  it('offers the "not sure" option when the backend supplies one', () => {
    renderStep();

    expect(screen.getByRole('radio', { name: 'לא בטוח' })).toBeInTheDocument();
  });

  it('answering "not sure" is submitted like any other answer rather than blocked', async () => {
    classifyIssueMock.mockResolvedValue(classified());
    const { onClassified } = renderStep();

    await userEvent.click(screen.getByRole('radio', { name: 'לא בטוח' }));
    await userEvent.click(screen.getByRole('button', { name: 'המשך' }));

    expect(classifyIssueMock).toHaveBeenCalledWith(
      expect.objectContaining({
        clarificationAnswers: [{ question: 'מאיפה מגיעים המים?', answer: 'לא בטוח' }],
      }),
    );
    expect(onClassified).toHaveBeenCalled();
  });

  it('cannot continue until the question is answered', async () => {
    renderStep();

    expect(screen.getByRole('button', { name: 'המשך' })).toBeDisabled();

    await userEvent.click(screen.getByRole('radio', { name: 'מהכיור' }));
    expect(screen.getByRole('button', { name: 'המשך' })).toBeEnabled();
  });

  it('never renders a raw confidence score or technical diagnosis to the customer', () => {
    renderStep();

    // The response type carries no confidence at all; this guards the rendered output too.
    expect(document.body.textContent).not.toMatch(/0\.\d|confidence|ביטחון|%/);
  });

  // -- the round-by-round loop --------------------------------------------------------------

  it('resolves straight through when the first pass needs no clarification', async () => {
    classifyIssueMock.mockResolvedValue(classified());
    const { onClassified } = renderStep();

    await userEvent.click(screen.getByRole('radio', { name: 'מהמזגן' }));
    await userEvent.click(screen.getByRole('button', { name: 'המשך' }));

    expect(onClassified).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'CLASSIFIED' }),
      [{ question: 'מאיפה מגיעים המים?', answer: 'מהמזגן' }],
    );
  });

  /**
   * The second round is a fresh render with the first round's answer carried in
   * `previousAnswers` — the shape the page uses. The whole conversation must be resubmitted,
   * not just the newest answer, because the endpoint is stateless.
   */
  it('resubmits the whole conversation on the second round, not just the newest answer', async () => {
    classifyIssueMock.mockResolvedValue(classified());
    const firstAnswer = { question: 'מאיפה מגיעים המים?', answer: 'מהמזגן' };

    renderStep({
      questions: [question({ id: 'q2', question: 'האם זה קורה רק כשהמזגן דולק?', options: ['כן', 'לא', 'לא בטוח'] })],
      previousAnswers: [firstAnswer],
    });

    await userEvent.click(screen.getByRole('radio', { name: 'כן' }));
    await userEvent.click(screen.getByRole('button', { name: 'המשך' }));

    expect(classifyIssueMock).toHaveBeenCalledWith(
      expect.objectContaining({
        clarificationAnswers: [
          firstAnswer,
          { question: 'האם זה קורה רק כשהמזגן דולק?', answer: 'כן' },
        ],
      }),
    );
  });

  /**
   * After the second answer the backend has spent its budget and must return `CLASSIFIED`.
   * The component simply reports that result — there is no third round for it to render, and
   * no counter here that could disagree with the server about whether one is allowed.
   */
  it('reports the final classification after the second answer, with no third question', async () => {
    classifyIssueMock.mockResolvedValue(classified());
    const previousAnswers = [
      { question: 'מאיפה מגיעים המים?', answer: 'מהמזגן' },
      { question: 'האם זה קורה רק כשהמזגן דולק?', answer: 'כן' },
    ];

    const { onClassified } = renderStep({
      questions: [question({ id: 'q3', question: 'שאלה שלישית' })],
      previousAnswers,
    });

    await userEvent.click(screen.getByRole('radio', { name: 'מהכיור' }));
    await userEvent.click(screen.getByRole('button', { name: 'המשך' }));

    const [result] = onClassified.mock.calls[0];
    expect(result.status).toBe('CLASSIFIED');
    expect(result.questions).toHaveLength(0);
  });

  it('renders another round when the backend asks again rather than resolving', async () => {
    classifyIssueMock.mockResolvedValue(asking(question({ id: 'q2', question: 'שאלה שנייה' })));
    const { onClassified } = renderStep();

    await userEvent.click(screen.getByRole('radio', { name: 'מהמזגן' }));
    await userEvent.click(screen.getByRole('button', { name: 'המשך' }));

    // Handing a QUESTIONS result back to the page is what produces the next round; the
    // component itself neither counts rounds nor decides that another is permitted.
    const [result] = onClassified.mock.calls[0];
    expect(result.status).toBe('QUESTIONS');
    expect(result.questions[0].question).toBe('שאלה שנייה');
  });

  // -- provider failure ---------------------------------------------------------------------

  it('shows a recoverable error instead of a loading loop when classification fails', async () => {
    classifyIssueMock.mockRejectedValue(new Error('AI_SERVICE_ERROR'));
    const { onClassified } = renderStep();

    await userEvent.click(screen.getByRole('radio', { name: 'מהמזגן' }));
    await userEvent.click(screen.getByRole('button', { name: 'המשך' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(onClassified).not.toHaveBeenCalled();
    // Still usable: the customer can retry rather than being stranded on a spinner.
    expect(screen.getByRole('button', { name: 'המשך' })).toBeEnabled();
  });

  it('clears a previous error when the retry succeeds', async () => {
    classifyIssueMock.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(classified());
    const { onClassified } = renderStep();

    await userEvent.click(screen.getByRole('radio', { name: 'מהמזגן' }));
    await userEvent.click(screen.getByRole('button', { name: 'המשך' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'המשך' }));

    expect(onClassified).toHaveBeenCalled();
  });
});
