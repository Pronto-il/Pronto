import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReviewStep } from './ReviewStep';
import { CATEGORIES } from '../../shared/api';
import type { ClassifyIssueResponse } from '../../shared/api';

/**
 * **Confirming a classification writes nothing. For anyone.**
 *
 * This screen used to create the issue for signed-in customers — `handleConfirm` read
 * `!isAuthenticated && !existingIssue`, so only guests deferred — which made the review step the
 * earliest point in the journey at which a database row appeared. Abandoning afterwards (closing
 * the tab, wandering off at the address or matching screen) left an `OPEN` issue nobody ever
 * booked, and starting a fresh report stranded it for good, because `NewIssuePage` drops
 * `reusableIssue` on any re-classification and the next confirm created a second issue.
 *
 * Guests and customers now share one lifecycle: the draft carries the report, and the first write
 * happens at the commit (`BookingSummary` for Standard, `ProntoSosEntryPage` for SOS).
 *
 * The `existingIssue` path is deliberately NOT removed with it, and these tests pin the
 * distinction that matters: a *new* report never persists, while a report whose issue genuinely
 * already exists is reused or corrected — never duplicated.
 */

const createIssue = vi.hoisted(() => vi.fn());
const updateIssueCategory = vi.hoisted(() => vi.fn());

vi.mock('../../shared/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api')>();
  return { ...actual, createIssue, updateIssueCategory };
});

const CATEGORY_A = CATEGORIES[0].id;
const CATEGORY_B = CATEGORIES[1].id;

const CONFIRM = 'אישור והמשך';
const CHANGE_CATEGORY = 'זה לא נראה נכון? שינוי תחום';

let onConfirmed: ReturnType<typeof vi.fn>;

function renderReview(options: { existingIssue?: { id: number; categoryId: number } } = {}) {
  onConfirmed = vi.fn();
  const classification = {
    status: 'CLASSIFIED',
    suggestedCategoryId: CATEGORY_A,
  } as unknown as ClassifyIssueResponse;

  return render(
    <ReviewStep
      classification={classification}
      urgencyType="STANDARD"
      existingIssue={options.existingIssue}
      onConfirmed={onConfirmed}
    />,
  );
}

/** Opens the override select and picks `categoryId`. */
async function overrideCategoryTo(user: ReturnType<typeof userEvent.setup>, categoryId: number) {
  await user.click(screen.getByRole('button', { name: CHANGE_CATEGORY }));
  await user.selectOptions(screen.getByLabelText('תחום שירות'), String(categoryId));
}

beforeEach(() => {
  updateIssueCategory.mockResolvedValue({ id: 4242, categoryId: CATEGORY_B, urgencyType: 'STANDARD' });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('a NEW report never creates an issue', () => {
  it('confirms without calling createIssue when a customer is signed in', async () => {
    // The regression itself. `ReviewStep` no longer consults auth at all, so "signed in" is
    // expressed by the absence of `existingIssue` plus the absence of any write.
    const user = userEvent.setup();
    renderReview();

    await user.click(screen.getByRole('button', { name: CONFIRM }));

    expect(createIssue).not.toHaveBeenCalled();
    expect(updateIssueCategory).not.toHaveBeenCalled();
    expect(onConfirmed).toHaveBeenCalledWith({
      id: null,
      categoryId: CATEGORY_A,
      urgencyType: 'STANDARD',
    });
  });

  it('reports id: null so the flow knows there is nothing to book against yet', async () => {
    const user = userEvent.setup();
    renderReview();

    await user.click(screen.getByRole('button', { name: CONFIRM }));

    expect(onConfirmed.mock.calls[0][0].id).toBeNull();
  });

  it('carries a corrected category forward without persisting anything', async () => {
    // "Do not create an Issue just so there is something to PATCH": the correction travels in the
    // confirmation payload, which `NewIssuePage` writes to the draft, and is what `createIssue` is
    // eventually called with at the commit.
    const user = userEvent.setup();
    renderReview();

    await overrideCategoryTo(user, CATEGORY_B);
    await user.click(screen.getByRole('button', { name: CONFIRM }));

    expect(createIssue).not.toHaveBeenCalled();
    expect(updateIssueCategory).not.toHaveBeenCalled();
    expect(onConfirmed).toHaveBeenCalledWith({
      id: null,
      categoryId: CATEGORY_B,
      urgencyType: 'STANDARD',
    });
  });

  it('preserves the SOS urgency it was given, so the SOS commit still knows what to create', async () => {
    const user = userEvent.setup();
    onConfirmed = vi.fn();
    render(
      <ReviewStep
        classification={{ status: 'CLASSIFIED', suggestedCategoryId: CATEGORY_A } as unknown as ClassifyIssueResponse}
        urgencyType="SOS"
        onConfirmed={onConfirmed}
      />,
    );

    await user.click(screen.getByRole('button', { name: CONFIRM }));

    expect(createIssue).not.toHaveBeenCalled();
    expect(onConfirmed).toHaveBeenCalledWith({ id: null, categoryId: CATEGORY_A, urgencyType: 'SOS' });
  });
});

describe('a REAL existing issue is reused, never duplicated', () => {
  it('continues with the same issue when nothing changed', async () => {
    // Re-entry: the customer walked back into the classification from a flow whose issue already
    // exists (most importantly after a `createOrder` failure, where the id is on the draft and the
    // retry must reuse it).
    const user = userEvent.setup();
    renderReview({ existingIssue: { id: 4242, categoryId: CATEGORY_A } });

    await user.click(screen.getByRole('button', { name: CONFIRM }));

    expect(createIssue).not.toHaveBeenCalled();
    expect(updateIssueCategory).not.toHaveBeenCalled();
    expect(onConfirmed).toHaveBeenCalledWith({
      id: 4242,
      categoryId: CATEGORY_A,
      urgencyType: 'STANDARD',
    });
  });

  it('PATCHes that issue when the category is corrected, and creates no second one', async () => {
    const user = userEvent.setup();
    renderReview({ existingIssue: { id: 4242, categoryId: CATEGORY_A } });

    await overrideCategoryTo(user, CATEGORY_B);
    await user.click(screen.getByRole('button', { name: CONFIRM }));

    expect(updateIssueCategory).toHaveBeenCalledWith(4242, CATEGORY_B);
    expect(createIssue).not.toHaveBeenCalled();
    expect(onConfirmed).toHaveBeenCalledWith(
      expect.objectContaining({ id: 4242, categoryId: CATEGORY_B }),
    );
  });

  it('explains an issue that moved on instead of failing generically', async () => {
    const user = userEvent.setup();
    const { ApiError } = await import('../../shared/api');
    updateIssueCategory.mockRejectedValueOnce(new ApiError('ISSUE_NOT_EDITABLE', 'nope', null, 409));
    renderReview({ existingIssue: { id: 4242, categoryId: CATEGORY_A } });

    await overrideCategoryTo(user, CATEGORY_B);
    await user.click(screen.getByRole('button', { name: CONFIRM }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'הבקשה הזו כבר בטיפול, ולכן לא ניתן לשנות את תחום השירות שלה.',
    );
    expect(onConfirmed).not.toHaveBeenCalled();
    expect(createIssue).not.toHaveBeenCalled();
  });
});
