import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Textarea } from './Textarea';
import {
  ISSUE_DESCRIPTION_MAX_LENGTH,
  REVIEW_COMMENT_MAX_LENGTH,
  CLARIFICATION_ANSWER_MAX_LENGTH,
  BIO_MAX_LENGTH,
  ADDRESS_MAX_LENGTHS,
} from '../api/fieldLimits';

/**
 * The client half of the free-text length rule: the field stops accepting input at the same
 * number the server refuses past, and says how much room is left while there still is some.
 *
 * <p>Stopping input is not truncation — nothing already typed is discarded and nothing is cut on
 * the way to the server. The value simply cannot grow past the limit, and the limit is visible
 * before it is reached. The server enforces the same numbers independently
 * (`FreeTextLengthLimitsTest`), which is what covers a caller that never renders this component.
 */

/** A controlled wrapper, because the counter counts the value the parent holds. */
function ControlledTextarea({ maxLength, initial = '' }: { maxLength?: number; initial?: string }) {
  const [value, setValue] = useState(initial);
  return (
    <Textarea
      label="תיאור"
      value={value}
      onChange={(event) => setValue(event.target.value)}
      maxLength={maxLength}
    />
  );
}

describe('the character counter', () => {
  it('shows used/limit, and follows what is typed', async () => {
    const user = userEvent.setup();
    render(<ControlledTextarea maxLength={300} />);

    expect(screen.getByText('0/300')).toBeInTheDocument();

    await user.type(screen.getByRole('textbox'), 'נזילה');
    expect(screen.getByText('5/300')).toBeInTheDocument();
  });

  it('is absent on a field with no limit', () => {
    render(<ControlledTextarea />);

    expect(screen.queryByText(/\/\d+$/)).not.toBeInTheDocument();
  });

  it('leaves the hint and error treatments where they were', () => {
    render(
      <Textarea label="תיאור" value="abc" onChange={() => {}} maxLength={300} hint="רמז" />,
    );
    expect(screen.getByText('רמז')).toBeInTheDocument();

    render(
      <Textarea label="תיאור" value="abc" onChange={() => {}} maxLength={300} error="שגיאה" />,
    );
    // Same `role="alert"` treatment as before — this is what a backend rejection surfaces through.
    expect(screen.getByRole('alert')).toHaveTextContent('שגיאה');
  });
});

describe('the field cannot be typed past its limit', () => {
  it('accepts exactly the limit and refuses the next character', async () => {
    const user = userEvent.setup();
    render(<ControlledTextarea maxLength={20} />);

    const field = screen.getByRole('textbox');
    await user.type(field, 'a'.repeat(20));
    expect(field).toHaveValue('a'.repeat(20));
    expect(screen.getByText('20/20')).toBeInTheDocument();

    await user.type(field, 'b');
    // Still exactly the limit — the extra keystroke is refused, and nothing already typed is lost.
    expect(field).toHaveValue('a'.repeat(20));
  });

  it('carries the limit as an attribute, so a paste is bounded too', () => {
    render(<ControlledTextarea maxLength={ISSUE_DESCRIPTION_MAX_LENGTH} />);

    expect(screen.getByRole('textbox')).toHaveAttribute('maxlength', String(ISSUE_DESCRIPTION_MAX_LENGTH));
  });
});

describe('the limits mirror the backend @Size constraints', () => {
  // If one of these numbers moves, the annotation named in `fieldLimits.ts` has to move with it.
  // Written out as literals on purpose: a test that re-derives the value from the same constant it
  // is checking would pass no matter what either side said.
  it('are the agreed values', () => {
    expect(ISSUE_DESCRIPTION_MAX_LENGTH).toBe(300);
    expect(CLARIFICATION_ANSWER_MAX_LENGTH).toBe(200);
    expect(REVIEW_COMMENT_MAX_LENGTH).toBe(500);
    expect(BIO_MAX_LENGTH).toBe(2000);
    expect(ADDRESS_MAX_LENGTHS).toEqual({
      city: 100,
      street: 150,
      houseNumber: 20,
      apartment: 20,
      floor: 20,
      entrance: 2,
      addressNotes: 500,
    });
  });
});
