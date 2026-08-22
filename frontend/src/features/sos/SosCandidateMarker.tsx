import { Star } from 'lucide-react';
import type { CSSProperties } from 'react';
import type { SosCandidate } from '../../shared/api';
import { SosAvatar } from './SosAvatar';
import styles from './SosCandidateMarker.module.css';

export interface SosCandidateMarkerProps {
  candidate: SosCandidate;
  /** Which of the fixed scan-surface slots this marker occupies. See `MARKER_SLOTS`. */
  slot: number;
  /** Opens the details surface. **Never selects** — see the component doc. */
  onOpen: (candidate: SosCandidate) => void;
}

/**
 * Fixed, deterministic positions on the scan surface, as a **(row, column) pair** the CSS turns
 * into a real offset.
 *
 * <b>These are not locations.</b> The backend holds no live GPS for professionals, so a marker
 * that implied "he is over there, 300m north" would be inventing data. The scan is a search
 * surface, and these slots are a layout.
 *
 * Three things the geometry has to keep clear of, and does:
 * <ul>
 *   <li><b>The header band</b> (location + counters) across the top of the panel — the rows start
 *       below it.</li>
 *   <li><b>The centre</b>, which marks the customer. The two columns stop well short of the middle
 *       from either side, so the middle row passes either side of the pin rather than over it.</li>
 *   <li><b>The lower band</b>, left empty. That corner of the viewport belongs to the floating
 *       {@code ActiveOrderIndicator}, and nothing tappable may live under it.</li>
 * </ul>
 *
 * <b>Row and column rather than a literal {@code top}/{@code insetInlineStart}</b>, and that is
 * load-bearing: an inline style beats every media query, so a percentage-based table could not be
 * adjusted per breakpoint — and it needed to be. At 360px and below the header wraps to two lines
 * and grows by about 25px, which pushed it straight into the first card row. With the arithmetic
 * in CSS, {@code --sos-scan-header} moves at that breakpoint and every row follows.
 *
 * Six slots for a shortlist that tops out at five (three at the initial scope, plus one per
 * expansion), so the layout never has to wrap or overflow however wide the customer widens the
 * search.
 *
 * Stability matters more than prettiness here. A slot is assigned from the candidate's position in
 * offer-id order, not from its index in the (ETA-sorted) candidates array — otherwise one
 * professional shaving two minutes off their ETA would reshuffle the whole surface and every card
 * would jump to a new place mid-glance.
 */
const MARKER_SLOTS = [
  { row: 0, column: 0 },
  { row: 0, column: 1 },
  { row: 1, column: 0 },
  { row: 1, column: 1 },
  { row: 2, column: 0 },
  { row: 2, column: 1 },
] as const;

export const MARKER_SLOT_COUNT = MARKER_SLOTS.length;

/** First name only. The marker is a glance-sized object; a full name wraps and blows the box. */
function shortName(fullName: string | null): string {
  if (!fullName) {
    return 'בעל מקצוע';
  }
  return fullName.trim().split(/\s+/)[0];
}


/**
 * One available professional, as a compact card floating on the Pronto Scan surface.
 *
 * **It appears the moment they respond.** There is no waiting for three: one available
 * professional is one marker, and the scan carries on looking for more underneath it. That is the
 * product rule the whole SOS screen turns on — a customer with an emergency should see their first
 * real option immediately, not once a quota fills.
 *
 * **Tapping opens, it does not select.** This is deliberate and load-bearing: the marker is a
 * glance-sized summary (photo, first name, rating, ETA) and committing to a professional from four
 * data points, with a mis-tap, on an urgent job that creates a real order, is exactly the mistake
 * worth designing out. The tap opens the details surface; selection stays an explicit
 * "בחר את [name]" press inside it.
 *
 * Everything richer — price breakdown, bio, reviews, service area — belongs in that surface, not
 * here. A marker that grew a price table would stop being readable at a glance, which is its only
 * job.
 */
export function SosCandidateMarker({ candidate, slot, onOpen }: SosCandidateMarkerProps) {
  const { row, column } = MARKER_SLOTS[slot % MARKER_SLOTS.length];
  const name = shortName(candidate.fullName);

  return (
    <button
      type="button"
      className={styles.marker}
      // Two integers, not a position. The CSS owns the arithmetic so a breakpoint can move the
      // whole grid — see MARKER_SLOTS.
      style={{ '--sos-slot-row': row, '--sos-slot-column': column } as CSSProperties}
      onClick={() => onOpen(candidate)}
      // The accessible name spells out what the icons mean and, critically, what pressing does —
      // "פרטים", not "בחירה".
      aria-label={
        `${candidate.fullName ?? 'בעל מקצוע'}` +
        `${candidate.averageRating !== null ? `, דירוג ${candidate.averageRating.toFixed(1)}` : ''}` +
        `${candidate.estimatedArrivalMinutes !== null ? `, הגעה בעוד כ־${candidate.estimatedArrivalMinutes} דקות` : ''}` +
        '. פתיחת פרטים'
      }
    >
      {/* The discovery beat: a single soft ring expanding out of the card as it mounts, so a new
          professional reads as *found* rather than as the surface silently gaining an element.
          Decorative, one cycle, and gone — see the CSS for why it does not repeat. */}
      <span className={styles.discovery} aria-hidden="true" />

      <SosAvatar
        imageUrl={candidate.profileImageUrl}
        fullName={candidate.fullName}
        imageClassName={styles.avatar}
        fallbackClassName={styles.avatarFallback}
      />

      <span className={styles.body}>
        <span className={styles.name}>{name}</span>
        <span className={styles.meta} aria-hidden="true">
          {candidate.averageRating !== null && (
            <span className={styles.rating}>
              <Star size={10} fill="currentColor" aria-hidden="true" />
              {candidate.averageRating.toFixed(1)}
            </span>
          )}
          {candidate.estimatedArrivalMinutes !== null && (
            // Keyed on the ETA so a revision re-triggers the CSS flash — the number changing under
            // a glance is otherwise completely silent.
            <span key={candidate.estimatedArrivalMinutes} className={styles.eta}>
              {candidate.estimatedArrivalMinutes} דק׳
            </span>
          )}
        </span>
      </span>
    </button>
  );
}
