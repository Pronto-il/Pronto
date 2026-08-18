import { useState } from 'react';
import { Button, Card } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { createOrder, ApiError, GENERIC_ERROR_MESSAGE, getCategoryNameHe } from '../../shared/api';
import type { AvailabilitySlotItem, OrderResponse, ProfessionalCard as ProfessionalCardData } from '../../shared/api';
import { formatDateLabel, formatTimeLabel } from '../../shared/utils/formatDateTime';
import styles from './BookingSummary.module.css';

export interface BookingSummaryProps {
  issueId: number;
  categoryId: number;
  professional: ProfessionalCardData;
  slot: AvailabilitySlotItem;
  address: AddressValue;
  onConfirmed: (order: OrderResponse) => void;
  /** The chosen slot was claimed by someone else in the meantime — send the customer back to pick another. */
  onSlotUnavailable: () => void;
}

const ORDER_ERROR_MESSAGES: Record<string, string> = {
  SLOT_UNAVAILABLE: 'התור הזה כבר נתפס. אפשר לבחור זמן אחר.',
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
  slot,
  address,
  onConfirmed,
  onSlotUnavailable,
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
        slotId: slot.slotId,
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
      if (error instanceof ApiError && error.code === 'SLOT_UNAVAILABLE') {
        setBannerError(ORDER_ERROR_MESSAGES.SLOT_UNAVAILABLE);
        onSlotUnavailable();
      } else if (error instanceof ApiError && ORDER_ERROR_MESSAGES[error.code]) {
        setBannerError(ORDER_ERROR_MESSAGES[error.code]);
      } else {
        setBannerError(GENERIC_ERROR_MESSAGE);
      }
    } finally {
      setIsSubmitting(false);
    }
  }

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
            {formatDateLabel(slot.startTime)}, {formatTimeLabel(slot.startTime)}
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
