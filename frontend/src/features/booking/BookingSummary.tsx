import { useState } from 'react';
import { Button, Card } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { createOrder, ApiError, GENERIC_ERROR_MESSAGE, getCategoryNameHe } from '../../shared/api';
import type { OrderResponse, ProfessionalCard as ProfessionalCardData } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './BookingSummary.module.css';

export interface BookingSummaryProps {
  issueId: number;
  categoryId: number;
  professional: ProfessionalCardData;
  /** The chosen ISO start instant — `bookedEnd` is derived here for display only (never sent
   *  to/trusted from the server, design §9.2.3). */
  bookedStart: string;
  /** Echoed from `GET .../available-windows`'s response — never hardcoded client-side. */
  defaultDurationMinutes: number;
  address: AddressValue;
  onConfirmed: (order: OrderResponse) => void;
  /** The chosen start time became unavailable (raced by another customer) — send the customer
   *  back to pick another. Takes the error message to display, because this component
   *  unmounts as part of that transition (the parent swaps back to the `slot` step), so any
   *  banner state set here would never get a chance to paint — the parent must own and render
   *  it instead (see `BookingFlowPage.tsx`'s `slot`-step banner). */
  onTimeUnavailable: (message: string) => void;
}

const ORDER_ERROR_MESSAGES: Record<string, string> = {
  BOOKING_TIME_UNAVAILABLE: 'הזמן הזה כבר לא פנוי. אפשר לבחור זמן אחר.',
  ISSUE_NOT_BOOKABLE: 'הבקשה הזו כבר בטיפול. אפשר לעקוב אחריה בדף ההזמנות שלך.',
};

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
  categoryId,
  professional,
  bookedStart,
  defaultDurationMinutes,
  address,
  onConfirmed,
  onTimeUnavailable,
}: BookingSummaryProps) {
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [bannerError, setBannerError] = useState<string | null>(null);

  async function handleConfirm() {
    setBannerError(null);
    setIsSubmitting(true);
    try {
      const order = await createOrder({
        issueId,
        professionalId: professional.professionalId,
        bookedStart,
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
      <Card className={styles.card}>
        <div>
          <p className={styles.professionalName}>{professional.fullName}</p>
          <p className={styles.category}>{getCategoryNameHe(categoryId)}</p>
        </div>

        <hr className={styles.divider} />

        <div className={styles.row}>
          <span className={styles.rowLabel}>תאריך ושעה</span>
          <span className={styles.rowValue}>
            {formatDateLabel(bookedStart)}, {formatTimeLabel(bookedStart)}–{formatTimeLabel(bookedEnd)}
          </span>
        </div>

        <div className={styles.row}>
          <span className={styles.rowLabel}>כתובת</span>
          <span className={styles.rowValue}>{addressLine}</span>
        </div>

        <hr className={styles.divider} />

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
