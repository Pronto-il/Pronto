import { useEffect, useRef } from 'react';
import { MapPin, Navigation, Siren, Timer, X } from 'lucide-react';
import { Badge, Button, Card } from '../../shared/components';
import { useCountdown } from '../../shared/hooks';
import { getCategoryNameHe, isSosOfferOpen, isSosTerminalStatus } from '../../shared/api';
import type { SosOfferResponse } from '../../shared/api';
import { SOS_JOB_STEPS, SOS_OFFER_COPY } from './sosProUiState';
import styles from './SosOfferCard.module.css';

export interface SosOfferCardProps {
  offer: SosOfferResponse;
  /**
   * Fired once when an answerable offer is actually put in front of the professional. The caller
   * turns this into `GET /api/sos/offers/{id}`, which marks it `VIEWED` server-side — so it must
   * only fire when the card is genuinely rendered, never speculatively.
   */
  onViewed?: (offerId: number) => void;
  /** Opens the availability sheet. Absent for a card with nothing to answer. */
  onRespondAvailable?: (offer: SosOfferResponse) => void;
  onDecline?: (offer: SosOfferResponse) => void;
  /** Clears a resolved outcome card early. */
  onDismiss?: (offerId: number) => void;
  /**
   * The response window ran out while this card was on screen. The caller refetches — **the
   * backend decides whether the offer really expired**, this only says the clock hit zero.
   */
  onCountdownElapsed?: (offerId: number) => void;
  isDeclining?: boolean;
  /** Any offer action is in flight; every control on the card is inert. */
  isBusy?: boolean;
}

function price(amount: number): string {
  return `₪${Number.isInteger(amount) ? amount : amount.toFixed(2)}`;
}

/**
 * One SOS offer, in whichever of its seven states it is currently in.
 *
 * **The card never claims the job is theirs until it is.** `ACCEPTED` renders as *"אישרת שאתה
 * זמין"* with a plain informational tone and no success styling, because at that moment the
 * professional is one of up to three candidates and the customer has not chosen. Only `SELECTED`
 * says *"הלקוח בחר בך"* — and a selected offer isn't rendered by this component at all; it becomes
 * `SosJobPanel`, which is a job rather than an offer.
 *
 * **Privacy**: city, distance and ETA only. No street, house number, floor, coordinates or phone —
 * the backend withholds all of them until selection (offers go to up to 15 people), and this card
 * makes no second request to go looking.
 *
 * The countdown is presentation only. At zero the controls go inert and the caller refetches; what
 * the offer *is* remains the server's answer.
 */
