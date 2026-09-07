import { useContext } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import NewIssuePage from './NewIssuePage';
import { CATEGORIES } from '../../shared/api';
import { classificationSignature } from './classificationCache';
import { HeaderBackProvider, HeaderBackContext } from '../../shared/hooks';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraft, BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';

/**
 * The profession hint across the whole flow, and the classification calls the flow does NOT make.
 *
 * <p>Two properties that only exist at page level and cannot be tested on a single step:
 *
 * <ul>
 *   <li>the hint is the customer's own choice about their own problem, so it has to survive going
 *       back, clarification rounds and resuming a draft tomorrow — losing it silently is
 *       indistinguishable, on screen, from the customer never having chosen;</li>
 *   <li>resuming a draft used to re-run the model unconditionally, purely because the previous
 *       answer had not been kept. On the measured baseline that was a p50 of 10.4 seconds spent
 *       re-deriving an answer that had not changed.</li>
 * </ul>
 */

const classifyIssue = vi.hoisted(() => vi.fn());
const getPresignedImageUrls = vi.hoisted(() => vi.fn());
const createIssue = vi.hoisted(() => vi.fn());
const updateIssueCategory = vi.hoisted(() => vi.fn());

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return { ...actual, classifyIssue, getPresignedImageUrls, createIssue, updateIssueCategory };
});

const CATEGORY_A = CATEGORIES[0].id;
const HINT = CATEGORIES[1].id;
const PICKER_LABEL = 'איזה בעל מקצוע דרוש לך?';
const DESCRIPTION = 'נזילה מתחת לכיור במטבח';
const IMAGE_KEY = 'guests/abc/1.jpg';

let updateDraft: ReturnType<typeof vi.fn>;

function classified(overrides: Record<string, unknown> = {}) {
  return {
    status: 'CLASSIFIED',
    detectedProfession: 'אינסטלטור',
    professionCode: 'PLUMBER',
    subcategoryCode: 'FAUCET_OR_CONNECTION_LEAK',
    intent: 'REPAIR',
    urgency: 'NORMAL',
    suggestedCategoryId: CATEGORY_A,
    suggestedCategoryCode: 'plumbing',
    questions: [],
    ...overrides,
  };
}

function draft(overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: 42,
    stage: 'ISSUE_DESCRIBE',
    urgencyType: 'STANDARD',
    description: DESCRIPTION,
    photos: [{ imageKey: IMAGE_KEY }],
    updatedAt: new Date().toISOString(),
    ...overrides,
  } as BookingDraft;
}

/**
 * Stands in for `AppLayout`: this page hoists its back control into the app bar, so an isolated
 * render has nowhere to put it and the control simply does not exist. Rendering whatever the page
 * registered is what makes back navigation reachable here.
 */
function HeaderSlot() {
  const { action } = useContext(HeaderBackContext);
  return action ? (
    <button type="button" onClick={action.onBack}>
      {action.label}
    </button>
  ) : null;
}

