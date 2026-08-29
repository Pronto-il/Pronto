import { useState } from 'react';
import { CalendarClock, MapPin } from 'lucide-react';
import { Button, Card } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import type { ClarificationAnswer } from '../../shared/api';
import { createIssue, createOrder, ApiError, GENERIC_ERROR_MESSAGE, getCategoryNameHe } from '../../shared/api';
import { useAuth } from '../../shared/hooks';
import type { OrderResponse, ProfessionalCard as ProfessionalCardData } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './BookingSummary.module.css';

export interface BookingSummaryProps {
  /** Present only when an issue already exists (a customer returning to one they created on an
   *  earlier pass). `undefined` on the normal path, where the issue is created here, at the
   *  commit, together with the order. */
  issueId?: number;
  /** The report itself, carried from the draft so the issue can be created at the commit rather
   *  than before matching. Ignored when `issueId` is already set. */
  issueDescription: string;
  issueImageKeys: string[];
  issueClarificationAnswers: ClarificationAnswer[];
  /** Called instead of booking when nobody is signed in. The draft is already persisted, so the
   *  caller only has to route to login/registration. */
  onAuthRequired: () => void;
  categoryId: number;
  professional: ProfessionalCardData;
  /** The chosen ISO start instant — `bookedEnd` is derived here for display only (never sent
   *  to/trusted from the server, design §9.2.3). */
  bookedStart: string;
  /** Echoed from `GET .../available-windows`'s response — never hardcoded client-side. */
  defaultDurationMinutes: number;
  address: AddressValue;
  onConfirmed: (order: OrderResponse) => void;
  /** The chosen start time can no longer be booked — either raced by another customer, or it
   *  slipped into the past while the customer was on this screen. Send them back to pick
   *  another. Takes the message to display, because this component unmounts as part of that
   *  transition (the parent swaps back to the `slot` step), so any banner state set here would
   *  never get a chance to paint — the parent must own and render it instead (see
   *  `BookingFlowPage.tsx`'s `slot`-step notice). */
  onTimeUnavailable: (message: string) => void;
}

const ORDER_ERROR_MESSAGES: Record<string, string> = {
  BOOKING_TIME_UNAVAILABLE: 'הזמן הזה כבר לא פנוי. אפשר לבחור זמן אחר.',
  ISSUE_NOT_BOOKABLE: 'הבקשה הזו כבר בטיפול. אפשר לעקוב אחריה בדף ההזמנות שלך.',
};

/** MS4 final corrections, item 1. Distinct from `BOOKING_TIME_UNAVAILABLE`'s "someone else
 *  took it" copy — this is the "you took a while, that time has now passed" case, so the
 *  message says so and promises the refreshed list the parent is already fetching. */
const STALE_START_MESSAGE = 'הזמן שבחרת כבר עבר. אלה הזמנים הפנויים המעודכנים — אפשר לבחור מועד חדש.';

/**
 * The server's only rejection of a non-future `bookedStart` is a generic
 * `400 VALIDATION_ERROR` carrying a `bookedStart` field error (`BookingsService.createOrder`
 * step 0) — there is no dedicated error code for it. Recognising it by field name is what
 * lets this component route the customer back to the picker instead of dead-ending on
 * `GENERIC_ERROR_MESSAGE` ("משהו השתבש"), which was the pre-existing gap this milestone's
 * final corrections close. Reachable despite the pre-flight guard below via clock skew
 * between browser and server, or a slot that expires while the request is in flight.
 */
function isStaleStartError(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.code === 'VALIDATION_ERROR' &&
    Array.isArray(error.details) &&
    (error.details as Array<{ field?: string }>).some((entry) => entry?.field === 'bookedStart')
  );
}

/**
 * Confirmation card, per DESIGN_SYSTEM.md §48/FRONTEND_AGENT.md §35/§59: professional,
 * service/category, date, time, address, price — all visible at once, nothing hidden
 * behind an expandable section. Owns the actual `POST /api/bookings/orders` call (mirrors
 * `ReviewStep`'s self-contained pattern), so it also owns the double-submission guard
 * (button `loading` state disables it while the request is in flight).
 *
 * All 7 `AddressValue` fields (`city`/`street`/`houseNumber`/`apartment`/`floor`/
 * `entrance`/`addressNotes`) are forwarded to the API, per `ms3-ms4-corrections-design.md`
 * §2.7.
 */
