import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ProntoAnalysisCard } from './ProntoAnalysisCard';
import type { ClarificationEntry, ProntoAnalysis } from '../../shared/api';

/**
 * The professional's preparation brief (Production MS3, roadmap §18–§20 and §35).
 *
 * <p>The contract worth testing here is not the layout, it is the <b>separation</b>: what the
 * customer reported must stay readable as reported fact, and Pronto's inference must stay
 * readable as inference. A professional who mistakes a hypothesis for something the customer
 * said turns up prepared for the wrong job — so the "מה כנראה הבעיה" heading and the "על סמך:"
 * evidence trail are load-bearing, not decoration.
 *
 * <p>The empty/failed paths matter for the same reason in reverse: a brief is generated
 * asynchronously and can legitimately be absent, and a card that renders a labelled shell when
 * it has nothing to say reads as "Pronto looked and found nothing", which is not what happened.
 */

function analysis(overrides: Partial<ProntoAnalysis> = {}): ProntoAnalysis {
  return {
    status: 'READY',
    customerProblemSummary: 'הלקוח מדווח על מים על הרצפה במטבח.',
    clarificationSummary: null,
    imageObservations: [],
    likelyIssue: {
      description: 'ייתכן שמדובר בדליפה מחיבור הסיפון מתחת לכיור',
      confidence: 0.7,
      evidence: ['הלקוח מדווח שהמים מופיעים רק אחרי שימוש בכיור'],
    },
    possibleCauses: [],
    recommendedTools: [],
    recommendedParts: [],
    safetyNotes: [],
    ...overrides,
  };
}

const clarifications: ClarificationEntry[] = [
  { question: 'מאיפה מגיעים המים?', answer: 'מתחת לכיור' },
];

describe('ProntoAnalysisCard', () => {
  it('labels the whole card as Pronto analysis rather than customer-reported fact', () => {
    render(<ProntoAnalysisCard analysis={analysis()} clarifications={[]} />);

    expect(screen.getByText('ניתוח Pronto')).toBeInTheDocument();
  });

  /** §19: observation and hypothesis must be distinguishable, not merged into one claim. */
  it('separates what the customer answered from what Pronto suspects', () => {
    render(<ProntoAnalysisCard analysis={analysis()} clarifications={clarifications} />);

    // Customer-supplied fact, under its own heading.
    expect(screen.getByRole('heading', { name: 'מה הלקוח ענה' })).toBeInTheDocument();
    expect(screen.getByText('מאיפה מגיעים המים?')).toBeInTheDocument();
    expect(screen.getByText('מתחת לכיור')).toBeInTheDocument();

    // Pronto's inference, under a heading that reads as a hypothesis.
    expect(screen.getByRole('heading', { name: /מה כנראה הבעיה/ })).toBeInTheDocument();
    expect(
      screen.getByText('ייתכן שמדובר בדליפה מחיבור הסיפון מתחת לכיור'),
    ).toBeInTheDocument();
  });

  it('shows the evidence a hypothesis rests on', () => {
    render(<ProntoAnalysisCard analysis={analysis()} clarifications={[]} />);

    expect(screen.getByText('על סמך:')).toBeInTheDocument();
    expect(
      screen.getByText('הלקוח מדווח שהמים מופיעים רק אחרי שימוש בכיור'),
    ).toBeInTheDocument();
  });

  /** §35: model metadata is backend-internal; the professional sees preparation, not scores. */
  it('never renders the hypothesis confidence as a number', () => {
    render(<ProntoAnalysisCard analysis={analysis()} clarifications={clarifications} />);

    expect(document.body.textContent).not.toContain('0.7');
    expect(document.body.textContent).not.toMatch(/70%|confidence/i);
  });

  it('renders preparation recommendations when the evidence produced any', () => {
    render(
      <ProntoAnalysisCard
        analysis={analysis({
          recommendedTools: ['מפתח צינורות'],
          recommendedParts: ['אטמים לסיפון'],
          possibleCauses: ['אטם שחוק'],
        })}
        clarifications={[]}
      />,
    );

    expect(screen.getByRole('heading', { name: /כלים שכדאי לקחת/ })).toBeInTheDocument();
    expect(screen.getByText('מפתח צינורות')).toBeInTheDocument();
    expect(screen.getByText('אטמים לסיפון')).toBeInTheDocument();
    expect(screen.getByText('אטם שחוק')).toBeInTheDocument();
  });

  /** §20: an empty list is a real answer ("the evidence identifies no part"), shown as silence. */
  it('omits empty sections instead of rendering them blank', () => {
    render(<ProntoAnalysisCard analysis={analysis()} clarifications={[]} />);

    expect(screen.queryByRole('heading', { name: /כלים שכדאי לקחת/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /סיבות אפשריות/ })).not.toBeInTheDocument();
  });

  it('renders nothing at all when generation failed', () => {
    const { container } = render(
      <ProntoAnalysisCard analysis={analysis({ status: 'FAILED' })} clarifications={[]} />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('says the brief is still coming rather than pretending there is none', () => {
    render(<ProntoAnalysisCard analysis={analysis({ status: 'PENDING' })} clarifications={[]} />);

    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('renders nothing when READY but stripped empty by validation', () => {
    const { container } = render(
      <ProntoAnalysisCard
        analysis={analysis({ customerProblemSummary: null, likelyIssue: null })}
        clarifications={[]}
      />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('surfaces safety notes when the evidence indicates a real hazard', () => {
    render(
      <ProntoAnalysisCard
        analysis={analysis({ safetyNotes: ['יש לנתק את החשמל לפני עבודה באזור רטוב'] })}
        clarifications={[]}
      />,
    );

    expect(screen.getByText('יש לנתק את החשמל לפני עבודה באזור רטוב')).toBeInTheDocument();
  });
});
