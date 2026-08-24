import { MapPin } from 'lucide-react';
import type { SosCandidate } from '../../shared/api';
import { MARKER_SLOT_COUNT, SosCandidateMarker } from './SosCandidateMarker';
import { SosScanDecor } from './SosScanDecor';
import styles from './SosScanPanel.module.css';

export type SosScanState =
  /** Offers are out and answers are still coming in — the rings breathe. */
  | 'scanning'
  /** Somebody is available and the customer may choose — the surface calms so they can read. */
  | 'ready'
  /** Terminal without a professional — the scan is over and says so. */
  | 'stopped';

export interface SosScanPanelProps {
  state: SosScanState;
  /** Where the professional has to reach. The centre of the scan, literally. */
  city: string;
  /** Street + number, when the customer is entitled to see it (they always are — it's theirs). */
  addressLine?: string | null;
  /** How many professionals were contacted. Never who they are. */
  offerCount: number;
  /** How many have said they are available so far. */
  availableCount: number;
  /**
   * The available professionals themselves, rendered as tappable mini-cards on the surface.
   * Mobile-only presentation (see the CSS): desktop keeps the richer tray alongside the panel.
   * Defaults to empty, so the panel still renders standalone in its terminal/stopped states.
   */
  candidates?: SosCandidate[];
  /** Opens the details surface. **Never selects** — see `SosCandidateMarker`. */
  onOpenCandidate?: (candidate: SosCandidate) => void;
  /**
   * How many times the search has widened by itself so far (`sos_requests.search_expansions`).
   * The surface says so once it is above zero — the customer never asked for it, so if the
   * platform quietly went further afield it should say that rather than look idle.
   */
  searchExpansions?: number;
  /** Server-derived: is anybody new still being contacted? Drives what the panel *says*, never
   *  what the customer may do. */
  stillSearching?: boolean;
}

/**
 * **Pronto Scan** — the screen's live search surface, in its final visual treatment.
 *
 * ## What it is trying to say
 *
 * "Pronto is actively looking for the right available professional." Calm, premium and
 * unmistakably Pronto — three soft teal rings breathing outward from a clean centre, six
 * home-service tools drifting around them, and a scatter of much smaller hardware between the two.
 * Deliberately **not** a radar: an earlier pass used a rotating conic sweep over a map grid, which
 * reads as military tracking, implies the platform knows where people physically are (it does not —
 * there is no GPS in this milestone), and turns an anxious moment into a tense one. Nothing here
 * sweeps, glows or moves quickly; the fastest ambient cycle takes six seconds.
 *
 * The decoration is `SosScanDecor` — see it for what the two tiers are for, and the CSS for how
 * placement and motion are kept on separate properties so an animation cannot discard a position.
 *
 * ## Layout, and one rule it exists to enforce
 *
 * The location and the two counters sit in a **header at the top of the panel**, not in a strip
 * along the bottom. That is a fix, not a preference: the bottom-inline-end corner of the viewport
 * belongs to the floating `ActiveOrderIndicator`, which was landing squarely on top of the stats
 * on mobile. Everything the customer needs to read now lives above the fold of that corner, and
 * the candidate slots deliberately leave the lower band clear as well.
 *
 * Everything animated is CSS, all of it stops under `prefers-reduced-motion` (where the panel
 * still reads correctly as a static diagram), and there is no animation library involved.
 *
 * The two counters are the honest, aggregate view the backend deliberately gives a customer:
 * *how many* were contacted and *how many* are available — never who declined or who ignored it.
 */
