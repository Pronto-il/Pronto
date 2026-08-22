import { Timer } from 'lucide-react';
import { useCountdown } from '../../shared/hooks';
import type { SosCandidate } from '../../shared/api';
import { SosCandidateCard } from './SosCandidateCard';
import { SOS_SELECTION_PENDING_HINT } from './sosUiState';
import styles from './SosCandidateTray.module.css';

export interface SosCandidateTrayProps {
  candidates: SosCandidate[];
  /** The backend's authority on whether a selection posted right now would be accepted. */
  selectionOpen: boolean;
  /** Absolute deadline for the choosing window. Drives the countdown; `null` before it opens. */
  selectionExpiresAt: string | null;
  /** Absolute deadline of the professional-response window, used to say how long the wait can last. */
  matchingExpiresAt: string | null;
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
 * from the server on every read: the window opens once the target count is reached, or when the
 * response window closes with at least one professional available. Until then the cards are fully
 * readable and comparable but their CTA is disabled, with a line saying why and until when — an
 * enabled button that produced a `SOS_INVALID_STATE` error would be a worse lie than a disabled
 * one that explains itself.
 */
export function SosCandidateTray({
  candidates,
  selectionOpen,
  selectionExpiresAt,
  matchingExpiresAt,
  isSubmitting,
  pendingOfferId,
  onSelect,
  onOpenDetails,
  errorMessage,
}: SosCandidateTrayProps) {
  const selection = useCountdown(selectionOpen ? selectionExpiresAt : null);
  const matching = useCountdown(!selectionOpen && candidates.length > 0 ? matchingExpiresAt : null);

  const isEmpty = candidates.length === 0;

  return (
    <section className={styles.tray} aria-label="בעלי מקצוע שאישרו זמינות">
      <div className={styles.header}>
        <h2 className={styles.title}>
          בעלי מקצוע שאישרו זמינות
          {!isEmpty && <span className={styles.count}>{candidates.length}</span>}
        </h2>

        {/* The countdown is presentation only — the server enforces the deadline on the next
            read regardless of what this shows, and reaching 0:00 here changes nothing by itself. */}
        {selectionOpen && selection.label && (
          <span className={styles.timer} aria-label={`נותרו ${selection.label} דקות לבחירה`}>
            <Timer size={15} aria-hidden="true" />
            <span className={styles.timerValue}>{selection.label}</span>
          </span>
        )}
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
              ? 'אפשר לבחור כבר עכשיו — וממשיכים לחפש עוד אפשרויות עד שתבחרו.'
              : matching.label
                ? `רגע, מעדכנים את מצב הקריאה. עוד ${matching.label} לכל היותר.`
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
