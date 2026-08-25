import { Check } from 'lucide-react';
import type { OrderStatus } from '../../shared/api';
import styles from './OrderProgressStepper.module.css';

export interface OrderProgressStepperProps {
  status: OrderStatus;
}

/** The happy path, in order. Terminal-negative statuses don't appear here — see the component
 *  doc: they render no stepper at all rather than a fake final stage.
 *
 *  Display labels only: `key` is the unchanged backend `OrderStatus` enum value, and this table
 *  is the single place the tracking screen maps a status to the wording the customer sees. */
const STAGES: { key: OrderStatus; label: string }[] = [
  { key: 'PENDING', label: 'בטיפול' },
  { key: 'CONFIRMED', label: 'אושרה' },
  { key: 'ON_THE_WAY', label: 'בדרך אליך' },
  // Production MS2. A real stage rather than a variant of "בדרך אליך", because it is a
  // genuinely new fact about the world: the platform has verified that this person is at the
  // customer's address. Note it is OPTIONAL in the backend -- ON_THE_WAY -> COMPLETED is still
  // legal for a professional whose device has no usable fix -- and the stepper handles that
  // correctly without special-casing: an order that jumps straight to COMPLETED simply has both
  // preceding stages behind its index and renders them all as done.
  { key: 'ARRIVED', label: 'הגיע אליך' },
  { key: 'COMPLETED', label: 'הושלמה' },
];

/**
 * Four-stage progress for an order on the happy path (design doc §3.B). Answers the question
 * a `PENDING` customer otherwise has no way to answer — "what happens next, and where am I?"
 *
 * **Deliberately carries no timestamps.** `orders` persists `bookedStart`/`bookedEnd`/
 * `expectedArrivalAt` and nothing else time-wise — there is no `confirmedAt`/`onTheWayAt`
 * column and no field for one on `OrderDetailResponse` — so a stage can honestly be marked
 * done/current/upcoming, but "אושר ב-14:12" would be invented. Not invented.
 *
 * Renders nothing for `CANCELLED`/`REJECTED`/`EXPIRED`: an order that ended early has no
 * meaningful position on this track, and drawing three greyed-out future stages under a
 * "cancelled" hero reads as a system that hasn't noticed. The hero carries those states
 * instead.
 */
export function OrderProgressStepper({ status }: OrderProgressStepperProps) {
  const currentIndex = STAGES.findIndex((stage) => stage.key === status);
  if (currentIndex === -1) {
    return null;
  }

  return (
    <ol className={styles.stepper} aria-label="התקדמות ההזמנה">
      {STAGES.map((stage, index) => {
        // A completed order has no "current" stage — every stage including the last is done,
        // so the track reads as finished rather than as still waiting on its final step.
        const isFinished = status === 'COMPLETED';
        const isDone = index < currentIndex || isFinished;
        const isCurrent = !isFinished && index === currentIndex;
        return (
          <li
            key={stage.key}
            className={`${styles.stage} ${isDone ? styles.done : ''} ${isCurrent ? styles.current : ''}`}
            aria-current={isCurrent ? 'step' : undefined}
          >
            <span className={styles.marker} aria-hidden="true">
              {isDone ? <Check size={13} strokeWidth={3} /> : <span className={styles.dot} />}
            </span>
            <span className={styles.label}>{stage.label}</span>
          </li>
        );
      })}
    </ol>
  );
}
