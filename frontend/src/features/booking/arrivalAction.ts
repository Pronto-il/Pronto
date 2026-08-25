import { ApiError, GENERIC_ERROR_MESSAGE } from '../../shared/api';
import { getArrivalFix, type DeviceFix } from '../../shared/lib/geolocation';

/**
 * The `הגעתי` flow, once, for both places that run it.
 *
 * The Standard tracking screen and the professional SOS screen do exactly the same three things
 * — take a fresh high-quality fix, send it, turn whatever comes back into a Hebrew sentence — and
 * they must keep doing exactly the same three things, because the backend holds both to one
 * shared geofence rule (`maps.service.ArrivalVerifier`). Two copies of this on the client is how
 * the two screens end up disagreeing about what a `422` means.
 *
 * ## What it deliberately does not do
 *
 * It does not decide whether the professional is close enough. It cannot: the client never
 * receives the customer's coordinates, and an endpoint that handed them over so the client could
 * compare would both leak the address and let any modified client claim to be anywhere. This
 * function's entire job on the happy path is to obtain good evidence and pass it on.
 */

/**
 * One Hebrew sentence per refusal, because each one calls for a different next action.
 *
 * This is the whole reason the backend gives these separate codes rather than one generic
 * "arrival refused": telling a professional standing at the right door to "move closer", or
 * telling one standing two streets away to "wait for a better signal", sends them off to do the
 * wrong thing.
 */
export const ARRIVAL_ERROR_MESSAGES: Record<string, string> = {
  /** The fix is fine and says they are not there. Retrying from the same spot will fail identically. */
  ARRIVAL_OUT_OF_RANGE: 'לא הצלחנו לאמת שאתה נמצא ליד כתובת הלקוח. יש להתקרב לכתובת ולנסות שוב.',
  /** The fix is too old or too coarse. Waiting a moment, or stepping outside, genuinely helps. */
  LOCATION_QUALITY_INSUFFICIENT:
    'המיקום שהתקבל אינו עדכני או מדויק מספיק. יש להפעיל שירותי מיקום מדויקים ולנסות שוב בעוד רגע.',
  /** Nothing the professional does with their phone can fix this — the address never resolved. */
  ORDER_DESTINATION_UNKNOWN:
    'לא ניתן לאמת הגעה להזמנה הזו — לכתובת השירות אין קואורדינטות מאומתות. אפשר להמשיך ולסיים את העבודה בסיום.',
  /** The job moved underneath them: a cancellation, or a second tab that got there first. */
  ORDER_NOT_ARRIVABLE: 'לא ניתן לסמן הגעה להזמנה הזו כרגע.',
  SOS_INVALID_STATE: 'לא ניתן לסמן הגעה לקריאה הזו כרגע.',
};

export type ArrivalOutcome =
  | { ok: true }
  /**
   * @param stage where it failed. `device` means nothing was sent and the order is untouched —
   *              worth distinguishing so a caller can decide whether a refetch is warranted.
   */
  | { ok: false; stage: 'device' | 'server'; message: string };

/**
 * Take a fresh fix and submit it.
 *
 * @param submit the endpoint call — `markArrived` for a Standard order, `markSosArrived` for an
 *               SOS request. Injected rather than branched on, so this function knows nothing
 *               about which flow it is serving and neither flow can drift from the other.
 */
export async function performArrival(
  submit: (fix: DeviceFix) => Promise<unknown>,
): Promise<ArrivalOutcome> {
  const outcome = await getArrivalFix();
  if (!outcome.ok) {
    // Nothing was sent. The message already explains the specific device-side problem — denied,
    // timed out, unsupported, too coarse — in the professional's own terms.
    return { ok: false, stage: 'device', message: outcome.message };
  }

  try {
    await submit(outcome.fix);
    return { ok: true };
  } catch (err) {
    if (err instanceof ApiError && ARRIVAL_ERROR_MESSAGES[err.code]) {
      return { ok: false, stage: 'server', message: ARRIVAL_ERROR_MESSAGES[err.code] };
    }
    return { ok: false, stage: 'server', message: GENERIC_ERROR_MESSAGE };
  }
}
