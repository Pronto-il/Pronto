import { useCallback, useRef, useState } from 'react';
import { ApiError, checkContactAvailability } from '../../shared/api';
import type { ContactField } from '../../shared/api';
import { EMAIL_INVALID_MESSAGE, EMAIL_TAKEN_MESSAGE, PHONE_INVALID_MESSAGE, PHONE_TAKEN_MESSAGE } from './registrationValidation';

/**
 * `idle`      nothing has been asked about the current value
 * `checking`  a request for the current value is in flight
 * `available` the backend said registration would accept it
 * `taken`     the backend said it is already registered
 * `invalid`   the backend rejected the value's shape — for a phone this is the real
 *             libphonenumber verdict (`03-1234567` is not a number that can receive an SMS),
 *             which the local shape rule deliberately cannot produce
 * `unknown`   the question could not be asked: offline, rate limited, or a server error
 */
export type AvailabilityStatus =
  | 'idle'
  | 'checking'
  | 'available'
  | 'taken'
  | 'invalid'
  | 'unknown';

export interface ContactAvailability {
  /** The status of the value currently in the field. Reverts to `idle` the moment the customer
   *  edits it, because the previous answer was about the previous value. */
  status: AvailabilityStatus;
  /** Hebrew copy for the field, or `undefined` when there is nothing to say. */
  error: string | undefined;
  /** Whether this field must block the wizard. See {@link isBlocking}. */
  blocking: boolean;
  /**
   * Asks about `value`, or returns the cached/in-flight answer for it. Always resolves to the
   * settled status, so a caller that needs to decide *right now* (the Continue button) can await
   * it rather than racing the blur handler that probably already started it.
   */
  check: (value: string) => Promise<AvailabilityStatus>;
}

/**
 * `unknown` is deliberately not blocking.
 *
 * The endpoint is a convenience, not a gate: registration performs its own duplicate checks and is
 * the authoritative answer. If the availability call fails — the customer is on a train, or a
 * shared office address has exhausted the rate limit — refusing to let them register would trade a
 * UX improvement for an outage. They proceed, and a genuine duplicate still comes back as
 * `DUPLICATE_EMAIL`/`DUPLICATE_PHONE` at submit, which the form still handles.
 */
export function isBlocking(status: AvailabilityStatus): boolean {
  return status === 'taken' || status === 'invalid';
}

const MESSAGES: Record<ContactField, { taken: string; invalid: string }> = {
  EMAIL: { taken: EMAIL_TAKEN_MESSAGE, invalid: EMAIL_INVALID_MESSAGE },
  PHONE: { taken: PHONE_TAKEN_MESSAGE, invalid: PHONE_INVALID_MESSAGE },
};

/**
 * "Is this email/phone already registered?", asked of the backend and remembered per value.
 *
 * ## Blur, not keystroke
 *
 * Nothing here runs on typing. `POST /api/auth/availability` is rate limited at 20 requests per
 * 10 minutes per client — tightly, on purpose, because it is the cheapest form of an
 * account-existence disclosure (see `ContactAvailabilityService`) — and a debounced per-character
 * caller would spend that entire budget on one slow typist and then start receiving `429`s on the
 * checks that matter. "The customer finished entering the field" is exactly what `blur` means, and
 * it is one request per value.
 *
 * ## Answers are cached per value, and `unknown` is not
 *
 * Tabbing back and forth across a field the customer did not change must not re-ask, so settled
 * answers are kept in a map keyed by the trimmed value. A failed check is *not* cached: it is not
 * an answer about the value, it is the absence of one, and caching it would mean a single
 * dropped request permanently stopped the field from ever being checked again.
 */
export function useContactAvailability(field: ContactField, value: string): ContactAvailability {
  /** Settled answers, by trimmed value. A ref (not state) because `check` must read the latest
   *  map from inside its own closure; `version` below is what actually re-renders. */
  const answers = useRef(new Map<string, AvailabilityStatus>());
  const inFlight = useRef(new Map<string, Promise<AvailabilityStatus>>());
  const [pending, setPending] = useState<string | null>(null);
  const [, setVersion] = useState(0);

  const trimmed = value.trim();
  const status: AvailabilityStatus =
    answers.current.get(trimmed) ?? (pending === trimmed && trimmed !== '' ? 'checking' : 'idle');

  const check = useCallback(
    async (raw: string): Promise<AvailabilityStatus> => {
      const target = raw.trim();
      if (!target) {
        return 'idle';
      }
      const settled = answers.current.get(target);
      if (settled) {
        return settled;
      }
      const running = inFlight.current.get(target);
      if (running) {
        return running;
      }

      const request = checkContactAvailability(field, target)
        .then((response): AvailabilityStatus => (response.available ? 'available' : 'taken'))
        .catch((error): AvailabilityStatus =>
          error instanceof ApiError && error.code === 'VALIDATION_ERROR' ? 'invalid' : 'unknown',
        )
        .then((result) => {
          inFlight.current.delete(target);
          if (result !== 'unknown') {
            answers.current.set(target, result);
          }
          setPending((current) => (current === target ? null : current));
          setVersion((n) => n + 1);
          return result;
        });

      inFlight.current.set(target, request);
      setPending(target);
      return request;
    },
    [field],
  );

  const copy = MESSAGES[field];
  const error =
    status === 'taken' ? copy.taken : status === 'invalid' ? copy.invalid : undefined;

  return { status, error, blocking: isBlocking(status), check };
}