export function SosScanPanel({
  state,
  city,
  addressLine,
  offerCount,
  availableCount,
  candidates = [],
  onOpenCandidate,
  searchExpansions = 0,
  stillSearching = true,
}: SosScanPanelProps) {
  /**
   * Slot assignment, by ascending offer id rather than by array index.
   *
   * `candidates` arrives ETA-sorted from the server, so indexing into it directly would re-seat
   * every card the instant one professional revised their ETA — the customer would look up from
   * reading one card to find them all somewhere else. Offer ids are assigned at dispatch and never
   * change, so a card claims a slot when it appears and keeps it.
   */
  const slotByOfferId = new Map(
    [...candidates]
      .sort((a, b) => a.offerId - b.offerId)
      .map((candidate, index) => [candidate.offerId, index % MARKER_SLOT_COUNT]),
  );
  const markers = candidates.slice(0, MARKER_SLOT_COUNT);

  /** What the discovery ripple is keyed on. Only meaningful while the scan is still running. */
  const isSearching = state !== 'stopped';
  const discoveryCount = availableCount;

  return (
    <section
      className={`${styles.panel} ${styles[state]}`}
      aria-label="חיפוש בעל מקצוע"
    >
      {/* The scan itself. Entirely decorative — everything it represents is stated in words by the
          header below and by SosHeader above, so none of it is exposed to assistive tech. */}
      <div className={styles.scan} aria-hidden="true">
        <span className={`${styles.ring} ${styles.ringOuter}`} />
        <span className={`${styles.ring} ${styles.ringMiddle}`} />
        <span className={`${styles.ring} ${styles.ringInner}`} />

        {/* Hardware first, tools over it: the small pieces are the layer furthest back, so a
            13px screw can never sit on top of a 30px wrench. Both are below the centre and far
            below the candidate cards. */}
        <SosScanDecor tier="part" />
        <SosScanDecor tier="tool" />

        {/*
          "Pronto found someone." Keyed on the candidate count, so a new arrival unmounts the old
          ripple and mounts a fresh one — which restarts a run-once animation without a class
          toggle, and guarantees it plays exactly once per discovery rather than looping. Suppressed
          at zero so the panel does not announce a discovery on first paint.
        */}
        {isSearching && discoveryCount > 0 && (
          <span key={discoveryCount} className={styles.discoveryRipple} />
        )}

        <span className={styles.coreHalo} />
        <span className={styles.core}>
          <MapPin size={20} strokeWidth={2} />
        </span>
      </div>

      {/* Location + the aggregate counters, together at the top. See this component's doc for why
          they are not along the bottom any more. */}
      <div className={styles.header}>
        <div className={styles.location}>
          <span className={styles.locationCity}>{city}</span>
          {addressLine && <span className={styles.locationAddress}>{addressLine}</span>}
        </div>

        {/* `aria-live` off deliberately — SosHeader already narrates state changes, and a second
            live region would talk over it. */}
        <dl className={styles.stats}>
          <div className={styles.stat}>
            <dt className={styles.statLabel}>נשלחו קריאות</dt>
            <dd className={styles.statValue}>{offerCount}</dd>
          </div>
          <div className={`${styles.stat} ${availableCount > 0 ? styles.statActive : ''}`}>
            <dt className={styles.statLabel}>אישרו זמינות</dt>
            <dd className={styles.statValue}>{availableCount}</dd>
          </div>
        </dl>
      </div>

      {/* Mobile: every available professional as a tappable card, directly on the search surface.
          One appears the moment one responds — never held back waiting for a third. */}
      {onOpenCandidate && markers.length > 0 && (
        <div className={styles.markers}>
          {markers.map((candidate) => (
            <SosCandidateMarker
              key={candidate.offerId}
              candidate={candidate}
              slot={slotByOfferId.get(candidate.offerId) ?? 0}
              onOpen={onOpenCandidate}
            />
          ))}
        </div>
      )}

      {/* What the scan is actually doing, in the customer's terms. Only professionals who
          confirm they can come appear here, and each brings an arrival time — saying so is what
          stops "נשלחו קריאות 8 / אישרו זמינות 0" reading as a failure thirty seconds in. No
          radius, no distance and no wave number: none of that is backed by real data yet. */}
      {isSearching && (
        <div className={styles.notes}>
          {/* The platform went further afield on its own. Stated plainly rather than left to look
              like nothing is happening — the customer pressed nothing and should still know. */}
          {searchExpansions > 0 && (
            <p className={styles.expandingNote}>הרחבנו את החיפוש כדי למצוא עוד בעלי מקצוע</p>
          )}
          <p className={styles.searchNote}>
            {!stillSearching
              ? 'סיימנו לפנות לבעלי מקצוע. מי שאישר זמינות נשאר זמין לבחירה.'
              : state === 'scanning'
                ? 'פונים לבעלי מקצוע מתאימים באזור. רק מי שמאשר שהוא יכול להגיע יופיע כאן, עם זמן ההגעה שלו.'
                : 'ממשיכים לפנות לבעלי מקצוע נוספים. אפשר לבחור כבר עכשיו מבין מי שאישר.'}
          </p>
        </div>
      )}
    </section>
  );
}
