import type { SosCandidate } from '../../shared/api';
import { SosCandidateCard } from './SosCandidateCard';
import { SOS_SELECTION_PENDING_HINT } from './sosUiState';
import styles from './SosCandidateTray.module.css';

export interface SosCandidateTrayProps {
  candidates: SosCandidate[];
  /** The backend's authority on whether a selection posted right now would be accepted. */
  selectionOpen: boolean;
  /** Server-derived: is anybody new still being contacted? Only changes what the hint *says* —
   *  the choice is open either way. */
  stillSearching: boolean;
  isSubmitting: boolean;
  pendingOfferId: number | null;
  onSelect: (candidate: SosCandidate) => void;
  /**
   * Opens the in-place details surface. Distinct from `onSelect` on purpose: inspecting a
   * professional and committing to one are different decisions, and the tray offers both without
   * ever conflating them.
   */
  onOpenDetails: (candidate: SosCandidate) => void;
  /** Hebrew, user-facing. Rendered above the list so it isn't hidden behind a card the user scrolled past. */
  errorMessage?: string | null;
}

/**
 * The live tray — **the core product behaviour of this screen.**
 *
 * A professional who has responded is saying only *"I am available and can come"*, and each one
 * appears here the moment they say it. The tray never waits for three: it starts empty with a
 * waiting state, fills progressively, and keeps filling while the backend is still searching.
 * Earlier candidates are never hidden to make room for later ones.
 *
 * What the tray does **not** decide is whether the customer may commit. `selectionOpen` comes
 * from the server on every read — it is true from the first acceptance onward, and stays true
 * until the customer acts. Until then the cards are fully readable and comparable but their CTA
 * is disabled, with a line saying so: an enabled button that produced a `SOS_INVALID_STATE`
 * error would be a worse lie than a disabled one that explains itself.
 *
 * **There is no countdown here any more** (MS3 follow-up). The tray used to show the customer's
 * decision window ticking down, because there was one — ten minutes from the first acceptance,
 * after which every professional who had committed to come was deleted. That rule is gone, and
 * so is the clock: a timer counting toward nothing would be the most misleading thing on the
 * screen, and its absence is what tells the customer, correctly, that they can take the time
 * they need.
 */
export function SosCandidateTray({
  candidates,
  selectionOpen,
  stillSearching,
  isSubmitting,
  pendingOfferId,
  onSelect,
  onOpenDetails,
  errorMessage,
}: SosCandidateTrayProps) {
  const isEmpty = candidates.length === 0;

  return (
    <section className={styles.tray} aria-label="בעלי מקצוע שאישרו זמינות">
      <div className={styles.header}>
        <h2 className={styles.title}>
          בעלי מקצוע שאישרו זמינות
          {!isEmpty && <span className={styles.count}>{candidates.length}</span>}
        </h2>

      </div>

      {errorMessage && (
        <p className={styles.error} role="alert">
          {errorMessage}
        </p>
      )}

      {isEmpty ? (
        <div className={styles.waiting}>
          <span className={styles.dots} aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          <p className={styles.waitingTitle}>ממתינים לאישורי זמינות</p>
          <p className={styles.waitingBody}>
            ברגע שבעל מקצוע יאשר שהוא פנוי להגיע, הוא יופיע כאן — לא צריך להמתין לכולם.
          </p>
        </div>
      ) : (
        <>
          <p className={styles.hint}>
            {selectionOpen
              ? stillSearching
                ? 'אפשר לבחור בכל רגע — הבחירה נשארת פתוחה, וממשיכים לחפש עוד אפשרויות בינתיים.'
                : 'סיימנו לחפש. הבחירה נשארת פתוחה — אפשר לבחור מתי שנוח.'
              : // The shared fallback, also used by the details sheet — one rule, one wording.
                SOS_SELECTION_PENDING_HINT}
          </p>

          <ul className={styles.list}>
            {candidates.map((candidate) => (
              <li key={candidate.offerId}>
                <SosCandidateCard
                  candidate={candidate}
                  selectionOpen={selectionOpen}
                  isSubmitting={isSubmitting}
                  isPending={pendingOfferId === candidate.offerId}
                  onSelect={onSelect}
                  onOpenDetails={onOpenDetails}
                />
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
