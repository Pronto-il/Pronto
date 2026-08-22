import { Radar } from 'lucide-react';
import { Button } from '../../shared/components';
import styles from './SosScanAgainControl.module.css';

export interface SosScanAgainControlProps {
  /**
   * **The backend's answer**, straight off `SosRequestResponse.canExpandSearch` — not a rule this
   * component re-derives. It is false once a professional is selected, once the request stops
   * searching, and once the configured expansion ceiling is reached.
   */
  canExpand: boolean;
  /** How many expansions have been used, and the platform's ceiling. Both come from the server. */
  expansionsUsed: number;
  maxExpansions: number;
  /** True while a request is in flight — the button disables and says what it is doing. */
  isExpanding: boolean;
  /** Hebrew message from the last failed attempt, or `null`. */
  errorMessage: string | null;
  /** Whether any professional has already said they are available. Changes only the supporting copy. */
  hasCandidates: boolean;
  onExpand: () => void;
}

/**
 * **"סרוק שוב"** — the customer's control over how wide Pronto looks.
 *
 * ## What pressing it does
 *
 * Widens the search on the *same* SOS request: further offers go out to professionals who were not
 * contacted the first time, and the deadline the search runs against is extended. It is a real
 * backend operation (`POST /api/sos/requests/{id}/scan-again`), not a refetch and not an
 * animation — and nothing about the attempt is reset. Every professional who has already said they
 * are available stays on screen and stays selectable throughout, which is the point: this is for
 * the customer who has one option and would like to see whether there is a better one, without
 * risking the one they have.
 *
 * ## Why the state comes from the server
 *
 * `canExpand` is `SosRequestResponse.canExpandSearch`, computed backend-side from the same three
 * conditions the guarded `UPDATE` enforces. This component does not decide when expansion is
 * allowed, and deliberately cannot: a client that worked out the rules for itself would eventually
 * offer a button that 409s, or hide one that would have worked. The double-tap guard here is a
 * courtesy on top of a compare-and-set that already makes two requests produce one expansion.
 *
 * ## What it never says
 *
 * No radius, no kilometres, no wave or scope number. None of that is backed by real geographic data
 * in this milestone (see `sos.service.SosSearchScope` backend-side), and a screen that reads
 * "מרחיבים ל־15 ק״מ" would be making a promise the platform cannot keep. The copy stays in the
 * customer's own terms: we are looking further, and here is when we have looked as far as we can.
 */
export function SosScanAgainControl({
  canExpand,
  expansionsUsed,
  maxExpansions,
  isExpanding,
  errorMessage,
  hasCandidates,
  onExpand,
}: SosScanAgainControlProps) {
  const atMaximum = expansionsUsed >= maxExpansions;

  // At the widest scope the control is replaced by a final, calm statement of fact. The candidates
  // already found stay exactly where they are — this says the search stopped growing, never that
  // it failed.
  if (!canExpand && atMaximum) {
    return (
      <p className={styles.finalState}>
        {hasCandidates
          ? 'הרחבנו את החיפוש עד הסוף. אפשר לבחור מבין בעלי המקצוע שאישרו שהם זמינים.'
          : 'הרחבנו את החיפוש עד הסוף וממשיכים להמתין לתשובות.'}
      </p>
    );
  }

  // Not expandable for any other reason (somebody was selected, the request ended) means the
  // control has no business being on screen at all.
  if (!canExpand) {
    return null;
  }

  return (
    <div className={styles.wrapper}>
      <Button
        variant="secondary"
        onClick={onExpand}
        loading={isExpanding}
        // `loading` already disables, but stating it is what makes the intent survive a future
        // change to Button: two expansions from one impatient double-tap is the thing to prevent.
        disabled={isExpanding}
        fullWidth
      >
        <Radar size={18} aria-hidden="true" />
        {isExpanding ? 'מרחיבים את החיפוש…' : 'סרוק שוב'}
      </Button>

      <p className={styles.hint}>
        {hasCandidates
          ? 'מי שכבר אישר שהוא זמין יישאר כאן, ונחפש עבורך אפשרויות נוספות.'
          : 'נפנה לעוד בעלי מקצוע באזור שלך.'}
      </p>

      {errorMessage && (
        <p className={styles.error} role="alert">
          {errorMessage}
        </p>
      )}
    </div>
  );
}
