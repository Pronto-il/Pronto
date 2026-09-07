import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DescribeIssueStep } from './DescribeIssueStep';
import type { DescribeIssueStepProps } from './DescribeIssueStep';
import type { ClassifyIssueResponse } from '../../shared/api';
import { CATEGORIES } from '../../shared/api';
import { ApiError } from '../../shared/api/httpClient';

/**
 * The profession picker, and the cancellation/deadline behaviour around the call it feeds.
 *
 * <p>Two things are being protected here. The first is that the picker is a <b>hint</b>: it is
 * optional, it is sent in the existing `selectedCategoryId` field, and it never becomes a way
 * around classification. The second is that a customer is never left staring at a spinner — the
 * call is bounded, and a superseded or abandoned call can neither drive the flow forward nor
 * clear the loading state belonging to the call that replaced it.
 */

vi.mock('../../shared/api', async () => {
  const actual = await vi.importActual<typeof import('../../shared/api')>('../../shared/api');
  return { ...actual, classifyIssue: vi.fn() };
});

const { classifyIssue } = await import('../../shared/api');
const classifyIssueMock = vi.mocked(classifyIssue);

const PICKER_LABEL = 'איזה בעל מקצוע דרוש לך?';
const TIMEOUT_MESSAGE = 'לא הספקנו לנתח את התקלה בזמן. אפשר לנסות שוב, או לבחור את בעל המקצוע הדרוש ולהמשיך.';
const DESCRIPTION = 'יש נזילת מים מתחת לכיור במטבח';

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
    description: DESCRIPTION,
    onDescriptionChange: vi.fn(),
    photos: [],
    onPhotosChange: vi.fn(),
    urgencyType: 'STANDARD',
    onUrgencyChange: vi.fn(),
    selectedCategoryId: undefined,
    onSelectedCategoryChange: vi.fn(),
    onClassified: vi.fn(),
    onAnalyzingChange: vi.fn(),
    ...overrides,
  };
  return { props, ...render(<DescribeIssueStep {...props} />) };
}

beforeEach(() => {
  classifyIssueMock.mockReset();
});

