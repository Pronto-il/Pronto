import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import NewIssuePage from './NewIssuePage';
import { CATEGORIES } from '../../shared/api';
import { AuthContext } from '../../shared/hooks/authContext';
import type { AuthContextValue } from '../../shared/hooks/authContext';
import { BookingDraftContext } from '../../shared/hooks/bookingDraftContext';
import type { BookingDraft, BookingDraftContextValue } from '../../shared/hooks/bookingDraftContext';

/**
 * **The abandonment guarantee.**
 *
 * A signed-in customer who describes a problem, confirms the classification and then walks away —
 * closes the tab at the address screen, gets distracted during matching, or starts a different
 * report — must leave **no** issue behind. Until this fix they left one per confirmation: the
 * review step created the row, so every abandoned flow became an `OPEN` issue indistinguishable
 * from a genuine unbooked request, and every restart stranded the previous one.
 *
 * These tests drive the real page rather than `ReviewStep` alone, because the guarantee is a
 * property of the whole screen: what it writes to the server (nothing) and what it writes to the
 * draft (everything the commit will later need).
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
const CATEGORY_B = CATEGORIES[1].id;
const CONFIRM = 'אישור והמשך';

let updateDraft: ReturnType<typeof vi.fn>;

/** A draft parked on the review step, so the page resumes straight onto `ReviewStep`. */
function reviewDraft(overrides: Partial<BookingDraft> = {}): BookingDraft {
  return {
    version: 2,
    ownerId: 42,
    stage: 'ISSUE_REVIEW',
    urgencyType: 'STANDARD',
    description: 'נזילה מתחת לכיור במטבח',
    photos: [{ imageKey: 'guests/abc/1.jpg' }],
    clarificationAnswers: [{ question: 'מאיפה דולף?', answer: 'מהצינור' }],
    categoryId: CATEGORY_A,
    updatedAt: new Date().toISOString(),
    ...overrides,
  } as BookingDraft;
}

function renderPage(options: { draft?: BookingDraft } = {}) {
  updateDraft = vi.fn();
  const auth = {
    user: { id: 42, role: 'CUSTOMER' },
    token: 'jwt-abc',
    isLoading: false,
  } as unknown as AuthContextValue;
  const draftValue = {
    draft: options.draft ?? reviewDraft(),
    updateDraft,
    clearDraft: vi.fn(),
  } as unknown as BookingDraftContextValue;

  return render(
    <MemoryRouter initialEntries={['/issues/new']}>
      <AuthContext.Provider value={auth}>
        <BookingDraftContext.Provider value={draftValue}>
          <Routes>
            <Route path="/issues/new" element={<NewIssuePage />} />
            <Route path="/matching" element={<p>matching-screen</p>} />
          </Routes>
        </BookingDraftContext.Provider>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

/** The last `updateDraft` patch, i.e. what confirming wrote. */
function lastPatch(): Record<string, unknown> {
  const calls = updateDraft.mock.calls;
  return calls[calls.length - 1][0];
}

beforeEach(() => {
  classifyIssue.mockResolvedValue({ status: 'CLASSIFIED', suggestedCategoryId: CATEGORY_A });
  getPresignedImageUrls.mockResolvedValue({
    images: [{ imageKey: 'guests/abc/1.jpg', imageUrl: 'https://example.test/1.jpg' }],
  });
  updateIssueCategory.mockResolvedValue({ id: 4242, categoryId: CATEGORY_B, urgencyType: 'STANDARD' });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('an authenticated customer who abandons after the review leaves nothing behind', () => {
  it('creates no issue when the classification is confirmed', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: CONFIRM }));

    // The whole point: the customer has now reached the address/matching screens with a fully
    // described problem, and the database has not been touched.
    await screen.findByText('matching-screen');
    expect(createIssue).not.toHaveBeenCalled();
    expect(updateIssueCategory).not.toHaveBeenCalled();
  });

  it('leaves no issue id on the draft to book against', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: CONFIRM }));

    await waitFor(() => expect(updateDraft).toHaveBeenCalled());
    expect(lastPatch().issueId).toBeUndefined();
  });
});

describe('the draft carries everything the commit will need', () => {
  it('persists category and urgency on confirmation, and keeps the report itself', async () => {
    // `createIssue` at the commit needs categoryId, description, urgencyType, imageKeys and
    // clarificationAnswers. The last three were already written by the classification steps;
    // confirming adds the category and moves the stage on. `updateDraft` shallow-merges, so
    // nothing already stored is dropped.
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: CONFIRM }));

    await waitFor(() => expect(updateDraft).toHaveBeenCalled());
    expect(lastPatch()).toMatchObject({
      stage: 'ADDRESS_SELECTION',
      categoryId: CATEGORY_A,
      urgencyType: 'STANDARD',
    });
  });

  it('persists a corrected category rather than PATCHing a nonexistent issue', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'זה לא נראה נכון? שינוי תחום' }));
    await user.selectOptions(screen.getByLabelText('תחום שירות'), String(CATEGORY_B));
    await user.click(screen.getByRole('button', { name: CONFIRM }));

    await waitFor(() => expect(updateDraft).toHaveBeenCalled());
    expect(lastPatch()).toMatchObject({ categoryId: CATEGORY_B });
    expect(updateIssueCategory).not.toHaveBeenCalled();
    expect(createIssue).not.toHaveBeenCalled();
  });
});

describe('restarting does not accumulate issues', () => {
  it('clears a stale issue id instead of creating a second issue', async () => {
    // A draft that reached a commit once (so it carries an id) whose report has since moved on —
    // `NewIssuePage` only offers an issue for reuse while the stored category still matches the
    // report, so `reusableIssue` is undefined here and this is a *different* problem.
    //
    // Two things must both hold, and before this fix neither did: no second issue is created, and
    // the new selection is not booked against the previous problem's id. `updateDraft`
    // shallow-merges, so confirming has to actively overwrite `issueId` with `undefined`.
    const user = userEvent.setup();
    renderPage({ draft: reviewDraft({ issueId: 4242, categoryId: undefined }) });

    await user.click(await screen.findByRole('button', { name: CONFIRM }));

    await waitFor(() => expect(updateDraft).toHaveBeenCalled());
    expect(createIssue).not.toHaveBeenCalled();
    expect(updateIssueCategory).not.toHaveBeenCalled();
    expect(lastPatch()).toHaveProperty('issueId', undefined);
  });

  it('warns that a booking in progress will be lost, without needing an issue to exist', async () => {
    // The warning used to key on `draft.issueId !== undefined`, which was a proxy for "past the
    // report" only while confirming created the issue. Nothing is persisted now, so it keys on the
    // stage — which also fixes it for guests, who never had an id to find.
    renderPage({ draft: reviewDraft({ stage: 'PROFESSIONAL_SELECTION' }) });

    expect(
      await screen.findByText('יש לך בקשה פעילה בתהליך הזמנה — התחלת תקלה חדשה תבטל אותה.'),
    ).toBeInTheDocument();
  });
});
