import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DescribeIssueStep } from './DescribeIssueStep';
import type { DescribeIssueStepProps } from './DescribeIssueStep';
import type { ClassifyIssueResponse } from '../../shared/api';
import { ISSUE_DESCRIPTION_MAX_LENGTH } from '../../shared/api';
import { ApiError } from '../../shared/api/httpClient';

/**
 * Step 1 of "יש לי תקלה", as a mobile screen.
 *
 * <p>These tests cover the layout contract the mobile pass introduced — one question, guidance
 * where it can still be read, suggestions on a single row, the optional photo section ahead of
 * the urgency choice — because it is a contract about *what is on screen and in what order*,
 * which is exactly the kind of thing a later well-meaning edit reorders without noticing. The
 * classification behaviour is asserted alongside it precisely because none of it was supposed
 * to change.
 */

vi.mock('../../shared/api', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api')>('../../shared/api');
  return { ...actual, classifyIssue: vi.fn() };
});

const { classifyIssue } = await import('../../shared/api');
const classifyIssueMock = vi.mocked(classifyIssue);

const HELPER_TEXT = 'תאר בכמה מילים את התקלה - המערכת תמצא את בעלי המקצוע המתאימים';

function classified(): ClassifyIssueResponse {
  return {
    status: 'CLASSIFIED',
    detectedProfession: 'אינסטלטור',
    professionCode: 'PLUMBER',
    subcategoryCode: 'FAUCET_OR_CONNECTION_LEAK',
    intent: 'REPAIR',
    urgency: 'NORMAL',
    suggestedCategoryId: 1,
    suggestedCategoryCode: 'plumbing',
    questions: [],
  };
}

function renderStep(overrides: Partial<DescribeIssueStepProps> = {}) {
  const props: DescribeIssueStepProps = {
    description: '',
    onDescriptionChange: vi.fn(),
    photos: [],
    onPhotosChange: vi.fn(),
    urgencyType: 'STANDARD',
    onUrgencyChange: vi.fn(),
    onClassified: vi.fn(),
    onAnalyzingChange: vi.fn(),
    ...overrides,
  };
  return { props, ...render(<DescribeIssueStep {...props} />) };
}

beforeEach(() => {
  classifyIssueMock.mockReset();
});

describe('DescribeIssueStep layout', () => {
  it('asks the question once, with the guidance attached to the field itself', () => {
    renderStep();

    expect(screen.queryByText('ספר לי מה קרה')).not.toBeInTheDocument();

    const field = screen.getByLabelText(/מה הבעיה\?/);
    const helper = screen.getByText(HELPER_TEXT);
    expect(helper).toBeInTheDocument();
    expect(field.getAttribute('aria-describedby')).toContain(helper.id);
  });

  it('keeps the optional photo section between the description and the urgency choice', () => {
    renderStep();

    const field = screen.getByLabelText(/מה הבעיה\?/);
    const addPhoto = screen.getByRole('button', { name: 'הוספת תמונה' });
    const standard = screen.getByRole('button', { name: /רגיל/ });

    // DOCUMENT_POSITION_FOLLOWING === 4: the second node comes later in the document.
    expect(field.compareDocumentPosition(addPhoto) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(addPhoto.compareDocumentPosition(standard) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByRole('button', { name: /דחוף/ })).toBeInTheDocument();
  });

  it('prefills the description from a suggestion chip', async () => {
    const user = userEvent.setup();
    const { props } = renderStep();

    await user.click(screen.getByRole('button', { name: 'נזילת מים' }));

    expect(props.onDescriptionChange).toHaveBeenCalledWith('יש לי נזילת מים מתחת לכיור במטבח');
  });

  it('still classifies the described issue on submit', async () => {
    const user = userEvent.setup();
    classifyIssueMock.mockResolvedValue(classified());
    const { props } = renderStep({ description: 'יש נזילת מים מתחת לכיור במטבח' });

    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(classifyIssueMock).toHaveBeenCalledWith({
      description: 'יש נזילת מים מתחת לכיור במטבח',
      imageKeys: [],
    });
    expect(props.onClassified).toHaveBeenCalledWith(classified());
  });
});

describe('the description length limit', () => {
  it('caps the field at the same number the server enforces, and counts up to it', () => {
    renderStep({ description: 'יש נזילה' });

    const field = screen.getByLabelText(/מה הבעיה\?/);
    expect(field).toHaveAttribute('maxlength', String(ISSUE_DESCRIPTION_MAX_LENGTH));
    expect(screen.getByText(`8/${ISSUE_DESCRIPTION_MAX_LENGTH}`)).toBeInTheDocument();
  });

  it('accepts a description of exactly the limit', async () => {
    const user = userEvent.setup();
    classifyIssueMock.mockResolvedValue(classified());
    const atLimit = 'א'.repeat(ISSUE_DESCRIPTION_MAX_LENGTH);
    renderStep({ description: atLimit });

    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(classifyIssueMock).toHaveBeenCalledWith({ description: atLimit, imageKeys: [] });
  });

  it('refuses one character over — in the field\'s existing error style, with no request sent', async () => {
    // A value that got past the input attribute: a draft written before this limit existed, which
    // is exactly the case the server would otherwise reject at the end of the flow.
    const user = userEvent.setup();
    renderStep({ description: 'א'.repeat(ISSUE_DESCRIPTION_MAX_LENGTH + 1) });

    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(screen.getByRole('alert')).toHaveTextContent(
      `יש לתאר את התקלה באורך של 10 עד ${ISSUE_DESCRIPTION_MAX_LENGTH} תווים.`,
    );
    expect(classifyIssueMock).not.toHaveBeenCalled();
  });

  it('surfaces a server-side rejection in the same banner as any other API error', async () => {
    // The backend is the rule, and it can still say no — a bypassed client, or a limit changed
    // server-side. Nothing swallows it; it lands in the existing alert treatment.
    const user = userEvent.setup();
    classifyIssueMock.mockRejectedValue(new ApiError('VALIDATION_ERROR', 'description: size must be between 10 and 300', null, 400));
    renderStep({ description: 'יש נזילת מים מתחת לכיור במטבח' });

    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
