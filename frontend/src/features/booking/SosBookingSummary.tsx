import { useState } from 'react';
import { Button, Card } from '../../shared/components';
import type { AddressValue } from '../../shared/components';
import { createSosOrder, ApiError, GENERIC_ERROR_MESSAGE, getCategoryNameHe } from '../../shared/api';
import type { OrderResponse, ProfessionalCard as ProfessionalCardData } from '../../shared/api';
import styles from './SosBookingSummary.module.css';

/**
 * Flat, backend-hardcoded SOS surcharge placeholder — mirrors
 * `BookingsService.SOS_SURCHARGE_AMOUNT` (currently `new BigDecimal("50.00")`, explicitly
 * flagged in that class's Javadoc and `bookings/README.md` as a placeholder, not sourced
 * from any config/pricing engine). There is no endpoint exposing this value ahead of order
 * creation, so it's duplicated here — flagged, not silently duplicated — purely to satisfy
 * DESIGN_SYSTEM.md §49's "any additional fee must be visible before final confirmation"
 * requirement with an honest estimate. Keep this in sync with the backend constant if it
 * ever changes. The actual authoritative `finalPrice`/`sosSurcharge` from the real
 * `OrderResponse` is what's shown after order creation (success screen / `/orders/:id`).
 */
const SOS_SURCHARGE_AMOUNT = 50;

export interface SosBookingSummaryProps {
  issueId: number;
  categoryId: number;
  professional: ProfessionalCardData;
  address: AddressValue;
  onConfirmed: (order: OrderResponse) => void;
  /** The professional toggled SOS-availability off between listing and this call — send the customer back to a re-fetched list. */
  onProfessionalUnavailable: () => void;
}

const ORDER_ERROR_MESSAGES: Record<string, string> = {
  SOS_PROFESSIONAL_UNAVAILABLE: 'בעל המקצוע הזה כבר לא זמין לעבודות דחופות כרגע. הצגנו לכם רשימה מעודכנת.',
  ISSUE_NOT_BOOKABLE: 'הבקשה הזו כבר בטיפול. אפשר לעקוב אחריה בדף ההזמנות שלך.',
};

/**
 * SOS confirmation card — mirrors `BookingSummary.tsx`'s pattern (owns the actual
 * `POST /api/bookings/sos-orders` call, its own double-submission guard via `Button`'s
 * `loading` prop) but with no date/time row (SOS has no scheduled slot — the order's
 * `bookedStart` is set to `now()` server-side) and a price breakdown (base price + flat SOS
 * surcharge = estimated total) instead of a single total, per DESIGN_SYSTEM.md §49's
 * fee-disclosure requirement.
 */
export function SosBookingSummary({
  issueId,
  categoryId,
  professional,
  address,
  onConfirmed,
  onProfessionalUnavailable,
}: SosBookingSummaryProps) {
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [bannerError, setBannerError] = useState<string | null>(null);

  async function handleConfirm() {
    setBannerError(null);
    setIsSubmitting(true);
    try {
      const order = await createSosOrder({
        issueId,
        professionalId: professional.professionalId,
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
      if (error instanceof ApiError && error.code === 'SOS_PROFESSIONAL_UNAVAILABLE') {
        setBannerError(ORDER_ERROR_MESSAGES.SOS_PROFESSIONAL_UNAVAILABLE);
        onProfessionalUnavailable();
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
  const estimatedTotal = professional.basePrice + SOS_SURCHARGE_AMOUNT;

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
          <span className={styles.rowLabel}>כתובת</span>
          <span className={styles.rowValue}>{addressLine}</span>
        </div>

        <hr className={styles.divider} />

        <div className={styles.priceBreakdown}>
          <div className={styles.priceRow}>
            <span>מחיר בסיס</span>
            <span>₪{professional.basePrice}</span>
          </div>
          <div className={styles.priceRow}>
            <span>תוספת דחיפות (SOS)</span>
            <span>₪{SOS_SURCHARGE_AMOUNT}</span>
          </div>
        </div>

        <hr className={styles.divider} />

        <div className={styles.totalRow}>
          <span className={styles.totalLabel}>סה״כ משוער</span>
          <span className={styles.totalPrice}>₪{estimatedTotal}</span>
        </div>
        <p className={styles.estimateNote}>המחיר הסופי יוצג לאחר אישור ההזמנה.</p>
      </Card>
      <Button onClick={handleConfirm} loading={isSubmitting} fullWidth>
        אישור הזמנה דחופה
      </Button>
    </div>
  );
}
