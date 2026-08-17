import { useState } from 'react';
import type { FormEvent } from 'react';
import { Input, Button } from '../../shared/components';
import { createAvailabilitySlot, ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import type { SlotResponse } from '../../shared/api';
import styles from './SlotForm.module.css';

export interface SlotFormProps {
  onCreated: (slot: SlotResponse) => void;
}

/**
 * "הוספת זמן פנוי" — two `datetime-local` inputs + submit → `POST /api/availability/slots`.
 * `datetime-local` values have no timezone of their own; `new Date(value)` interprets them
 * in the browser's local timezone, which is the professional's own timezone — the same
 * assumption `Date#toISOString()` then encodes as the UTC-offset ISO string the backend
 * expects.
 */
export function SlotForm({ onCreated }: SlotFormProps) {
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [bannerError, setBannerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBannerError(null);
    setFieldError(undefined);

    if (!startTime || !endTime) {
      setFieldError('יש למלא תאריך ושעת התחלה וסיום.');
      return;
    }
    const start = new Date(startTime);
    const end = new Date(endTime);
    if (end <= start) {
      setFieldError('שעת הסיום צריכה להיות אחרי שעת ההתחלה.');
      return;
    }

    setIsSubmitting(true);
    try {
      const slot = await createAvailabilitySlot({
        startTime: start.toISOString(),
        endTime: end.toISOString(),
      });
      onCreated(slot);
      setStartTime('');
      setEndTime('');
    } catch (error) {
      if (error instanceof ApiError && error.code === 'VALIDATION_ERROR') {
        setFieldError('יש לבדוק את התאריכים שהוזנו.');
      } else {
        setBannerError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit} noValidate>
      {bannerError && (
        <div className={styles.banner} role="alert">
          <p>{bannerError}</p>
        </div>
      )}
      <div className={styles.fields}>
        <Input
          label="התחלה"
          type="datetime-local"
          value={startTime}
          onChange={(event) => setStartTime(event.target.value)}
          error={fieldError && !endTime ? fieldError : undefined}
          required
        />
        <Input
          label="סיום"
          type="datetime-local"
          value={endTime}
          onChange={(event) => setEndTime(event.target.value)}
          error={fieldError && endTime ? fieldError : undefined}
          required
        />
      </div>
      <Button type="submit" loading={isSubmitting}>
        הוספת זמן פנוי
      </Button>
    </form>
  );
}
