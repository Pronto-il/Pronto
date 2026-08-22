import { useEffect, useState } from 'react';
import { Button, FilterChipGroup, Input, Modal } from '../../shared/components';
import type { FilterChipOption } from '../../shared/components';
import { SOS_ETA_MAX_MINUTES, SOS_ETA_MIN_MINUTES } from '../../shared/api';
import type { SosOfferResponse } from '../../shared/api';
import { SOS_ETA_PRESET_MINUTES } from './sosProUiState';
import styles from './SosEtaModal.module.css';

export type SosEtaMode = 'accept' | 'revise';

export interface SosEtaModalProps {
  isOpen: boolean;
  mode: SosEtaMode;
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
 * Two jobs, one component, because they ask the identical question: committing an ETA when
 * responding available (`accept`), and revising it afterwards (`revise`). The backend treats them
 * as separate endpoints only because one may omit the value and the other may not.
 *
 * The wording is load-bearing. The confirm button says **"אישור זמינות"**, never "קבלת העבודה" —
 * the professional is telling the customer they are free, and the customer may still choose
 * somebody else. The note under the field says so out loud.
 *
 * Bounds mirror the backend's `@Min(0) @Max(480)` so an out-of-range value is refused here rather
 * than spending a round trip on a guaranteed `VALIDATION_ERROR`. 480 is a fat-finger guard, not a
 * business rule.
 */
export function SosEtaModal({
  isOpen,
  mode,
  offer,
  isSubmitting,
  errorMessage,
  onSubmit,
  onClose,
}: SosEtaModalProps) {
  const [minutes, setMinutes] = useState<string>(String(FALLBACK_ETA_MINUTES));
  const [validationError, setValidationError] = useState<string | null>(null);

  // Prefilled from whatever figure already exists — the platform's dispatch-time estimate when
  // accepting, the professional's own committed one when revising. Re-seeded per opening, keyed on
  // the offer, so the sheet never opens showing the previous offer's answer.
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
      title={mode === 'accept' ? 'תוך כמה זמן תוכל להגיע?' : 'עדכון זמן הגעה'}
      size="normal"
      footer={
        <div className={styles.actions}>
          <Button onClick={handleSubmit} loading={isSubmitting} fullWidth>
            {mode === 'accept' ? 'אישור זמינות' : 'עדכון זמן ההגעה'}
          </Button>
          <Button variant="secondary" onClick={onClose} disabled={isSubmitting} fullWidth>
            ביטול
          </Button>
        </div>
      }
    >
      <div className={styles.body}>
        <p className={styles.intro}>
          {mode === 'accept'
            ? 'הזמן שתמסור הוא מה שהלקוח יראה כשהוא בוחר. עדיף להיות מדויק.'
            : 'הלקוח יראה את הזמן המעודכן מיד.'}
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

        {/* The whole point, said plainly at the moment of commitment. */}
        {mode === 'accept' && (
          <p className={styles.note}>
            אישור זמינות אומר שאתה יכול להגיע — הוא לא אומר שקיבלת את העבודה. הלקוח רואה עד שלושה
            בעלי מקצוע זמינים ובוחר אחד מהם.
          </p>
        )}

        {errorMessage && (
          <p className={styles.error} role="alert">
            {errorMessage}
          </p>
        )}
      </div>
    </Modal>
  );
}
