import { Siren } from 'lucide-react';
import { Badge } from '../../shared/components';
import { getCategoryNameHe } from '../../shared/api';
import type { SosRequestStatus } from '../../shared/api';
import { SOS_STATUS_COPY } from './sosUiState';
import styles from './SosHeader.module.css';

export interface SosHeaderProps {
  status: SosRequestStatus;
  categoryId: number;
  /** The service city, shown as the second context chip. Omitted when unknown. */
  city?: string | null;
  /** Subtle "live channel is up" affordance. Absent rather than red when it is down — a broken
   *  socket is not the customer's problem, and REST polling keeps the screen correct. */
  isLive?: boolean;
}

/**
 * The screen's status area: what this is, what is happening right now, and for which service.
 *
 * One brand mark, one status pill, one sentence — the customer reading this is under time
 * pressure, so the header answers "what is happening" and then gets out of the way
 * (FRONTEND_AGENT.md §32). The whole screen never turns red (§13): the SOS colour is the mark
 * and the wordmark only.
 */
export function SosHeader({ status, categoryId, city, isLive = false }: SosHeaderProps) {
  const copy = SOS_STATUS_COPY[status];

  return (
    <header className={styles.header}>
      <div className={styles.topRow}>
        <span className={styles.brand}>
          <span className={styles.mark} aria-hidden="true">
            <Siren size={18} />
          </span>
          <span className={styles.wordmark}>Pronto SOS</span>
        </span>
        <Badge tone={copy.badgeTone} size="sm">
          {copy.badgeLabel}
        </Badge>
      </div>

      {/* `aria-live` so a screen-reader user hears each transition without re-reading the page —
          this is the one element that always states the current state in words. */}
      <h1 className={styles.title} aria-live="polite">
        {copy.title}
      </h1>
      <p className={styles.description}>{copy.description}</p>

      <div className={styles.context}>
        <span className={styles.chip}>{getCategoryNameHe(categoryId)}</span>
        {city && <span className={styles.chip}>{city}</span>}
        {isLive && (
          <span className={styles.live}>
            <span className={styles.liveDot} aria-hidden="true" />
            עדכון חי
          </span>
        )}
      </div>
    </header>
  );
}
