import { CheckCircle2, MapPin, Navigation, StickyNote } from 'lucide-react';
import { Badge, Button, Card } from '../../shared/components';
import { getCategoryNameHe } from '../../shared/api';
import type { ProSosJob } from '../../shared/hooks';
import { SosStatusSteps } from './SosStatusSteps';
import { SOS_JOB_STEPS } from './sosProUiState';
import styles from './SosJobPanel.module.css';

export interface SosJobPanelProps {
  job: ProSosJob;
  /** The one operational transition available in this state, already resolved by the caller. */
  onAdvance: () => void;
  isAdvancing: boolean;
  /** Revise the committed ETA — still allowed while `SELECTED`, and traffic changes. */
  onReviseEta?: () => void;
  /** Hebrew, user-facing. */
  errorMessage?: string | null;
}

function price(amount: number): string {
  return `₪${Number.isInteger(amount) ? amount : amount.toFixed(2)}`;
}

/**
 * The job a professional was actually chosen for — **the first surface in the professional app
 * that says the work is theirs.** Everything before this is an offer.
 *
 * One primary CTA per state, resolved from `SOS_JOB_STEPS`: אישור יציאה → יצאתי לדרך → הגעתי →
 * סיום העבודה. When a state has no CTA (`action: null`) none is rendered, rather than a disabled
 * button for a transition that doesn't exist — the customer's cancellation and a lapsed
 * confirmation window both land here, and both mean "there is nothing left for you to press".
 *
 * ## Address
 *
 * The exact address renders from `job.request`, which is `GET /api/sos/requests/{id}` — and the
 * backend only fills those fields in for the *selected* professional (`SosAddressAccess.FULL`).
 * If that fetch failed, the panel falls back to the offer's city rather than hiding a job the
 * professional is committed to. Nothing here reconstructs an address from anywhere else.
 *
 * No confirmation countdown is shown at `PROFESSIONAL_SELECTED`: the backend enforces a grace
 * period but does not expose that deadline on any DTO, and a client-side timer derived from a
 * hardcoded config value would be a number this app cannot stand behind. The copy carries the
 * urgency instead.
 */
export function SosJobPanel({ job, onAdvance, isAdvancing, onReviseEta, errorMessage }: SosJobPanelProps) {
  const { offer, request } = job;
  // The request is canonical when present; the offer's echo of the status covers the moment before
  // that fetch lands, and the failure case.
  const status = request?.status ?? offer.requestStatus;
  const step = SOS_JOB_STEPS[status];

  const addressLine = request?.serviceStreet
    ? [
        `${request.serviceStreet} ${request.serviceHouseNumber ?? ''}`.trim(),
        request.serviceApartment ? `דירה ${request.serviceApartment}` : null,
        request.serviceFloor ? `קומה ${request.serviceFloor}` : null,
        request.serviceEntrance ? `כניסה ${request.serviceEntrance}` : null,
      ]
        .filter(Boolean)
        .join(', ')
    : null;
  const city = request?.serviceCity ?? offer.serviceCity;

  return (
    <Card className={styles.panel}>
      <div className={styles.topRow}>
        <span className={styles.selectedMark}>
          <CheckCircle2 size={18} aria-hidden="true" />
          {getCategoryNameHe(offer.categoryId)}
        </span>
        <Badge tone={step.badgeTone} size="sm">
          {step.badgeLabel}
        </Badge>
      </div>

      <div>
        <h2 className={styles.title} aria-live="polite">
          {step.title}
        </h2>
        <p className={styles.description}>{step.description}</p>
      </div>

      <SosStatusSteps status={status} />

      {offer.issueSummary && <p className={styles.summary}>“{offer.issueSummary}”</p>}

      <div className={styles.facts}>
        <p className={styles.fact}>
          <MapPin size={16} aria-hidden="true" />
          <span>
            <span className={styles.factStrong}>{city}</span>
            {addressLine && <span className={styles.factLine}>{addressLine}</span>}
          </span>
        </p>
        {request?.serviceAddressNotes && (
          <p className={styles.fact}>
            <StickyNote size={16} aria-hidden="true" />
            {request.serviceAddressNotes}
          </p>
        )}
        {offer.estimatedArrivalMinutes !== null && (
          <p className={styles.fact}>
            <Navigation size={16} aria-hidden="true" />
            זמן ההגעה שמסרת: כ־{offer.estimatedArrivalMinutes} דק׳
          </p>
        )}
      </div>

      {offer.professionalNet !== null && (
        <div className={styles.netRow}>
          <span className={styles.netLabel}>נטו אליך</span>
          <span className={styles.netValue}>{price(offer.professionalNet)}</span>
        </div>
      )}

      {errorMessage && (
        <p className={styles.error} role="alert">
          {errorMessage}
        </p>
      )}

      {step.cta && (
        <Button onClick={onAdvance} loading={isAdvancing} fullWidth className={styles.cta}>
          {step.cta}
        </Button>
      )}

      {/* Only while the customer is still waiting on arrival — once on site, an ETA is moot. */}
      {onReviseEta && (status === 'PROFESSIONAL_SELECTED' || status === 'CONFIRMED' || status === 'ON_THE_WAY') && (
        <Button variant="ghost" onClick={onReviseEta} disabled={isAdvancing} fullWidth>
          עדכון זמן הגעה
        </Button>
      )}
    </Card>
  );
}
