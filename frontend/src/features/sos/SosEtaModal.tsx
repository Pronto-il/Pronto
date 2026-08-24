import { useEffect, useState } from 'react';
import { Button, FilterChipGroup, Input, Modal } from '../../shared/components';
import type { FilterChipOption } from '../../shared/components';
import { SOS_ETA_MAX_MINUTES, SOS_ETA_MIN_MINUTES } from '../../shared/api';
import type { SosOfferResponse } from '../../shared/api';
import { SOS_ETA_PRESET_MINUTES } from './sosProUiState';
import styles from './SosEtaModal.module.css';

export interface SosEtaModalProps {
  isOpen: boolean;
  /** The offer being answered. `null` while the sheet is closed. */
  offer: SosOfferResponse | null;
  isSubmitting: boolean;
  /** Hebrew, user-facing. Rendered inside the sheet so the failure sits next to the retry. */
  errorMessage?: string | null;
  onSubmit: (estimatedArrivalMinutes: number) => void;
  onClose: () => void;
}

const PRESET_OPTIONS: FilterChipOption[] = SOS_ETA_PRESET_MINUTES.map((minutes) => ({
  value: String(minutes),
  label: `${minutes} דק׳`,
}));

/** The default offered when the platform has no estimate to prefill — a realistic urgent call-out. */
const FALLBACK_ETA_MINUTES = 30;

/**
 * The availability sheet: **"I can come, and here is when."**
 *
 * One job, since MS3: committing an ETA at the moment of accepting. The revise mode it used to
 * carry is gone because the commitment is now final — the customer chooses partly on this number,
 * so a professional able to revise it afterwards could win the job with fifteen minutes and
 * deliver fifty. The backend refuses a revision outright (`409 SOS_ETA_LOCKED`); this sheet not
 * offering one is the honest front of that rule, not the enforcement of it.
 *
 * The wording is load-bearing. The confirm button says **"אישור זמינות"**, never "קבלת העבודה" —
 * the professional is telling the customer they are free, and the customer may still choose
 * somebody else. The note under the field says so out loud, and now also that the time is final.
 *
 * Bounds mirror the backend's `@Min(0) @Max(480)` so an out-of-range value is refused here rather
 * than spending a round trip on a guaranteed `VALIDATION_ERROR`. 480 is a fat-finger guard, not a
 * business rule.
 */
export function SosEtaModal({
  isOpen,
  offer,
  isSubmitting,
  errorMessage,
  onSubmit,
  onClose,
}: SosEtaModalProps) {
  const [minutes, setMinutes] = useState<string>(String(FALLBACK_ETA_MINUTES));
  const [validationError, setValidationError] = useState<string | null>(null);

  // Prefilled from the platform's dispatch-time estimate, as a starting point the professional
  // is expected to correct. Re-seeded per opening, keyed on the offer, so the sheet never opens
  // showing the previous offer's answer.
  useEffect(() => {
    if (!isOpen || !offer) {
      return;
    }
    setValidationError(null);
    setMinutes(String(offer.estimatedArrivalMinutes ?? FALLBACK_ETA_MINUTES));
  }, [isOpen, offer]);

  function handleSubmit() {
    const parsed = Number(minutes);
    if (!Number.isInteger(parsed) || parsed < SOS_ETA_MIN_MINUTES || parsed > SOS_ETA_MAX_MINUTES) {
      setValidationError(`יש להזין מספר דקות שלם בין ${SOS_ETA_MIN_MINUTES} ל־${SOS_ETA_MAX_MINUTES}.`);
      return;
    }
    setValidationError(null);
    onSubmit(parsed);
  }

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="תוך כמה זמן תוכל להגיע?"
      size="normal"
      footer={
        <div className={styles.actions}>
          <Button onClick={handleSubmit} loading={isSubmitting} fullWidth>
            אישור זמינות
          </Button>
          <Button variant="secondary" onClick={onClose} disabled={isSubmitting} fullWidth>
            ביטול
          </Button>
        </div>
      }
    >
      <div className={styles.body}>
        <p className={styles.intro}>
          הזמן שתמסור הוא מה שהלקוח יראה כשהוא בוחר, ולא ניתן לשנות אותו אחר כך. עדיף להיות מדויק.
        </p>

        <FilterChipGroup
          options={PRESET_OPTIONS}
          value={minutes}
          onChange={(next) => {
            setMinutes(next);
            setValidationError(null);
          }}
          aria-label="זמן הגעה משוער"
        />

        <Input
          label="או זמן אחר (בדקות)"
          type="number"
          inputMode="numeric"
          min={SOS_ETA_MIN_MINUTES}
          max={SOS_ETA_MAX_MINUTES}
          value={minutes}
          onChange={(event) => {
            setMinutes(event.target.value);
            setValidationError(null);
          }}
          error={validationError ?? undefined}
        />

        {/* The whole point, said plainly at the moment of commitment — including the part that
            is new: the time is a promise, not an estimate that can be edited later. */}
        <p className={styles.note}>
          אישור זמינות אומר שאתה יכול להגיע — הוא לא אומר שקיבלת את העבודה. הלקוח רואה את כל מי
          שאישר זמינות, עם זמן ההגעה של כל אחד, ובוחר. הזמן שתמסור ננעל ולא ניתן לעדכון.
        </p>

        {errorMessage && (
          <p className={styles.error} role="alert">
            {errorMessage}
          </p>
        )}
      </div>
    </Modal>
  );
}