export function BookingSummary({
  issueId,
  issueDescription,
  issueImageKeys,
  issueClarificationAnswers,
  onAuthRequired,
  categoryId,
  professional,
  bookedStart,
  defaultDurationMinutes,
  address,
  onConfirmed,
  onTimeUnavailable,
}: BookingSummaryProps) {
  const { token } = useAuth();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [bannerError, setBannerError] = useState<string | null>(null);

  async function handleConfirm() {
    setBannerError(null);

    // ---- THE AUTHENTICATION BOUNDARY ----
    //
    // This is the first and only point in the standard booking journey where an account is
    // required, and it is required because the very next thing that happens is a write that
    // dispatches a real professional to a real address.
    //
    // Nothing is created before this check: no issue, no order, and no notification. A guest who
    // gets this far and turns back at the login screen leaves no trace in the database.
    //
    // The draft has already been written by the step transitions that led here, so `onAuthRequired`
    // only has to send them to the login screen -- everything they entered is on disk and is
    // adopted by whichever account signs in (see BookingDraftProvider's adoption rule).
    if (!token) {
      onAuthRequired();
      return;
    }
    // Pre-flight: never send a request the server is guaranteed to reject. Catches the common
    // shape of this problem (the customer sat on this screen until the chosen time passed)
    // without a round trip, and without any invented lead-time rule — this mirrors exactly
    // what the server checks, no stricter.
    if (new Date(bookedStart).getTime() <= Date.now()) {
      onTimeUnavailable(STALE_START_MESSAGE);
      return;
    }
    setIsSubmitting(true);
    try {
      // The issue is created HERE, not at the review step, and only now that the caller is
      // authenticated. `POST /api/issues` is a write and the person confirming this booking may
      // have been a guest thirty seconds ago.
      //
      // Two calls rather than one, and deliberately in this order: an issue with no order is a
      // state the product already has (a customer who reported a fault and did not book), while an
      // order with no issue is not a state at all. If the second call fails the customer retries
      // and the first is reused via `issueId`.
      const resolvedIssueId =
        issueId ??
        (
          await createIssue({
            categoryId,
            description: issueDescription,
            urgencyType: 'STANDARD',
            imageKeys: issueImageKeys,
            clarificationAnswers: issueClarificationAnswers,
          })
        ).id;

      const order = await createOrder({
        issueId: resolvedIssueId,
        professionalId: professional.professionalId,
        bookedStart,
        // V55: the selected place travels with the address. Omitted for a grandfathered legacy
        // default address, which the backend accepts by recognising it as the caller's own.
        servicePlaceId: address.placeId ?? undefined,
        serviceFormattedAddress: address.formattedAddress ?? undefined,
        serviceLatitude: address.latitude ?? undefined,
        serviceLongitude: address.longitude ?? undefined,
        serviceCity: address.city,
        serviceStreet: address.street,
        serviceHouseNumber: address.houseNumber,
        serviceApartment: address.apartment || undefined,
        serviceFloor: address.floor || undefined,
        serviceEntrance: address.entrance || undefined,
        serviceAddressNotes: address.addressNotes || undefined,
      });
      onConfirmed(order);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'BOOKING_TIME_UNAVAILABLE') {
        // Not `setBannerError` here — this component is about to unmount as `onTimeUnavailable`
        // sends the customer back to the `slot` step, so a banner set on local state would
        // never paint. The parent renders the message instead.
        onTimeUnavailable(ORDER_ERROR_MESSAGES.BOOKING_TIME_UNAVAILABLE);
      } else if (isStaleStartError(error)) {
        onTimeUnavailable(STALE_START_MESSAGE);
      } else if (error instanceof ApiError && ORDER_ERROR_MESSAGES[error.code]) {
        setBannerError(ORDER_ERROR_MESSAGES[error.code]);
      } else {
        setBannerError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  // Display-only — never sent to/trusted from the server; the server independently
  // recomputes and validates the real `bookedEnd` (design §9.2.2/§9.2.3).
  const bookedEnd = new Date(new Date(bookedStart).getTime() + defaultDurationMinutes * 60_000).toISOString();

  const addressLine = [address.city, address.street, address.houseNumber].filter(Boolean).join(', ') + (address.apartment ? `, דירה ${address.apartment}` : '');

  return (
    <div className={styles.wrapper}>
      {bannerError && (
        <div className={styles.banner} role="alert">
          <p>{bannerError}</p>
        </div>
      )}
      <div className={styles.intro}>
        <h2 className={styles.question}>סיכום ההזמנה</h2>
        <p className={styles.introNote}>כדאי לוודא שהפרטים נכונים לפני האישור.</p>
      </div>
      <Card className={styles.card}>
        <div>
          <p className={styles.professionalName}>{professional.fullName}</p>
          <p className={styles.category}>{getCategoryNameHe(categoryId)}</p>
        </div>

        <hr className={styles.divider} />

        <div className={styles.row}>
          <span className={styles.rowLabel}>
            <CalendarClock size={15} aria-hidden="true" />
            תאריך ושעה
          </span>
          <span className={styles.rowValue}>
            {formatDateLabel(bookedStart)}, {formatTimeLabel(bookedStart)}–{formatTimeLabel(bookedEnd)}
          </span>
        </div>

        <div className={styles.row}>
          <span className={styles.rowLabel}>
            <MapPin size={15} aria-hidden="true" />
            כתובת
          </span>
          <span className={styles.rowValue}>{addressLine}</span>
        </div>

        <hr className={styles.divider} />

        {/* §48 lists the visit price and the total as separate lines, both always visible —
            never collapsed into one number or hidden behind an expandable section. Mirrors
            `SosBookingSummary`'s existing breakdown treatment. */}
        <div className={styles.priceRow}>
          <span>מחיר ביקור</span>
          <span>₪{professional.basePrice}</span>
        </div>

        <div className={styles.totalRow}>
          <span className={styles.totalLabel}>סה״כ</span>
          <span className={styles.totalPrice}>₪{professional.basePrice}</span>
        </div>
      </Card>
      <Button onClick={handleConfirm} loading={isSubmitting} fullWidth>
        אישור הזמנה
      </Button>
    </div>
  );
}