describe('the profession picker', () => {
  it('asks the question with the agreed label, as one compact control', () => {
    renderStep();

    const picker = screen.getByLabelText(PICKER_LABEL);
    // A <select>, not a grid of seven permanently-expanded chips: the latter pushes the urgency
    // choice and the submit button off a phone screen for a field most customers should be able
    // to skip without reading.
    expect(picker.tagName).toBe('SELECT');
  });

  it('sits after the example prompts and before the photo uploader', () => {
    // Empty description, so the example chips render.
    renderStep({ description: '' });

    const exampleChip = screen.getByRole('button', { name: 'נזילת מים' });
    const picker = screen.getByLabelText(PICKER_LABEL);
    const addPhoto = screen.getByRole('button', { name: 'הוספת תמונה' });

    // DOCUMENT_POSITION_FOLLOWING === 4.
    expect(exampleChip.compareDocumentPosition(picker) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(picker.compareDocumentPosition(addPhoto) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('offers exactly the seven bookable trades plus an automatic option', () => {
    renderStep();

    const options = screen.getByLabelText(PICKER_LABEL).querySelectorAll('option');

    // Not the AI's 50-profession taxonomy: most of that names trades Pronto cannot dispatch, and
    // listing them here would advertise services that do not exist.
    expect(options).toHaveLength(CATEGORIES.length + 1);
    expect(options[0]).toHaveValue('');
    CATEGORIES.forEach((category, index) => {
      // Labelled with the practitioner ("אינסטלטור"), not the field of work ("אינסטלציה") —
      // the question asked is who the customer needs.
      expect(options[index + 1]).toHaveTextContent(category.professionalNameHe);
      expect(options[index + 1]).toHaveValue(String(category.id));
    });
  });

  it('reports a chosen profession to the parent as a category id', async () => {
    const user = userEvent.setup();
    const { props } = renderStep();

    await user.selectOptions(screen.getByLabelText(PICKER_LABEL), String(CATEGORIES[1].id));

    expect(props.onSelectedCategoryChange).toHaveBeenCalledWith(CATEGORIES[1].id);
  });

  it('reports going back to automatic as undefined rather than a magic number', async () => {
    const user = userEvent.setup();
    const { props } = renderStep({ selectedCategoryId: CATEGORIES[1].id });

    await user.selectOptions(screen.getByLabelText(PICKER_LABEL), '');

    expect(props.onSelectedCategoryChange).toHaveBeenCalledWith(undefined);
  });

  it('shows the parent-held selection, so it survives leaving and re-entering the step', () => {
    renderStep({ selectedCategoryId: CATEGORIES[2].id });

    expect(screen.getByLabelText(PICKER_LABEL)).toHaveValue(String(CATEGORIES[2].id));
  });

  it('sends the selection in the existing selectedCategoryId field', async () => {
    const user = userEvent.setup();
    classifyIssueMock.mockResolvedValue(classified());
    renderStep({ selectedCategoryId: CATEGORIES[1].id });

    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(classifyIssueMock).toHaveBeenCalledWith(
      { description: DESCRIPTION, imageKeys: [], selectedCategoryId: CATEGORIES[1].id },
      expect.anything(),
    );
  });

  it('omits the field entirely when no profession was chosen, keeping classification automatic', async () => {
    const user = userEvent.setup();
    classifyIssueMock.mockResolvedValue(classified());
    renderStep();

    await user.click(screen.getByRole('button', { name: 'המשך' }));

    const payload = classifyIssueMock.mock.calls[0][0];
    expect(payload).not.toHaveProperty('selectedCategoryId');
  });

  it('still classifies rather than treating the hint as the answer', async () => {
    // The customer said "electrician"; the backend still gets to disagree, and the flow still
    // advances on the CLASSIFICATION, not on the selection. A hint that short-circuited
    // classification would also short-circuit the unsupported-profession check.
    const user = userEvent.setup();
    classifyIssueMock.mockResolvedValue(classified());
    const { props } = renderStep({ selectedCategoryId: CATEGORIES[1].id });

    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(classifyIssueMock).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(props.onClassified).toHaveBeenCalledWith(
        expect.objectContaining({ suggestedCategoryId: 1, suggestedCategoryCode: 'plumbing' }),
      ),
    );
  });
});

describe('the client deadline', () => {
  it('shows a Hebrew recovery message offering retry and manual selection', async () => {
    const user = userEvent.setup();
    classifyIssueMock.mockRejectedValue(
      new ApiError('CLASSIFY_TIMEOUT', 'Classification exceeded the client deadline.', null, 0),
    );
    renderStep();

    await user.click(screen.getByRole('button', { name: 'המשך' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(TIMEOUT_MESSAGE);
    expect(screen.getByRole('button', { name: 'נסו שוב' })).toBeInTheDocument();
  });

  it('never reports a timeout as a classification', async () => {
    const user = userEvent.setup();
    classifyIssueMock.mockRejectedValue(
      new ApiError('CLASSIFY_TIMEOUT', 'timeout', null, 0),
    );
    const { props } = renderStep();

    await user.click(screen.getByRole('button', { name: 'המשך' }));
    await screen.findByRole('alert');

    // The whole rule, in one assertion: running out of time is not a result, so the flow does
    // not advance and no category is invented.
    expect(props.onClassified).not.toHaveBeenCalled();
  });

  it('clears the loading state so the customer can act again', async () => {
    const user = userEvent.setup();
    classifyIssueMock.mockRejectedValue(new ApiError('CLASSIFY_TIMEOUT', 'timeout', null, 0));
    const { props } = renderStep();

    await user.click(screen.getByRole('button', { name: 'המשך' }));
    await screen.findByRole('alert');

    // The overlay must come down; a stuck spinner over a timed-out call is the failure this
    // whole change exists to remove.
    expect(props.onAnalyzingChange).toHaveBeenLastCalledWith(false);
    expect(screen.getByRole('button', { name: 'נסו שוב' })).toBeEnabled();
  });

  it('retries with a fresh call when the customer asks', async () => {
    const user = userEvent.setup();
    classifyIssueMock.mockRejectedValueOnce(new ApiError('CLASSIFY_TIMEOUT', 'timeout', null, 0));
    classifyIssueMock.mockResolvedValueOnce(classified());
    const { props } = renderStep();

    await user.click(screen.getByRole('button', { name: 'המשך' }));
    await user.click(await screen.findByRole('button', { name: 'נסו שוב' }));

    await waitFor(() => expect(props.onClassified).toHaveBeenCalledWith(classified()));
    expect(classifyIssueMock).toHaveBeenCalledTimes(2);
  });
});

describe('superseded and abandoned calls', () => {
  it('starts only one classification however many times the customer taps continue', async () => {
    // The first line of defence, and it is the button itself: `loading` disables it, so an
    // impatient double-tap cannot open a second request. Worth pinning — the guard is a prop on
    // a shared component, and it would be easy to lose while restyling this form.
    const user = userEvent.setup();
    classifyIssueMock.mockReturnValue(new Promise<ClassifyIssueResponse>(() => {}));
    renderStep();

    const submit = screen.getByRole('button', { name: 'המשך' });
    await user.click(submit);
    expect(submit).toBeDisabled();
    await user.click(submit);
    await user.click(submit);

    expect(classifyIssueMock).toHaveBeenCalledTimes(1);
  });

  it('ignores a response that arrives after the customer has left the step', async () => {
    // The second line of defence, for the window the disabled button does not cover: the call is
    // still in flight when the step goes away. Aborting is not instantaneous and a response that
    // was already parsed would otherwise still resolve and advance a flow nobody is on.
    const user = userEvent.setup();
    let resolveLate: (value: ClassifyIssueResponse) => void = () => {};
    classifyIssueMock.mockReturnValue(
      new Promise<ClassifyIssueResponse>((resolve) => {
        resolveLate = resolve;
      }),
    );
    const { props, unmount } = renderStep();

    await user.click(screen.getByRole('button', { name: 'המשך' }));
    unmount();
    resolveLate(classified());

    await waitFor(() => expect(classifyIssueMock).toHaveBeenCalledTimes(1));
    expect(props.onClassified).not.toHaveBeenCalled();
    expect(props.onAnalyzingChange).not.toHaveBeenCalledWith(false);
  });

  it('aborts an in-flight call when the step is left', async () => {
    const user = userEvent.setup();
    classifyIssueMock.mockReturnValue(new Promise<ClassifyIssueResponse>(() => {}));
    const { unmount } = renderStep();

    await user.click(screen.getByRole('button', { name: 'המשך' }));
    const signal = classifyIssueMock.mock.calls[0][1]!.signal!;

    unmount();

    // Nothing renders it any more, so holding the socket open is pure waste — and the backend
    // stops being waited on.
    expect(signal.aborted).toBe(true);
  });

  it('says nothing to the customer about a call it cancelled itself', async () => {
    const user = userEvent.setup();
    classifyIssueMock.mockRejectedValue(new ApiError('ABORTED', 'Request cancelled.', null, 0));
    renderStep();

    await user.click(screen.getByRole('button', { name: 'המשך' }));

    // A cancellation is something the app did on purpose. Surfacing it as an error would tell the
    // customer something went wrong when nothing did.
    await waitFor(() => expect(classifyIssueMock).toHaveBeenCalled());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
