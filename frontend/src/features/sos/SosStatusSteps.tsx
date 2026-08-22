import { Check } from 'lucide-react';
import type { SosRequestStatus } from '../../shared/api';
import { SOS_TRACKING_STEPS } from './sosUiState';
import styles from './SosStatusSteps.module.css';

export interface SosStatusStepsProps {
  status: SosRequestStatus;
}

/**
 * Post-selection progress: נבחר → אישר → בדרך → הגיע → הושלם.
 *
 * The same idea as `features/booking`'s `OrderProgressStepper`, with the one step a scheduled
 * booking has no equivalent for: `ARRIVED`. An urgent call needs the "they're here" beat, which
 * is why the SOS lifecycle carries a status `orders` does not.
 *
 * Rendered only once a professional owns the job — before that there is no linear progress to
 * show, only a search, which the scan panel is for.
 */
export function SosStatusSteps({ status }: SosStatusStepsProps) {
  const currentIndex = SOS_TRACKING_STEPS.findIndex((step) => step.status === status);

  return (
    <ol className={styles.steps}>
      {SOS_TRACKING_STEPS.map((step, index) => {
        const isDone = currentIndex > index;
        const isCurrent = currentIndex === index;
        return (
          <li
            key={step.status}
            className={`${styles.step} ${isDone ? styles.done : ''} ${isCurrent ? styles.current : ''}`}
            aria-current={isCurrent ? 'step' : undefined}
          >
            <span className={styles.marker} aria-hidden="true">
              {isDone ? <Check size={13} strokeWidth={3} /> : <span className={styles.dot} />}
            </span>
            <span className={styles.label}>{step.label}</span>
          </li>
        );
      })}
    </ol>
  );
}