export function SosOfferCard({
  offer,
  onViewed,
  onRespondAvailable,
  onDecline,
  onDismiss,
  onCountdownElapsed,
  isDeclining = false,
  isBusy = false,
}: SosOfferCardProps) {
  const isOpen = isSosOfferOpen(offer.status);
  /**
   * A won offer stays `SELECTED` forever, including long after the visit is finished — so once the
   * *request* is terminal, the offer's own status stops describing the situation. Reading the copy
   * off `SOS_OFFER_COPY['SELECTED']` there would tell a professional "הלקוח בחר בך — אשר את
   * היציאה כדי להתחיל" about a job they completed an hour ago, inviting an action that no longer
   * exists. The request's own step copy is the honest answer.
   */
  const isFinishedJob = offer.status === 'SELECTED' && isSosTerminalStatus(offer.requestStatus);
  const copy = isFinishedJob ? SOS_JOB_STEPS[offer.requestStatus] : SOS_OFFER_COPY[offer.status];
  const { label, isElapsed } = useCountdown(isOpen ? offer.expiresAt : null);
  const canRespond = isOpen && !isElapsed;
  /** The fees still mean something: this offer is answerable, or answered and in the running. */
  const isMoneyLive = isOpen || offer.status === 'ACCEPTED';

  // Marking VIEWED is a server-side mutation, so it fires exactly once per mounted open offer.
  const hasReportedViewRef = useRef(false);
  useEffect(() => {
    if (isOpen && !hasReportedViewRef.current) {
      hasReportedViewRef.current = true;
      onViewed?.(offer.id);
    }
  }, [isOpen, offer.id, onViewed]);

  // One shot: the moment the clock runs out, ask the server what actually happened.
  const hasReportedElapsedRef = useRef(false);
  useEffect(() => {
    if (isOpen && isElapsed && !hasReportedElapsedRef.current) {
      hasReportedElapsedRef.current = true;
      onCountdownElapsed?.(offer.id);
    }
  }, [isOpen, isElapsed, offer.id, onCountdownElapsed]);

  return (
    <Card
      className={[
        styles.card,
        isOpen ? styles.live : '',
        isFinishedJob ? styles.finished : (styles[offer.status.toLowerCase()] ?? ''),
        onDismiss ? styles.dismissable : '',
      ]
        .filter(Boolean)
        .join(' ')}
    >
      <div className={styles.topRow}>
        <span className={styles.brand}>
          <span className={styles.mark} aria-hidden="true">
            <Siren size={16} />
          </span>
          <span className={styles.category}>{getCategoryNameHe(offer.categoryId)}</span>
          {offer.urgency === 'EMERGENCY' && <span className={styles.emergency}>חירום</span>}
        </span>
        <Badge tone={copy.badgeTone} size="sm">
          {copy.badgeLabel}
        </Badge>
      </div>

      <div>
        <h3 className={styles.title}>{copy.title}</h3>
        <p className={styles.description}>
          {isOpen && isElapsed ? 'הזמן להגיב הסתיים. בודקים מול השרת מה מצב הקריאה.' : copy.description}
        </p>
      </div>

      {offer.issueSummary && <p className={styles.summary}>“{offer.issueSummary}”</p>}

      {canRespond && label && (
        <p className={styles.countdown} aria-label={`נשארו ${label} דקות להגיב`}>
          <Timer size={16} aria-hidden="true" />
          נשארו <span className={styles.countdownValue}>{label}</span> להגיב
        </p>
      )}

      {/* Street and city — enough to judge the journey and commit to an arrival time. The house
          number and everything below it are withheld by the backend until selection, deliberately;
          `serviceStreet` is simply absent on an older payload, so the city stands alone. */}
      <div className={styles.facts}>
        <span className={styles.fact}>
          <MapPin size={15} aria-hidden="true" />
          {offer.serviceStreet ? `${offer.serviceStreet}, ${offer.serviceCity}` : offer.serviceCity}
          {offer.distanceKm !== null && ` · ${offer.distanceKm.toFixed(1)} ק״מ`}
        </span>
        {offer.estimatedArrivalMinutes !== null && (
          <span className={styles.fact}>
            <Navigation size={15} aria-hidden="true" />
            {offer.status === 'ACCEPTED' || offer.status === 'NOT_SELECTED'
              ? `זמן ההגעה שמסרת: כ־${offer.estimatedArrivalMinutes} דק׳`
              : `הערכת הגעה: כ־${offer.estimatedArrivalMinutes} דק׳`}
          </span>
        )}
      </div>

      {/* What answering is worth, itemized — but only while it is still worth anything.
          `professionalNet` on a card that says the customer chose someone else would read as money
          they are owed, so a resolved outcome shows no figures at all. */}
      {isMoneyLive && (
        <div className={styles.money}>
          {offer.visitFee !== null && (
            <div className={styles.moneyRow}>
              <span className={styles.moneyLabel}>דמי ביקור</span>
              <span className={styles.moneyValue}>{price(offer.visitFee)}</span>
            </div>
          )}
          <div className={styles.moneyRow}>
            <span className={styles.moneyLabel}>תוספת SOS</span>
            <span className={styles.moneyValue}>{price(offer.sosFee)}</span>
          </div>
          {/* Computed server-side — never arithmetic done here. */}
          {offer.professionalNet !== null && (
            <div className={`${styles.moneyRow} ${styles.netRow}`}>
              <span className={styles.netLabel}>נטו אליך</span>
              <span className={styles.netValue}>{price(offer.professionalNet)}</span>
            </div>
          )}
        </div>
      )}

      {isOpen && (
        <div className={styles.actions}>
          <Button
            variant="secondary"
            onClick={() => onDecline?.(offer)}
            disabled={!canRespond || isBusy}
            loading={isDeclining}
            fullWidth
          >
            לא זמין
          </Button>
          <Button onClick={() => onRespondAvailable?.(offer)} disabled={!canRespond || isBusy} fullWidth>
            אני יכול להגיע
          </Button>
        </div>
      )}

      {offer.status === 'ACCEPTED' && (
        <div className={styles.waitingFooter}>
          <p className={styles.waitingNote}>
            האישור נשלח ללקוח עם זמן ההגעה שמסרת. מחכים לבחירה שלו.
          </p>
        </div>
      )}

      {onDismiss && (
        <button
          type="button"
          className={styles.dismiss}
          onClick={() => onDismiss(offer.id)}
          aria-label="הסרת ההודעה"
        >
          <X size={16} aria-hidden="true" />
        </button>
      )}
    </Card>
  );
}
