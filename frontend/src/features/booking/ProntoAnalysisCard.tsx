import { AlertTriangle, Lightbulb, Package, Wrench } from 'lucide-react';
import { Card } from '../../shared/components';
import type { ClarificationEntry, ProntoAnalysis } from '../../shared/api';
import styles from './ProntoAnalysisCard.module.css';

export interface ProntoAnalysisCardProps {
  analysis: ProntoAnalysis;
  /** The customer's own answers — rendered here as reported fact, visually separate from the
   *  AI's interpretation below them. */
  clarifications: ClarificationEntry[];
}

/**
 * Pronto's preparation brief for the professional, shown on the job screen beneath the
 * customer's own report.
 *
 * **The whole point of this component is the separation it enforces.** The customer's
 * description stays where it is, quoted and untouched, under its own heading; everything in
 * here is labelled as Pronto's analysis and is styled distinctly (tinted surface, "ניתוח
 * Pronto" eyebrow) so a professional can never mistake an AI hypothesis for something the
 * customer actually said. Clarification answers sit at the top of this card as the bridge
 * between the two: customer-supplied fact, which is why they are quoted rather than phrased
 * as findings.
 *
 * Wording is deliberately hedged throughout — "כנראה", "ייתכן", "שווה לקחת" — because nothing
 * here was inspected. The professional does the real diagnosis on site.
 *
 * Empty sections are omitted rather than shown empty: an empty parts list means the evidence
 * did not identify a part, and rendering "מוצרים מומלצים: —" would turn useful silence into
 * visual noise.
 *
 * **Three ways this renders nothing at all**, all returning `null` rather than a shell:
 * generation failed, generation succeeded but produced nothing worth showing, or the caller
 * passed no analysis (the brief is generated asynchronously, so a professional can easily open
 * a job before it exists — see `OrderTrackingPage`'s null guard). In every case the
 * professional still has the customer's description, photos, clarification answers and
 * category above, which is exactly what they had before briefs existed. A card announcing
 * "no analysis available" would be pure noise; worse, on `FAILED` it risks reading as
 * "Pronto looked and could not work it out", which is not what happened.
 *
 * `PENDING` is the one non-empty exception: a single unobtrusive line, because "it is coming"
 * is genuinely different information from "there is none". Deliberately no polling — the
 * brief appears on the next natural fetch.
 */
export function ProntoAnalysisCard({ analysis, clarifications }: ProntoAnalysisCardProps) {
  if (analysis.status === 'PENDING') {
    return (
      <p className={styles.pendingNote} role="status">
        ניתוח Pronto עדיין בהכנה — אפשר להתחיל מהתיאור והתשובות של הלקוח למעלה.
      </p>
    );
  }

  if (analysis.status === 'FAILED') {
    return null;
  }

  const hasLists =
    analysis.possibleCauses.length > 0 ||
    analysis.recommendedTools.length > 0 ||
    analysis.recommendedParts.length > 0;

  // READY but with nothing in it — possible when validation stripped an unsupported hypothesis
  // and the model returned no lists. Render nothing rather than an empty labelled card.
  const hasAnythingToShow =
    Boolean(analysis.customerProblemSummary) ||
    Boolean(analysis.likelyIssue) ||
    analysis.imageObservations.length > 0 ||
    analysis.safetyNotes.length > 0 ||
    hasLists ||
    clarifications.length > 0;

  if (!hasAnythingToShow) {
    return null;
  }

  return (
    <Card className={styles.card}>
      <p className={styles.eyebrow}>ניתוח Pronto</p>

      {clarifications.length > 0 && (
        <div className={styles.section}>
          <h3 className={styles.sectionTitle}>מה הלקוח ענה</h3>
          <dl className={styles.qa}>
            {clarifications.map((entry) => (
              <div key={entry.question} className={styles.qaRow}>
                <dt className={styles.qaQuestion}>{entry.question}</dt>
                <dd className={styles.qaAnswer}>{entry.answer}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}

      {analysis.customerProblemSummary && (
        <p className={styles.summary}>{analysis.customerProblemSummary}</p>
      )}

      {analysis.likelyIssue && (
        <div className={styles.section}>
          <h3 className={styles.sectionTitle}>
            <Lightbulb size={16} aria-hidden="true" className={styles.sectionIcon} />
            מה כנראה הבעיה
          </h3>
          <p className={styles.likelyIssue}>{analysis.likelyIssue.description}</p>
          {analysis.likelyIssue.evidence.length > 0 && (
            <>
              <p className={styles.evidenceLabel}>על סמך:</p>
              <ul className={styles.evidenceList}>
                {analysis.likelyIssue.evidence.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}

      {analysis.imageObservations.length > 0 && (
        <div className={styles.section}>
          <h3 className={styles.sectionTitle}>מה נראה בתמונות</h3>
          <ul className={styles.list}>
            {analysis.imageObservations.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      )}

      {hasLists && (
        <div className={styles.section}>
          {analysis.possibleCauses.length > 0 && (
            <>
              <h3 className={styles.sectionTitle}>סיבות אפשריות</h3>
              <ul className={styles.list}>
                {analysis.possibleCauses.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </>
          )}

          {analysis.recommendedTools.length > 0 && (
            <>
              <h3 className={styles.sectionTitle}>
                <Wrench size={16} aria-hidden="true" className={styles.sectionIcon} />
                כלים שכדאי לקחת
              </h3>
              <ul className={styles.list}>
                {analysis.recommendedTools.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </>
          )}

          {analysis.recommendedParts.length > 0 && (
            <>
              <h3 className={styles.sectionTitle}>
                <Package size={16} aria-hidden="true" className={styles.sectionIcon} />
                חלקים שאולי יידרשו
              </h3>
              <ul className={styles.list}>
                {analysis.recommendedParts.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}

      {analysis.safetyNotes.length > 0 && (
        <div className={styles.safety} role="note">
          <h3 className={styles.safetyTitle}>
            <AlertTriangle size={16} aria-hidden="true" className={styles.sectionIcon} />
            לשים לב
          </h3>
          <ul className={styles.list}>
            {analysis.safetyNotes.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      )}

      <p className={styles.disclaimer}>
        הניתוח מבוסס על מה שהלקוח תיאר ולא על בדיקה בשטח. האבחון הסופי הוא שלך.
      </p>
    </Card>
  );
}