function renderPage(options: { draft?: BookingDraft | null } = {}) {
  updateDraft = vi.fn();
  const auth = {
    user: { id: 42, role: 'CUSTOMER' },
    token: 'jwt-abc',
    isLoading: false,
  } as unknown as AuthContextValue;
  const draftValue = {
    draft: options.draft === undefined ? draft() : options.draft,
    updateDraft,
    clearDraft: vi.fn(),
  } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={['/issues/new']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={draftValue}>
          <HeaderBackProvider>
            <HeaderSlot />
            <Routes>
              <Route path="/issues/new" element={<NewIssuePage />} />
              <Route path="/matching" element={<p>matching-screen</p>} />
            </Routes>
          </HeaderBackProvider>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

function patches(): Record<string, unknown>[] {
  return updateDraft.mock.calls.map((call) => call[0]);
}

beforeEach(() => {
  classifyIssue.mockResolvedValue(classified());
  getPresignedImageUrls.mockResolvedValue({
    images: [{ imageKey: IMAGE_KEY, imageUrl: 'https://example.test/1.jpg' }],
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('the profession hint across the flow', () => {
  it('is persisted to the draft when the classification advances the flow', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.selectOptions(await screen.findByLabelText(PICKER_LABEL), String(HINT));
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await waitFor(() => expect(patches().length).toBeGreaterThan(0));
    expect(patches().at(-1)).toMatchObject({ selectedCategoryId: HINT, stage: 'ISSUE_REVIEW' });
  });

  it('is restored into the picker from a resumed draft', async () => {
    renderPage({ draft: draft({ selectedCategoryId: HINT }) });

    expect(await screen.findByLabelText(PICKER_LABEL)).toHaveValue(String(HINT));
  });

  it('survives going back from a later step', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.selectOptions(await screen.findByLabelText(PICKER_LABEL), String(HINT));
    await user.click(screen.getByRole('button', { name: 'המשך' }));
    await screen.findByText(/אישור והמשך/);

    // Back to the description. The clarification answers are deliberately dropped (they answer
    // questions about a description being restated); the customer's own choice is not.
    await user.click(screen.getByRole('button', { name: 'חזרה' }));

    expect(await screen.findByLabelText(PICKER_LABEL)).toHaveValue(String(HINT));
    expect(patches().at(-1)).toMatchObject({
      stage: 'ISSUE_DESCRIBE',
      selectedCategoryId: HINT,
      clarificationAnswers: [],
    });
  });

  it('is carried into every clarification round, not only the first call', async () => {
    const user = userEvent.setup();
    classifyIssue.mockResolvedValueOnce(
      classified({
        status: 'QUESTIONS',
        questions: [{ id: 'q1', question: 'מאיפה דולף?', options: ['מהצינור', 'לא בטוח'] }],
      }),
    );
    classifyIssue.mockResolvedValueOnce(classified());
    renderPage();

    await user.selectOptions(await screen.findByLabelText(PICKER_LABEL), String(HINT));
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await user.click(await screen.findByRole('radio', { name: 'מהצינור' }));
    await user.click(screen.getByRole('button', { name: 'המשך' }));

    await waitFor(() => expect(classifyIssue).toHaveBeenCalledTimes(2));
    // `/classify` is stateless and re-runs over the COMPLETE evidence each round, so dropping the
    // hint here would look to the backend like the customer withdrawing their choice by answering
    // a question.
    expect(classifyIssue.mock.calls[1][0]).toMatchObject({
      selectedCategoryId: HINT,
      clarificationAnswers: [{ question: 'מאיפה דולף?', answer: 'מהצינור' }],
    });
  });
});

describe('avoiding a re-classification that would answer the same question twice', () => {
  const cached = {
    signature: classificationSignature({
      description: DESCRIPTION,
      imageKeys: [IMAGE_KEY],
      selectedCategoryId: HINT,
      clarificationAnswers: [],
    }),
    result: classified(),
  };

  it('reuses the stored answer when the evidence is unchanged', async () => {
    renderPage({
      draft: draft({
        stage: 'ISSUE_REVIEW',
        selectedCategoryId: HINT,
        categoryId: CATEGORY_A,
        classification: cached,
      }),
    });

    await screen.findByRole('button', { name: 'אישור והמשך' });
    // The whole point: no model call, no spinner, no wait.
    expect(classifyIssue).not.toHaveBeenCalled();
  });

  it('re-classifies when the description changed', async () => {
    renderPage({
      draft: draft({
        stage: 'ISSUE_REVIEW',
        description: 'משהו אחר לגמרי',
        selectedCategoryId: HINT,
        classification: cached,
      }),
    });

    await waitFor(() => expect(classifyIssue).toHaveBeenCalledTimes(1));
  });

  it('re-classifies when the photos changed', async () => {
    getPresignedImageUrls.mockResolvedValue({ images: [] });
    renderPage({
      draft: draft({
        stage: 'ISSUE_REVIEW',
        photos: [],
        selectedCategoryId: HINT,
        classification: cached,
      }),
    });

    await waitFor(() => expect(classifyIssue).toHaveBeenCalledTimes(1));
  });

  it('re-classifies when the chosen profession changed', async () => {
    // The hint is part of the evidence the backend reads, so changing it genuinely changes the
    // question — reusing the old answer here would show the customer a result for a choice they
    // have since revised.
    renderPage({
      draft: draft({
        stage: 'ISSUE_REVIEW',
        selectedCategoryId: CATEGORIES[3].id,
        classification: cached,
      }),
    });

    await waitFor(() => expect(classifyIssue).toHaveBeenCalledTimes(1));
  });

  it('re-classifies when a clarification answer changed', async () => {
    renderPage({
      draft: draft({
        stage: 'ISSUE_CLARIFY',
        selectedCategoryId: HINT,
        clarificationAnswers: [{ question: 'מאיפה דולף?', answer: 'מהצינור' }],
        classification: cached,
      }),
    });

    await waitFor(() => expect(classifyIssue).toHaveBeenCalledTimes(1));
  });

  it('re-classifies when there is no stored answer at all', async () => {
    renderPage({ draft: draft({ stage: 'ISSUE_REVIEW', selectedCategoryId: HINT }) });

    await waitFor(() => expect(classifyIssue).toHaveBeenCalledTimes(1));
  });

  it('restores an unsupported-profession verdict as the terminal step, not as a review', async () => {
    // A reused result makes this reachable on resume, so it has to be handled: falling through to
    // review would offer a category this result deliberately does not have.
    const unsupported = classified({
      status: 'UNSUPPORTED_PROFESSION',
      detectedProfession: 'טכנאי גז',
      suggestedCategoryId: null,
      suggestedCategoryCode: null,
    });
    renderPage({
      draft: draft({
        stage: 'ISSUE_REVIEW',
        selectedCategoryId: HINT,
        classification: { signature: cached.signature, result: unsupported },
      }),
    });

    expect(await screen.findByText(/טכנאי גז/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'אישור והמשך' })).not.toBeInTheDocument();
    expect(classifyIssue).not.toHaveBeenCalled();
  });
});
